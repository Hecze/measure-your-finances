"""Servidor web: FastAPI + plantillas Jinja2 (sin build de frontend)."""
from __future__ import annotations

import asyncio
import hmac
import json
import logging
from contextlib import asynccontextmanager
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any

from fastapi import FastAPI, File, Form, HTTPException, Request, UploadFile
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from starlette.middleware.sessions import SessionMiddleware

from . import analytics, categorize as cat, config, db, ingest, llm
from .parsers import Email, parse_email
from .textutil import counterparty_key
from .yape_excel import YapeExcelError, parse_yape_excel

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("finanzas")
BASE = Path(__file__).parent
templates = Jinja2Templates(directory=str(BASE / "templates"))


def money(v: float | None, currency: str = "PEN") -> str:
    if v is None:
        return "—"
    sym = "US$" if currency == "USD" else "S/"
    return f"{sym} {v:,.2f}"


templates.env.filters["money"] = money
templates.env.globals["FLAG_LABELS"] = cat.FLAG_LABELS


# ------------------------------------------------------------- sync en segundo plano
async def _sync_loop() -> None:
    while True:
        try:
            minutes = int(db.get_setting("sync_interval_minutes") or 15)
        except ValueError:
            minutes = 15
        if minutes > 0 and db.get_setting("imap_user") and db.get_setting("imap_password"):
            try:
                res = await asyncio.to_thread(ingest.sync_imap)
                log.info("sync: %s", res)
            except Exception:  # noqa: BLE001
                log.exception("sync falló")
        await asyncio.sleep(max(60, minutes * 60))


@asynccontextmanager
async def lifespan(_: FastAPI):
    db.init_db()
    task = asyncio.create_task(_sync_loop())
    yield
    task.cancel()


app = FastAPI(title="Mis Gastos", lifespan=lifespan)
app.add_middleware(SessionMiddleware, secret_key=config.SECRET_KEY, max_age=60 * 60 * 24 * 30, same_site="lax")
app.mount("/static", StaticFiles(directory=str(BASE / "static")), name="static")


@app.middleware("http")
async def auth(request: Request, call_next):
    open_paths = ("/login", "/static", "/healthz")
    if config.APP_PASSWORD and not request.url.path.startswith(open_paths):
        if not request.session.get("ok"):
            if request.url.path.startswith("/api"):
                return JSONResponse({"error": "no autenticado"}, status_code=401)
            return RedirectResponse("/login", status_code=303)
    return await call_next(request)


def render(request: Request, name: str, **ctx: Any) -> HTMLResponse:
    ctx.setdefault("wide", True)
    return templates.TemplateResponse(request, name, ctx)


def categories() -> list[dict[str, Any]]:
    return [dict(r) for r in db.query("SELECT * FROM categories ORDER BY sort_order, name")]


# ------------------------------------------------------------- login
@app.get("/healthz")
def healthz() -> dict[str, str]:
    return {"ok": "1"}


@app.get("/login", response_class=HTMLResponse)
def login_form(request: Request):
    return templates.TemplateResponse(request, "login.html", {"error": ""})


@app.post("/login")
def login(request: Request, password: str = Form(...)):
    if config.APP_PASSWORD and hmac.compare_digest(password, config.APP_PASSWORD):
        request.session["ok"] = True
        return RedirectResponse("/", status_code=303)
    return templates.TemplateResponse(request, "login.html", {"error": "Contraseña incorrecta"}, status_code=401)


@app.get("/logout")
def logout(request: Request):
    request.session.clear()
    return RedirectResponse("/login", status_code=303)


# ------------------------------------------------------------- vistas principales
MONTHS_ES = ["enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre",
             "noviembre", "diciembre"]
DAYS_ES = ["lun", "mar", "mié", "jue", "vie", "sáb", "dom"]


def month_name(ym: str) -> str:
    y, m = (int(x) for x in ym.split("-"))
    return f"{MONTHS_ES[m - 1].capitalize()} {y}" if y != datetime.now().year else MONTHS_ES[m - 1].capitalize()


def when_label(ts: str) -> str:
    dt = datetime.fromisoformat(ts)
    return f"{DAYS_ES[dt.weekday()]} {dt.day} {MONTHS_ES[dt.month - 1][:3]} · {dt:%H:%M}"


