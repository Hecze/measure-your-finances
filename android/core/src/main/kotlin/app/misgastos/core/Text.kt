package app.misgastos.core

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeVisitor
import java.text.Normalizer
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Utilidades de texto: HTML -> texto, montos, fechas en español, nombres enmascarados. */
object Text {

    private val BLOCK = setOf("p", "div", "tr", "br", "li", "h1", "h2", "h3", "h4", "h5", "h6", "table", "tbody", "thead", "section")
    private val CELL = setOf("td", "th")
    private val SKIP = setOf("style", "script", "head", "title")

    /**
     * Convierte el HTML de un correo bancario a texto. Cada fila de tabla queda en
     * su línea y las celdas separadas por " | ", así "Monto total | S/ 399.00" se
     * mantiene junto.
     */
    fun htmlToText(html: String): String {
        if (html.isBlank()) return ""
        val doc = Jsoup.parse(html)
        val sb = StringBuilder()
        doc.body().traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                if (node is Element) {
                    val tag = node.normalName()
                    if (tag in BLOCK) sb.append('\n') else if (tag in CELL) sb.append(" | ")
                } else if (node is TextNode) {
                    if (!insideSkip(node)) sb.append(node.wholeText)
                }
            }

            override fun tail(node: Node, depth: Int) {
                if (node is Element && node.normalName() in BLOCK) sb.append('\n')
            }
        })
        return clean(sb.toString())
    }

    private fun insideSkip(n: Node): Boolean {
        var p = n.parent()
        while (p != null) {
            if (p is Element && p.normalName() in SKIP) return true
            p = p.parent()
        }
        return false
    }

    fun clean(text: String): String = text
        .replace("\r", "").replace("​", "").replace(' ', ' ')
        .split('\n')
        .map { it.replace(Regex("[ \\t]+"), " ").replace(Regex("(\\s*\\|\\s*)+"), " | ").trim(' ', '|') }
        .filter { it.isNotEmpty() }
        .joinToString("\n")

    /** Una sola línea sin separadores, para buscar frases. */
    fun flat(text: String): String = text.replace("|", " ").replace("*", " ").replace(Regex("\\s+"), " ").trim()

    fun stripAccents(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    /** minúsculas, sin tildes, sin signos, espacios simples. */
    fun norm(s: String?): String {
        val t = stripAccents((s ?: "").lowercase()).replace(Regex("[^a-z0-9ñ]+"), " ")
        return t.replace(Regex("\\s+"), " ").trim()
    }

    private val PREFIXES = listOf("plin ", "bim ", "izi ", "yape ", "payu ", "pyu ", "dp ", "paypal ", "bbva ", "ibk ")

    /** Clave estable de una contraparte ('PLIN - Juan Per*' -> 'juan per'). */
    fun counterpartyKey(s: String?): String {
        var k = norm(s)
        for (p in PREFIXES) if (k.startsWith(p)) k = k.removePrefix(p)
        k = k.replace(Regex("\\b(?=[a-z0-9]*\\d)[a-z0-9]{6,}\\b"), " ") // códigos de transacción
        return k.replace(Regex("\\s+"), " ").trim()
    }

    fun similarity(a: String?, b: String?): Double {
        val x = counterpartyKey(a)
        val y = counterpartyKey(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x.contains(y) || y.contains(x)) return 1.0
        val d = levenshtein(x, y)
        return 1.0 - d.toDouble() / maxOf(x.length, y.length)
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }

    /**
     * ¿'Ana Per*' y 'ANA MARIA PEREZ LOPEZ' son la misma persona? Yape enmascara
     * nombres: primer nombre + inicio del apellido + '*'. Cada palabra de la forma
     * corta debe ser prefijo de alguna palabra de la forma larga.
     */
    fun namesMatch(a: String?, b: String?): Boolean {
        fun tokens(s: String?) = norm((s ?: "").replace("*", " * ")).split(" ").filter { it.isNotEmpty() && it != "plin" && it != "bim" }
        val ta = tokens(a)
        val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return false
        val (short, long) = if (ta.size <= tb.size) ta to tb else tb to ta
        if (short.size < 2 && long.size >= 2) return false
        return short.all { sw -> long.any { it.startsWith(sw) } }
    }

    // ------------------------------------------------------------- montos
    private const val CUR = "(S/\\.?|PEN|US\\$|USD|\\$)"
    private const val NUM = "(\\d{1,3}(?:[,\\s]\\d{3})*(?:\\.\\d{1,2})?|\\d+(?:[.,]\\d{1,2})?)"
    val AMOUNT = Regex(CUR + "\\s*\\*{0,2}\\s*" + NUM, RegexOption.IGNORE_CASE)

    fun parseNumber(raw: String): Double? {
        var s = raw.trim().replace(" ", "")
        if (s.isEmpty()) return null
        if (s.contains(',') && s.contains('.')) s = s.replace(",", "")
        else if (s.contains(',')) {
            val head = s.substringBeforeLast(',')
            val tail = s.substringAfterLast(',')
            s = head.replace(",", "") + (if (tail.length <= 2) "." else "") + tail
        }
        return s.toDoubleOrNull()?.let { Math.round(it * 100) / 100.0 }
    }

    fun currencyCode(sym: String): String {
        val s = sym.uppercase().replace(".", "")
        return if (s == "$" || s == "US$" || s == "USD") "USD" else "PEN"
    }

    data class Money(val amount: Double, val currency: String)

    /** Primer monto que sigue a una etiqueta. Si la etiqueta no está, null: nunca "cualquier monto". */
    fun amountAfterLabel(text: String, labels: List<String>): Money? {
        val f = flat(text)
        for (label in labels) {
            val re = Regex(Regex.escape(label) + "\\s*:?\\s*(?:\\|\\s*)?" + AMOUNT.pattern, RegexOption.IGNORE_CASE)
            val m = re.find(f) ?: continue
            val v = parseNumber(m.groupValues[2]) ?: continue
            return Money(v, currencyCode(m.groupValues[1]))
        }
        return null
    }

    // ------------------------------------------------------------- fechas
    val MONTHS = mapOf(
        "ene" to 1, "enero" to 1, "feb" to 2, "febrero" to 2, "mar" to 3, "marzo" to 3, "abr" to 4, "abril" to 4,
        "may" to 5, "mayo" to 5, "jun" to 6, "junio" to 6, "jul" to 7, "julio" to 7, "ago" to 8, "agosto" to 8,
        "set" to 9, "sep" to 9, "sept" to 9, "septiembre" to 9, "setiembre" to 9, "oct" to 10, "octubre" to 10,
        "nov" to 11, "noviembre" to 11, "dic" to 12, "diciembre" to 12,
    )
    private const val TIME = "(\\d{1,2}):(\\d{2})(?::(\\d{2}))?\\s*((?:a|p)\\.?\\s?m\\.?)?"
    private val D1 = Regex("(\\d{1,2})\\s+(?:de\\s+)?([a-záéíóú]{3,10})\\.?,?\\s+(?:de\\s+|del\\s+)?(\\d{4})\\s*(?:-|a las|,)?\\s*$TIME", RegexOption.IGNORE_CASE)
    private val D2 = Regex("(\\d{1,2})/(\\d{1,2})/(\\d{4})\\s*(?:-|a las)?\\s*$TIME", RegexOption.IGNORE_CASE)
    private val D3 = Regex("(\\d{1,2})\\s+(?:de\\s+)?([a-záéíóú]{3,10})\\.?,?\\s+(?:de\\s+)?(\\d{4})", RegexOption.IGNORE_CASE)

    private fun to24(h: Int, ampm: String?): Int {
        if (ampm.isNullOrBlank()) return h
        val pm = ampm.lowercase().replace(".", "").replace(" ", "").startsWith("p")
        return when {
            pm && h < 12 -> h + 12
            !pm && h == 12 -> 0
            else -> h
        }
    }

    fun parseEsDateTime(text: String?): LocalDateTime? {
        if (text.isNullOrBlank()) return null
        val t = flat(text)
        for ((i, re) in listOf(D1, D2).withIndex()) {
            for (m in re.findAll(t)) {
                val g = m.groupValues
                try {
                    val day = g[1].toInt()
                    val month = if (i == 0) MONTHS[stripAccents(g[2].lowercase()).trimEnd('.')] ?: continue else g[2].toInt()
                    val year = g[3].toInt()
                    val hour = to24(g[4].toInt(), g[7])
                    return LocalDateTime.of(year, month, day, hour, g[5].toInt(), g[6].ifEmpty { "0" }.toInt())
                } catch (_: Exception) {
                    continue
                }
            }
        }
        val m = D3.find(t) ?: return null
        val month = MONTHS[stripAccents(m.groupValues[2].lowercase())] ?: return null
        return try {
            LocalDateTime.of(m.groupValues[3].toInt(), month, m.groupValues[1].toInt(), 0, 0)
        } catch (_: Exception) {
            null
        }
    }

    val FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    fun fmt(dt: LocalDateTime): String = dt.format(FMT)
    fun parse(s: String): LocalDateTime = LocalDateTime.parse(s, FMT)
}
