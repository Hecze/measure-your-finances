"""Ingesta: correos (IMAP) y Excel de Yape -> transacciones, sin duplicados."""
from __future__ import annotations

import email
import email.policy
import imaplib
import json
import logging
import re
from collections import Counter
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from email.utils import parsedate_to_datetime
from typing import Any
from zoneinfo import ZoneInfo

import httpx

from . import categorize as cat
from . import config, db, llm
from .parsers import Email, ParseResult, bank_name, parse_email
from .textutil import counterparty_key, fmt_dt, norm, similarity
from .yape_excel import YapeRow, parse_yape_excel

log = logging.getLogger(__name__)
TZ = ZoneInfo(config.TIMEZONE)

GENERIC_COUNTERPARTIES = {"yape promos", "pagoefectivo", ""}


# ===================================================================== dedupe

def find_duplicate(t: dict[str, Any]) -> dict[str, Any] | None:
    """Busca la misma operación ya registrada por otra vía.

    Casos reales que cubre:
      * Excel de Yape  vs  correo "Pago exitoso" de Yape (PEDIDOS YA).
      * Excel de Yape (pago a PLIN/BIM)  vs  correo BCP de consumo con débito
        ("PLIN-DIEGO ARMANDO CAME"), porque Yape cobra esos pagos a tu tarjeta.
      * Correo "Tu compra de Roky's" vs "Pago en Yape Promos" (mismo pago, dos correos).
    """
    if t.get("dedupe_key"):
        row = db.query_one("SELECT * FROM transactions WHERE dedupe_key=?", (t["dedupe_key"],))
        if row:
            return dict(row)
    dt = datetime.fromisoformat(t["occurred_at"])
    loose = "fecha_del_correo" in (t.get("flags") or [])
    window = timedelta(minutes=30 if loose else 5)
    rows = db.query(
        "SELECT * FROM transactions WHERE direction=? AND currency=? AND ABS(amount-?)<0.005 "
        "AND occurred_at BETWEEN ? AND ?",
        (t["direction"], t["currency"], t["amount"], fmt_dt(dt - window), fmt_dt(dt + window)),
    )
    best = None
    for r in rows:
        r = dict(r)
        if t.get("external_ref") and r["external_ref"] and r["account"] == t.get("account"):
            if r["external_ref"] == t["external_ref"]:
                return r
            continue  # mismo banco, distinto nº de operación: son dos operaciones distintas
        if r["source"] == "yape_excel" and t["source"] == "yape_excel":
            continue  # el Excel ya se deduplica por dedupe_key
        if r["source"] != t["source"]:
            best = best or r
            continue
        # Misma fuente (dos correos): exige contraparte parecida o una genérica.
        cp_a, cp_b = norm(r["counterparty"]), norm(t.get("counterparty", ""))
        if cp_a in GENERIC_COUNTERPARTIES or cp_b in GENERIC_COUNTERPARTIES or similarity(cp_a, cp_b) >= 0.6:
            best = best or r
    return best


def _merge_into(existing: dict[str, Any], new: dict[str, Any]) -> list[str]:
    """Completa datos faltantes del registro existente. Nunca pisa lo que el usuario editó."""
    updates: dict[str, Any] = {}
    changed = []
    if new.get("description") and not existing["description"]:
        updates["description"] = new["description"]
        changed.append("descripción")
    if new.get("counterparty") and norm(existing["counterparty"]) in GENERIC_COUNTERPARTIES:
        updates["counterparty"] = new["counterparty"]
        changed.append("contraparte")
    # El Excel de Yape trae el nombre completo; el correo del BCP lo trae recortado.
    if (new.get("source") == "yape_excel" and existing["source"] == "email"
            and len(new.get("counterparty", "")) > len(existing["counterparty"])
            and similarity(new["counterparty"], existing["counterparty"]) >= 0.6):
        updates["counterparty"] = new["counterparty"]
        changed.append("nombre completo")
    if updates:
        sets = ", ".join(f"{k}=?" for k in updates)
        with db.tx() as conn:
            conn.execute(f"UPDATE transactions SET {sets}, updated_at=datetime('now') WHERE id=?",
                         (*updates.values(), existing["id"]))
        if "description" in updates and existing["category_source"] in ("", "memory", "keyword", "llm"):
            _auto_categorize(existing["id"], force=True)
        cat.refresh_flags(existing["id"])
    return changed


