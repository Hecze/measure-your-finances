# Mis Gastos

App open source y auto-alojable para saber **cuánto gastas y en qué**, leyendo los correos de Yape y de tus bancos en Perú, más el historial de Yape.

- **Ultra simple:** un número (lo que gastaste este mes), tus categorías y un botón de *pendientes*.
- **No adivina:** cada tipo de correo tiene una plantilla exacta. Si llega un correo de un banco que no reconoce, te lo pregunta.
- **Mide gastos, no ingresos.** Tú decides qué no cuenta (cambio de efectivo, encargos, transferencias a tus cuentas).
- **Tus datos son tuyos:** todo se guarda en un archivo SQLite en tu servidor. La IA es opcional y puede ser 100% local.

## Cómo funciona

```
 Gmail (IMAP, solo remitentes de bancos) ─┐
                                          ├─► plantillas exactas ─► gasto / no es gasto / ingreso / ignorar / ❓ revisar
 Historial de Yape (.xlsx) ───────────────┘          │
   · llega por correo y se importa solo              ▼
   · o lo subes a mano                     deduplicación (mismo pago por 2 vías)
                                                     │
                                                     ▼
                                     categoría: tus correcciones › reglas › palabras clave › IA (opcional)
                                                     │
                                                     ▼
                                     Pendientes: una tarjeta a la vez → "¿En qué fue?" · Listo / No es gasto
```

### Lo que aprendimos con correos reales (y por qué otras IA se pierden)

| Trampa | Qué pasa | Cómo se resuelve |
|---|---|---|
| **Yape no manda correo por cada yapeo a personas** | La mayoría de tus gastos del día a día (pagos a personas) no está en el correo | Importar el **historial de Yape**: lo pides en la app de Yape, llega por correo y se importa solo |
| Gmail agrupa correos con el mismo asunto en un hilo | Si lees "hilos" ves 5 consumos y crees que son todos | Se lee **mensaje por mensaje** por IMAP |
| Todos los correos de consumo del BCP dicen al pie *"Si tu compra fue rechazada…"* | Un filtro ingenuo descarta consumos reales | Los rechazos se detectan **solo por el asunto** |
| *"Constancia de Favorito: Agregaste un Pago de Servicios"* | Parece un pago y no lo es | Se ignora antes que cualquier plantilla |
| El monto del pago de servicios (ej. universidad) no está en el resumen del correo | La IA que lee el resumen no ve el monto | Se lee la etiqueta exacta *Monto total* en el cuerpo |
| El mismo gasto se paga por distintas vías | La universidad un mes por *Pago de servicios* y otro con *consumo de tarjeta* | Ambas plantillas cubiertas |
| Un pago de Yape a Plin/BIM también llega como *consumo con débito BCP* | Se contaría doble | Deduplicación por monto y hora (±5 min) entre correo y Excel |
| Pagar tu tarjeta de crédito | Se contaría doble (el consumo y el pago) | El pago de tu propia tarjeta **no es gasto** |
| Te yapean para que tú pagues/transfieras por ellos | Parece gasto tuyo | Sugerencias: *"X te envió S/150 un minuto antes"* o *"esta persona también te envía dinero"* |

Plantillas incluidas (BCP y Yape, verificadas con correos reales): consumo con tarjeta de crédito/débito, transferencia a terceros, a otros bancos, entre tus cuentas, pago de servicios, pago de tarjeta propia, devolución, wardadito, yapeo recibido, yapeo, "Pagar con Yape" en otras apps (PedidosYa…), yapeo de servicio, recarga, Yape Promos. Otros bancos usan una plantilla genérica que **siempre** pide confirmación.

## Instalar (Docker)

```bash
git clone https://github.com/hecze/measure-your-finances.git
```

```bash
cd measure-your-finances && cp .env.example .env
```

Edita `.env` (al menos `APP_PASSWORD` y `SECRET_KEY`) y luego:

```bash
docker compose up -d
```

Abre http://localhost:8000. Los datos quedan en `./data/finanzas.db`.

Sin Docker:

```bash
pip install -r requirements.txt && uvicorn app.main:app --port 8000
```

### Conectar Gmail

1. Activa la verificación en 2 pasos en tu cuenta de Google.
2. Crea una **contraseña de aplicación** (Cuenta de Google → Seguridad → Contraseñas de aplicaciones).
3. En la app: ⚙︎ → Correo → tu correo y esa contraseña.

Solo se descargan correos de los remitentes de la lista (⚙︎ → Avanzado → Reglas): `notificaciones@yape.pe`, `notificaciones@notificacionesbcp.com.pe`, BBVA, Interbank, Scotiabank, etc. Los correos nunca se marcan como leídos.

### Historial de Yape

En la app de Yape pide tu historial de movimientos. Te llega por correo como *"Historial de Movimientos"* con un Excel adjunto. Si conectaste tu correo, **se importa solo**. Si no, súbelo en ⚙︎ → Importar historial de Yape. Importarlo dos veces no duplica nada.

### IA (opcional)

⚙︎ → Inteligencia artificial:
- **Claude (Anthropic):** pega tu API key.
- **Local u otro:** cualquier servidor compatible con `/chat/completions` (Ollama, LM Studio, OpenRouter). Con Ollama todo queda en tu máquina.

La IA solo **sugiere** categorías y lee correos que ninguna plantilla reconoce. Todo lo que propone queda pendiente hasta que lo confirmas.

## Mejorar la detección

- **Pendientes:** cada corrección enseña a la app (categoría por comercio, "no contar nunca a esta persona").
- **⚙︎ → Avanzado → Correos leídos:** cada correo muestra qué se decidió y por qué. Puedes corregirlo y crear una regla.
- **⚙︎ → Avanzado → Reglas:** probador para pegar un correo y ver qué entiende la app, sin guardar nada.
- ¿Tu banco no está? Abre un issue con la estructura del correo (**sin datos personales**) o agrega una plantilla en `app/parsers.py` con su test en `tests/`.

## Desarrollo

```bash
pip install -r requirements.txt pytest && python -m pytest -q
```

Python + FastAPI + SQLite + plantillas Jinja2. Sin build de frontend.

## Licencia

MIT