def ordered_categories() -> list[dict[str, Any]]:
    """Categorías ordenadas por cuánto las usas (para los botones rápidos)."""
    return [dict(r) for r in db.query(
        "SELECT c.id, c.name, c.icon, COUNT(t.id) n FROM categories c LEFT JOIN transactions t "
        "ON t.category_id=c.id AND t.category_source='user' GROUP BY c.id ORDER BY n DESC, c.sort_order")]


def hint_for(t: dict[str, Any]) -> str:
    """Una sola pista, la más útil, en lenguaje simple."""
    flags = t.get("flags") or []
    if "pase_de_dinero" in flags:
        p = cat.find_passthrough(t)
        if p:
            return f"{p['counterparty']} te envió {money(p['amount'])} a las {p['occurred_at'][11:16]}. ¿Era para pagar esto?"
    if "persona_tambien_te_envia" in flags:
        r = cat.find_reciprocal(t)
        if r:
            return f"{r['counterparty']} también te envió {money(r['total'])} estas semanas. ¿Es un encargo?"
    if "posible_cambio_o_encargo" in flags:
        return f"Dice “{t['description']}”. ¿Fue un cambio de efectivo o un encargo?"
    if "revisar_transferencia" in flags:
        return "Es una transferencia. ¿Fue un gasto tuyo?"
    if "intermediario_pago" in flags:
        return "Pagado con PagoEfectivo: escribe qué compraste."
    if "llm" in flags or "plantilla_generica" in flags:
        return "Lo leímos de un correo nuevo. Verifica el monto."
    if "posible_duplicado_promo" in flags:
        return "Puede estar repetido con otro pago igual."
    return ""


def tx_item(t: dict[str, Any]) -> dict[str, Any]:
    return {"id": t["id"], "amount": t["amount"], "currency": t["currency"],
            "who": t["counterparty"] or t["account"] or "Sin nombre",
            "when": when_label(t["occurred_at"]) + (f" · {t['account']}" if t["account"] else ""),
            "description": t["description"], "category_id": t["category_id"], "status": t["status"],
            "hint": hint_for(t), "icon": t.get("category_icon") or ("👤" if t["counterparty_type"] == "person" else "🏪")}


REVIEW_FLAGS = ("pase_de_dinero", "persona_tambien_te_envia", "posible_cambio_o_encargo", "revisar_transferencia",
                "intermediario_pago", "llm", "plantilla_generica", "posible_duplicado_promo")


def build_queue() -> list[dict[str, Any]]:
    items: list[dict[str, Any]] = []
    last = analytics.last_yape_tx()
    if last is None or (datetime.now() - datetime.fromisoformat(last)).days > 10:
        items.append({"type": "yape", "last": last[:10] if last else None,
                      "auto": bool(db.get_setting("imap_user"))})
    for e in db.query("SELECT id, subject, from_addr, received_at FROM emails WHERE decision='unknown' "
                      "ORDER BY received_at DESC LIMIT 30"):
        items.append({"type": "email", **dict(e)})
    since = (datetime.now() - timedelta(days=45)).strftime("%Y-%m-%d")
    rows = [db.row_to_dict(r) for r in db.query(
        "SELECT * FROM transactions WHERE direction='out' AND status='pending' ORDER BY occurred_at DESC LIMIT 600")]
    flagged, nodesc = [], []
    for t in rows:
        if any(f in REVIEW_FLAGS for f in t["flags"]):
            flagged.append(t)
        elif t["counterparty_type"] == "person" and not t["description"].strip() and t["occurred_at"] >= since:
            nodesc.append(t)
    items += [{"type": "tx", **tx_item(t)} for t in flagged + nodesc]
    return items


@app.get("/", response_class=HTMLResponse)
def home(request: Request, m: str | None = None):
    d = analytics.dashboard(m)
    queue = build_queue()
    n_todo = len(queue)
    n_nodesc = sum(1 for i in queue if i["type"] == "tx" and not i["description"])
    delta = None
    if d["prev_total"]:
        delta = round((d["total"] - d["prev_total"]) / d["prev_total"] * 100)
    return templates.TemplateResponse(request, "home.html", {
        "d": d, "month": month_name(d["ym"]), "prev_month": month_name(d["prev_ym"]).split(" ")[0].lower(),
        "delta": delta, "n_todo": n_todo, "n_nodesc": n_nodesc,
        "is_current": d["ym"] == datetime.now().strftime("%Y-%m"),
    })


