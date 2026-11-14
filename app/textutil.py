"""Utilidades de texto: HTML -> texto, montos, fechas en español, normalización."""
from __future__ import annotations

import html as htmllib
import re
import unicodedata
from datetime import datetime
from difflib import SequenceMatcher
from html.parser import HTMLParser

# ------------------------------------------------------------------ HTML

_BLOCK_TAGS = {"p", "div", "tr", "br", "li", "h1", "h2", "h3", "h4", "h5", "h6", "table", "tbody", "thead", "section"}
_CELL_TAGS = {"td", "th"}
_SKIP_TAGS = {"style", "script", "head", "title"}


class _TextExtractor(HTMLParser):
    """Convierte HTML de correos bancarios a texto plano.

    Cada fila de tabla queda en su propia línea y las celdas separadas por
    " | ". Así "Monto total | S/ 399.00" se mantiene junto aunque en el HTML
    estén en celdas distintas.
    """

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.parts: list[str] = []
        self._skip = 0

    def handle_starttag(self, tag: str, attrs) -> None:  # noqa: ANN001
        if tag in _SKIP_TAGS:
            self._skip += 1
        elif tag in _BLOCK_TAGS:
            self.parts.append("\n")
        elif tag in _CELL_TAGS:
            self.parts.append(" | ")

    def handle_startendtag(self, tag: str, attrs) -> None:  # noqa: ANN001
        if tag == "br":
            self.parts.append("\n")

    def handle_endtag(self, tag: str) -> None:
        if tag in _SKIP_TAGS:
            self._skip = max(0, self._skip - 1)
        elif tag in _BLOCK_TAGS:
            self.parts.append("\n")

    def handle_data(self, data: str) -> None:
        if not self._skip:
            self.parts.append(data)


def html_to_text(html: str) -> str:
    if not html:
        return ""
    # Comentarios condicionales de Outlook (<!--[if mso]> ... <![endif]-->)
    html = re.sub(r"<!--.*?-->", " ", html, flags=re.S)
    p = _TextExtractor()
    try:
        p.feed(html)
        p.close()
    except Exception:  # HTML roto: último recurso
        return clean_text(re.sub(r"<[^>]+>", " ", htmllib.unescape(html)))
    return clean_text("".join(p.parts))


def clean_text(text: str) -> str:
    text = text.replace("\r", "").replace("​", "").replace("\xa0", " ")
    lines = []
    for raw in text.split("\n"):
        line = re.sub(r"[ \t]+", " ", raw)
        line = re.sub(r"(\s*\|\s*)+", " | ", line).strip(" |")
        if line:
            lines.append(line)
    return "\n".join(lines)


def flat(text: str) -> str:
    """Una sola línea, sin separadores de celdas, para buscar frases."""
    t = text.replace("|", " ").replace("*", " ")
    return re.sub(r"\s+", " ", t).strip()


# ------------------------------------------------------------------ texto

def strip_accents(s: str) -> str:
    return "".join(c for c in unicodedata.normalize("NFD", s) if unicodedata.category(c) != "Mn")


def norm(s: str) -> str:
    """minúsculas, sin tildes, sin signos, espacios simples."""
    s = strip_accents((s or "").lower())
    s = re.sub(r"[^a-z0-9ñ]+", " ", s)
    return re.sub(r"\s+", " ", s).strip()


_NAME_PREFIXES = ("plin ", "bim ", "izi ", "yape ", "payu ", "pyu ", "dp ", "paypal ")


def counterparty_key(s: str) -> str:
    """Clave estable para recordar una contraparte (ej. 'PLIN - Juan Per*')."""
    k = norm(s)
    for p in _NAME_PREFIXES:
        if k.startswith(p):
            k = k[len(p):]
    # Quitar códigos de transacción tipo '5b8qlda3f4', '00586984'
    k = re.sub(r"\b(?=[a-z0-9]*\d)[a-z0-9]{6,}\b", " ", k)
    return re.sub(r"\s+", " ", k).strip()


def similarity(a: str, b: str) -> float:
    a, b = counterparty_key(a), counterparty_key(b)
    if not a or not b:
        return 0.0
    if a in b or b in a:
        return 1.0
    return SequenceMatcher(None, a, b).ratio()


def names_match(a: str, b: str) -> bool:
    """¿'Ana Per*' y 'ANA MARIA PEREZ LOPEZ' son la misma persona?

    Yape enmascara nombres: primer nombre + inicio del apellido + '*'. Se
    considera coincidencia si cada palabra (o prefijo enmascarado) de la forma
    corta aparece como prefijo de alguna palabra de la forma larga.
    """
    ta = [w for w in norm(a.replace("*", " * ")).split() if w]
    tb = [w for w in norm(b.replace("*", " * ")).split() if w]
    for p in ("plin", "bim"):
        ta = [w for w in ta if w != p]
        tb = [w for w in tb if w != p]
    if not ta or not tb:
        return False
    short, long_ = (ta, tb) if len(ta) <= len(tb) else (tb, ta)
    if len(short) < 2 and len(long_) >= 2:
        return False
    return all(any(lw.startswith(sw) for lw in long_) for sw in short)


