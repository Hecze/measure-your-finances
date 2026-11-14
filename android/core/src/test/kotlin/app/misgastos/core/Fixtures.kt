package app.misgastos.core

import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object Fixtures {
    const val BCP = "notificaciones@notificacionesbcp.com.pe"
    const val YAPE = "notificaciones@yape.pe"

    fun html(name: String): String = Fixtures::class.java.getResource("/emails/$name")!!.readText()

    fun email(from: String, subject: String, file: String) =
        Email(from, subject, html = html(file), receivedAt = LocalDateTime.of(2026, 11, 1, 0, 0))

    /** Excel con el mismo layout que el reporte real de Yape (encabezado en la fila 5). */
    fun yapeXlsx(rows: List<List<String>>): ByteArray {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;")
        fun row(n: Int, cells: List<String>) = "<row r=\"$n\">" + cells.mapIndexed { i, v ->
            "<c r=\"${'A' + i}$n\" t=\"inlineStr\"><is><t>${esc(v)}</t></is></c>"
        }.joinToString("") + "</row>"
        val header = listOf("Tipo de Transacción", "Origen", "Destino", "Monto", "Mensaje", "Fecha de operación")
        val sheet = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>" +
            row(2, listOf("Reporte de Movimientos")) + row(5, header) +
            rows.mapIndexed { i, r -> row(6 + i, r) }.joinToString("") + "</sheetData></worksheet>"
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml")); z.write(sheet.toByteArray()); z.closeEntry()
        }
        return out.toByteArray()
    }

    val ROWS = listOf(
        listOf("PAGASTE", "Ana Per*", "Jose Ram*", "150.00", "", "30/10/2026 21:14:12"),
        listOf("TE PAGÓ", "PLIN - Carlos Per Gomez", "ANA MARIA PEREZ LOPEZ", "150.00", "", "30/10/2026 21:13:43"),
        listOf("PAGASTE", "Ana Per*", "Lucia Pat*", "12.00", "almuerzo", "29/10/2026 14:27:13"),
        listOf("PAGASTE", "ANA MARIA PEREZ LOPEZ", "PLIN - JUAN PEREZ GARCIA", "6.00", "", "26/10/2026 11:41:59"),
        listOf("PAGASTE", "Ana Per*", "Flor Per*", "100.00", "sencillo", "29/10/2026 12:14:40"),
        listOf("PAGASTE", "Ana Per*", "PEDIDOS YA", "17.20", "Pago por internet", "06/11/2026 14:08:30"),
    )
}