@app.get("/pendientes", response_class=HTMLResponse)
def pending_page(request: Request):
    return templates.TemplateResponse(request, "pending.html", {"queue": build_queue(), "cats": ordered_categories()})


@app.get("/lista", response_class=HTMLResponse)
def list_page(request: Request, m: str | None = None, c: str = "", q: str = ""):
    ym, start, end = analytics.month_bounds(m)
    where, params = ["direction='out'", "occurred_at >= ?", "occurred_at < ?"], [start, end]
    title = "Todos los gastos"
    if c == "none":
        where.append("category_id IS NULL")
        title = "Sin categoría"
    elif c:
        where.append("category_id=?")
        params.append(int(c))
        crow = db.query_one("SELECT name, icon FROM categories WHERE id=?", (int(c),))
        title = f"{crow['icon']} {crow['name']}" if crow else title
    if q:
        where.append("(counterparty LIKE ? OR description LIKE ?)")
        params += [f"%{q}%", f"%{q}%"]
        title = f"“{q}”"
    rows = [db.row_to_dict(r) for r in db.query(
        "SELECT t.*, c.icon category_icon FROM transactions t LEFT JOIN categories c ON c.id=t.category_id WHERE "
        + " AND ".join(where) + " ORDER BY occurred_at DESC", tuple(params))]
    total = sum(analytics.pen(r["amount"], r["currency"]) for r in rows if r["status"] != "excluded")
    return templates.TemplateResponse(request, "list.html", {
        "rows": rows, "items": [tx_item(r) for r in rows], "title": title, "total": total, "month": month_name(ym),
        "ym": ym, "c": c, "q": q, "cats": ordered_categories()})


@app.get("/t/{tx_id}", response_class=HTMLResponse)
def tx_page(request: Request, tx_id: int):
    t = db.row_to_dict(db.query_one("SELECT * FROM transactions WHERE id=?", (tx_id,)))
    if not t:
        raise HTTPException(404)
    return templates.TemplateResponse(request, "pending.html", {"queue": [{"type": "tx", **tx_item(t)}],
                                                                "cats": ordered_categories(), "single": True})


@app.post("/api/tx/{tx_id}")
async def tx_update(tx_id: int, request: Request):
    data = await request.json()
    row = db.row_to_dict(db.query_one("SELECT * FROM transactions WHERE id=?", (tx_id,)))
    if not row:
        raise HTTPException(404)
    sets: dict[str, Any] = {}
    if "description" in data:
        sets["description"] = str(data["description"]).strip()[:300]
    if "category_id" in data:
        sets["category_id"] = int(data["category_id"]) if data["category_id"] not in (None, "", "none") else None
        sets["category_source"] = "user"
    if "status" in data and data["status"] in ("pending", "confirmed", "excluded"):
        sets["status"] = data["status"]
        sets["exclude_reason"] = str(data.get("exclude_reason", "")).strip()[:200] if data["status"] == "excluded" else ""
    if "counterparty_type" in data and data["counterparty_type"] in ("person", "business", "unknown"):
        sets["counterparty_type"] = data["counterparty_type"]
    if "amount" in data:
        try:
            sets["amount"] = abs(float(data["amount"]))
        except (TypeError, ValueError):
            pass
    if not sets:
        return {"ok": True}
    q = ", ".join(f"{k}=?" for k in sets)
    with db.tx() as conn:
        conn.execute(f"UPDATE transactions SET {q}, updated_at=datetime('now') WHERE id=?", (*sets.values(), tx_id))
    new = db.row_to_dict(db.query_one("SELECT * FROM transactions WHERE id=?", (tx_id,)))

    # --- aprender de la corrección
    if "category_id" in sets and sets["category_id"]:
        cname = db.query_one("SELECT name FROM categories WHERE id=?", (sets["category_id"],))["name"]
        with db.tx() as conn:
            conn.execute("INSERT INTO feedback(kind, input, output) VALUES ('category', ?, ?)",
                         (f"{new['counterparty']} | {new['description']} | {new['amount']}", cname))
        if new["counterparty_type"] != "person" or data.get("remember"):
            cat.remember(new["counterparty"], sets["category_id"], new["counterparty_type"])
    if "description" in sets and "category_id" not in sets and new["category_source"] != "user":
        cid, src = cat.suggest_category(new["counterparty"], new["description"], new["counterparty_type"])
        if cid:
            with db.tx() as conn:
                conn.execute("UPDATE transactions SET category_id=?, category_source=? WHERE id=?", (cid, src, tx_id))
    if sets.get("status") == "excluded":
        with db.tx() as conn:
            conn.execute("INSERT INTO feedback(kind, input, output) VALUES ('not_expense', ?, ?)",
                         (f"{new['counterparty']} | {new['description']} | {new['amount']}", sets["exclude_reason"]))
            if data.get("remember"):
                conn.execute("INSERT INTO rules(action, field, pattern, note) VALUES ('not_expense','counterparty',?,?)",
                             (new["counterparty"], f"No es gasto: {sets['exclude_reason'] or 'marcado por ti'}"))
    if "counterparty_type" in sets:
        cat.remember(new["counterparty"], new["category_id"], sets["counterparty_type"])
    cat.refresh_flags(tx_id)
    new = db.row_to_dict(db.query_one(
        "SELECT t.*, c.name category_name, c.icon category_icon FROM transactions t "
        "LEFT JOIN categories c ON c.id=t.category_id WHERE t.id=?", (tx_id,)))
    return {"ok": True, "tx": new}


