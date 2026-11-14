"""SQLite: esquema, conexión y ajustes."""
from __future__ import annotations

import json
import sqlite3
import threading
from contextlib import contextmanager
from typing import Any, Iterator

from . import config

_lock = threading.RLock()

SCHEMA = """
CREATE TABLE IF NOT EXISTS settings (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS categories (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL UNIQUE,
    icon TEXT NOT NULL DEFAULT '💸',
    color TEXT NOT NULL DEFAULT '#888888',
    keywords TEXT NOT NULL DEFAULT '',      -- separadas por coma, en minúsculas
    monthly_budget REAL,                     -- opcional
    sort_order INTEGER NOT NULL DEFAULT 100
);

-- Remitentes de correo que se leen (bancos / billeteras). Nada fuera de esta
-- lista se descarga.
CREATE TABLE IF NOT EXISTS sources (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    from_pattern TEXT NOT NULL UNIQUE,       -- dirección o dominio, ej. "notificacionesbcp.com.pe"
    enabled INTEGER NOT NULL DEFAULT 1
);

-- Reglas de detección que el usuario va creando.
--   action: 'ignore' (no es transacción), 'expense' (es gasto), 'not_expense'
--           (es transacción pero no cuenta como gasto), 'income'.
CREATE TABLE IF NOT EXISTS rules (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    action TEXT NOT NULL,
    field TEXT NOT NULL DEFAULT 'subject',   -- 'from' | 'subject' | 'body' | 'counterparty'
    pattern TEXT NOT NULL,
    is_regex INTEGER NOT NULL DEFAULT 0,
    category_id INTEGER REFERENCES categories(id) ON DELETE SET NULL,
    note TEXT NOT NULL DEFAULT '',
    hits INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS emails (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    message_id TEXT NOT NULL UNIQUE,
    from_addr TEXT NOT NULL DEFAULT '',
    subject TEXT NOT NULL DEFAULT '',
    received_at TEXT,
    body_text TEXT NOT NULL DEFAULT '',
    decision TEXT NOT NULL,                  -- expense | income | not_expense | ignored | duplicate | error | import
    decision_source TEXT NOT NULL DEFAULT '',-- parser:<nombre> | rule:<id> | llm | user
    reason TEXT NOT NULL DEFAULT '',
    transaction_id INTEGER REFERENCES transactions(id) ON DELETE SET NULL,
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS transactions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    source TEXT NOT NULL,                    -- email | yape_excel | manual
    direction TEXT NOT NULL DEFAULT 'out',   -- out (gasto) | in (ingreso, no se cuenta)
    account TEXT NOT NULL DEFAULT '',        -- Yape, BCP, BBVA...
    account_detail TEXT NOT NULL DEFAULT '', -- "Tarjeta de Crédito ****1234", "Débito", "Plin"...
    amount REAL NOT NULL,
    currency TEXT NOT NULL DEFAULT 'PEN',
    occurred_at TEXT NOT NULL,               -- hora local 'YYYY-MM-DD HH:MM:SS'
    counterparty TEXT NOT NULL DEFAULT '',
    counterparty_type TEXT NOT NULL DEFAULT 'unknown', -- person | business | self | unknown
    description TEXT NOT NULL DEFAULT '',    -- mensaje de Yape / descripción del usuario
    category_id INTEGER REFERENCES categories(id) ON DELETE SET NULL,
    category_source TEXT NOT NULL DEFAULT '',-- keyword | memory | llm | user | rule
    status TEXT NOT NULL DEFAULT 'pending',  -- pending | confirmed | excluded
    exclude_reason TEXT NOT NULL DEFAULT '',
    flags TEXT NOT NULL DEFAULT '[]',        -- JSON: sugerencias (pase de dinero, cambio de efectivo...)
    external_ref TEXT NOT NULL DEFAULT '',   -- nº de operación
    dedupe_key TEXT UNIQUE,                  -- evita reimportar la misma fila
    email_id INTEGER,
    notified INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_tx_occurred ON transactions(occurred_at);
CREATE INDEX IF NOT EXISTS idx_tx_amount ON transactions(amount);

-- Lo que el usuario enseña: contraparte -> categoría (y si no es gasto).
CREATE TABLE IF NOT EXISTS counterparty_memory (
    key TEXT PRIMARY KEY,                    -- contraparte normalizada
    category_id INTEGER REFERENCES categories(id) ON DELETE CASCADE,
    counterparty_type TEXT,
    updated_at TEXT NOT NULL DEFAULT (datetime('now'))
);

-- Ejemplos corregidos por el usuario; se usan como few-shot para el LLM.
CREATE TABLE IF NOT EXISTS feedback (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    kind TEXT NOT NULL,                      -- category | not_expense | email_decision
    input TEXT NOT NULL,
    output TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS sync_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    started_at TEXT NOT NULL DEFAULT (datetime('now')),
    finished_at TEXT,
    ok INTEGER,
    summary TEXT NOT NULL DEFAULT ''
);
"""

