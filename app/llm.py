"""LLM opcional para (1) categorizar gastos y (2) sugerir la lectura de correos
que ninguna plantilla reconoció.

El LLM nunca decide solo: todo lo que propone queda como "pendiente" con la
marca "Extraído por IA" hasta que el usuario lo confirma.

Proveedores:
  * anthropic          -> SDK oficial de Anthropic (ANTHROPIC_API_KEY o llm_api_key).
  * openai_compatible  -> cualquier servidor con /chat/completions (Ollama, LM Studio,
                          OpenRouter, vLLM...). Ideal para auto-alojar todo.
  * none               -> sin LLM. La app funciona con reglas y palabras clave.
"""
from __future__ import annotations

import json
import logging
import os
from typing import Any

import httpx

from . import config, db

log = logging.getLogger(__name__)


class LLMError(RuntimeError):
    pass


def provider() -> str:
    return (db.get_setting("llm_provider") or "none").strip().lower()


def enabled() -> bool:
    return provider() in ("anthropic", "openai_compatible")


def _model() -> str:
    m = db.get_setting("llm_model").strip()
    if m:
        return m
    return config.ANTHROPIC_DEFAULT_MODEL if provider() == "anthropic" else "llama3.1"


def complete_json(system: str, user: str, schema: dict[str, Any], max_tokens: int = 4000) -> dict[str, Any]:
    p = provider()
    if p == "anthropic":
        return _anthropic(system, user, schema, max_tokens)
    if p == "openai_compatible":
        return _openai_compatible(system, user, schema, max_tokens)
    raise LLMError("No hay un LLM configurado (Ajustes → IA).")


def _anthropic(system: str, user: str, schema: dict[str, Any], max_tokens: int) -> dict[str, Any]:
    try:
        import anthropic
    except ImportError as exc:  # pragma: no cover
        raise LLMError("Instala el paquete 'anthropic'") from exc
    key = db.get_setting("llm_api_key").strip() or os.environ.get("ANTHROPIC_API_KEY", "")
    client = anthropic.Anthropic(api_key=key or None)
    try:
        # Fallback del lado del servidor: si el modelo rechaza la solicitud por
        # política, la API la reintenta con otro modelo dentro de la misma llamada.
        resp = client.beta.messages.create(
            model=_model(),
            max_tokens=max_tokens,
            betas=["server-side-fallback-2026-07-01"],
            fallbacks="default",
            output_config={"effort": "low", "format": {"type": "json_schema", "schema": schema}},
            system=system,
            messages=[{"role": "user", "content": user}],
        )
    except anthropic.APIStatusError as exc:
        raise LLMError(f"Error de la API de Anthropic ({exc.status_code}): {exc.message}") from exc
    except anthropic.APIConnectionError as exc:
        raise LLMError(f"No se pudo conectar con Anthropic: {exc}") from exc
    if resp.stop_reason == "refusal":
        raise LLMError("El modelo rechazó la solicitud")
    text = next((b.text for b in resp.content if b.type == "text"), "")
    try:
        return json.loads(text)
    except json.JSONDecodeError as exc:
        raise LLMError("Respuesta del LLM no es JSON válido") from exc


def _openai_compatible(system: str, user: str, schema: dict[str, Any], max_tokens: int) -> dict[str, Any]:
    base = db.get_setting("llm_base_url").rstrip("/")
    key = db.get_setting("llm_api_key").strip()
    headers = {"Authorization": f"Bearer {key}"} if key else {}
    body = {
        "model": _model(),
        "max_tokens": max_tokens,
        "temperature": 0,
        "messages": [
            {"role": "system", "content": system + "\n\nResponde SOLO con JSON que cumpla este esquema:\n"
                                                   + json.dumps(schema, ensure_ascii=False)},
            {"role": "user", "content": user},
        ],
        "response_format": {"type": "json_schema", "json_schema": {"name": "out", "schema": schema}},
    }
    try:
        r = httpx.post(f"{base}/chat/completions", json=body, headers=headers, timeout=120)
        if r.status_code == 400:
            # Algunos servidores no soportan json_schema; probar modo JSON simple.
            body["response_format"] = {"type": "json_object"}
            r = httpx.post(f"{base}/chat/completions", json=body, headers=headers, timeout=120)
        r.raise_for_status()
        text = r.json()["choices"][0]["message"]["content"]
    except (httpx.HTTPError, KeyError, IndexError) as exc:
        raise LLMError(f"Error llamando al LLM en {base}: {exc}") from exc
    text = text.strip().removeprefix("```json").removeprefix("```").removesuffix("```")
    try:
        return json.loads(text)
    except json.JSONDecodeError as exc:
        raise LLMError("Respuesta del LLM no es JSON válido") from exc


