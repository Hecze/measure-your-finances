"""Categorización y sugerencias de "esto quizás no es un gasto".

Orden de decisión para la categoría (la primera que aplique gana):
  1. Lo que el usuario ya eligió (nunca se sobrescribe).
  2. Reglas del usuario por contraparte.
  3. Memoria: el usuario ya categorizó esta contraparte antes (solo empresas,
     o personas sin descripción).
  4. Palabras clave en la descripción (mensaje de Yape) y la contraparte.
  5. LLM (opcional, en lote, ver llm.py).
Si nada aplica se deja sin categoría: es preferible preguntar a adivinar.
"""
from __future__ import annotations

import json
import re
from datetime import datetime, timedelta
from typing import Any

from . import db
from .textutil import counterparty_key, names_match, norm

BUSINESS_HINTS = re.compile(
    r"\b(s\.?a\.?c?|s\.?r\.?l|e\.?i\.?r\.?l|sac|srl|eirl|s\.a|ltda|inc|corp|peru|perú|store|tienda|market|"
    r"restaurant|pollerias?|farmacia|botica|minimarket|servicios|ingenieria|inversiones|comercial|"
    r"universidad|instituto|colegio|clinica|grifo|cinemark|cineplanet|pedidos ya|rappi|tambo|oxxo|tottus|"
    r"plaza vea|metro|wong|listo|mass|vega|izi|payu|pyu|paypal|google|apple|netflix|spotify|facebk|meta|"
    r"vercel|hostinger|scribd|oculus|nintendo|bitel|claro|movistar|entel)\b",
    re.I,
)
_MASKED_PERSON = re.compile(r"^[A-Za-zÁÉÍÓÚáéíóúÑñ]+(?: [A-Za-zÁÉÍÓÚáéíóúÑñ]+)? [A-Za-zÁÉÍÓÚáéíóúÑñ]{1,6}\*$")

# Mensajes típicos de "no es gasto real": cambio de efectivo, encargos, préstamos.
NOT_EXPENSE_WORDS = [
    "sencillo", "cambio", "vuelto", "efectivo", "devolucion", "devolución", "presto", "préstamo", "prestamo",
    "te devuelvo", "encargo", "para que", "de parte de", "pasame", "pásame", "deposito", "depósito",
    "me diste", "me dio", "le debo", "adelanto",
]


def owner_names() -> list[str]:
    return [n.strip() for n in db.get_setting("owner_names").split(",") if n.strip()]


def is_self(name: str) -> bool:
    if not name:
        return False
    return any(names_match(name, own) for own in owner_names())


def guess_counterparty_type(name: str, hint: str = "unknown") -> str:
    if hint in ("person", "business", "self"):
        return hint
    if not name:
        return "unknown"
    if is_self(name):
        return "self"
    n = name.strip()
    if n.lower().startswith("pagaste el servicio"):
        return "business"
    m = re.match(r"^(PLIN|BIM|BBVA|IBK|INTERBANK|SCOTIABANK|SCOTIA|TUNKI|AGORA)\s*-\s*(.+)$", n, re.I)
    if m:
        # 'PLIN - JUAN PEREZ GARCIA' (persona) vs 'BBVA - INVERSIONES EJEMPLO SAC' (comercio)
        return "business" if BUSINESS_HINTS.search(m.group(2)) else "person"
    if _MASKED_PERSON.match(n):
        return "person"
    if BUSINESS_HINTS.search(n) or "*" in n:
        return "business"
    if n.isupper() and len(n.split()) >= 2:
        # Yape muestra a los comercios en mayúsculas ('SA LISTO PLAZA NORTE')
        return "business"
    return "unknown"


def _categories() -> list[dict[str, Any]]:
    return [dict(r) for r in db.query("SELECT * FROM categories ORDER BY sort_order")]


def category_by_keywords(text: str) -> int | None:
    t = " " + norm(text) + " "
    best: tuple[int, int] | None = None  # (largo de keyword, id)
    for c in _categories():
        for kw in [k.strip() for k in (c["keywords"] or "").split(",") if k.strip()]:
            k = norm(kw)
            if k and f" {k} " in t:
                if best is None or len(k) > best[0]:
                    best = (len(k), c["id"])
    return best[1] if best else None