@app.post("/api/tx/bulk")
async def tx_bulk(request: Request):
    data = await request.json()
    ids = [int(i) for i in data.get("ids", [])][:1000]
    action = data.get("action")
    if not ids or action not in ("confirm", "exclude", "pending"):
        return {"ok": False}
    status = {"confirm": "confirmed", "exclude": "excluded", "pending": "pending"}[action]
    with db.tx() as conn:
        conn.executemany("UPDATE transactions SET status=?, exclude_reason=?, updated_at=datetime('now') WHERE id=?",
                         [(status, data.get("reason", "") if status == "excluded" else "", i) for i in ids])
    return {"ok": True, "n": len(ids)}


@app.post("/api/tx")
async def tx_create(request: Request):
    d = await request.json()
    try:
        amount = abs(float(d["amount"]))
        when = d.get("occurred_at") or datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        when = when.replace("T", " ")
        if len(when) == 16:
            when += ":00"
    except (KeyError, ValueError):
        raise HTTPException(400, "monto inválido")
    state, tx_id, _ = ingest.add_transaction({
        "source": "manual", "direction": "out", "account": d.get("account") or "Efectivo",
        "account_detail": d.get("account_detail", ""), "amount": amount, "currency": d.get("currency", "PEN"),
        "occurred_at": when, "counterparty": d.get("counterparty", ""), "description": d.get("description", ""),
        "status": "confirmed",
    })
    if d.get("category_id") and tx_id:
        with db.tx() as conn:
            conn.execute("UPDATE transactions SET category_id=?, category_source='user' WHERE id=?",
                         (int(d["category_id"]), tx_id))
    return {"ok": True, "state": state, "id": tx_id}


@app.delete("/api/tx/{tx_id}")
def tx_delete(tx_id: int):
    with db.tx() as conn:
        conn.execute("DELETE FROM transactions WHERE id=? AND source='manual'", (tx_id,))
    return {"ok": True}


# ------------------------------------------------------------- importar Yape
@app.get("/importar", response_class=HTMLResponse)
def import_form(request: Request):
    return render(request, "import.html", wide=False, preview=None, summary=None, error="",
                  last_yape=analytics.last_yape_tx())


_PREVIEWS: dict[str, bytes] = {}


