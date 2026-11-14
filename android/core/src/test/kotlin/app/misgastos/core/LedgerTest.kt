package app.misgastos.core

import app.misgastos.core.Fixtures.BCP
import app.misgastos.core.Fixtures.ROWS
import app.misgastos.core.Fixtures.html
import app.misgastos.core.Fixtures.yapeXlsx
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LedgerTest {
    private fun ledger() = Ledger(AppData().also { it.seedCategories(false) }) { LocalDateTime.of(2026, 11, 14, 12, 0) }
    private fun cat(l: Ledger, name: String) = l.data.categories.first { it.name == name }.id

    @Test fun excelReimportNoDuplica() {
        val l = ledger()
        val rows = YapeExcel.parse(yapeXlsx(ROWS))
        assertEquals(6, rows.size)
        val a = l.importYape(rows)
        assertEquals(5, a["gasto_nuevo"]); assertEquals(1, a["ingreso_nuevo"])
        val b = l.importYape(YapeExcel.parse(yapeXlsx(ROWS)))
        assertEquals(null, b["gasto_nuevo"]); assertEquals(5, b["gasto_repetido"])
        assertTrue("Ana Per*" in l.data.settings.ownerNames)
    }

    @Test fun correoYExcelNoSeDuplican() {
        val l = ledger()
        l.processEmail(IncomingEmail("<1>", BCP, "Realizaste un consumo con tu Tarjeta de Débito BCP", html("consumo_plin.html"),
            receivedAt = LocalDateTime.of(2026, 10, 26, 11, 42)))
        l.processEmail(IncomingEmail("<2>", "notificaciones@yape.pe", "Pago exitoso", html("yape_pago_exitoso.html"),
            receivedAt = LocalDateTime.of(2026, 11, 6, 14, 9)))
        val s = l.importYape(YapeExcel.parse(yapeXlsx(ROWS)))
        assertEquals(3, s["gasto_nuevo"]) // Jose, Lucia, Flor
        val six = l.data.txs.filter { it.amount == 6.0 }
        assertEquals(1, six.size)
        assertEquals("PLIN - JUAN PEREZ GARCIA", six[0].counterparty) // nombre completo del Excel
        assertEquals("already", l.processEmail(IncomingEmail("<1>", BCP, "x")).decision)
    }

    @Test fun paseDeDineroYCambioDeEfectivo() {
        val l = ledger()
        l.importYape(YapeExcel.parse(yapeXlsx(ROWS)))
        val jose = l.data.txs.first { it.counterparty == "Jose Ram*" }
        assertTrue("pase_de_dinero" in jose.flags)
        val flor = l.data.txs.first { it.counterparty == "Flor Per*" }
        assertTrue("posible_cambio_o_encargo" in flor.flags)
        assertEquals("pending", jose.status) // solo sugerencia
        assertTrue(l.queue().first() == jose || l.queue().contains(jose))
    }

    @Test fun personaQueTambienTeEnvia() {
        val l = ledger()
        l.importYape(YapeExcel.parse(yapeXlsx(listOf(
            listOf("TE PAGÓ", "Rodolfo Gom*", "Ana Per*", "200.00", "", "01/11/2026 10:00:00"),
            listOf("TE PAGÓ", "Rodolfo Gom*", "Ana Per*", "300.00", "", "03/11/2026 10:00:00"),
        ))))
        l.processEmail(IncomingEmail("<3>", BCP, "Constancia de Transferencia a Otros Bancos", html("transfer_otros.html")
            .replace("Gomez Gomez Luis A.", "Gomez Gomez Rodolfo A."), receivedAt = LocalDateTime.of(2026, 11, 13, 20, 23)))
        val t = l.data.txs.first { it.amount == 650.0 }
        assertTrue("persona_tambien_te_envia" in t.flags)
    }

    @Test fun fijosYVariables() {
        val l = ledger()
        // Servicio pagado por banca móvil -> fijo
        l.processEmail(IncomingEmail("<4>", BCP, "ENVIO AUTOMATICO - CONSTANCIA DE PAGO DE SERVICIO", html("pago_servicio.html")))
        // Alquiler por transferencia con mensaje -> fijo
        l.processEmail(IncomingEmail("<5>", BCP, "Constancia de Transferencia a Terceros BCP", html("transfer_terceros.html")))
        l.importYape(YapeExcel.parse(yapeXlsx(ROWS)))
        assertTrue(l.data.txs.first { it.amount == 399.0 }.fixed)
        assertTrue(l.data.txs.first { it.amount == 600.0 }.fixed)
        assertTrue(!l.data.txs.first { it.counterparty == "Lucia Pat*" }.fixed)
    }

    @Test fun recurrenteMensualEsFijo() {
        val l = ledger()
        val rows = (6..10).map { m -> listOf("PAGASTE", "Ana Per*", "GIMNASIO EJEMPLO SAC", "120.00", "", "05/%02d/2026 09:00:00".format(m)) }
        l.importYape(YapeExcel.parse(yapeXlsx(rows)))
        assertTrue(l.data.txs.all { it.fixed })
    }

    @Test fun recordarNoContarPersona() {
        val l = ledger()
        l.importYape(YapeExcel.parse(yapeXlsx(ROWS)))
        val jose = l.data.txs.first { it.counterparty == "Jose Ram*" }
        l.update(jose.id, status = "excluded", excludeReason = "Pase de dinero", remember = true)
        l.importYape(YapeExcel.parse(yapeXlsx(listOf(listOf("PAGASTE", "Ana Per*", "Jose Ram*", "80.00", "", "10/11/2026 10:00:00")))))
        assertEquals("excluded", l.data.txs.first { it.amount == 80.0 }.status)
    }

    @Test fun historialAdjuntoSeImportaSolo() {
        val l = ledger()
        val r = l.processEmail(IncomingEmail("<6>", "notificaciones@yape.pe", "Historial de Movimientos",
            "<p>Te enviamos adjunta la lista de movimientos de tu cuenta Yape como solicitaste.</p>",
            attachments = listOf("ReporteTransacciones.xlsx" to yapeXlsx(ROWS))))
        assertEquals("import", r.decision); assertEquals(5, r.imported!!["gasto_nuevo"])
    }

    @Test fun guardarYCargar() {
        val l = ledger()
        l.importYape(YapeExcel.parse(yapeXlsx(ROWS)))
        val copy = AppData.fromJson(l.data.toJson())
        assertEquals(l.data.txs.size, copy.txs.size)
        assertEquals(l.data.toJson(), copy.toJson())
    }

    @Test fun entiendeDescripcionesPeruanas() {
        val l = ledger()
        val rows = listOf(
            listOf("PAGASTE", "Ana Per*", "Sonia Puz*", "24.00", "1/4 + 1l de chicha", "01/11/2026 13:00:00"),
            listOf("PAGASTE", "Ana Per*", "Alex Flo*", "2.00", "pasaje mall", "01/11/2026 14:00:00"),
            listOf("PAGASTE", "Ana Per*", "Flor Per*", "13.00", "chaufa + maracuyá", "01/11/2026 15:00:00"),
            listOf("PAGASTE", "Ana Per*", "Flor Per*", "5.00", "gillet más agua", "01/11/2026 16:00:00"),
            listOf("PAGASTE", "Ana Per*", "Flor Per*", "20.00", "karate valery", "01/11/2026 17:00:00"),
            listOf("PAGASTE", "Ana Per*", "Flor Per*", "15.00", "enanita", "01/11/2026 18:00:00"),
        )
        l.importYape(YapeExcel.parse(yapeXlsx(rows)))
        fun c(d: String) = l.data.txs.first { it.description == d }.categoryId
        assertEquals(cat(l, "Comida"), c("1/4 + 1l de chicha"))
        assertEquals(cat(l, "Transporte"), c("pasaje mall"))
        assertEquals(cat(l, "Comida"), c("chaufa + maracuyá"))
        assertEquals(cat(l, "Salud y cuidado"), c("gillet más agua"))
        assertEquals(cat(l, "Educación"), c("karate valery"))
        assertEquals(null, c("enanita")) // no se adivina
    }

    @Test fun aprendeDeLasCorrecciones() {
        val l = ledger()
        l.importYape(YapeExcel.parse(yapeXlsx(listOf(listOf("PAGASTE", "Ana Per*", "Flor Per*", "15.00", "enanita", "01/11/2026 18:00:00")))))
        val t = l.data.txs.first()
        l.update(t.id, categoryId = cat(l, "Comida"))
        l.importYape(YapeExcel.parse(yapeXlsx(listOf(listOf("PAGASTE", "Ana Per*", "Luis Gom*", "15.00", "2 enanitas no, enanita", "02/11/2026 18:00:00")))))
        assertEquals(cat(l, "Comida"), l.data.txs.first { it.counterparty == "Luis Gom*" }.categoryId)
    }

    @Test fun loUsualConEstaPersona() {
        val l = ledger()
        val rows = listOf(
            listOf("PAGASTE", "Ana Per*", "Sonia Puz*", "24.00", "1/4 de pollo", "01/11/2026 13:00:00"),
            listOf("PAGASTE", "Ana Per*", "Sonia Puz*", "16.00", "filete de pollo con arroz", "02/11/2026 13:00:00"),
            listOf("PAGASTE", "Ana Per*", "Sonia Puz*", "12.00", "", "03/11/2026 13:00:00"),
        )
        l.importYape(YapeExcel.parse(yapeXlsx(rows)))
        val t = l.data.txs.first { it.amount == 12.0 }
        assertEquals(cat(l, "Comida"), t.categoryId)
        assertEquals("persona", t.categorySource) // solo sugerencia
    }

    @Test fun apiCompleta() {
        var saved: String? = null
        val secrets = mutableMapOf<String, String?>()
        val api = Api(object : Storage { override fun load() = saved; override fun save(json: String) { saved = json } },
            object : Secrets { override fun get(name: String) = secrets[name]; override fun set(name: String, value: String?) { secrets[name] = value } },
            "es") { LocalDateTime.of(2026, 11, 14, 12, 0) }
        val b64 = java.util.Base64.getEncoder().encodeToString(yapeXlsx(ROWS))
        assertTrue(api.call("import.preview", """{"base64":"$b64"}""").contains("\"gasto_nuevo\":5"))
        api.call("import.commit", """{"base64":"$b64"}""")
        val home = org.json.JSONObject(api.call("home", """{"ym":"2026-10"}""")).getJSONObject("data")
        assertTrue(home.getDouble("total") > 0)
        val list = org.json.JSONObject(api.call("list", """{"ym":"2026-10","group":"variable"}""")).getJSONObject("data")
        assertTrue(list.getJSONArray("breakdown").length() > 0)
        val q = org.json.JSONObject(api.call("queue", null)).getJSONArray("data")
        assertTrue(q.length() > 0)
        assertTrue(saved != null)
        assertTrue(api.call("nope", null).contains("\"ok\":false"))
    }
}