# ------------------------------------------------------------------ montos

_CURRENCY_RE = r"(S/\.?|PEN|US\$|USD|\$)"
_NUMBER_RE = r"(\d{1,3}(?:[,\s]\d{3})*(?:\.\d{1,2})?|\d+(?:[.,]\d{1,2})?)"
AMOUNT_RE = re.compile(_CURRENCY_RE + r"\s*\*{0,2}\s*" + _NUMBER_RE, re.I)


def parse_number(s: str) -> float | None:
    s = s.strip().replace(" ", "")
    if not s:
        return None
    if "," in s and "." in s:
        s = s.replace(",", "")
    elif "," in s:
        # "1,234" (miles) vs "12,50" (decimal)
        head, _, tail = s.rpartition(",")
        s = head.replace(",", "") + ("." if len(tail) <= 2 else "") + tail
    try:
        return round(float(s), 2)
    except ValueError:
        return None


def currency_code(sym: str) -> str:
    sym = sym.upper().replace(".", "")
    return "USD" if sym in ("$", "US$", "USD") else "PEN"


def find_amount(text: str) -> tuple[float, str] | None:
    m = AMOUNT_RE.search(text)
    if not m:
        return None
    val = parse_number(m.group(2))
    if val is None:
        return None
    return val, currency_code(m.group(1))


def amount_after_label(text: str, labels: list[str]) -> tuple[float, str] | None:
    """Busca el primer monto que sigue a alguna de las etiquetas dadas.

    Las etiquetas se prueban en orden (la primera tiene prioridad), y el monto
    debe estar a pocos caracteres de la etiqueta. Nunca se toma "cualquier
    monto del correo": si la etiqueta no aparece, devuelve None.
    """
    f = flat(text)
    for label in labels:
        pat = re.compile(re.escape(label) + r"\s*:?\s*(?:\|\s*)?" + AMOUNT_RE.pattern, re.I)
        m = pat.search(f)
        if m:
            val = parse_number(m.group(2))
            if val is not None:
                return val, currency_code(m.group(1))
    return None


# ------------------------------------------------------------------ fechas

MONTHS = {
    "ene": 1, "enero": 1, "feb": 2, "febrero": 2, "mar": 3, "marzo": 3, "abr": 4, "abril": 4,
    "may": 5, "mayo": 5, "jun": 6, "junio": 6, "jul": 7, "julio": 7, "ago": 8, "agosto": 8,
    "set": 9, "sep": 9, "sept": 9, "septiembre": 9, "setiembre": 9, "oct": 10, "octubre": 10,
    "nov": 11, "noviembre": 11, "dic": 12, "diciembre": 12,
}

_TIME_RE = r"(\d{1,2}):(\d{2})(?::(\d{2}))?\s*((?:a|p)\.?\s?m\.?)?"

_DATE_PATTERNS = [
    # 12 de noviembre de 2026 - 08:17 PM | Lunes, 28 Septiembre 2026 - 10:28 A. M. | 02 Nov, 2026 - 08:25 am
    re.compile(
        r"(\d{1,2})\s+(?:de\s+)?([a-záéíóú]{3,10})\.?,?\s+(?:de\s+|del\s+)?(\d{4})\s*(?:-|a las|,)?\s*" + _TIME_RE,
        re.I,
    ),
    # 30/10/2026 21:14:12 | 16/09/2026 a las 21:21
    re.compile(r"(\d{1,2})/(\d{1,2})/(\d{4})\s*(?:-|a las)?\s*" + _TIME_RE, re.I),
]
_DATE_ONLY = re.compile(r"(\d{1,2})\s+(?:de\s+)?([a-záéíóú]{3,10})\.?,?\s+(?:de\s+)?(\d{4})", re.I)


def _to_24h(h: int, ampm: str | None) -> int:
    if not ampm:
        return h
    is_pm = ampm.lower().replace(".", "").replace(" ", "").startswith("p")
    if is_pm and h < 12:
        return h + 12
    if not is_pm and h == 12:
        return 0
    return h


def parse_es_datetime(text: str) -> datetime | None:
    """Fecha y hora local en los formatos que usan BCP, Yape y otros bancos."""
    if not text:
        return None
    t = flat(text)
    for i, pat in enumerate(_DATE_PATTERNS):
        for m in pat.finditer(t):
            g = m.groups()
            try:
                day = int(g[0])
                if i == 0:
                    month = MONTHS.get(strip_accents(g[1].lower()).rstrip("."))
                    if not month:
                        continue
                else:
                    month = int(g[1])
                year = int(g[2])
                hour = _to_24h(int(g[3]), g[6])
                minute = int(g[4])
                second = int(g[5] or 0)
                return datetime(year, month, day, hour, minute, second)
            except (ValueError, TypeError):
                continue
    m = _DATE_ONLY.search(t)
    if m:
        month = MONTHS.get(strip_accents(m.group(2).lower()))
        if month:
            try:
                return datetime(int(m.group(3)), month, int(m.group(1)))
            except ValueError:
                return None
    return None


def fmt_dt(dt: datetime) -> str:
    return dt.strftime("%Y-%m-%d %H:%M:%S")
