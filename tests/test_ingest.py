import io
from datetime import datetime

import openpyxl

from app import ingest
from app.yape_excel import parse_yape_excel

from . import fixtures as fx


def make_yape_excel(rows):
    """Mismo layout que el reporte real de Yape (encabezado en la fila 5, celdas F:G combinadas)."""
    wb = openpyxl.Workbook()
    ws = wb.active
    ws["A2"] = "Reporte de Movimientos"
    ws.append([])
    ws.append([])
    ws.append(["Tipo de Transacción", "Origen", "Destino", "Monto", "Mensaje", "Fecha de operación", None])
    for r in rows:
        ws.append(list(r) + [None])
        ws.merge_cells(start_row=ws.max_row, start_column=6, end_row=ws.max_row, end_column=7)
    buf = io.BytesIO()
    wb.save(buf)
    return buf.getvalue()


ROWS = [
    ("PAGASTE", "Ana Per*", "Jose Ram*", "150.00", None, "30/10/2026 21:14:12"),
    ("TE PAGÓ", "PLIN - Carlos Per Gomez", "ANA MARIA PEREZ LOPEZ", "150.00", None, "30/10/2026 21:13:43"),
    ("PAGASTE", "Ana Per*", "Lucia Pat*", "12.00", "almuerzo", "29/10/2026 14:27:13"),
    ("PAGASTE", "ANA MARIA PEREZ LOPEZ", "PLIN - JUAN PEREZ GARCIA", "6.00", None, "26/10/2026 11:41:59"),
    ("PAGASTE", "Ana Per*", "Flor Per*", "100.00", "sencillo", "29/10/2026 12:14:40"),
    ("PAGASTE", "Ana Per*", "PEDIDOS YA", "17.20", "Pago por internet: Nº 1700000000001", "06/11/2026 14:08:30"),
]


def test_excel_parse_y_reimport_idempotente(fresh_db):
    data = make_yape_excel(ROWS)
    rows = parse_yape_excel(data)
    assert len(rows) == 6
    assert sum(1 for r in rows if r.direction == "in") == 1
    s1 = ingest.import_yape_rows(rows)
    assert s1["gasto_new"] == 5 and s1["ingreso_new"] == 1
    s2 = ingest.import_yape_rows(parse_yape_excel(data))
    assert s2.get("gasto_new", 0) == 0 and s2["gasto_duplicate"] == 5


def test_excel_aprende_tu_nombre(fresh_db):
    ingest.import_yape_rows(parse_yape_excel(make_yape_excel(ROWS)))
    assert "Ana Per*" in fresh_db.get_setting("owner_names")


def test_correo_y_excel_no_se_duplican(fresh_db):
    # El pago a PLIN llega como consumo con débito BCP y también en el Excel.
    ingest.process_email(ingest.IncomingEmail(
        message_id="<1@x>", from_addr="notificaciones@notificacionesbcp.com.pe",
        subject="Realizaste un consumo con tu Tarjeta de Débito BCP",
        html=fx.bcp_consumo(amount="S/ 6.00", tipo="Débito", merchant="PLIN-JUAN PEREZ GARC",
                            fecha="26 de octubre de 2026 - 11:41 AM"),
        received_at=datetime(2026, 10, 26, 11, 42)))
    ingest.process_email(ingest.IncomingEmail(
        message_id="<2@x>", from_addr="notificaciones@yape.pe", subject="Pago exitoso",
        html=fx.yape_pago_exitoso(), received_at=datetime(2026, 11, 6, 14, 9)))
    s = ingest.import_yape_rows(parse_yape_excel(make_yape_excel(ROWS)))
    assert s["gasto_new"] == 3  # Jose, Lucia, Flor
    rows = fresh_db.query("SELECT * FROM transactions WHERE direction='out' AND amount=6")
    assert len(rows) == 1
    assert rows[0]["counterparty"] == "PLIN - JUAN PEREZ GARCIA"  # nombre completo del Excel
    assert len(fresh_db.query("SELECT * FROM transactions WHERE amount=17.2")) == 1
    # Procesar el mismo correo otra vez no hace nada
    assert ingest.process_email(ingest.IncomingEmail(message_id="<1@x>", from_addr="", subject=""))["status"] == "already_processed"


def test_pase_de_dinero_y_cambio_de_efectivo(fresh_db):
    ingest.import_yape_rows(parse_yape_excel(make_yape_excel(ROWS)))
    jose = fresh_db.row_to_dict(fresh_db.query_one("SELECT * FROM transactions WHERE counterparty='Jose Ram*'"))
    assert "pase_de_dinero" in jose["flags"]
    flor = fresh_db.row_to_dict(fresh_db.query_one("SELECT * FROM transactions WHERE counterparty='Flor Per*'"))
    assert "posible_cambio_o_encargo" in flor["flags"]
    # Sugerencias, no exclusiones automáticas:
    assert jose["status"] == "pending" and flor["status"] == "pending"


def test_categoria_por_descripcion(fresh_db):
    ingest.import_yape_rows(parse_yape_excel(make_yape_excel(ROWS)))
    row = fresh_db.query_one("SELECT c.name FROM transactions t JOIN categories c ON c.id=t.category_id "
                             "WHERE t.counterparty='Lucia Pat*'")
    assert row["name"] == "Comida"


def test_persona_que_tambien_te_envia(fresh_db):
    rows = [
        ("TE PAGÓ", "Rodolfo Gom*", "Ana Per*", "200.00", None, "01/11/2026 10:00:00"),
        ("TE PAGÓ", "Rodolfo Gom*", "Ana Per*", "300.00", None, "03/11/2026 10:00:00"),
    ]
    ingest.import_yape_rows(parse_yape_excel(make_yape_excel(rows)))
    ingest.process_email(ingest.IncomingEmail(
        message_id="<3@x>", from_addr="notificaciones@notificacionesbcp.com.pe",
        subject="Constancia de Transferencia a Otros Bancos",
        html=fx.bcp_transfer(kind="Transferencia a otros bancos", amount="S/ 650.00", dest="Gomez Gomez Rodolfo A.",
                             mensaje="", banco_destino="Ripley"),
        received_at=datetime(2026, 11, 13, 20, 23)))
    t = fresh_db.row_to_dict(fresh_db.query_one("SELECT * FROM transactions WHERE amount=650"))
    assert "persona_tambien_te_envia" in t["flags"]
    assert t["category_id"] is None  # "(Ripley)" es el banco, no una compra en Ripley


def test_regla_de_contraparte_no_cuenta(fresh_db):
    with fresh_db.tx() as c:
        c.execute("INSERT INTO rules(action, field, pattern, note) VALUES ('not_expense','counterparty','Jose Ram','Encargos')")
    ingest.import_yape_rows(parse_yape_excel(make_yape_excel(ROWS)))
    t = fresh_db.query_one("SELECT * FROM transactions WHERE counterparty='Jose Ram*'")
    assert t["status"] == "excluded" and t["exclude_reason"] == "Encargos"


def test_historial_de_yape_adjunto_se_importa_solo(fresh_db):
    res = ingest.process_email(ingest.IncomingEmail(
        message_id="<4@x>", from_addr="notificaciones@yape.pe", subject="Historial de Movimientos",
        html="<p>Hola, ANA Te enviamos adjunta la lista de movimientos de tu cuenta Yape como solicitaste.</p>",
        attachments=[("ReporteTransacciones.xlsx", make_yape_excel(ROWS))]))
    assert res["status"] == "import"
    assert res["import"]["gasto_new"] == 5