# ===================================================================== alta

def add_transaction(t: dict[str, Any], dry_run: bool = False) -> tuple[str, int | None, list[str]]:
    """Inserta una transacción si no es duplicada.

    Devuelve (estado, id, cambios) con estado en: 'new' | 'duplicate' | 'merged'.
    """
    t = dict(t)
    t.setdefault("flags", [])
    t.setdefault("status", "pending")
    t.setdefault("counterparty", "")
    t.setdefault("description", "")
    t.setdefault("account_detail", "")
    t.setdefault("external_ref", "")
    t["counterparty_type"] = cat.guess_counterparty_type(t["counterparty"], t.get("counterparty_type", "unknown"))
    dup = find_duplicate(t)
    if dup:
        if dry_run:
            would = []
            if t.get("description") and not dup["description"]:
                would.append("descripción")
            return ("merged" if would else "duplicate"), dup["id"], would
        changed = _merge_into(dup, t)
        return ("merged" if changed else "duplicate"), dup["id"], changed
    if dry_run:
        return "new", None, []

    if t["direction"] == "out" and t["counterparty_type"] == "self":
        t["status"] = "excluded"
        t["exclude_reason"] = t.get("exclude_reason") or "Transferencia a ti mismo"
    if t["direction"] == "out" and t["status"] != "excluded":
        for rule in db.query("SELECT * FROM rules WHERE field='counterparty' AND action IN ('not_expense','ignore')"):
            if cat._match(rule["pattern"], t["counterparty"], bool(rule["is_regex"])):
                t["status"] = "excluded"
                t["exclude_reason"] = rule["note"] or "Regla: esta contraparte no cuenta como gasto"
                break
    with db.tx() as conn:
        cur = conn.execute(
            "INSERT INTO transactions(source, direction, account, account_detail, amount, currency, occurred_at, "
            "counterparty, counterparty_type, description, status, exclude_reason, flags, external_ref, dedupe_key, email_id) "
            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (t["source"], t["direction"], t.get("account", ""), t["account_detail"], t["amount"],
             t.get("currency", "PEN"), t["occurred_at"], t["counterparty"], t["counterparty_type"],
             t["description"], t["status"], t.get("exclude_reason", ""), json.dumps(sorted(set(t["flags"]))),
             t["external_ref"], t.get("dedupe_key"), t.get("email_id")),
        )
        tx_id = cur.lastrowid
    if t["direction"] == "out":
        _auto_categorize(tx_id)
        cat.refresh_flags(tx_id)
    return "new", tx_id, []


def _auto_categorize(tx_id: int, force: bool = False) -> None:
    row = db.query_one("SELECT * FROM transactions WHERE id=?", (tx_id,))
    if not row or (row["category_source"] == "user" and not force) or row["category_source"] == "user":
        return
    cid, src = cat.suggest_category(row["counterparty"], row["description"], row["counterparty_type"])
    if cid or force:
        with db.tx() as conn:
            conn.execute("UPDATE transactions SET category_id=?, category_source=? WHERE id=?", (cid, src, tx_id))


def _tx_from_parse(r: ParseResult, email_id: int) -> dict[str, Any]:
    status, reason = "pending", ""
    direction = "in" if r.kind == "income" else "out"
    if r.kind == "not_expense":
        status, reason = "excluded", r.reason
    return {
        "source": "email", "direction": direction, "account": r.account, "account_detail": r.account_detail,
        "amount": r.amount, "currency": r.currency, "occurred_at": fmt_dt(r.occurred_at),
        "counterparty": r.counterparty, "counterparty_type": r.counterparty_type, "description": r.description,
        "status": status, "exclude_reason": reason, "flags": r.flags, "external_ref": r.external_ref,
        "email_id": email_id,
    }


