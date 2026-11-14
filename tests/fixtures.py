"""Correos de prueba con la MISMA estructura que los reales de BCP y Yape
(tablas con etiqueta en una celda y valor en otra, pies de página con trampas),
pero con datos inventados."""

BCP_FOOTER = """
<tr><td><p>¿Problemas con tus compras?</p></td></tr>
<tr><td><p>Si tu compra fue rechazada, cancelada o duplicada, el tiempo estimado de devolución será de 3 a 7 días.</p></td></tr>
<tr><td><p><b>¿No reconoces esta operación?</b></p><p>Comunícate al (01) 311-9898</p></td></tr>
<tr><td><p>El BCP nunca te solicitará datos confidenciales... número de tu tarjeta de débito o crédito</p></td></tr>
"""


def row(label: str, value: str) -> str:
    return (f'<tr><td style="width:276px" valign="top">{label}</td>'
            f'<td style="text-align:right" valign="top"><b><a style="color:#202E44">{value}</a></b></td></tr>')


def bcp_consumo(amount="S/ 25.40", tipo="Crédito", merchant="BODEGA DON PEPE", fecha="12 de noviembre de 2026 - 08:17 PM",
                card="************1234", op="0000111111"):
    return f"""<html><head><style>p{{margin:0}}</style></head><body><table width="600">
<tr><td><img src="header.png"></td></tr>
<tr><td><p>Hola <b>Ana María,</b></p>
<p>Realizaste un consumo de <b>{amount}</b> con tu <b>Tarjeta de {tipo} BCP</b> en <b>{merchant}.</b></p>
<p>Por tu seguridad, te enviamos los <b>datos de tu operación.</b></p></td></tr>
<tr><td><p><b>Monto</b></p></td></tr>
<tr><td><table>{row("Total del consumo", amount)}</table></td></tr>
<tr><td><p><b>Datos de la operación</b></p></td></tr>
<tr><td><table>
{row("Operación realizada", f"Consumo Tarjeta de {tipo}")}
{row("Fecha y hora", fecha)}
{row(f"Número de Tarjeta de {tipo}", card)}
{row("Empresa", merchant)}
{row("Número de operación", op)}
</table></td></tr>
{BCP_FOOTER}
</table></body></html>"""


def bcp_transfer(kind="Transferencia a terceros BCP", amount="S/ 600.00", dest="Inmobiliaria Los Pinos S.",
                 mensaje="Alquiler", banco_destino=None):
    amounts = row("Monto transferido", amount) if banco_destino is None else (
        row("Monto enviado", amount) + row("Comisión", "S/ 0.00") + row("Total cobrado", amount))
    extra = row("Banco destino", banco_destino) if banco_destino else ""
    return f"""<html><body><table>
<tr><td><p>Hola <b>Ana María,</b></p><p>Realizaste una transferencia de <b>{amount}</b> desde tu <b>Clasica.</b></p></td></tr>
<tr><td><p><b>Montos</b></p></td></tr><tr><td><table>{amounts}</table></td></tr>
<tr><td><p><b>Datos de la operación</b></p></td></tr>
<tr><td><table>
{row("Operación realizada", kind)}
{row("Fecha y hora", "13 de Noviembre de 2026 - 08:22 PM")}
<tr><td>Enviado a</td><td><b>{dest}</b><br>**** 0001<br>Moneda Soles</td></tr>
{extra}
<tr><td>Desde</td><td><b>Clasica</b><br>**** 9999<br>Moneda Soles</td></tr>
<tr><td>Mensaje</td><td><b>{mensaje}</b></td></tr>
{row("Canal", "Banca Móvil BCP")}
{row("Número de operación", "01234567")}
</table></td></tr>{BCP_FOOTER}</table></body></html>"""


def bcp_pago_servicio(company="UNIVERSIDAD EJEMPLO SAC", service="CARRERAS", amount="S/ 399.00"):
    # Formato real: "Etiqueta: *valor*" y varios campos vacíos ("Comisión: **").
    return f"""<html><body><table>
<tr><td><p><b>Hola ANA MARIA,</b></p><p>¡Tu operación se realizó con éxito!</p></td></tr>
<tr><td><p>Operación realizada:</p><p><b>Pago de servicios</b></p><p>Número de operación:</p><p><b>07654321</b></p></td></tr>
<tr><td>Fecha y hora: <b>Lunes, 28 Septiembre 2026 - 10:28 A. M.</b></td></tr>
<tr><td>Empresa: <b>{company}</b></td></tr>
<tr><td>Servicio: <b>{service}</b></td></tr>
<tr><td>Código de usuario: <b>I202600001</b></td></tr>
<tr><td>Comisión: <b></b></td></tr>
<tr><td>Cuenta de origen: <b>Tarjeta de crédito<br>**** 1234<br>ANA MARIA</b></td></tr>
<tr><td>Valor venta: <b></b></td></tr>
<tr><td>Monto total: <b>{amount}</b></td></tr>
<tr><td>Doc. pago: <b>BV501-0000001</b> Importe: <b>{amount}</b> Mora: <b>S/ 0.00</b></td></tr>
</table></body></html>"""


def bcp_pago_tarjeta(amount="S/ 293.06"):
    return f"""<html><body><table>
<tr><td><p>Hola <b>Ana María,</b></p><p>Realizaste un pago a tu tarjeta de <b>{amount}</b> desde tu <b>Cuenta digital.</b></p></td></tr>
<tr><td><table>{row("Monto pagado", amount)}{row("Operación realizada", "Pago de tarjeta propia BCP")}
{row("Fecha y hora", "11 de Noviembre de 2026 - 08:00 PM")}{row("Número de operación", "02222222")}</table></td></tr>
</table></body></html>"""


def yape_yapeo(amount="3.00", name="PIZZERIA EJEMPLO"):
    return f"""<html><body><table>
<tr><td><p><strong>¡Hola, ANA MARIA P.!</strong></p><p><strong>¡Acabas de yapear exitosamente!</strong></p></td></tr>
<tr><td><p><strong>Monto de yapeo*</strong></p><table><tr><td>S/</td><td>{amount}</td></tr></table></td></tr>
<tr><td><table cellSpacing="16px">
<tr><td>Yapero</td><td>ANA MARIA P.</td></tr>
<tr><td>Fecha y Hora de la operación</td><td>15 octubre 2026 - 11:34 p. m.</td></tr>
<tr><td>Nombre del Beneficiario</td><td>{name}</td></tr>
<tr><td>Nº de operación</td><td>1111111</td></tr>
</table></td></tr></table></body></html>"""


def yape_pago_exitoso(merchant="PEDIDOS YA", amount="17.20"):
    return f"""<html><body><table>
<tr><td>Hola ANA,<br>¡Tu pago en {merchant}
 fue exitoso!</td></tr>
<tr><td>Monto total</td></tr>
<tr><td><span>S/</span>{amount}</td></tr>
<tr><td>Fecha y hora:</td><td>06 noviembre 2026 - 02:08 p. m.</td></tr>
<tr><td>Titular:</td><td>ANA MARIA PEREZ</td></tr>
<tr><td>ID de operación:</td><td><a>1700000000001</a></td></tr>
</table></body></html>"""