DEFAULT_CATEGORIES: list[tuple[str, str, str, str]] = [
    # (nombre, icono, color, keywords)
    ("Comida", "🍽️", "#E4572E",
     "almuerzo,menu,menú,desayuno,cena,pollo,broaster,broster,chaufa,causa,ceviche,cebiche,pizza,hamburguesa,"
     "milanesa,anticucho,salchipapa,sandwich,sanguche,pan,panaderia,panadería,pasteleria,pastelería,cafe,café,"
     "jugo,postre,helado,chicha,restaurant,restaurante,cevicheria,polleria,pollería,chifa,kfc,bembos,"
     "mcdonald,popeyes,starbucks,inka pizza,roky,norky,pardos,papa john,dunkin"),
    ("Delivery", "🛵", "#F18F01", "delivery,pedidos ya,pedidosya,rappi,didi food,glovo"),
    ("Supermercado y bodega", "🛒", "#2E86AB",
     "tottus,plaza vea,plazavea,metro,wong,vivanda,makro,vega,mass,tambo,oxxo,listo,repshop,mercado,bodega,"
     "abarrotes,fruta,durazno,verdura,huevos,leche,agua,gaseosa,fruguitos,minimarket,market"),
    ("Transporte", "🚌", "#3BB273",
     "pasaje,taxi,uber,cabify,indrive,didi,metropolitano,corredor,combi,colectivo,bus,peaje,gasolina,grifo,"
     "primax,repsol,pecsa,estacionamiento,tarjeta preferencial,recarga de tarjetas,mototaxi"),
    ("Vivienda", "🏠", "#7768AE", "alquiler,hab,habitación,habitacion,depa,departamento,cuarto,mantenimiento,inmobiliaria"),
    ("Servicios", "💡", "#1B998B",
     "luz,agua,internet,celular,postpago,prepago,recarga,bitel,claro,movistar,entel,win,enel,luz del sur,"
     "sedapal,calidda,cálidda,gas,cable,mibitel"),
    ("Suscripciones", "🔁", "#C73E1D",
     "netflix,spotify,openai,chatgpt,anthropic,claude,apple,icloud,google one,google,youtube,disney,hbo,max,"
     "prime video,amazon prime,capcut,canva,notion,github,vercel,hostinger,scribd,oculus,meta,facebk,"
     "nintendo,playstation,xbox,steam,deepseek,cursor,paypal"),
    ("Salud", "🩺", "#E84855", "farmacia,inkafarma,mifarma,boticas,botica,clinica,clínica,doctor,medico,médico,consulta,dentista,laboratorio,pastillas,medicina"),
    ("Educación", "📚", "#3E92CC", "curso,universidad,colegio,instituto,pension,pensión,matricula,matrícula,carreras,ciclo,libro,udemy,platzi,"
     "coursera,academia,clases,karate,taller,cibertec,upc,pucp,usil,utp,ulima,upn,senati,idat,toulouse,certus,unmsm"),
    ("Entretenimiento", "🎬", "#9B5DE5", "cine,cinemark,cineplanet,cineestar,concierto,teleticket,joinnus,bar,discoteca,juego,karaoke,bowling"),
    ("Compras", "🛍️", "#F15BB5", "ropa,zapatillas,falabella,ripley,oechsle,saga,paris,mercado libre,mercadolibre,amazon,aliexpress,temu,shein,promart,sodimac,maestro,miniso"),
    ("Cuidado personal", "💈", "#00BBF9", "peluqueria,peluquería,barberia,barbería,corte,gillette,gillet,shampoo,spa,uñas,manicure,lavanderia,lavandería,limpieza"),
    ("Familia y regalos", "🎁", "#FEE440", "regalo,cumpleaños,cumple,propina,mesada,donación,donacion,ofrenda"),
    ("Mascotas", "🐶", "#8AC926", "veterinaria,veterinario,mascota,perro,gato,petshop,croquetas"),
    ("Deporte", "🏋️", "#06D6A0", "gym,gimnasio,smart fit,smartfit,bodytech,futbol,fútbol,cancha,pichanga,deporte"),
    ("Viajes", "✈️", "#118AB2", "hotel,hostal,airbnb,latam,sky airline,jetsmart,booking,viaje,pasaje aereo,cruz del sur,oltursa"),
    ("Comisiones bancarias", "🏦", "#6C757D", "comision,comisión,mantenimiento de cuenta,itf,membresia tarjeta,membresía"),
    ("Otros", "💸", "#ADB5BD", ""),
]