# ===================================================================== reglas

def match_user_rules(e: Email) -> dict[str, Any] | None:
    for r in db.query("SELECT * FROM rules WHERE field IN ('from','subject','body') ORDER BY id"):
        hay = {"from": e.from_addr, "subject": e.subject, "body": e.body}[r["field"]]
        if cat._match(r["pattern"], hay, bool(r["is_regex"])):
            with db.tx() as conn:
                conn.execute("UPDATE rules SET hits=hits+1 WHERE id=?", (r["id"],))
            return dict(r)
    return None


# ===================================================================== correo

@dataclass
class IncomingEmail:
    message_id: str
    from_addr: str
    subject: str
    html: str = ""
    text: str = ""
    received_at: datetime | None = None
    attachments: list[tuple[str, bytes]] = field(default_factory=list)


def process_email(m: IncomingEmail, force_kind: str | None = None) -> dict[str, Any]:
    """Procesa un correo. Idempotente por Message-ID."""
    if db.query_one("SELECT id FROM emails WHERE message_id=?", (m.message_id,)) and not force_kind:
        return {"status": "already_processed"}
    e = Email(from_addr=m.from_addr, subject=m.subject, html=m.html, text=m.text, received_at=m.received_at,
              attachments=[a[0] for a in m.attachments])
    rule = match_user_rules(e)
    if force_kind:
        r = parse_email(e)
        decision_source = "user"
        if force_kind == "ignore":
            r = ParseResult(kind="ignore", parser="usuario", reason="Marcado por el usuario")
        elif r.kind in ("unknown", "ignore") and force_kind == "expense":
            r.kind = "unknown"  # queda en revisión: necesita monto
    elif rule and rule["action"] == "ignore":
        r = ParseResult(kind="ignore", parser=f"regla:{rule['id']}", reason=rule["note"] or "Regla del usuario")
        decision_source = f"rule:{rule['id']}"
    else:
        r = parse_email(e)
        decision_source = f"parser:{r.parser}"
        if rule and rule["action"] in ("expense", "not_expense", "income") and r.amount is not None:
            r.kind = rule["action"]
            decision_source = f"rule:{rule['id']}"

    extra: dict[str, Any] = {}
    if r.kind == "unknown" and llm.enabled():
        try:
            s = llm.extract_email(m.from_addr, m.subject, e.body)
            extra["llm"] = s
            if s.get("kind") in ("expense", "income", "not_expense") and s.get("amount"):
                r.kind = s["kind"]
                r.amount = float(s["amount"])
                r.currency = s.get("currency") or "PEN"
                r.counterparty = r.counterparty or s.get("counterparty", "")
                r.description = r.description or s.get("description", "")
                if s.get("occurred_at"):
                    try:
                        r.occurred_at = datetime.fromisoformat(s["occurred_at"])
                    except ValueError:
                        pass
                r.occurred_at = r.occurred_at or m.received_at
                r.account = r.account or bank_name(m.from_addr)
                r.flags.append("llm")
                r.reason = "Sugerido por IA: " + s.get("reason", "")
                decision_source = "llm"
            elif s.get("kind") == "ignore":
                r.reason = "IA sugiere que no es un movimiento: " + s.get("reason", "") + " (confírmalo)"
        except llm.LLMError as exc:
            extra["llm_error"] = str(exc)

    decision = r.kind if r.kind != "yape_history" else "import"
    with db.tx() as conn:
        conn.execute("DELETE FROM emails WHERE message_id=?", (m.message_id,))
        cur = conn.execute(
            "INSERT INTO emails(message_id, from_addr, subject, received_at, body_text, decision, decision_source, reason) "
            "VALUES (?,?,?,?,?,?,?,?)",
            (m.message_id, m.from_addr, m.subject, fmt_dt(m.received_at) if m.received_at else None,
             e.body[:20000], decision, decision_source, r.reason),
        )
        email_id = cur.lastrowid

    result: dict[str, Any] = {"status": decision, "parser": r.parser, "reason": r.reason, **extra}
    if r.kind == "yape_history":
        summ = Counter()
        for name, data in m.attachments:
            if name.lower().endswith((".xlsx", ".xls")):
                try:
                    s = import_yape_rows(parse_yape_excel(data))
                    summ.update(s)
                except Exception as exc:  # noqa: BLE001
                    result["import_error"] = str(exc)
        result["import"] = dict(summ)
        with db.tx() as conn:
            conn.execute("UPDATE emails SET reason=? WHERE id=?",
                         (f"Excel de Yape importado: {dict(summ)}" if summ else
                          "Correo de historial sin Excel adjunto legible", email_id))
    elif r.kind in ("expense", "income", "not_expense") and r.amount is not None and r.occurred_at:
        state, tx_id, _ = add_transaction(_tx_from_parse(r, email_id))
        result["transaction"] = {"state": state, "id": tx_id}
        with db.tx() as conn:
            conn.execute("UPDATE emails SET transaction_id=?, decision=? WHERE id=?",
                         (tx_id, "duplicate" if state != "new" else decision, email_id))
        if state == "new" and r.kind == "expense":
            notify_new(tx_id)
    return result