@app.post("/importar", response_class=HTMLResponse)
async def import_upload(request: Request, file: UploadFile = File(...)):
    data = await file.read()
    try:
        rows = parse_yape_excel(data)
    except YapeExcelError as exc:
        return render(request, "import.html", wide=False, preview=None, summary=None, error=str(exc),
                      last_yape=analytics.last_yape_tx())
    token = f"{datetime.now().timestamp():.0f}-{len(data)}"
    _PREVIEWS.clear()
    _PREVIEWS[token] = data
    preview = ingest.preview_yape(rows)
    stats = {
        "gastos_nuevos": sum(1 for p in preview if p["direction"] == "out" and p["state"] == "nuevo"),
        "gastos_duplicados": sum(1 for p in preview if p["direction"] == "out" and p["state"] != "nuevo"),
        "ingresos": sum(1 for p in preview if p["direction"] == "in"),
        "desde": min((r.occurred_at for r in rows), default=None),
        "hasta": max((r.occurred_at for r in rows), default=None),
    }
    return render(request, "import.html", wide=False, preview=preview, stats=stats, token=token, summary=None, error="",
                  last_yape=analytics.last_yape_tx())


@app.post("/importar/confirmar", response_class=HTMLResponse)
def import_confirm(request: Request, token: str = Form(...)):
    data = _PREVIEWS.pop(token, None)
    if data is None:
        return render(request, "import.html", wide=False, preview=None, summary=None,
                      error="La vista previa expiró. Sube el archivo otra vez.", last_yape=analytics.last_yape_tx())
    summary = ingest.import_yape_rows(parse_yape_excel(data))
    return render(request, "import.html", wide=False, preview=None, summary=summary, error="", last_yape=analytics.last_yape_tx())


# ------------------------------------------------------------- correos
@app.get("/correos", response_class=HTMLResponse)
def emails(request: Request, d: str = ""):
    where, params = "", ()
    if d:
        where, params = "WHERE decision=?", (d,)
    rows = [dict(r) for r in db.query(f"SELECT id, from_addr, subject, received_at, decision, decision_source, reason, "
                                      f"transaction_id FROM emails {where} ORDER BY received_at DESC LIMIT 300", params)]
    counts = {r["decision"]: r["n"] for r in db.query("SELECT decision, COUNT(*) n FROM emails GROUP BY decision")}
    last_sync = db.query_one("SELECT * FROM sync_log ORDER BY id DESC LIMIT 1")
    return render(request, "emails.html", rows=rows, counts=counts, filt=d,
                  last_sync=dict(last_sync) if last_sync else None)


@app.get("/correos/{email_id}", response_class=HTMLResponse)
def email_detail(request: Request, email_id: int):
    row = db.query_one("SELECT * FROM emails WHERE id=?", (email_id,))
    if not row:
        raise HTTPException(404)
    e = dict(row)
    parsed = parse_email(Email(from_addr=e["from_addr"], subject=e["subject"], text=e["body_text"]))
    tx = db.row_to_dict(db.query_one("SELECT * FROM transactions WHERE id=?", (e["transaction_id"],))) \
        if e["transaction_id"] else None
    return render(request, "email.html", e=e, parsed=parsed.as_dict(), tx=tx, cats=categories())


@app.post("/api/email/{email_id}/decide")
async def email_decide(email_id: int, request: Request):
    """El usuario corrige la decisión sobre un correo.

    * ignore: no es un movimiento. Opcionalmente crea una regla por asunto.
    * expense / not_expense / income: crea (o corrige) la transacción con los datos indicados.
    """
    d = await request.json()
    e = db.query_one("SELECT * FROM emails WHERE id=?", (email_id,))
    if not e:
        raise HTTPException(404)
    action = d.get("action")
    if action == "ignore":
        with db.tx() as conn:
            conn.execute("UPDATE emails SET decision='ignored', decision_source='user', reason=? WHERE id=?",
                         (d.get("reason", "Marcado por ti: no es un movimiento"), email_id))
            if e["transaction_id"]:
                conn.execute("UPDATE transactions SET status='excluded', exclude_reason='Correo marcado como no movimiento' "
                             "WHERE id=?", (e["transaction_id"],))
            if d.get("rule_pattern"):
                conn.execute("INSERT INTO rules(action, field, pattern, note) VALUES ('ignore', ?, ?, ?)",
                             (d.get("rule_field", "subject"), d["rule_pattern"], "Creada desde un correo"))
            conn.execute("INSERT INTO feedback(kind, input, output) VALUES ('email_decision', ?, 'ignore')",
                         (f"{e['from_addr']} | {e['subject']}",))
        return {"ok": True}
    if action in ("expense", "not_expense", "income"):
        try:
            amount = abs(float(d["amount"]))
        except (KeyError, TypeError, ValueError):
            raise HTTPException(400, "Indica el monto")
        when = (d.get("occurred_at") or e["received_at"] or datetime.now().strftime("%Y-%m-%d %H:%M:%S"))
        when = when.replace("T", " ")
        if len(when) == 16:
            when += ":00"
        t = {"source": "email", "direction": "in" if action == "income" else "out",
             "account": d.get("account") or "", "account_detail": d.get("account_detail", ""), "amount": amount,
             "currency": d.get("currency", "PEN"), "occurred_at": when, "counterparty": d.get("counterparty", ""),
             "description": d.get("description", ""), "email_id": email_id,
             "status": "excluded" if action == "not_expense" else "confirmed",
             "exclude_reason": d.get("reason", "") if action == "not_expense" else ""}
        state, tx_id, _ = ingest.add_transaction(t)
        with db.tx() as conn:
            conn.execute("UPDATE emails SET decision=?, decision_source='user', transaction_id=? WHERE id=?",
                         (action, tx_id, email_id))
            conn.execute("INSERT INTO feedback(kind, input, output) VALUES ('email_decision', ?, ?)",
                         (f"{e['from_addr']} | {e['subject']}", action))
        return {"ok": True, "state": state, "id": tx_id}
    raise HTTPException(400, "acción inválida")