# ------------------------------------------------------------ tareas

CATEGORIZE_SCHEMA = {
    "type": "object",
    "properties": {
        "items": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "id": {"type": "integer"},
                    "category": {"type": "string"},
                    "counterparty_type": {"type": "string", "enum": ["person", "business", "unknown"]},
                    "maybe_not_expense": {"type": "boolean"},
                    "reason": {"type": "string"},
                },
                "required": ["id", "category", "counterparty_type", "maybe_not_expense", "reason"],
                "additionalProperties": False,
            },
        }
    },
    "required": ["items"],
    "additionalProperties": False,
}

CATEGORIZE_SYSTEM = """Eres un asistente que categoriza gastos personales en Perú (soles).
Recibirás movimientos con: contraparte (a quién se pagó), descripción (mensaje que el usuario escribió en Yape o la transferencia), monto y medio de pago.

Reglas:
- Usa SOLO una categoría de la lista dada, escrita exactamente igual. Si no hay información suficiente para decidir (por ejemplo, un pago a una persona sin descripción), usa "Otros" y explica en reason qué falta. No inventes.
- counterparty_type: "person" si es una persona natural (ej. 'Lucia Pat*', 'PLIN - JUAN PEREZ'), "business" si es un comercio o empresa.
- maybe_not_expense=true solo si la descripción sugiere que no es un gasto real: cambio de efectivo ("sencillo", "cambio"), encargos o pases de dinero ("para que le pases a..."), préstamos o devoluciones.
- reason: una frase corta en español.
"""


def categorize(items: list[dict[str, Any]], categories: list[str], examples: list[dict[str, str]]) -> list[dict[str, Any]]:
    ex = "\n".join(f"- {e['input']} => {e['output']}" for e in examples[-30:])
    user = (
        "Categorías disponibles: " + ", ".join(categories) + "\n\n"
        + ("Ejemplos corregidos por el usuario (síguelos):\n" + ex + "\n\n" if ex else "")
        + "Movimientos:\n" + json.dumps(items, ensure_ascii=False)
    )
    out = complete_json(CATEGORIZE_SYSTEM, user, CATEGORIZE_SCHEMA, max_tokens=8000)
    return out.get("items", [])


EXTRACT_SCHEMA = {
    "type": "object",
    "properties": {
        "kind": {"type": "string", "enum": ["expense", "income", "not_expense", "ignore", "unsure"]},
        "amount": {"type": ["number", "null"]},
        "currency": {"type": "string", "enum": ["PEN", "USD"]},
        "counterparty": {"type": "string"},
        "occurred_at": {"type": ["string", "null"], "description": "YYYY-MM-DD HH:MM:SS hora de Lima"},
        "description": {"type": "string"},
        "reason": {"type": "string"},
    },
    "required": ["kind", "amount", "currency", "counterparty", "occurred_at", "description", "reason"],
    "additionalProperties": False,
}

EXTRACT_SYSTEM = """Lees correos de notificación de bancos peruanos y extraes el movimiento.
- kind: "expense" (compra, consumo, pago, transferencia enviada), "income" (te transfirieron, devolución),
  "not_expense" (pago de tu propia tarjeta, transferencia entre tus cuentas, ahorro),
  "ignore" (no es un movimiento: publicidad, avisos de seguridad, estados de cuenta, operación rechazada),
  "unsure" si no estás seguro.
- amount: el monto EXACTO que aparece en el correo junto a una etiqueta como "Monto", "Total", "Importe". Si no aparece, null. Nunca lo calcules ni lo inventes.
- Cuidado: los pies de página de los bancos mencionan "compra rechazada" o montos de promociones; ignóralos.
"""


def extract_email(from_addr: str, subject: str, body: str) -> dict[str, Any]:
    user = f"De: {from_addr}\nAsunto: {subject}\n\nCuerpo:\n{body[:6000]}"
    return complete_json(EXTRACT_SYSTEM, user, EXTRACT_SCHEMA, max_tokens=2000)