# ===================================================================== Yape Excel

def learn_owner_names(rows: list[YapeRow]) -> None:
    """En el Excel, el 'Origen' de PAGASTE eres tú. Lo guardamos para detectar
    transferencias a ti mismo sin que el usuario tenga que configurarlo."""
    names = Counter(r.origin for r in rows if r.direction == "out" and r.origin)
    current = [n.strip() for n in db.get_setting("owner_names").split(",") if n.strip()]
    for name, _ in names.most_common(2):
        if not any(norm(name) == norm(c) for c in current):
            current.append(name)
    db.set_setting("owner_names", ", ".join(current))


def yape_row_to_tx(r: YapeRow) -> dict[str, Any]:
    cp = r.counterparty
    detail = "Yape"
    m = re.match(r"^(PLIN|BIM)\s*-\s*", cp, re.I)
    if m:
        detail = f"Yape → {m.group(1).upper()}"
    if cp.lower().startswith("pagaste el servicio"):
        # 'Pagaste el servicio de JUAN PEREZ' + mensaje 'Bitel - Postpago'
        company = r.message.split(" - ")[0].strip() if r.message else ""
        cp = company or cp
    return {
        "source": "yape_excel", "direction": r.direction, "account": "Yape", "account_detail": detail,
        "amount": r.amount, "currency": "PEN", "occurred_at": fmt_dt(r.occurred_at), "counterparty": cp,
        "description": r.message or "", "dedupe_key": r.dedupe_key(),
    }


def import_yape_rows(rows: list[YapeRow], dry_run: bool = False) -> dict[str, int]:
    if not dry_run:
        learn_owner_names(rows)
    summary = Counter()
    # Primero ingresos, para que la detección de "pase de dinero" los vea.
    for r in sorted(rows, key=lambda x: (x.direction != "in", x.occurred_at)):
        state, _, changes = add_transaction(yape_row_to_tx(r), dry_run=dry_run)
        key = ("ingreso_" if r.direction == "in" else "gasto_") + state
        summary[key] += 1
        if "descripción" in changes:
            summary["descripciones_completadas"] += 1
    if not dry_run and rows:
        cat.refresh_all_flags(since=fmt_dt(min(r.occurred_at for r in rows) - timedelta(days=45)))
    return dict(summary)