# ------------------------------------------------------------- reglas y probador
@app.get("/reglas", response_class=HTMLResponse)
def rules_page(request: Request):
    rules = [dict(r) for r in db.query("SELECT r.*, c.name category_name FROM rules r "
                                       "LEFT JOIN categories c ON c.id=r.category_id ORDER BY r.id DESC")]
    sources = [dict(r) for r in db.query("SELECT * FROM sources ORDER BY name")]
    return render(request, "rules.html", rules=rules, sources=sources, cats=categories(), test=None)


@app.post("/api/rules")
async def rule_create(request: Request):
    d = await request.json()
    if d.get("action") not in ("ignore", "expense", "not_expense", "income", "categorize") or not d.get("pattern"):
        raise HTTPException(400, "Regla inválida")
    field = d.get("field", "subject")
    if field not in ("from", "subject", "body", "counterparty", "description"):
        raise HTTPException(400, "Campo inválido")
    with db.tx() as conn:
        conn.execute("INSERT INTO rules(action, field, pattern, is_regex, category_id, note) VALUES (?,?,?,?,?,?)",
                     (d["action"], field, d["pattern"].strip(), 1 if d.get("is_regex") else 0,
                      int(d["category_id"]) if d.get("category_id") else None, d.get("note", "")))
    return {"ok": True}


@app.delete("/api/rules/{rule_id}")
def rule_delete(rule_id: int):
    with db.tx() as conn:
        conn.execute("DELETE FROM rules WHERE id=?", (rule_id,))
    return {"ok": True}


@app.post("/api/sources")
async def source_upsert(request: Request):
    d = await request.json()
    with db.tx() as conn:
        if d.get("id"):
            conn.execute("UPDATE sources SET enabled=? WHERE id=?", (1 if d.get("enabled") else 0, int(d["id"])))
        elif d.get("from_pattern"):
            conn.execute("INSERT OR IGNORE INTO sources(name, from_pattern) VALUES (?,?)",
                         (d.get("name") or d["from_pattern"], d["from_pattern"].strip().lower()))
    return {"ok": True}


@app.post("/api/probar")
async def test_email(request: Request):
    """Probador: pega un correo y ve qué detecta la app, sin guardar nada."""
    d = await request.json()
    body = d.get("body", "")
    is_html = "<td" in body.lower() or "<p" in body.lower()
    e = Email(from_addr=d.get("from_addr", ""), subject=d.get("subject", ""),
              html=body if is_html else "", text="" if is_html else body)
    r = parse_email(e)
    out: dict[str, Any] = {"result": r.as_dict(), "text": e.body[:3000]}
    if d.get("use_llm") and llm.enabled() and r.kind == "unknown":
        try:
            out["llm"] = llm.extract_email(e.from_addr, e.subject, e.body)
        except llm.LLMError as exc:
            out["llm_error"] = str(exc)
    return out


