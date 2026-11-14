"""Configuración vía variables de entorno.

Casi todo también se puede cambiar desde la pantalla de Ajustes (se guarda en la
tabla `settings`). Las variables de entorno sirven como valores por defecto.
"""
from __future__ import annotations

import os
from pathlib import Path

DATA_DIR = Path(os.environ.get("DATA_DIR", "./data")).resolve()
DATA_DIR.mkdir(parents=True, exist_ok=True)
DB_PATH = Path(os.environ.get("DB_PATH", DATA_DIR / "finanzas.db"))

# Si está vacío, la app no pide contraseña (úsalo solo en localhost).
APP_PASSWORD = os.environ.get("APP_PASSWORD", "")
SECRET_KEY = os.environ.get("SECRET_KEY", "") or "cambia-esto-" + str(DB_PATH)

TIMEZONE = os.environ.get("TZ_NAME", "America/Lima")
BASE_URL = os.environ.get("BASE_URL", "http://localhost:8000")

# Valores por defecto de ajustes editables desde la UI.
DEFAULT_SETTINGS: dict[str, str] = {
    # Correo (IMAP). Para Gmail: imap.gmail.com + "contraseña de aplicación".
    "imap_host": os.environ.get("IMAP_HOST", "imap.gmail.com"),
    "imap_port": os.environ.get("IMAP_PORT", "993"),
    "imap_user": os.environ.get("IMAP_USER", ""),
    "imap_password": os.environ.get("IMAP_PASSWORD", ""),
    "imap_folder": os.environ.get("IMAP_FOLDER", '"[Gmail]/All Mail"'),
    "sync_days_back": os.environ.get("SYNC_DAYS_BACK", "60"),
    "sync_interval_minutes": os.environ.get("SYNC_INTERVAL_MINUTES", "15"),
    # LLM: "none" | "anthropic" | "openai_compatible" (Ollama, LM Studio, OpenRouter...)
    "llm_provider": os.environ.get("LLM_PROVIDER", "anthropic" if os.environ.get("ANTHROPIC_API_KEY") else "none"),
    "llm_model": os.environ.get("LLM_MODEL", ""),
    "llm_base_url": os.environ.get("LLM_BASE_URL", "http://localhost:11434/v1"),
    "llm_api_key": os.environ.get("LLM_API_KEY", ""),
    # Tus nombres (como aparecen en Yape/bancos), separados por coma. Sirve para
    # detectar transferencias a ti mismo, que no son gasto.
    "owner_names": os.environ.get("OWNER_NAMES", ""),
    # Monto a partir del cual un gasto se considera "grande" (regla 80/20).
    "big_expense_threshold": os.environ.get("BIG_EXPENSE_THRESHOLD", "200"),
    "usd_to_pen": os.environ.get("USD_TO_PEN", "3.75"),
    # Notificaciones push opcionales vía ntfy.sh (o tu propio servidor ntfy).
    "ntfy_url": os.environ.get("NTFY_URL", ""),
}

ANTHROPIC_DEFAULT_MODEL = "claude-opus-5-5"