def category_from_rules(counterparty: str, description: str) -> int | None:
    for r in db.query("SELECT * FROM rules WHERE field IN ('counterparty','description') AND category_id IS NOT NULL"):
        hay = counterparty if r["field"] == "counterparty" else description
        if _match(r["pattern"], hay, bool(r["is_regex"])):
            return r["category_id"]
    return None


def _match(pattern: str, text: str, is_regex: bool) -> bool:
    if not text:
        return False
    if is_regex:
        try:
            return re.search(pattern, text, re.I) is not None
        except re.error:
            return False
    return norm(pattern) in norm(text)


def category_from_memory(counterparty: str) -> int | None:
    key = counterparty_key(counterparty)
    if not key:
        return None
    row = db.query_one("SELECT category_id FROM counterparty_memory WHERE key=?", (key,))
    return row["category_id"] if row else None


def remember(counterparty: str, category_id: int | None, ctype: str | None = None) -> None:
    key = counterparty_key(counterparty)
    if not key:
        return
    with db.tx() as conn:
        conn.execute(
            "INSERT INTO counterparty_memory(key, category_id, counterparty_type, updated_at) VALUES (?,?,?,datetime('now')) "
            "ON CONFLICT(key) DO UPDATE SET category_id=excluded.category_id, "
            "counterparty_type=COALESCE(excluded.counterparty_type, counterparty_memory.counterparty_type), "
            "updated_at=datetime('now')",
            (key, category_id, ctype),
        )


def suggest_category(counterparty: str, description: str, ctype: str) -> tuple[int | None, str]:
    cid = category_from_rules(counterparty, description)
    if cid:
        return cid, "rule"
    # Con personas la categoría depende de la descripción, no de a quién pagas.
    if description:
        cid = category_by_keywords(description)
        if cid:
            return cid, "keyword"
    if ctype != "person" or not description:
        cid = category_from_memory(counterparty)
        if cid:
            return cid, "memory"
    if ctype == "business":
        # Sin el sufijo "(Banco destino)": "(Ripley)" es el banco, no una compra en Ripley.
        cid = category_by_keywords(re.sub(r"\([^)]*\)", " ", counterparty))
        if cid:
            return cid, "keyword"
    return None, ""


# ------------------------------------------------------------- banderas

def flags_for(tx: dict[str, Any]) -> list[str]:
    """Sugerencias que se muestran al usuario. No excluyen nada automáticamente
    (salvo transferencias a ti mismo, que nunca son gasto)."""
    flags = list(tx.get("flags") or [])
    desc = norm(tx.get("description") or "")
    if desc and any(f" {norm(w)} " in f" {desc} " for w in NOT_EXPENSE_WORDS):
        flags.append("posible_cambio_o_encargo")
    if tx.get("counterparty_type") == "person" and not (tx.get("description") or "").strip():
        flags.append("sin_descripcion")
    return sorted(set(flags))


def find_passthrough(tx: dict[str, Any]) -> dict[str, Any] | None:
    """¿Recibiste un monto parecido poco antes? (alguien te dio plata para que
    pagues por él). Busca un ingreso de ±3% hasta 6 horas antes."""
    if tx.get("direction") != "out" or not tx.get("amount"):
        return None
    dt = datetime.fromisoformat(tx["occurred_at"])
    lo, hi = tx["amount"] * 0.97, tx["amount"] * 1.03
    row = db.query_one(
        "SELECT * FROM transactions WHERE direction='in' AND amount BETWEEN ? AND ? "
        "AND occurred_at BETWEEN ? AND ? AND counterparty_type != 'self' ORDER BY occurred_at DESC LIMIT 1",
        (lo, hi, (dt - timedelta(hours=6)).isoformat(sep=" "), tx["occurred_at"]),
    )
    return dict(row) if row else None


