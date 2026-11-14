"""Parsers de correos bancarios.

Principio: NO ADIVINAR.

* Cada tipo de correo conocido tiene una plantilla explícita que busca frases y
  etiquetas exactas ("Total del consumo", "Monto de yapeo", ...).
* Si un correo de un banco no coincide con ninguna plantilla, el resultado es
  `unknown` y aparece en la bandeja de revisión. Nunca se descarta en silencio.
* Si una plantilla coincide pero falta un dato obligatorio (el monto), también
  es `unknown`, con la razón exacta.

Las plantillas se construyeron a partir de correos reales de BCP y Yape
(noviembre 2026). Si tu banco cambia el formato, lo verás en "Correos" como
"sin reconocer" y podrás crear una regla o reportar la plantilla.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from datetime import datetime
from typing import Callable

from .textutil import amount_after_label, flat, html_to_text, norm, parse_es_datetime

# kind:
#   expense      -> gasto (cuenta en el total)
#   not_expense  -> movimiento real que NO es gasto (pago de tu tarjeta, entre tus cuentas...)
#   income       -> ingreso / devolución (no cuenta como gasto)
#   ignore       -> no es un movimiento (promos, avisos de seguridad, estados de cuenta)
#   yape_history -> correo con el Excel de movimientos de Yape adjunto
#   unknown      -> no se pudo reconocer con certeza: requiere revisión


@dataclass
class ParseResult:
    kind: str
    parser: str
    reason: str = ""
    amount: float | None = None
    currency: str = "PEN"
    counterparty: str = ""
    counterparty_type: str = "unknown"
    account: str = ""
    account_detail: str = ""
    occurred_at: datetime | None = None
    external_ref: str = ""
    description: str = ""
    flags: list[str] = field(default_factory=list)

    def as_dict(self) -> dict:
        d = self.__dict__.copy()
        d["occurred_at"] = self.occurred_at.isoformat(sep=" ") if self.occurred_at else None
        return d


@dataclass
class Email:
    from_addr: str
    subject: str
    html: str = ""
    text: str = ""
    received_at: datetime | None = None
    attachments: list[str] = field(default_factory=list)  # nombres de archivo

    _body: str | None = None

    @property
    def body(self) -> str:
        """Texto del correo. Se prefiere el HTML (conserva la estructura de tabla)."""
        if self._body is None:
            self._body = html_to_text(self.html) if self.html else (self.text or "")
        return self._body

    @property
    def flat_body(self) -> str:
        return flat(self.body)

    @property
    def nbody(self) -> str:
        return norm(self.body)


# ------------------------------------------------------------- utilidades

_STOP_LABELS = [
    "Fecha y hora", "Fecha y Hora", "Número de operación", "Nº de operación", "N° de operación",
    "Numero de operacion", "Número de Tarjeta", "Empresa", "Servicio", "Titular", "Código de usuario",
    "Comisión", "Cuenta de origen", "Vigencia", "Valor venta", "Monto", "Mensaje", "Canal", "Desde",
    "Enviado a", "Operación realizada", "Celular", "Tipo de cambio", "Total", "Yapero", "Tu número",
    "Nombre del Beneficiario", "Celular del Beneficiario", "ID de operación", "Banco", "Cuenta destino",
    "Destino", "Beneficiario", "Moneda", "Detalle del servicio", "Número de recibo", "Vencimiento",
    "Operadora", "Operador", "Número recargado", "Cantidad", "Comercio", "Código", "Doc. pago", "Importe",
    "Si tú no", "Por tu seguridad", "Resuelve tus dudas",
]


_LABEL_SET = {norm(x) for x in _STOP_LABELS} | {"canal", "moneda soles", "pagado a", "tipo de pago"}


def value_after(text: str, labels: list[str], max_len: int = 80) -> str:
    """Valor que sigue a una etiqueta ("Empresa | CIBERTEC SAC" o "Empresa: CIBERTEC SAC").

    Corta en la siguiente etiqueta conocida, salto de celda o '****'.
    """
    f = " " + flat(text) + " "
    stops = "|".join(re.escape(s) for s in _STOP_LABELS)
    for label in labels:
        pat = re.compile(
            r"(?<![A-Za-zÁÉÍÓÚáéíóúñÑ])" + re.escape(label) + r"\s*:?\s+(.{1,%d}?)(?=\s+(?:%s)\b|\s*\*{3,}|\s{2,}|$)"
            % (max_len, stops),
            re.I,
        )
        m = pat.search(f)
        if m:
            v = m.group(1).strip(" :.-*")
            nv = norm(v)
            if v and not any(nv == lab or nv.startswith(lab + " ") for lab in _LABEL_SET):
                return v
    return ""


def cell_after(text: str, label: str) -> str:
    """Valor de una etiqueta en el texto convertido desde HTML.

    Soporta los tres formatos que usan los bancos:
      * 'Etiqueta | Valor'   (celdas en la misma fila)
      * 'Etiqueta: Valor'    (misma línea)
      * 'Etiqueta' + salto de línea + 'Valor'  (BCP y Yape)
    La etiqueta debe ocupar la línea/celda completa: así "Servicio" no coincide
    con "¡Tu servicio fue yapeado!".
    """
    target = norm(label)
    lines = [ln.strip() for ln in text.split("\n")]
    for idx, line in enumerate(lines):
        cells = [c.strip() for c in line.split("|")]
        for i, c in enumerate(cells):
            head, sep, tail = c.partition(":")
            if sep and norm(head) == target and tail.strip(" *"):
                return tail.strip(" *")
            if norm(c) != target:
                continue
            if i + 1 < len(cells) and cells[i + 1].strip(" *"):
                return cells[i + 1].strip(" *")
            # siguiente línea no vacía (si es otra etiqueta, el campo estaba vacío)
            for nxt in lines[idx + 1: idx + 3]:
                v = nxt.split("|")[0].strip()
                if v.strip(" *"):
                    nv = norm(v.rstrip(":"))
                    if any(nv == lab or nv.startswith(lab + " ") for lab in _LABEL_SET):
                        return ""
                    return v.strip()
            return ""
    return ""


def last4(s: str) -> str:
    m = re.search(r"\*+\s*(\d{4})\b", s or "") or re.fullmatch(r"\s*(\d{4})\s*", s or "")
    return m.group(1) if m else ""


def op_number(text: str) -> str:
    for label in ["Número de operación Yape", "Nº de operación Yape", "Número de operación", "Nº de operación",
                  "N° de operación", "ID de operación", "Numero de operacion"]:
        v = cell_after(text, label) or value_after(text, [label], 40)
        m = re.search(r"[A-Z0-9-]{4,}", v or "")
        if m:
            return m.group(0)
    return ""


def when(text: str, email: Email, labels: list[str]) -> tuple[datetime | None, list[str]]:
    for label in labels:
        v = cell_after(text, label) or value_after(text, [label], 60)
        dt = parse_es_datetime(v) if v else None
        if dt:
            return dt, []
    dt = parse_es_datetime(text)
    if dt:
        return dt, []
    if email.received_at:
        return email.received_at, ["fecha_del_correo"]
    return None, ["sin_fecha"]


def sender_is(email: Email, *needles: str) -> bool:
    f = (email.from_addr or "").lower()
    return any(n in f for n in needles)


def has(email: Email, *phrases: str) -> bool:
    """Todas las frases (normalizadas) aparecen en asunto o cuerpo."""
    hay = norm(email.subject) + " " + email.nbody
    return all(norm(p) in hay for p in phrases)


def has_any(email: Email, *phrases: str) -> bool:
    hay = norm(email.subject) + " " + email.nbody
    return any(norm(p) in hay for p in phrases)


def wallet_counterparty(name: str) -> tuple[str, str]:
    """'PLIN-LEONEL BECERRA' -> ('PLIN - LEONEL BECERRA', 'person')."""
    m = re.match(r"^\s*(PLIN|BIM|YAPE|TUNKI|AGORA)\s*[-*]\s*(.+)$", name, re.I)
    if m:
        return f"{m.group(1).upper()} - {m.group(2).strip()}", "person"
    return name, "unknown"


# ------------------------------------------------------------- BCP

BCP = ("notificacionesbcp.com.pe",)


def bcp_card_purchase(e: Email) -> ParseResult | None:
    if not (sender_is(e, *BCP) and has(e, "realizaste un consumo")):
        return None
    t = e.body
    credit = has_any(e, "tarjeta de credito")
    amt = amount_after_label(t, ["Total del consumo", "Realizaste un consumo de"])
    merchant = cell_after(t, "Empresa") or value_after(t, ["Empresa"])
    if not merchant:
        m = re.search(r"Realizaste un consumo de .*? en (.+?)\.(?:\s|$)", e.flat_body)
        merchant = m.group(1).strip() if m else ""
    card = cell_after(t, "Número de Tarjeta de Crédito") or cell_after(t, "Número de Tarjeta de Débito") \
        or value_after(t, ["Número de Tarjeta de Crédito", "Número de Tarjeta de Débito"])
    dt, flags = when(t, e, ["Fecha y hora"])
    kind_label = "Tarjeta de Crédito" if credit else "Tarjeta de Débito"
    r = ParseResult(
        kind="expense", parser="bcp_consumo_tarjeta", account="BCP",
        account_detail=f"{kind_label} ****{last4(card)}" if last4(card) else kind_label,
        occurred_at=dt, external_ref=op_number(t), flags=flags,
    )
    r.counterparty, r.counterparty_type = wallet_counterparty(merchant)
    if r.counterparty_type == "person":
        r.flags.append("p2p_via_tarjeta")
    if not amt:
        return _missing(r, "monto ('Total del consumo')")
    r.amount, r.currency = amt
    return r


def bcp_transfer(e: Email) -> ParseResult | None:
    if not (sender_is(e, *BCP) and has_any(e, "realizaste una transferencia", "constancia de transferencia")):
        return None
    t = e.body
    op = (cell_after(t, "Operación realizada") or value_after(t, ["Operación realizada"]) or e.subject)
    opn = norm(op + " " + e.subject)
    dt, flags = when(t, e, ["Fecha y hora"])
    dest = cell_after(t, "Enviado a") or value_after(t, ["Enviado a", "Cuenta destino", "Beneficiario"])
    dest = re.split(r"\*{2,}|\bMoneda\b", dest)[0].strip(" .")
    origin = cell_after(t, "Desde") or value_after(t, ["Desde"]) or value_after(t, ["desde tu"], 40)
    origin = re.split(r"\*{2,}|\bMoneda\b", origin)[0].strip(" .")
    msg = cell_after(t, "Mensaje")
    bank = cell_after(t, "Banco destino")
    amt = amount_after_label(t, ["Total cobrado", "Monto enviado", "Monto transferido", "Realizaste una transferencia de"])
    r = ParseResult(
        kind="expense", parser="bcp_transferencia", account="BCP",
        account_detail=(origin if norm(origin).startswith("cuenta") else f"Cuenta {origin}") if origin else "Cuenta",
        counterparty=dest, counterparty_type="unknown", occurred_at=dt, external_ref=op_number(t),
        description=msg.strip(), flags=flags,
    )
    if "entre mis cuentas" in opn:
        r.kind = "not_expense"
        r.parser = "bcp_transferencia_propia"
        r.reason = "Transferencia entre tus propias cuentas"
        r.counterparty_type = "self"
    elif "otros bancos" in opn:
        r.parser = "bcp_transferencia_otros_bancos"
        if bank:
            r.counterparty = f"{dest} ({bank})" if dest else bank
        # Puede ser el pago de una tarjeta de otro banco, un pase de dinero o un gasto real.
        r.flags.append("revisar_transferencia")
    else:
        r.parser = "bcp_transferencia_terceros"
        r.flags.append("revisar_transferencia")
    if not amt:
        return _missing(r, "monto ('Monto transferido' / 'Total cobrado')")
    r.amount, r.currency = amt
    return r


def bcp_service_payment(e: Email) -> ParseResult | None:
    if not (sender_is(e, *BCP) and has(e, "pago de servicio")):
        return None
    t = e.body
    company = cell_after(t, "Empresa") or value_after(t, ["Empresa"])
    service = cell_after(t, "Servicio") or value_after(t, ["Servicio"])
    origin = cell_after(t, "Cuenta de origen") or value_after(t, ["Cuenta de origen"], 60)
    amt = amount_after_label(t, ["Monto total", "Importe", "Total pagado", "Monto pagado"])
    dt, flags = when(t, e, ["Fecha y hora"])
    origin_clean = re.split(r"\*{2,}", origin)[0].strip()
    detail = f"{origin_clean} ****{last4(origin)}".strip() if last4(origin) else (origin_clean or "Banca Móvil")
    r = ParseResult(
        kind="expense", parser="bcp_pago_servicio", account="BCP", account_detail=detail,
        counterparty=company, counterparty_type="business", occurred_at=dt,
        external_ref=op_number(t), description=service, flags=flags,
    )
    if norm(company) in ("pagoefectivo", "pago efectivo", "kasnet", "safetypay"):
        # Intermediario de pagos: el comercio real no aparece en el correo.
        r.flags.append("intermediario_pago")
    if not amt:
        return _missing(r, "monto ('Monto total')")
    r.amount, r.currency = amt
    return r


def bcp_card_payment(e: Email) -> ParseResult | None:
    if not (sender_is(e, *BCP) and has_any(e, "pago de tarjeta de credito propia", "realizaste un pago a tu tarjeta",
                                         "pagaste tu tarjeta")):
        return None
    t = e.body
    amt = amount_after_label(t, ["Monto pagado", "Total pagado", "Realizaste un pago a tu tarjeta de", "para pagar"])
    dt, flags = when(t, e, ["Fecha y hora", "hecha el"])
    r = ParseResult(
        kind="not_expense", parser="bcp_pago_tarjeta_propia", account="BCP", account_detail="Pago de tarjeta",
        counterparty=(cell_after(t, "Pagado a") or cell_after(t, "Tarjeta pagada") or "Tu tarjeta de crédito"),
        reason="Pago de tu propia tarjeta: sus consumos ya se cuentan uno por uno",
        occurred_at=dt, external_ref=op_number(t), counterparty_type="self", flags=flags,
    )
    if amt:
        r.amount, r.currency = amt
    return r


def bcp_refund(e: Email) -> ParseResult | None:
    if not (sender_is(e, *BCP) and has_any(e, "realizamos una devolucion", "se ha devuelto el monto")):
        return None
    t = e.body
    amt = amount_after_label(t, ["Total devuelto", "Se ha devuelto el monto de"])
    dt, flags = when(t, e, ["Fecha y hora"])
    r = ParseResult(kind="income", parser="bcp_devolucion", account="BCP", reason="Devolución de una compra",
                    occurred_at=dt, external_ref=op_number(t), flags=flags + ["devolucion"])
    if not amt:
        return _missing(r, "monto devuelto")
    r.amount, r.currency = amt
    return r


def bcp_yape_received(e: Email) -> ParseResult | None:
    """'Constancia de recepción de Yapeo a celular BCP': te yapearon (ingreso)."""
    if not (sender_is(e, *BCP) and has_any(e, "recepcion de yapeo", "recibiste un yapeo", "te yapearon")):
        return None
    t = e.body
    amt = amount_after_label(t, ["Monto recibido", "Monto", "Total", "recibiste un yapeo de", "te yapearon"])
    who = cell_after(t, "Enviado por") or value_after(t, ["Enviado por", "Yapero", "Remitente", "De"])
    dt, flags = when(t, e, ["Fecha y hora"])
    r = ParseResult(kind="income", parser="bcp_yapeo_recibido", account="Yape", account_detail="Yape",
                    counterparty=who, reason="Te yapearon (ingreso)", occurred_at=dt,
                    external_ref=op_number(t), flags=flags)
    if not amt:
        return _missing(r, "monto recibido")
    r.amount, r.currency = amt
    return r


def bcp_savings(e: Email) -> ParseResult | None:
    if not (sender_is(e, *BCP) and has_any(e, "wardadito", "aporte automatico")):
        return None
    if has_any(e, "no te olvides", "recuerda que tu aporte"):
        return ParseResult(kind="ignore", parser="bcp_recordatorio", reason="Recordatorio, no es un movimiento")
    t = e.body
    amt = amount_after_label(t, ["Total retirado", "Total aportado", "Monto aportado", "Monto", "Total"])
    dt, flags = when(t, e, ["Fecha y hora"])
    aporte = has_any(e, "aporte")
    r = ParseResult(kind="not_expense", parser="bcp_ahorro", account="BCP", counterparty_type="self",
                    counterparty="Wardadito (ahorro)",
                    reason=("Aporte a tu ahorro (wardadito): es ahorro, no gasto" if aporte
                            else "Retiro de tu ahorro (wardadito)"), occurred_at=dt, flags=flags)
    if amt:
        r.amount, r.currency = amt
    return r


_BCP_IGNORE = [
    # Ojo: "Favorito: Agregaste un Pago de Servicios" contiene la frase "pago de
    # servicios" pero NO es un pago. Por eso estas reglas van antes que todo.
    ("constancia de favorito", "Agregaste un favorito (no es un movimiento)"),
    ("ampliacion de linea", "Ampliación de línea de crédito"),
    ("rechazo tu compra", "Operación rechazada (no se cobró)"),
    ("compra rechazada", "Operación rechazada (no se cobró)"),
    ("constancia de configuracion", "Configuración de tarjeta o clave"),
    ("estado de cuenta", "Estado de cuenta (no es un movimiento)"),
    ("no te olvides", "Recordatorio de débito automático (aún no se cobra)"),
    ("viabcp", "Comunicación de ViaBCP (inscripción/campaña)"),
]
_BCP_IGNORE_BODY = [
    ("lo sentimos tu compra fue rechazada", "Operación rechazada (no se cobró)"),
]


def bcp_ignore(e: Email) -> ParseResult | None:
    """Solo mira el ASUNTO (y frases muy específicas del cuerpo).

    Trampa real: todos los correos de consumo del BCP traen al pie "Si tu compra
    fue rechazada, cancelada o duplicada...". Buscar "rechaz" en el cuerpo
    descartaba consumos reales.
    """
    if not sender_is(e, *BCP):
        return None
    subj = norm(e.subject)
    for needle, reason in _BCP_IGNORE:
        if needle in subj:
            return ParseResult(kind="ignore", parser="bcp_no_movimiento", reason=reason)
    for phrase, reason in _BCP_IGNORE_BODY:
        if phrase in e.nbody:
            return ParseResult(kind="ignore", parser="bcp_no_movimiento", reason=reason)
    return None


# ------------------------------------------------------------- Yape

YAPE = ("yape.pe",)


def yape_history(e: Email) -> ParseResult | None:
    if not (sender_is(e, *YAPE) and has(e, "historial de movimientos")):
        return None
    return ParseResult(kind="yape_history", parser="yape_historial",
                       reason="Excel de movimientos de Yape adjunto (se importa automáticamente)")


def yape_p2p(e: Email) -> ParseResult | None:
    if not (sender_is(e, *YAPE) and has(e, "acabas de yapear")):
        return None
    t = e.body
    amt = amount_after_label(t, ["Monto de yapeo*", "Monto de yapeo", "Monto"])
    name = cell_after(t, "Nombre del Beneficiario") or value_after(t, ["Nombre del Beneficiario"])
    dt, flags = when(t, e, ["Fecha y Hora de la operación", "Fecha y hora"])
    r = ParseResult(kind="expense", parser="yape_yapeo", account="Yape", account_detail="Yape",
                    counterparty=name, occurred_at=dt, external_ref=op_number(t), flags=flags)
    if not amt:
        return _missing(r, "monto ('Monto de yapeo')")
    r.amount, r.currency = amt
    return r


def yape_merchant(e: Email) -> ParseResult | None:
    if not (sender_is(e, *YAPE) and has_any(e, "fue exitoso", "yapeaste tu promo", "fue exitosa")):
        return None
    if has_any(e, "afiliacion"):
        return None
    t = e.body
    f = e.flat_body
    m = re.search(r"tu pago en (.+?) fue exitoso", f, re.I) or re.search(r"Tu compra de (.+?) fue exitosa",
                                                                          e.subject + " " + f, re.I)
    merchant = (cell_after(t, "Comercio") or value_after(t, ["Comercio"]) or (m.group(1).strip() if m else ""))
    amt = amount_after_label(t, ["Monto total"])
    dt, flags = when(t, e, ["Fecha y hora", "Fecha"])
    r = ParseResult(kind="expense", parser="yape_pago_comercio", account="Yape", account_detail="Yape",
                    counterparty=merchant.replace("&#39;", "'"), counterparty_type="business", occurred_at=dt,
                    external_ref=op_number(t), flags=flags)
    if norm(merchant) == "yape promos":
        r.flags.append("posible_duplicado_promo")
    promo = value_after(t, ["¡Yapeaste tu promo!"], 80)
    if promo:
        r.description = re.split(r"\s+[A-Z]{2}\d{5,}", promo)[0]
    if not amt:
        return _missing(r, "monto ('Monto total')")
    r.amount, r.currency = amt
    return r


def yape_service(e: Email) -> ParseResult | None:
    if not (sender_is(e, *YAPE) and has_any(e, "servicio fue yapeado", "yapeo de servicio")):
        return None
    t = e.body
    company = cell_after(t, "Empresa") or value_after(t, ["Empresa"])
    service = cell_after(t, "Servicio") or value_after(t, ["Servicio"])
    amt = amount_after_label(t, ["Monto total"])
    dt, flags = when(t, e, ["Fecha y hora"])
    r = ParseResult(kind="expense", parser="yape_pago_servicio", account="Yape", account_detail="Yape",
                    counterparty=company, counterparty_type="business", description=service,
                    occurred_at=dt, external_ref=op_number(t), flags=flags)
    if not amt:
        return _missing(r, "monto ('Monto total')")
    r.amount, r.currency = amt
    return r


def yape_recharge(e: Email) -> ParseResult | None:
    if not (sender_is(e, *YAPE) and has(e, "recarga") and has_any(e, "confirmada", "numero recargado")):
        return None
    t = e.body
    amt = amount_after_label(t, ["Monto total", "Monto recargado", "Monto"])
    if not amt:
        # El correo de recarga empieza con el monto ("S/ 6 Número recargado ...").
        m = re.match(r"\s*(S/\.?)\s*(\d+(?:\.\d{1,2})?)\s+N[úu]mero recargado", e.flat_body, re.I)
        if m:
            amt = (float(m.group(2)), "PEN")
    operator = cell_after(t, "Operadora") or value_after(t, ["Operadora"])
    dt, flags = when(t, e, ["Fecha"])
    r = ParseResult(kind="expense", parser="yape_recarga", account="Yape", account_detail="Yape",
                    counterparty=f"Recarga {operator}".strip(), counterparty_type="business",
                    description="Recarga de celular", occurred_at=dt, external_ref=op_number(t), flags=flags)
    if not amt:
        return _missing(r, "monto de la recarga")
    r.amount, r.currency = amt
    return r


_YAPE_IGNORE = [
    ("afiliacion", "Afiliación de Yape a un comercio"),
    ("biometria", "Aviso de seguridad"),
    ("limite de yapeo", "Cambio de límite"),
    ("ingresaste", "Aviso de inicio de sesión"),
    ("ingresaste a yape", "Aviso de inicio de sesión"),
    ("compras por internet", "Activación de compras por internet"),
    ("actualizaste", "Actualización de datos"),
]


def yape_ignore(e: Email) -> ParseResult | None:
    if not sender_is(e, *YAPE):
        return None
    subj = norm(e.subject)
    for needle, reason in _YAPE_IGNORE:
        if needle in subj:
            return ParseResult(kind="ignore", parser="yape_no_movimiento", reason=reason)
    return None


# ------------------------------------------------------------- BBVA

def bbva_statement(e: Email) -> ParseResult | None:
    if sender_is(e, "bbva.com.pe", "bbva.pe") and has_any(e, "estado de cuenta"):
        return ParseResult(kind="ignore", parser="bbva_estado_cuenta", reason="Estado de cuenta (no es un movimiento)")
    return None


# ------------------------------------------------------------- genérico

_GENERIC_EXPENSE = [
    "realizaste un consumo", "realizaste una compra", "compra realizada", "consumo realizado",
    "realizaste un pago", "pago realizado", "realizaste una transferencia", "transferencia realizada",
    "cargo en tu cuenta", "se realizo un cargo", "consumo con tu tarjeta", "compra con tu tarjeta",
]
_GENERIC_INCOME = ["te transfirieron", "recibiste", "abono en tu cuenta", "te yapearon", "deposito en tu cuenta"]


def generic(e: Email) -> ParseResult | None:
    """Para bancos sin plantilla propia. Solo marca 'expense' si encuentra una
    frase de gasto Y un monto etiquetado; siempre queda pendiente de revisión."""
    hay = norm(e.subject) + " " + e.nbody[:1500]
    if any(p in hay for p in _GENERIC_INCOME):
        amt = amount_after_label(e.body, ["Monto", "Importe", "Total"])
        r = ParseResult(kind="income", parser="generico_ingreso", reason="Frase de ingreso detectada",
                        flags=["plantilla_generica"])
        if amt:
            r.amount, r.currency = amt
        r.occurred_at, _ = when(e.body, e, ["Fecha y hora", "Fecha"])
        return r
    if not any(p in hay for p in _GENERIC_EXPENSE):
        return None
    amt = amount_after_label(e.body, ["Monto total", "Total del consumo", "Importe total", "Monto", "Importe", "Total"])
    dt, flags = when(e.body, e, ["Fecha y hora", "Fecha de operación", "Fecha"])
    r = ParseResult(kind="expense", parser="generico", account=bank_name(e.from_addr),
                    counterparty=value_after(e.body, ["Comercio", "Establecimiento", "Empresa", "Beneficiario",
                                                      "Enviado a", "Destino"]),
                    occurred_at=dt, external_ref=op_number(e.body), flags=flags + ["plantilla_generica"])
    if not amt:
        return _missing(r, "monto (plantilla genérica)")
    r.amount, r.currency = amt
    return r


def bank_name(from_addr: str) -> str:
    f = (from_addr or "").lower()
    for needle, name in [("yape", "Yape"), ("bcp", "BCP"), ("bbva", "BBVA"), ("interbank", "Interbank"),
                         ("scotiabank", "Scotiabank"), ("ripley", "Banco Ripley"), ("falabella", "Banco Falabella"),
                         ("banbif", "BanBif"), ("pichincha", "Pichincha"), ("plin", "Plin")]:
        if needle in f:
            return name
    m = re.search(r"@([\w.-]+)", f)
    return m.group(1) if m else f


def _missing(r: ParseResult, what: str) -> ParseResult:
    r.kind = "unknown"
    r.reason = f"Plantilla '{r.parser}' reconocida pero no se encontró {what}"
    return r


# Orden importa: lo más específico primero.
PARSERS: list[Callable[[Email], ParseResult | None]] = [
    yape_history,
    bcp_ignore,
    yape_ignore,
    bbva_statement,
    bcp_card_payment,
    bcp_refund,
    bcp_yape_received,
    bcp_savings,
    bcp_service_payment,
    bcp_transfer,
    bcp_card_purchase,
    yape_p2p,
    yape_service,
    yape_recharge,
    yape_merchant,
    generic,
]


def parse_email(e: Email) -> ParseResult:
    for p in PARSERS:
        r = p(e)
        if r is not None:
            return r
    return ParseResult(
        kind="unknown", parser="ninguno",
        reason="Correo de un banco que no coincide con ninguna plantilla conocida",
        account=bank_name(e.from_addr),
    )