def preview_yape(rows: list[YapeRow]) -> list[dict[str, Any]]:
    out = []
    for r in sorted(rows, key=lambda x: x.occurred_at, reverse=True):
        t = yape_row_to_tx(r)
        t["counterparty_type"] = cat.guess_counterparty_type(t["counterparty"])
        dup = find_duplicate(t)
        out.append({**t, "state": ("duplicado" if dup else "nuevo"), "match": dict(dup) if dup else None,
                    "is_self": t["counterparty_type"] == "self"})
    return out


# ===================================================================== IMAP

def _decode_message(raw: bytes) -> IncomingEmail:
    msg = email.message_from_bytes(raw, policy=email.policy.default)
    html, text, atts = "", "", []
    for part in msg.walk():
        if part.is_multipart():
            continue
        fname = part.get_filename()
        ctype = part.get_content_type()
        if fname:
            try:
                atts.append((fname, part.get_payload(decode=True) or b""))
            except Exception:  # noqa: BLE001
                pass
            continue
        try:
            content = part.get_content()
        except Exception:  # noqa: BLE001
            content = (part.get_payload(decode=True) or b"").decode("utf-8", "replace")
        if ctype == "text/html" and not html:
            html = content
        elif ctype == "text/plain" and not text:
            text = content
    received = None
    if msg["Date"]:
        try:
            received = parsedate_to_datetime(msg["Date"]).astimezone(TZ).replace(tzinfo=None)
        except (TypeError, ValueError):
            received = None
    mid = (msg["Message-ID"] or "").strip() or f"nomid-{hash(raw)}"
    return IncomingEmail(message_id=mid, from_addr=str(msg["From"] or ""), subject=str(msg["Subject"] or ""),
                         html=html, text=text, received_at=received, attachments=atts)


def sync_imap(days_back: int | None = None) -> dict[str, Any]:
    host = db.get_setting("imap_host")
    user = db.get_setting("imap_user")
    pwd = db.get_setting("imap_password")
    if not (host and user and pwd):
        return {"ok": False, "error": "Configura tu correo en Ajustes (usuario y contraseña de aplicación)."}
    days = days_back or int(db.get_setting("sync_days_back") or 60)
    since = (datetime.now() - timedelta(days=days)).strftime("%d-%b-%Y")
    folder = db.get_setting("imap_folder") or "INBOX"
    sources = [r["from_pattern"] for r in db.query("SELECT from_pattern FROM sources WHERE enabled=1")]
    with db.tx() as conn:
        log_id = conn.execute("INSERT INTO sync_log(summary) VALUES ('en curso')").lastrowid
    stats = Counter()
    try:
        conn_imap = imaplib.IMAP4_SSL(host, int(db.get_setting("imap_port") or 993))
        conn_imap.login(user, pwd)
        typ, _ = conn_imap.select(folder, readonly=True)
        if typ != "OK":
            conn_imap.select("INBOX", readonly=True)
        ids: set[bytes] = set()
        for pat in sources:
            typ, data = conn_imap.search(None, "SINCE", since, "FROM", f'"{pat}"')
            if typ == "OK" and data and data[0]:
                ids.update(data[0].split())
        known = {r["message_id"] for r in db.query("SELECT message_id FROM emails")}
        for num in sorted(ids, key=int):
            typ, hdr = conn_imap.fetch(num, "(BODY.PEEK[HEADER.FIELDS (MESSAGE-ID)])")
            mid = ""
            if typ == "OK" and hdr and isinstance(hdr[0], tuple):
                mm = re.search(rb"Message-ID:\s*(<[^>]+>)", hdr[0][1], re.I)
                mid = mm.group(1).decode() if mm else ""
            if mid and mid in known:
                stats["ya_procesados"] += 1
                continue
            typ, data = conn_imap.fetch(num, "(BODY.PEEK[])")  # PEEK: no marca como leído
            if typ != "OK" or not data or not isinstance(data[0], tuple):
                stats["error_descarga"] += 1
                continue
            res = process_email(_decode_message(data[0][1]))
            stats[res["status"]] += 1
        conn_imap.logout()
        ok, summary = True, dict(stats)
    except (imaplib.IMAP4.error, OSError) as exc:
        ok, summary = False, {"error": str(exc), **stats}
    with db.tx() as conn:
        conn.execute("UPDATE sync_log SET finished_at=datetime('now'), ok=?, summary=? WHERE id=?",
                     (1 if ok else 0, json.dumps(summary, ensure_ascii=False), log_id))
    if ok and llm.enabled():
        try:
            summary["categorizados_ia"] = categorize_pending_with_llm()
        except llm.LLMError as exc:
            summary["error_ia"] = str(exc)
    return {"ok": ok, **summary}