def _strip_wallet(name: str) -> str:
    """'PLIN - Carlos Ruiz' -> 'Carlos Ruiz'; 'Gomez Gomez Luis A (Ripley)' -> 'Gomez Gomez Luis A'."""
    n = re.sub(r"^\s*(PLIN|BIM|BBVA|IBK|INTERBANK|SCOTIABANK|SCOTIA|BCP|CAJA \w+|TUNKI|AGORA)\s*-\s*", "", name or "", flags=re.I)
    return re.sub(r"\s*\([^)]*\)\s*$", "", n).strip()


def find_reciprocal(tx: dict[str, Any], days: int = 45) -> dict[str, Any] | None:
    """¿Esta misma persona también te envía dinero? Caso real: alguien te yapea
    y tú le transfieres a su cuenta de Banco Ripley. Compara el nombre enmascarado
    de Yape ('Luis Gom*') con el nombre completo del banco ('Gomez Gomez Luis A.')."""
    if tx.get("direction") != "out" or tx.get("counterparty_type") == "business":
        return None
    # Si ya tiene una descripción de algo concreto ("chaufa"), el gasto está explicado.
    if (tx.get("description") or "").strip() and "revisar_transferencia" not in (tx.get("flags") or []):
        return None
    name = _strip_wallet(tx.get("counterparty") or "")
    if len(norm(name)) < 4:
        return None
    dt = datetime.fromisoformat(tx["occurred_at"])
    rows = db.query(
        "SELECT counterparty, COUNT(*) n, SUM(amount) total FROM transactions WHERE direction='in' "
        "AND occurred_at BETWEEN ? AND ? GROUP BY counterparty",
        ((dt - timedelta(days=days)).isoformat(sep=" "), (dt + timedelta(days=days)).isoformat(sep=" ")),
    )
    for r in rows:
        if names_match(name, _strip_wallet(r["counterparty"])) and not is_self(r["counterparty"]):
            return {"counterparty": r["counterparty"], "n": r["n"], "total": r["total"]}
    return None


def refresh_flags(tx_id: int) -> None:
    row = db.row_to_dict(db.query_one("SELECT * FROM transactions WHERE id=?", (tx_id,)))
    if not row:
        return
    flags = [f for f in flags_for(row) if f not in ("pase_de_dinero", "persona_tambien_te_envia")]
    if find_passthrough(row):
        flags.append("pase_de_dinero")
    if find_reciprocal(row):
        flags.append("persona_tambien_te_envia")
    flags = sorted(set(flags))
    if flags != row["flags"]:
        with db.tx() as conn:
            conn.execute("UPDATE transactions SET flags=? WHERE id=?", (json.dumps(flags), tx_id))


FLAG_LABELS = {
    "sin_descripcion": "Sin descripción: ¿en qué fue?",
    "posible_cambio_o_encargo": "¿Cambio de efectivo o encargo? Puede no ser un gasto",
    "pase_de_dinero": "Recibiste un monto parecido poco antes: ¿fue un pase de dinero?",
    "persona_tambien_te_envia": "Esta persona también te envía dinero: ¿es un encargo o pase de dinero?",
    "revisar_transferencia": "Transferencia: ¿gasto, pago de otra tarjeta o pase de dinero?",
    "p2p_via_tarjeta": "Pago a persona (Plin/BIM) con tu tarjeta de débito",
    "intermediario_pago": "Pagado vía PagoEfectivo: agrega qué compraste",
    "posible_duplicado_promo": "Puede duplicar la compra de Yape Promos",
    "plantilla_generica": "Detectado con plantilla genérica: verifica monto y comercio",
    "fecha_del_correo": "Fecha tomada del correo (no del detalle)",
    "llm": "Extraído por IA: verifica",
    "devolucion": "Devolución",
}


def refresh_all_flags(since: str | None = None) -> None:
    sql = "SELECT id FROM transactions WHERE direction='out'"
    params: tuple = ()
    if since:
        sql += " AND occurred_at >= ?"
        params = (since,)
    for r in db.query(sql, params):
        refresh_flags(r["id"])
