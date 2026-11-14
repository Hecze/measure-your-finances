"""Cálculos para el dashboard."""
from __future__ import annotations

import calendar
from collections import defaultdict
from datetime import date, datetime, timedelta
from typing import Any

from . import db
from .textutil import counterparty_key

COUNTED = "direction='out' AND status!='excluded'"


def month_bounds(ym: str | None) -> tuple[str, str, str]:
    today = date.today()
    if ym:
        y, m = (int(x) for x in ym.split("-"))
    else:
        y, m = today.year, today.month
    start = date(y, m, 1)
    end = date(y, m, calendar.monthrange(y, m)[1])
    return f"{y:04d}-{m:02d}", start.isoformat(), (end + timedelta(days=1)).isoformat()


def shift_month(ym: str, delta: int) -> str:
    y, m = (int(x) for x in ym.split("-"))
    m += delta
    while m < 1:
        m += 12
        y -= 1
    while m > 12:
        m -= 12
        y += 1
    return f"{y:04d}-{m:02d}"


def _rate() -> float:
    return db.get_float_setting("usd_to_pen", 3.75)


def pen(amount: float, currency: str) -> float:
    return amount * _rate() if currency == "USD" else amount


def _rows(start: str, end: str) -> list[dict[str, Any]]:
    return [db.row_to_dict(r) for r in db.query(
        f"SELECT t.*, c.name AS category_name, c.icon AS category_icon, c.color AS category_color "
        f"FROM transactions t LEFT JOIN categories c ON c.id=t.category_id "
        f"WHERE {COUNTED} AND occurred_at >= ? AND occurred_at < ? ORDER BY occurred_at DESC", (start, end))]


def dashboard(ym: str | None) -> dict[str, Any]:
    ym, start, end = month_bounds(ym)
    rows = _rows(start, end)
    prev_ym, pstart, pend = month_bounds(shift_month(ym, -1))
    prev_total = sum(pen(r["amount"], r["currency"]) for r in _rows(pstart, pend))
    total = sum(pen(r["amount"], r["currency"]) for r in rows)
    big = db.get_float_setting("big_expense_threshold", 200)

    by_cat: dict[str, dict[str, Any]] = {}
    for r in rows:
        name = r["category_name"] or "Sin categoría"
        d = by_cat.setdefault(name, {"name": name, "icon": r["category_icon"] or "❔",
                                     "color": r["category_color"] or "#B0B7C3", "total": 0.0, "n": 0,
                                     "id": r["category_id"]})
        d["total"] += pen(r["amount"], r["currency"])
        d["n"] += 1
    cats = sorted(by_cat.values(), key=lambda x: -x["total"])

    by_acc: dict[str, float] = defaultdict(float)
    for r in rows:
        label = r["account"] or "?"
        det = r["account_detail"] or ""
        if "Crédito" in det or "crédito" in det:
            label += " · Crédito"
        elif "Débito" in det:
            label += " · Débito"
        elif det.startswith("Yape →"):
            label = det
        elif "Cuenta" in det:
            label += " · Transferencias"
        by_acc[label] += pen(r["amount"], r["currency"])
    accounts = sorted(({"name": k, "total": v} for k, v in by_acc.items()), key=lambda x: -x["total"])

    # Regla 80/20: muchos pagos chicos a personas vs pocos pagos grandes.
    p2p = [r for r in rows if r["counterparty_type"] == "person"]
    bigs = [r for r in rows if pen(r["amount"], r["currency"]) >= big]
    split = {
        "p2p_total": sum(pen(r["amount"], r["currency"]) for r in p2p), "p2p_n": len(p2p),
        "big_total": sum(pen(r["amount"], r["currency"]) for r in bigs), "big_n": len(bigs),
        "other_total": 0.0, "other_n": 0,
    }
    others = [r for r in rows if r not in p2p and r not in bigs]
    split["other_total"] = sum(pen(r["amount"], r["currency"]) for r in others)
    split["other_n"] = len(others)
    # Superposición (pago grande a persona) se cuenta como grande en el gráfico:
    overlap = sum(pen(r["amount"], r["currency"]) for r in p2p if r in bigs)
    split["p2p_small_total"] = split["p2p_total"] - overlap

    days = calendar.monthrange(int(ym[:4]), int(ym[5:]))[1]
    daily = [0.0] * days
    for r in rows:
        d = int(r["occurred_at"][8:10])
        daily[d - 1] += pen(r["amount"], r["currency"])

    budgets = []
    for c in db.query("SELECT * FROM categories WHERE monthly_budget IS NOT NULL AND monthly_budget > 0 ORDER BY sort_order"):
        spent = by_cat.get(c["name"], {}).get("total", 0.0)
        budgets.append({"name": c["name"], "icon": c["icon"], "color": c["color"], "budget": c["monthly_budget"],
                        "spent": spent, "pct": min(100, round(100 * spent / c["monthly_budget"]))})

    return {
        "ym": ym, "prev_ym": prev_ym, "next_ym": shift_month(ym, 1), "total": total, "prev_total": prev_total,
        "count": len(rows), "categories": cats, "accounts": accounts, "split": split, "big_threshold": big,
        "big_list": sorted(bigs, key=lambda r: -pen(r["amount"], r["currency"]))[:12],
        "daily": daily, "budgets": budgets, "recurring": recurring(), "pending": pending_counts(),
        "top_people": top_people(p2p),
    }