# ------------------------------------------------------------- categorías
@app.get("/categorias", response_class=HTMLResponse)
def categories_page(request: Request):
    return render(request, "categories.html", cats=categories(), wide=False)


@app.post("/api/categories")
async def category_save(request: Request):
    d = await request.json()
    budget = d.get("monthly_budget")
    budget = float(budget) if budget not in (None, "") else None
    with db.tx() as conn:
        if d.get("id"):
            conn.execute("UPDATE categories SET name=?, icon=?, color=?, keywords=?, monthly_budget=? WHERE id=?",
                         (d["name"], d.get("icon", "💸"), d.get("color", "#888888"), d.get("keywords", ""), budget,
                          int(d["id"])))
        else:
            conn.execute("INSERT INTO categories(name, icon, color, keywords, monthly_budget) VALUES (?,?,?,?,?)",
                         (d["name"], d.get("icon", "💸"), d.get("color", "#888888"), d.get("keywords", ""), budget))
    return {"ok": True}


@app.delete("/api/categories/{cid}")
def category_delete(cid: int):
    with db.tx() as conn:
        conn.execute("DELETE FROM categories WHERE id=?", (cid,))
    return {"ok": True}


# ------------------------------------------------------------- ajustes
SETTING_KEYS = ["imap_host", "imap_port", "imap_user", "imap_password", "imap_folder", "sync_days_back",
                "sync_interval_minutes", "llm_provider", "llm_model", "llm_base_url", "llm_api_key", "owner_names",
                "big_expense_threshold", "usd_to_pen", "ntfy_url"]
SECRET_KEYS = {"imap_password", "llm_api_key"}


@app.get("/ajustes", response_class=HTMLResponse)
def settings_page(request: Request, saved: str = "", s: str = ""):
    sec = s
    vals = {k: db.get_setting(k) for k in SETTING_KEYS}
    for k in SECRET_KEYS:
        vals[k + "_set"] = bool(vals[k])
        vals[k] = ""
    return templates.TemplateResponse(request, "settings.html", {"s": vals, "sec": sec, "saved": saved,
                                                                 "default_model": config.ANTHROPIC_DEFAULT_MODEL})


@app.post("/ajustes")
async def settings_save(request: Request):
    form = await request.form()
    for k in SETTING_KEYS:
        if k in form:
            v = str(form[k]).strip()
            if k in SECRET_KEYS and not v:
                continue  # vacío = no cambiar
            db.set_setting(k, v)
    return RedirectResponse("/ajustes", status_code=303)


@app.post("/api/sync")
async def sync_now():
    res = await asyncio.to_thread(ingest.sync_imap)
    return res


@app.post("/api/llm/categorize")
async def llm_categorize():
    if not llm.enabled():
        return {"ok": False, "error": "Configura un LLM en Ajustes."}
    try:
        n = await asyncio.to_thread(ingest.categorize_pending_with_llm)
    except llm.LLMError as exc:
        return {"ok": False, "error": str(exc)}
    return {"ok": True, "n": n}


@app.get("/api/export.csv")
def export_csv():
    import csv
    import io
    buf = io.StringIO()
    w = csv.writer(buf)
    w.writerow(["fecha", "tipo", "estado", "monto", "moneda", "cuenta", "detalle", "contraparte", "descripcion",
                "categoria", "motivo_exclusion"])
    for r in db.query("SELECT t.*, c.name cname FROM transactions t LEFT JOIN categories c ON c.id=t.category_id "
                      "ORDER BY occurred_at"):
        w.writerow([r["occurred_at"], "gasto" if r["direction"] == "out" else "ingreso", r["status"], r["amount"],
                    r["currency"], r["account"], r["account_detail"], r["counterparty"], r["description"],
                    r["cname"] or "", r["exclude_reason"]])
    from fastapi.responses import Response
    return Response(buf.getvalue(), media_type="text/csv",
                    headers={"Content-Disposition": "attachment; filename=movimientos.csv"})


@app.get("/api/counterparty-key")
def cp_key(name: str) -> dict[str, str]:
    return {"key": counterparty_key(name)}


@app.exception_handler(json.JSONDecodeError)
async def bad_json(_: Request, exc: json.JSONDecodeError):
    return JSONResponse({"error": str(exc)}, status_code=400)
