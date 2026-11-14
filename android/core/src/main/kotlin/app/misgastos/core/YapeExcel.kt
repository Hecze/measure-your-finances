package app.misgastos.core

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Lee el "Reporte de Movimientos" de Yape (.xlsx).
 *
 * Formato real (2026): fila 5 = Tipo de Transacción | Origen | Destino | Monto | Mensaje | Fecha de operación,
 * luego filas PAGASTE / TE PAGÓ. El encabezado se busca, no se asume su posición.
 */
data class YapeRow(
    val direction: String, // "out" (PAGASTE) | "in" (TE PAGÓ)
    val origin: String,
    val destination: String,
    val amount: Double,
    val message: String,
    val occurredAt: LocalDateTime,
) {
    val counterparty: String get() = if (direction == "out") destination else origin
    fun dedupeKey(): String = "yape|$direction|${Text.norm(origin)}|${Text.norm(destination)}|${"%.2f".format(java.util.Locale.US, amount)}|${Text.fmt(occurredAt)}"
}

class YapeExcelException(msg: String) : Exception(msg)

object YapeExcel {
    private val HEADERS = mapOf(
        "tipo" to listOf("tipo de transaccion", "tipo"),
        "origen" to listOf("origen"),
        "destino" to listOf("destino"),
        "monto" to listOf("monto", "importe"),
        "mensaje" to listOf("mensaje", "descripcion"),
        "fecha" to listOf("fecha de operacion", "fecha"),
    )
    private val DATE_FORMATS = listOf("dd/MM/yyyy HH:mm:ss", "dd/MM/yyyy HH:mm", "yyyy-MM-dd HH:mm:ss").map { DateTimeFormatter.ofPattern(it) }

    fun parse(bytes: ByteArray): List<YapeRow> {
        val rows = readFirstSheet(bytes)
        var cols: Map<String, Int>? = null
        val out = mutableListOf<YapeRow>()
        for (cells in rows) {
            if (cols == null) {
                val normed = cells.map { Text.norm(it) }
                val found = mutableMapOf<String, Int>()
                for ((key, names) in HEADERS) {
                    val i = normed.indexOfFirst { it in names && it.isNotEmpty() && !found.containsValue(normed.indexOf(it)) }
                    if (i >= 0) found[key] = i
                }
                if (found.keys.containsAll(listOf("tipo", "monto", "fecha")) && (found.containsKey("origen") || found.containsKey("destino"))) cols = found
                continue
            }
            fun g(k: String): String = cols[k]?.let { cells.getOrNull(it) }?.trim() ?: ""
            val tipo = Text.norm(g("tipo"))
            if (tipo.isEmpty()) continue
            val direction = when {
                tipo.contains("pagaste") || tipo.contains("yapeaste") || tipo.contains("enviaste") -> "out"
                tipo.contains("te pago") || tipo.contains("recibiste") || tipo.contains("te yapeo") -> "in"
                else -> continue
            }
            val amount = Text.parseNumber(g("monto").replace("S/", "").trim()) ?: continue
            val dt = parseDate(g("fecha")) ?: continue
            out += YapeRow(direction, g("origen"), g("destino"), kotlin.math.abs(amount), g("mensaje"), dt)
        }
        if (cols == null) throw YapeExcelException("Este archivo no parece un historial de Yape.")
        return out
    }

    private fun parseDate(s: String): LocalDateTime? {
        for (f in DATE_FORMATS) try { return LocalDateTime.parse(s, f) } catch (_: Exception) { }
        // Fecha numérica de Excel (por si alguien guardó el archivo desde Excel)
        s.toDoubleOrNull()?.let { serial ->
            val base = LocalDateTime.of(1899, 12, 30, 0, 0)
            return base.plusSeconds((serial * 86400).toLong())
        }
        return null
    }

    /** Lee la primera hoja como lista de filas de texto. */
    private fun readFirstSheet(bytes: ByteArray): List<List<String>> {
        val entries = mutableMapOf<String, ByteArray>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.name == "xl/sharedStrings.xml" || e.name.startsWith("xl/worksheets/sheet")) entries[e.name] = zip.readBytes()
                }
            }
        } catch (ex: Exception) {
            throw YapeExcelException("No se pudo abrir el archivo como Excel (.xlsx).")
        }
        val sheetName = entries.keys.filter { it.startsWith("xl/worksheets/sheet") }.minByOrNull {
            Regex("sheet(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() ?: 999
        } ?: throw YapeExcelException("El Excel no tiene hojas.")
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
        val shared = mutableListOf<String>()
        entries["xl/sharedStrings.xml"]?.let { data ->
            val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(data))
            val sis = doc.getElementsByTagName("si")
            for (i in 0 until sis.length) {
                val ts = (sis.item(i) as Element).getElementsByTagName("t")
                shared += (0 until ts.length).joinToString("") { ts.item(it).textContent }
            }
        }
        val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(entries.getValue(sheetName)))
        val rowsOut = mutableListOf<List<String>>()
        val rows = doc.getElementsByTagName("row")
        for (r in 0 until rows.length) {
            val row = rows.item(r) as Element
            val cells = row.getElementsByTagName("c")
            val values = sortedMapOf<Int, String>()
            for (c in 0 until cells.length) {
                val cell = cells.item(c) as Element
                val col = colIndex(cell.getAttribute("r"))
                val type = cell.getAttribute("t")
                val v = cell.getElementsByTagName("v").item(0)?.textContent
                val text = when (type) {
                    "s" -> v?.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                    "inlineStr" -> cell.getElementsByTagName("t").let { ts -> (0 until ts.length).joinToString("") { ts.item(it).textContent } }
                    else -> v ?: ""
                }
                values[if (col >= 0) col else values.size] = text
            }
            val max = (values.keys.maxOrNull() ?: -1)
            rowsOut += (0..max).map { values[it] ?: "" }
        }
        return rowsOut
    }

    private fun colIndex(ref: String): Int {
        val letters = ref.takeWhile { it.isLetter() }
        if (letters.isEmpty()) return -1
        return letters.fold(0) { acc, ch -> acc * 26 + (ch.uppercaseChar() - 'A' + 1) } - 1
    }
}
