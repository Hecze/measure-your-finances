package app.misgastos.core

import java.util.Properties
import javax.mail.Session
import javax.mail.internet.MimeMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Correos MIME como llegan por IMAP: varias partes, quoted-printable, base64 y adjuntos. */
class ImapParseTest {
    private fun mime(raw: String) = MimeMessage(Session.getInstance(Properties()), raw.replace("\n", "\r\n").byteInputStream())

    @Test fun multipartAlternativoConHtmlQuotedPrintable() {
        val m = mime("""From: BCP Notificaciones <notificaciones@notificacionesbcp.com.pe>
Subject: =?UTF-8?Q?Realizaste_un_consumo_con_tu_Tarjeta_de_Cr=C3=A9dito_BCP?=
Date: Thu, 12 Nov 2026 20:17:53 -0500
Message-ID: <abc@bcp>
MIME-Version: 1.0
Content-Type: multipart/alternative; boundary="b1"

--b1
Content-Type: text/plain; charset="UTF-8"
Content-Transfer-Encoding: quoted-printable

Realizaste un consumo de S/ 25.40 con tu Tarjeta de Cr=C3=A9dito BCP
--b1
Content-Type: text/html; charset="UTF-8"
Content-Transfer-Encoding: quoted-printable

<p>Realizaste un consumo de <b>S/ 25.40</b> con tu <b>Tarjeta de Cr=C3=A9dito BCP</b> en <b>BODEGA DON PEPE.</b></p>
<table><tr><td>Total del consumo</td><td><b>S/ 25.40</b></td></tr></table>
--b1--
""")
        val e = Imap.toIncoming(m, "<abc@bcp>")
        assertEquals("notificaciones@notificacionesbcp.com.pe", e.from)
        assertTrue(e.subject.contains("Crédito"))
        assertTrue(e.html.contains("Crédito BCP"))
        val r = Parsers.parse(Email(e.from, e.subject, e.html, e.text, e.receivedAt))
        assertEquals(25.40, r.amount)
        assertEquals("BODEGA DON PEPE", r.counterparty)
    }

    @Test fun historialDeYapeConExcelAdjunto() {
        val xlsx = java.util.Base64.getMimeEncoder().encodeToString(Fixtures.yapeXlsx(Fixtures.ROWS))
        val m = mime("""From: YAPE Notificaciones <notificaciones@yape.pe>
Subject: Historial de Movimientos
Message-ID: <hist@yape>
MIME-Version: 1.0
Content-Type: multipart/mixed; boundary="b2"

--b2
Content-Type: multipart/alternative; boundary="b3"

--b3
Content-Type: text/html; charset="UTF-8"

<p>Hola, ANA Te enviamos adjunta la lista de movimientos de tu cuenta Yape como solicitaste.</p>
--b3--
--b2
Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet; name="ReporteTransacciones.xlsx"
Content-Disposition: attachment; filename="ReporteTransacciones.xlsx"
Content-Transfer-Encoding: base64

$xlsx
--b2--
""")
        val e = Imap.toIncoming(m, "<hist@yape>")
        assertEquals(1, e.attachments.size)
        val l = Ledger(AppData().also { it.seedCategories(false) })
        val r = l.processEmail(e)
        assertEquals("import", r.decision)
        assertEquals(5, r.imported!!["gasto_nuevo"])
    }
}