DEFAULT_SOURCES: list[tuple[str, str]] = [
    # Verificados con correos reales:
    ("Yape", "notificaciones@yape.pe"),
    ("BCP", "notificaciones@notificacionesbcp.com.pe"),
    # Otros bancos de Perú (dominios conocidos; ajústalos en Reglas si cambian):
    ("BBVA", "bbva.com.pe"),
    ("BBVA", "bbva.pe"),
    ("Interbank", "interbank.pe"),
    ("Interbank", "netinterbank.com.pe"),
    ("Scotiabank", "scotiabank.com.pe"),
    ("Banco Ripley", "bancoripley.com.pe"),
    ("Banco Falabella", "bancofalabella.pe"),
    ("Banco Falabella", "bancofalabella.com.pe"),
    ("BanBif", "banbif.com.pe"),
    ("Banco Pichincha", "pichincha.pe"),
    ("Plin", "plin.pe"),
    ("Caja Arequipa", "cajaarequipa.pe"),
    ("Caja Huancayo", "cajahuancayo.com.pe"),
]

DEFAULT_RULES: list[tuple[str, str, str, str]] = [
    # (action, field, pattern, note) — patrones en minúsculas, "contiene"
    ("ignore", "from", "bcpcomunica@", "Publicidad del BCP"),
    ("ignore", "from", "estadodecuenta@", "Estados de cuenta (no son movimientos)"),
    ("ignore", "from", "claveparacompras@", "Claves para compras por internet"),
    ("ignore", "from", "comprobante-electronico@", "Comprobantes electrónicos"),
]


def connect() -> sqlite3.Connection:
    conn = sqlite3.connect(config.DB_PATH, check_same_thread=False, timeout=30)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    conn.execute("PRAGMA journal_mode = WAL")
    return conn


_conn: sqlite3.Connection | None = None


def get_conn() -> sqlite3.Connection:
    global _conn
    if _conn is None:
        _conn = connect()
    return _conn


def reset_conn_for_tests(path: str) -> None:
    """Usado por los tests para apuntar a otra base."""
    global _conn
    config.DB_PATH = path  # type: ignore[assignment]
    if _conn is not None:
        _conn.close()
    _conn = None


@contextmanager
def tx() -> Iterator[sqlite3.Connection]:
    conn = get_conn()
    with _lock:
        try:
            yield conn
            conn.commit()
        except Exception:
            conn.rollback()
            raise


def query(sql: str, params: tuple | dict = ()) -> list[sqlite3.Row]:
    with _lock:
        return get_conn().execute(sql, params).fetchall()


def query_one(sql: str, params: tuple | dict = ()) -> sqlite3.Row | None:
    with _lock:
        return get_conn().execute(sql, params).fetchone()


def init_db() -> None:
    with tx() as conn:
        conn.executescript(SCHEMA)
        if conn.execute("SELECT COUNT(*) FROM categories").fetchone()[0] == 0:
            for i, (name, icon, color, kw) in enumerate(DEFAULT_CATEGORIES):
                conn.execute(
                    "INSERT INTO categories(name, icon, color, keywords, sort_order) VALUES (?,?,?,?,?)",
                    (name, icon, color, kw, i),
                )
        if conn.execute("SELECT COUNT(*) FROM sources").fetchone()[0] == 0:
            for name, pat in DEFAULT_SOURCES:
                conn.execute("INSERT INTO sources(name, from_pattern) VALUES (?,?)", (name, pat))
        if conn.execute("SELECT COUNT(*) FROM rules").fetchone()[0] == 0:
            for action, field, pattern, note in DEFAULT_RULES:
                conn.execute(
                    "INSERT INTO rules(action, field, pattern, note) VALUES (?,?,?,?)",
                    (action, field, pattern, note),
                )


# --------------------------------------------------------------------- ajustes
def get_setting(key: str) -> str:
    row = query_one("SELECT value FROM settings WHERE key=?", (key,))
    if row is not None:
        return row["value"]
    return config.DEFAULT_SETTINGS.get(key, "")


def set_setting(key: str, value: str) -> None:
    with tx() as conn:
        conn.execute(
            "INSERT INTO settings(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
            (key, value),
        )


def get_float_setting(key: str, default: float) -> float:
    try:
        return float(get_setting(key))
    except (TypeError, ValueError):
        return default


def row_to_dict(row: sqlite3.Row | None) -> dict[str, Any] | None:
    if row is None:
        return None
    d = dict(row)
    if "flags" in d and isinstance(d["flags"], str):
        try:
            d["flags"] = json.loads(d["flags"] or "[]")
        except json.JSONDecodeError:
            d["flags"] = []
    return d
