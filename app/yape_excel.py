"""Lectura del Excel "Reporte de Movimientos" de Yape.

Formato real (2026):
    fila 2: 'Reporte de Movimientos'
    fila 5: Tipo de Transacción | Origen | Destino | Monto | Mensaje | Fecha de operación
    filas siguientes: PAGASTE / TE PAGÓ, 'Ana Per*', 'Jose Ram*', '150.00', mensaje, '30/10/2026 21:14:12'

No se asume la fila del encabezado: se busca.
"""
from __future__ import annotations

import io
import warnings
from dataclasses import dataclass
from datetime import datetime

from .textutil import norm, parse_number

HEADERS = {
    "tipo": ["tipo de transaccion", "tipo"],
    "origen": ["origen"],
    "destino": ["destino"],
    "monto": ["monto", "importe"],
    "mensaje": ["mensaje", "descripcion"],
    "fecha": ["fecha de operacion", "fecha"],
}


@dataclass
class YapeRow:
    direction: str          # 'out' (PAGASTE) | 'in' (TE PAGÓ)
    origin: str
    destination: str
    amount: float
    message: str
    occurred_at: datetime
    raw_type: str

    @property
    def counterparty(self) -> str:
        return self.destination if self.direction == "out" else self.origin

    def dedupe_key(self) -> str:
        return "yape|{}|{}|{}|{:.2f}|{}".format(
            self.direction, norm(self.origin), norm(self.destination), self.amount,
            self.occurred_at.strftime("%Y-%m-%d %H:%M:%S"),
        )


class YapeExcelError(ValueError):
    pass


def _parse_date(v) -> datetime | None:  # noqa: ANN001
    if isinstance(v, datetime):
        return v
    if v is None:
        return None
    s = str(v).strip()
    for fmt in ("%d/%m/%Y %H:%M:%S", "%d/%m/%Y %H:%M", "%Y-%m-%d %H:%M:%S", "%d/%m/%Y"):
        try:
            return datetime.strptime(s, fmt)
        except ValueError:
            continue
    return None


def parse_yape_excel(data: bytes) -> list[YapeRow]:
    try:
        import openpyxl
    except ImportError as exc:  # pragma: no cover
        raise YapeExcelError("Falta openpyxl") from exc
    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        try:
            # read_only=True no funciona con el Excel de Yape (no declara dimensiones)
            wb = openpyxl.load_workbook(io.BytesIO(data), data_only=True)
        except Exception as exc:
            raise YapeExcelError(f"No se pudo abrir el archivo como Excel: {exc}") from exc
    ws = wb.worksheets[0]
    rows = list(ws.iter_rows(values_only=True))
    cols: dict[str, int] | None = None
    out: list[YapeRow] = []
    for r in rows:
        cells = [("" if c is None else str(c)).strip() for c in r]
        if cols is None:
            normed = [norm(c) for c in cells]
            found = {}
            for key, names in HEADERS.items():
                for i, c in enumerate(normed):
                    if c in names and i not in found.values():
                        found[key] = i
                        break
            if {"tipo", "monto", "fecha"} <= found.keys() and ("origen" in found or "destino" in found):
                cols = found
            continue
        if not any(cells):
            continue

        def g(key: str) -> str:
            i = cols.get(key)  # type: ignore[union-attr]
            return cells[i] if i is not None and i < len(cells) else ""

        tipo = norm(g("tipo"))
        if not tipo:
            continue
        if "pagaste" in tipo or "yapeaste" in tipo or "enviaste" in tipo:
            direction = "out"
        elif "te pago" in tipo or "recibiste" in tipo or "te yapeo" in tipo:
            direction = "in"
        else:
            continue
        amount = parse_number(g("monto").replace("S/", "").strip())
        dt = _parse_date(r[cols["fecha"]] if cols["fecha"] < len(r) else None)
        if amount is None or dt is None:
            continue
        out.append(YapeRow(direction=direction, origin=g("origen"), destination=g("destino"), amount=abs(amount),
                           message=g("mensaje"), occurred_at=dt, raw_type=g("tipo")))
    if cols is None:
        raise YapeExcelError("No parece un reporte de Yape: no se encontró la fila 'Tipo de Transacción / Monto / Fecha'.")
    return out