def top_people(p2p: list[dict[str, Any]]) -> list[dict[str, Any]]:
    agg: dict[str, dict[str, Any]] = {}
    for r in p2p:
        d = agg.setdefault(r["counterparty"], {"name": r["counterparty"], "total": 0.0, "n": 0, "no_desc": 0})
        d["total"] += r["amount"]
        d["n"] += 1
        d["no_desc"] += 0 if r["description"] else 1
    return sorted(agg.values(), key=lambda x: -x["total"])[:8]


def recurring(months: int = 4) -> list[dict[str, Any]]:
    """Pagos frecuentes: misma empresa en >=2 meses distintos con monto parecido (±15%)."""
    since = (date.today().replace(day=1) - timedelta(days=31 * months)).isoformat()
    rows = db.query(f"SELECT * FROM transactions WHERE {COUNTED} AND counterparty_type!='person' AND occurred_at>=?",
                    (since,))
    groups: dict[str, list] = defaultdict(list)
    for r in rows:
        k = counterparty_key(r["counterparty"])
        if k:
            groups[k].append(r)
    out = []
    for _, txs in groups.items():
        months_seen = {r["occurred_at"][:7] for r in txs}
        if len(months_seen) < 2:
            continue
        amounts = sorted(r["amount"] for r in txs)
        median = amounts[len(amounts) // 2]
        similar = [a for a in amounts if abs(a - median) <= 0.15 * median + 0.01]
        if len(similar) < 2:
            continue
        last = max(txs, key=lambda r: r["occurred_at"])
        out.append({"name": last["counterparty"], "amount": median, "currency": last["currency"],
                    "months": len(months_seen), "last": last["occurred_at"][:10],
                    "monthly_pen": pen(median, last["currency"]) * len(similar) / max(1, len(months_seen))})
    return sorted(out, key=lambda x: -x["monthly_pen"])[:15]


def pending_counts() -> dict[str, int]:
    q = db.query_one
    since = (datetime.now() - timedelta(days=45)).strftime("%Y-%m-%d")
    return {
        "review": q(f"SELECT COUNT(*) n FROM transactions WHERE {COUNTED} AND status='pending' "
                    "AND flags!='[]' AND flags!='[\"sin_descripcion\"]'")["n"],
        "no_desc": q(f"SELECT COUNT(*) n FROM transactions WHERE {COUNTED} AND counterparty_type='person' "
                     "AND TRIM(description)='' AND occurred_at>=?", (since,))["n"],
        "no_desc_7d": q(f"SELECT COUNT(*) n FROM transactions WHERE {COUNTED} AND counterparty_type='person' "
                        "AND TRIM(description)='' AND occurred_at>=?",
                        ((datetime.now() - timedelta(days=7)).strftime("%Y-%m-%d"),))["n"],
        "unknown_emails": q("SELECT COUNT(*) n FROM emails WHERE decision='unknown'")["n"],
        "uncategorized": q(f"SELECT COUNT(*) n FROM transactions WHERE {COUNTED} AND category_id IS NULL")["n"],
    }


def last_yape_import() -> str | None:
    r = db.query_one("SELECT MAX(created_at) d FROM transactions WHERE source='yape_excel'")
    return r["d"] if r else None


def last_yape_tx() -> str | None:
    r = db.query_one("SELECT MAX(occurred_at) d FROM transactions WHERE source='yape_excel'")
    return r["d"] if r else None
