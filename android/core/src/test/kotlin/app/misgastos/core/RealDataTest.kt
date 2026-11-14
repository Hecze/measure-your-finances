package app.misgastos.core

import org.json.JSONObject
import java.io.File
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verificación con correos reales. Solo corre si REAL_EMAILS_DIR apunta a una carpeta
 * con correos descargados (nunca se suben al repositorio).
 */
class RealDataTest {
    @Test fun ningunCorreoRealQuedaSinReconocer() {
        val dir = System.getenv("REAL_EMAILS_DIR")?.let { File(it) } ?: return
        if (!dir.isDirectory) return
        val counts = sortedMapOf<String, Int>()
        val unknown = mutableListOf<String>()
        val l = Ledger(AppData().also { it.seedCategories(false) })
        for (f in dir.listFiles()!!.filter { it.name.endsWith(".json") }.sorted()) {
            val m = JSONObject(f.readText())
            val rec = OffsetDateTime.parse(m.getString("date")).atZoneSameInstant(ZoneId.of("America/Lima")).toLocalDateTime()
            val e = Email(m.getString("sender"), m.getString("subject"), m.optString("htmlBody"), m.optString("plaintextBody"), rec)
            val r = Parsers.parse(e)
            counts["${r.kind}/${r.parser}"] = (counts["${r.kind}/${r.parser}"] ?: 0) + 1
            if (r.kind == Kind.UNKNOWN) unknown += "${m.getString("subject")}: ${r.reason}"
            l.processEmail(IncomingEmail(m.getString("id"), m.getString("sender"), m.getString("subject"), m.optString("htmlBody"),
                m.optString("plaintextBody"), rec), refresh = false)
        }
        System.getenv("REAL_YAPE_XLSX")?.let { p -> l.importYape(YapeExcel.parse(File(p).readBytes())) }
        l.refreshAll()
        println("== Correos reales: $counts")
        val out = l.data.txs.filter { it.direction == "out" && it.status != "excluded" }
        println("== Gastos: ${out.size}  fijos: ${out.count { it.fixed }}  variables: ${out.count { !it.fixed }}")
        val names = l.data.categories.associate { it.id to it.name }
        out.groupBy { names[it.categoryId] ?: "(sin clasificar)" }.forEach { (k, v) ->
            println("   $k: ${v.size} gastos, S/ ${"%.2f".format(v.sumOf { l.pen(it) })} [${v.groupingBy { it.categorySource }.eachCount()}]")
        }
        out.filter { it.description.isNotBlank() }.forEach { println("     '${it.description.take(30)}' -> ${names[it.categoryId] ?: "-"} (${it.categorySource})") }
        println("== Tipo de cambio: ${l.data.settings.usdToPen} (${l.data.settings.fxDate})")
        println("== Pendientes: ${l.queue().size}  movimientos: ${l.data.txs.size}")
        assertEquals(emptyList(), unknown)
    }
}
