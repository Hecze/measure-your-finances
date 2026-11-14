from datetime import datetime

from app.parsers import Email, parse_email
from app.textutil import names_match, parse_es_datetime

from . import fixtures as fx

BCP = "notificaciones@notificacionesbcp.com.pe"
YAPE = "notificaciones@yape.pe"


def p(sender, subject, html):
    return parse_email(Email(from_addr=sender, subject=subject, html=html, received_at=datetime(2026, 11, 1)))


def test_consumo_no_se_confunde_con_rechazo_del_pie_de_pagina():
    # Trampa real: todos los correos de consumo traen "Si tu compra fue rechazada..." al pie.
    r = p(BCP, "Realizaste un consumo con tu Tarjeta de Crédito BCP - Servicio de Notificaciones BCP", fx.bcp_consumo())
    assert r.kind == "expense"
    assert r.amount == 25.40 and r.currency == "PEN"
    assert r.counterparty == "BODEGA DON PEPE"
    assert r.account_detail == "Tarjeta de Crédito ****1234"
    assert r.external_ref == "0000111111"
    assert r.occurred_at == datetime(2026, 11, 12, 20, 17)


def test_consumo_en_dolares_y_debito():
    r = p(BCP, "Realizaste un consumo con tu Tarjeta de Débito BCP", fx.bcp_consumo(amount="$ 7.99", tipo="Débito",
                                                                                 merchant="OCULUS *5B8QLDA3F4"))
    assert (r.kind, r.amount, r.currency) == ("expense", 7.99, "USD")
    assert r.account_detail.startswith("Tarjeta de Débito")


def test_pago_a_plin_con_debito_es_persona():
    r = p(BCP, "Realizaste un consumo con tu Tarjeta de Débito BCP", fx.bcp_consumo(tipo="Débito", merchant="PLIN-JUAN PEREZ GARC"))
    assert r.counterparty == "PLIN - JUAN PEREZ GARC"
    assert r.counterparty_type == "person"


def test_compra_rechazada_por_asunto_se_ignora():
    r = p(BCP, "Se rechazó tu compra por e-commerce no permitido - Servicio de Notificaciones BCP", fx.bcp_consumo())
    assert r.kind == "ignore"


def test_transferencia_terceros_con_mensaje():
    r = p(BCP, "Constancia de Transferencia a Terceros BCP", fx.bcp_transfer())
    assert r.kind == "expense" and r.amount == 600.0
    assert r.counterparty.startswith("Inmobiliaria Los Pinos")
    assert r.description == "Alquiler"
    assert "revisar_transferencia" in r.flags


def test_transferencia_otros_bancos_mensaje_vacio_y_banco_destino():
    r = p(BCP, "Constancia de Transferencia a Otros Bancos", fx.bcp_transfer(
        kind="Transferencia a otros bancos", amount="S/ 250.85", dest="Gomez Gomez Luis A.", mensaje="", banco_destino="Ripley"))
    assert r.amount == 250.85
    assert r.description == ""  # no debe tomar "Canal" como mensaje
    assert r.counterparty == "Gomez Gomez Luis A (Ripley)"


def test_transferencia_entre_mis_cuentas_no_es_gasto():
    r = p(BCP, "Constancia de Transferencia Entre mis Cuentas", fx.bcp_transfer(kind="Transferencia entre mis cuentas"))
    assert r.kind == "not_expense"


def test_pago_de_servicio_universidad_monto_fuera_del_preview():
    r = p(BCP, "ENVIO AUTOMATICO - CONSTANCIA DE PAGO DE SERVICIO - BANCA MOVIL BCP", fx.bcp_pago_servicio())
    assert r.kind == "expense" and r.amount == 399.0
    assert r.counterparty == "UNIVERSIDAD EJEMPLO SAC"
    assert r.description == "CARRERAS"
    assert r.occurred_at == datetime(2026, 9, 28, 10, 28)


def test_favorito_de_pago_de_servicios_no_es_pago():
    # Trampa: contiene "Pago de Servicios" pero solo agregaste un favorito.
    r = p(BCP, "Constancia de Favorito: Agregaste un Pago de Servicios - Servicio de Notificaciones BCP",
          fx.bcp_pago_servicio())
    assert r.kind == "ignore"


def test_pago_de_tarjeta_propia_no_es_gasto():
    r = p(BCP, "Constancia de Pago de Tarjeta de Crédito Propia", fx.bcp_pago_tarjeta())
    assert r.kind == "not_expense" and r.amount == 293.06


def test_yapeo_por_correo():
    r = p(YAPE, "Por tu seguridad, te notificaremos por cada yapeo que realices", fx.yape_yapeo())
    assert (r.kind, r.amount, r.counterparty) == ("expense", 3.0, "PIZZERIA EJEMPLO")
    assert r.occurred_at == datetime(2026, 10, 15, 23, 34)


def test_pagar_con_yape_desde_otra_app():
    r = p(YAPE, "Pago exitoso", fx.yape_pago_exitoso())
    assert (r.kind, r.amount, r.counterparty) == ("expense", 17.2, "PEDIDOS YA")


def test_correo_de_banco_desconocido_no_se_adivina():
    r = p(BCP, "Nuevo tipo de correo que nunca vimos", "<p>Hola, algo pasó con S/ 50.00 en tu cuenta.</p>")
    assert r.kind == "unknown"


def test_plantilla_reconocida_sin_monto_queda_en_revision():
    r = p(BCP, "Realizaste un consumo con tu Tarjeta de Crédito BCP", "<p>Realizaste un consumo con tu tarjeta.</p>")
    assert r.kind == "unknown" and "monto" in r.reason


def test_fechas_en_espanol():
    assert parse_es_datetime("02 Nov, 2026 - 08:25 am") == datetime(2026, 11, 2, 8, 25)
    assert parse_es_datetime("22 Ago. 2026 - 11:27 am") == datetime(2026, 8, 22, 11, 27)
    assert parse_es_datetime("15 octubre 2026 - 11:34 p. m.") == datetime(2026, 10, 15, 23, 34)
    assert parse_es_datetime("Sábado, 07 Noviembre 2026 - 01:10 PM") == datetime(2026, 11, 7, 13, 10)
    assert parse_es_datetime("30/10/2026 21:14:12") == datetime(2026, 10, 30, 21, 14, 12)
    assert parse_es_datetime("16/09/2026 a las 21:21") == datetime(2026, 9, 16, 21, 21)


def test_nombres_enmascarados():
    assert names_match("Rodolfo Gom*", "Gomez Gomez Rodolfo A.")
    assert names_match("Ana Per*", "ANA MARIA PEREZ LOPEZ")
    assert not names_match("Ana Per*", "Luis Gomez")