# ===================================================================== LLM en lote

def categorize_pending_with_llm(limit: int = 60) -> int:
    rows = db.query(
        "SELECT * FROM transactions WHERE direction='out' AND status!='excluded' AND category_source IN ('', 'llm') "
        "AND category_id IS NULL ORDER BY occurred_at DESC LIMIT ?", (limit,))
    if not rows:
        return 0
    cats = {c["name"]: c["id"] for c in db.query("SELECT id, name FROM categories")}
    items = [{"id": r["id"], "contraparte": r["counterparty"], "descripcion": r["description"],
              "monto": r["amount"], "moneda": r["currency"], "medio": f"{r['account']} {r['account_detail']}"}
             for r in rows]
    examples = [dict(x) for x in db.query("SELECT input, output FROM feedback WHERE kind='category' ORDER BY id DESC LIMIT 30")]
    out = llm.categorize(items, list(cats), examples)
    n = 0
    for o in out:
        cid = cats.get(o.get("category", ""))
        if not cid or o.get("id") not in {r["id"] for r in rows}:
            continue
        with db.tx() as conn:
            conn.execute("UPDATE transactions SET category_id=?, category_source='llm' WHERE id=? AND category_source!='user'",
                         (cid, o["id"]))
            if o.get("maybe_not_expense"):
                row = db.row_to_dict(conn.execute("SELECT * FROM transactions WHERE id=?", (o["id"],)).fetchone())
                flags = sorted(set((row or {}).get("flags", []) + ["posible_cambio_o_encargo"]))
                conn.execute("UPDATE transactions SET flags=? WHERE id=?", (json.dumps(flags), o["id"]))
            if o.get("counterparty_type") in ("person", "business"):
                conn.execute("UPDATE transactions SET counterparty_type=? WHERE id=? AND counterparty_type='unknown'",
                             (o["counterparty_type"], o["id"]))
        n += 1
    return n


# ===================================================================== notificaciones

def notify_new(tx_id: int | None) -> None:
    url = db.get_setting("ntfy_url").strip()
    if not url or not tx_id:
        return
    row = db.query_one("SELECT * FROM transactions WHERE id=?", (tx_id,))
    if not row or row["notified"]:
        return
    if row["counterparty_type"] == "person" and not row["description"]:
        msg = f"Pagaste S/ {row['amount']:.2f} a {row['counterparty']}. ¿En qué fue? Agrega una descripción."
    else:
        msg = f"Nuevo gasto: {row['currency']} {row['amount']:.2f} en {row['counterparty'] or row['account']}"
    try:
        httpx.post(url, content=msg.encode(), headers={"Title": "Mis gastos", "Click": f"{config.BASE_URL}/t/{tx_id}"},
                   timeout=10)
        with db.tx() as conn:
            conn.execute("UPDATE transactions SET notified=1 WHERE id=?", (tx_id,))
    except httpx.HTTPError as exc:
        log.warning("ntfy falló: %s", exc)


def counterparty_examples(tx: dict[str, Any]) -> str:
    return f"{tx['counterparty']} | {tx['description']} | {tx['currency']} {tx['amount']}"


__all__ = ["add_transaction", "process_email", "sync_imap", "import_yape_rows", "preview_yape",
           "IncomingEmail", "categorize_pending_with_llm", "counterparty_key"]
