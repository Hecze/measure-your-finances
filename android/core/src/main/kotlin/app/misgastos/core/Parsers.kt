package app.misgastos.core

import app.misgastos.core.Text.Money
import app.misgastos.core.Text.amountAfterLabel
import app.misgastos.core.Text.flat
import app.misgastos.core.Text.norm
import app.misgastos.core.Text.parseEsDateTime
import java.time.LocalDateTime

/**
 * Parsers de correos bancarios. Principio: NO ADIVINAR.
 *
 * - Cada tipo de correo conocido tiene una plantilla con frases y etiquetas exactas.
 * - Un correo de banco que no coincide con ninguna plantilla es UNKNOWN y se le
 *   pregunta al usuario. Nunca se descarta en silencio.
 * - Si la plantilla coincide pero falta el monto, también es UNKNOWN.
 *
 * Plantillas construidas y verificadas con correos reales de BCP y Yape (2026).
 */
enum class Kind { EXPENSE, NOT_EXPENSE, INCOME, IGNORE, YAPE_HISTORY, UNKNOWN }

data class ParseResult(
    var kind: Kind,
    var parser: String,
    var reason: String = "",
    var amount: Double? = null,
    var currency: String = "PEN",
    var counterparty: String = "",
    var counterpartyType: String = "unknown",
    var account: String = "",
    var accountDetail: String = "",
    var occurredAt: LocalDateTime? = null,
    var externalRef: String = "",
    var description: String = "",
    val flags: MutableList<String> = mutableListOf(),
    /** Pista para fijo/variable: pagos de servicios suelen ser fijos. */
    var fixedHint: Boolean = false,
) {
    fun money(m: Money?) {
        if (m != null) {
            amount = m.amount
            currency = m.currency
        }
    }
}

class Email(
    val fromAddr: String,
    val subject: String,
    val html: String = "",
    val text: String = "",
    val receivedAt: LocalDateTime? = null,
) {
    /** Se prefiere el HTML: conserva la estructura de tabla. */
    val body: String by lazy { if (html.isNotBlank()) Text.htmlToText(html) else Text.clean(text) }
    val flatBody: String by lazy { flat(body) }
    val nbody: String by lazy { norm(body) }
}

object Parsers {

    private val STOP_LABELS = listOf(
        "Fecha y hora", "Fecha y Hora", "Número de operación", "Nº de operación", "N° de operación",
        "Numero de operacion", "Número de Tarjeta", "Empresa", "Servicio", "Titular", "Código de usuario",
        "Comisión", "Cuenta de origen", "Vigencia", "Valor venta", "Monto", "Mensaje", "Canal", "Desde",
        "Enviado a", "Operación realizada", "Celular", "Tipo de cambio", "Total", "Yapero", "Tu número",
        "Nombre del Beneficiario", "Celular del Beneficiario", "ID de operación", "Banco", "Cuenta destino",
        "Destino", "Beneficiario", "Moneda", "Detalle del servicio", "Número de recibo", "Vencimiento",
        "Operadora", "Operador", "Número recargado", "Cantidad", "Comercio", "Código", "Doc. pago", "Importe",
        "Si tú no", "Por tu seguridad", "Resuelve tus dudas",
    )
    private val LABEL_SET: Set<String> = STOP_LABELS.map { norm(it) }.toSet() + setOf("canal", "moneda soles", "pagado a", "tipo de pago")
    private val STOPS_RE = STOP_LABELS.joinToString("|") { Regex.escape(it) }

    private fun isLabel(v: String): Boolean {
        val nv = norm(v.trimEnd(':'))
        return LABEL_SET.any { nv == it || nv.startsWith("$it ") }
    }

    /** Valor tras una etiqueta en texto aplanado ("Empresa: CIBERTEC SAC"). Corta en la siguiente etiqueta. */
    fun valueAfter(text: String, labels: List<String>, maxLen: Int = 80): String {
        val f = " " + flat(text) + " "
        for (label in labels) {
            val re = Regex(
                "(?<![A-Za-zÁÉÍÓÚáéíóúñÑ])" + Regex.escape(label) +
                    "\\s*:?\\s+(.{1,$maxLen}?)(?=\\s+(?:$STOPS_RE)\\b|\\s*\\*{3,}|\\s{2,}|$)",
                RegexOption.IGNORE_CASE,
            )
            val m = re.find(f) ?: continue
            val v = m.groupValues[1].trim(' ', ':', '.', '-', '*')
            if (v.isNotEmpty() && !isLabel(v)) return v
        }
        return ""
    }

    /**
     * Valor de una etiqueta en el texto convertido desde HTML. Soporta
     * 'Etiqueta | Valor', 'Etiqueta: Valor' y 'Etiqueta' + salto + 'Valor'.
     * La etiqueta debe ocupar la celda completa ("Servicio" no coincide con
     * "¡Tu servicio fue yapeado!").
     */
    fun cellAfter(text: String, label: String): String {
        val target = norm(label)
        val lines = text.split('\n').map { it.trim() }
        for ((idx, line) in lines.withIndex()) {
            val cells = line.split('|').map { it.trim() }
            for ((i, c) in cells.withIndex()) {
                val colon = c.indexOf(':')
                if (colon >= 0) {
                    val head = c.substring(0, colon)
                    val tail = c.substring(colon + 1).trim(' ', '*')
                    if (norm(head) == target && tail.isNotEmpty()) return tail
                }
                if (norm(c) != target) continue
                if (i + 1 < cells.size && cells[i + 1].trim(' ', '*').isNotEmpty()) return cells[i + 1].trim(' ', '*')
                for (nxt in lines.subList(idx + 1, minOf(lines.size, idx + 3))) {
                    val v = nxt.split('|')[0].trim()
                    if (v.trim(' ', '*').isNotEmpty()) return if (isLabel(v)) "" else v
                }
                return ""
            }
        }
        return ""
    }

    private fun field(t: String, vararg labels: String): String {
        for (l in labels) cellAfter(t, l).takeIf { it.isNotEmpty() }?.let { return it }
        return valueAfter(t, labels.toList())
    }

    fun last4(s: String?): String {
        if (s == null) return ""
        Regex("\\*+\\s*(\\d{4})\\b").find(s)?.let { return it.groupValues[1] }
        return Regex("\\s*(\\d{4})\\s*").matchEntire(s)?.groupValues?.get(1) ?: ""
    }

    fun opNumber(text: String): String {
        for (label in listOf("Número de operación Yape", "Nº de operación Yape", "Número de operación", "Nº de operación",
            "N° de operación", "ID de operación", "Numero de operacion")) {
            val v = cellAfter(text, label).ifEmpty { valueAfter(text, listOf(label), 40) }
            Regex("[A-Z0-9-]{4,}").find(v)?.let { return it.value }
        }
        return ""
    }

    private fun whenOf(text: String, e: Email, labels: List<String>, r: ParseResult) {
        for (l in labels) {
            val v = cellAfter(text, l).ifEmpty { valueAfter(text, listOf(l), 60) }
            val dt = if (v.isNotEmpty()) parseEsDateTime(v) else null
            if (dt != null) { r.occurredAt = dt; return }
        }
        parseEsDateTime(text)?.let { r.occurredAt = it; return }
        if (e.receivedAt != null) {
            r.occurredAt = e.receivedAt
            r.flags += "fecha_del_correo"
        }
    }

    private fun senderIs(e: Email, vararg needles: String) = needles.any { e.fromAddr.lowercase().contains(it) }
    private fun hay(e: Email) = norm(e.subject) + " " + e.nbody
    private fun has(e: Email, vararg p: String): Boolean { val h = hay(e); return p.all { h.contains(norm(it)) } }
    private fun hasAny(e: Email, vararg p: String): Boolean { val h = hay(e); return p.any { h.contains(norm(it)) } }

    fun walletCounterparty(name: String): Pair<String, String> {
        val m = Regex("^\\s*(PLIN|BIM|YAPE|TUNKI|AGORA)\\s*[-*]\\s*(.+)$", RegexOption.IGNORE_CASE).find(name)
            ?: return name to "unknown"
        return "${m.groupValues[1].uppercase()} - ${m.groupValues[2].trim()}" to "person"
    }

    private fun missing(r: ParseResult, what: String): ParseResult {
        r.kind = Kind.UNKNOWN
        r.reason = "Reconocimos el tipo de correo (${r.parser}) pero no encontramos $what"
        return r
    }

    // ------------------------------------------------------------- BCP
    private const val BCP = "notificacionesbcp.com.pe"

    private val BCP_IGNORE = listOf(
        // "Favorito: Agregaste un Pago de Servicios" contiene "pago de servicios" pero NO es un pago.
        "constancia de favorito" to "Agregaste un favorito (no es un movimiento)",
        "ampliacion de linea" to "Ampliación de línea de crédito",
        "rechazo tu compra" to "Operación rechazada (no se cobró)",
        "compra rechazada" to "Operación rechazada (no se cobró)",
        "constancia de configuracion" to "Configuración de tarjeta o clave",
        "estado de cuenta" to "Estado de cuenta (no es un movimiento)",
        "no te olvides" to "Recordatorio de débito automático (aún no se cobra)",
        "viabcp" to "Comunicación de ViaBCP",
    )

    /**
     * Solo mira el ASUNTO. Trampa real: todos los correos de consumo del BCP dicen
     * al pie "Si tu compra fue rechazada, cancelada o duplicada...".
     */
    fun bcpIgnore(e: Email): ParseResult? {
        if (!senderIs(e, BCP)) return null
        val subj = norm(e.subject)
        for ((needle, reason) in BCP_IGNORE) if (subj.contains(needle)) return ParseResult(Kind.IGNORE, "bcp_no_movimiento", reason)
        if (e.nbody.contains("lo sentimos tu compra fue rechazada")) return ParseResult(Kind.IGNORE, "bcp_no_movimiento", "Operación rechazada (no se cobró)")
        return null
    }

    fun bcpCardPurchase(e: Email): ParseResult? {
        if (!(senderIs(e, BCP) && has(e, "realizaste un consumo"))) return null
        val t = e.body
        val credit = hasAny(e, "tarjeta de credito")
        val amt = amountAfterLabel(t, listOf("Total del consumo", "Realizaste un consumo de"))
        var merchant = field(t, "Empresa")
        if (merchant.isEmpty()) {
            merchant = Regex("Realizaste un consumo de .*? en (.+?)\\.(?:\\s|$)").find(e.flatBody)?.groupValues?.get(1)?.trim() ?: ""
        }
        val card = field(t, "Número de Tarjeta de Crédito", "Número de Tarjeta de Débito")
        val kindLabel = if (credit) "Tarjeta de Crédito" else "Tarjeta de Débito"
        val r = ParseResult(Kind.EXPENSE, "bcp_consumo_tarjeta", account = "BCP",
            accountDetail = if (last4(card).isNotEmpty()) "$kindLabel ****${last4(card)}" else kindLabel,
            externalRef = opNumber(t))
        whenOf(t, e, listOf("Fecha y hora"), r)
        val (cp, type) = walletCounterparty(merchant)
        r.counterparty = cp
        r.counterpartyType = type
        if (type == "person") r.flags += "p2p_via_tarjeta"
        if (amt == null) return missing(r, "el monto ('Total del consumo')")
        r.money(amt)
        return r
    }

    fun bcpTransfer(e: Email): ParseResult? {
        if (!(senderIs(e, BCP) && hasAny(e, "realizaste una transferencia", "constancia de transferencia"))) return null
        val t = e.body
        val op = field(t, "Operación realizada").ifEmpty { e.subject }
        val opn = norm(op + " " + e.subject)
        val splitter = Regex("\\*{2,}|\\bMoneda\\b")
        val dest = field(t, "Enviado a", "Cuenta destino", "Beneficiario").split(splitter)[0].trim(' ', '.')
        var origin = field(t, "Desde").ifEmpty { valueAfter(t, listOf("desde tu"), 40) }
        origin = origin.split(splitter)[0].trim(' ', '.')
        val msg = cellAfter(t, "Mensaje")
        val bank = cellAfter(t, "Banco destino")
        val amt = amountAfterLabel(t, listOf("Total cobrado", "Monto enviado", "Monto transferido", "Realizaste una transferencia de"))
        val r = ParseResult(Kind.EXPENSE, "bcp_transferencia", account = "BCP",
            accountDetail = if (origin.isEmpty()) "Cuenta" else if (norm(origin).startsWith("cuenta")) origin else "Cuenta $origin",
            counterparty = dest, externalRef = opNumber(t), description = msg.trim())
        whenOf(t, e, listOf("Fecha y hora"), r)
        when {
            opn.contains("entre mis cuentas") -> {
                r.kind = Kind.NOT_EXPENSE
                r.parser = "bcp_transferencia_propia"
                r.reason = "Transferencia entre tus propias cuentas"
                r.counterpartyType = "self"
            }
            opn.contains("otros bancos") -> {
                r.parser = "bcp_transferencia_otros_bancos"
                if (bank.isNotEmpty()) r.counterparty = if (dest.isNotEmpty()) "$dest ($bank)" else bank
                r.flags += "revisar_transferencia"
            }
            else -> {
                r.parser = "bcp_transferencia_terceros"
                r.flags += "revisar_transferencia"
            }
        }
        if (amt == null) return missing(r, "el monto ('Monto transferido')")
        r.money(amt)
        return r
    }

    fun bcpServicePayment(e: Email): ParseResult? {
        if (!(senderIs(e, BCP) && has(e, "pago de servicio"))) return null
        val t = e.body
        val company = field(t, "Empresa")
        val service = field(t, "Servicio")
        val origin = cellAfter(t, "Cuenta de origen").ifEmpty { valueAfter(t, listOf("Cuenta de origen"), 60) }
        val amt = amountAfterLabel(t, listOf("Monto total", "Importe", "Total pagado", "Monto pagado"))
        val originClean = origin.split(Regex("\\*{2,}"))[0].trim()
        val r = ParseResult(Kind.EXPENSE, "bcp_pago_servicio", account = "BCP",
            accountDetail = if (last4(origin).isNotEmpty()) "$originClean ****${last4(origin)}".trim() else originClean.ifEmpty { "Banca Móvil" },
            counterparty = company, counterpartyType = "business", externalRef = opNumber(t), description = service,
            fixedHint = true)
        whenOf(t, e, listOf("Fecha y hora"), r)
        if (norm(company) in setOf("pagoefectivo", "pago efectivo", "kasnet", "safetypay")) {
            r.flags += "intermediario_pago" // el comercio real no aparece en el correo
            r.fixedHint = false
        }
        if (amt == null) return missing(r, "el monto ('Monto total')")
        r.money(amt)
        return r
    }

    fun bcpCardPayment(e: Email): ParseResult? {
        if (!(senderIs(e, BCP) && hasAny(e, "pago de tarjeta de credito propia", "realizaste un pago a tu tarjeta", "pagaste tu tarjeta"))) return null
        val t = e.body
        val r = ParseResult(Kind.NOT_EXPENSE, "bcp_pago_tarjeta_propia", account = "BCP", accountDetail = "Pago de tarjeta",
            counterparty = cellAfter(t, "Pagado a").ifEmpty { cellAfter(t, "Tarjeta pagada") }.ifEmpty { "Tu tarjeta de crédito" },
            reason = "Pago de tu propia tarjeta: sus consumos ya se cuentan uno por uno",
            externalRef = opNumber(t), counterpartyType = "self")
        whenOf(t, e, listOf("Fecha y hora", "hecha el"), r)
        r.money(amountAfterLabel(t, listOf("Monto pagado", "Total pagado", "Realizaste un pago a tu tarjeta de", "para pagar")))
        return r
    }

    fun bcpRefund(e: Email): ParseResult? {
        if (!(senderIs(e, BCP) && hasAny(e, "realizamos una devolucion", "se ha devuelto el monto"))) return null
        val t = e.body
        val r = ParseResult(Kind.INCOME, "bcp_devolucion", account = "BCP", reason = "Devolución de una compra", externalRef = opNumber(t))
        r.flags += "devolucion"
        whenOf(t, e, listOf("Fecha y hora"), r)
        val amt = amountAfterLabel(t, listOf("Total devuelto", "Se ha devuelto el monto de")) ?: return missing(r, "el monto devuelto")
        r.money(amt)
        return r
    }

    fun bcpYapeReceived(e: Email): ParseResult? {
        if (!(senderIs(e, BCP) && hasAny(e, "recepcion de yapeo", "recibiste un yapeo", "te yapearon"))) return null
        val t = e.body
        val r = ParseResult(Kind.INCOME, "bcp_yapeo_recibido", account = "Yape", accountDetail = "Yape",
            counterparty = cellAfter(t, "Enviado por").ifEmpty { valueAfter(t, listOf("Enviado por", "Yapero", "Remitente", "De")) },
            reason = "Te yapearon (ingreso)", externalRef = opNumber(t))
        whenOf(t, e, listOf("Fecha y hora"), r)
        val amt = amountAfterLabel(t, listOf("Monto recibido", "Monto", "Total", "recibiste un yapeo de", "te yapearon"))
            ?: return missing(r, "el monto recibido")
        r.money(amt)
        return r
    }

    fun bcpSavings(e: Email): ParseResult? {
        if (!(senderIs(e, BCP) && hasAny(e, "wardadito", "aporte automatico"))) return null
        if (hasAny(e, "no te olvides", "recuerda que tu aporte")) return ParseResult(Kind.IGNORE, "bcp_recordatorio", "Recordatorio, no es un movimiento")
        val t = e.body
        val aporte = hasAny(e, "aporte")
        val r = ParseResult(Kind.NOT_EXPENSE, "bcp_ahorro", account = "BCP", counterpartyType = "self", counterparty = "Ahorro (wardadito)",
            reason = if (aporte) "Aporte a tu ahorro: es ahorro, no gasto" else "Retiro de tu ahorro")
        whenOf(t, e, listOf("Fecha y hora"), r)
        r.money(amountAfterLabel(t, listOf("Total retirado", "Total aportado", "Monto aportado", "Monto", "Total")))
        return r
    }

    // ------------------------------------------------------------- Yape
    private const val YAPE = "yape.pe"

    private val YAPE_IGNORE = listOf(
        "afiliacion" to "Afiliación de Yape a un comercio",
        "biometria" to "Aviso de seguridad",
        "limite de yapeo" to "Cambio de límite",
        "ingresaste" to "Aviso de inicio de sesión",
        "compras por internet" to "Activación de compras por internet",
        "actualizaste" to "Actualización de datos",
    )

    fun yapeIgnore(e: Email): ParseResult? {
        if (!senderIs(e, YAPE)) return null
        val subj = norm(e.subject)
        for ((needle, reason) in YAPE_IGNORE) if (subj.contains(needle)) return ParseResult(Kind.IGNORE, "yape_no_movimiento", reason)
        return null
    }

    fun yapeHistory(e: Email): ParseResult? {
        if (!(senderIs(e, YAPE) && has(e, "historial de movimientos"))) return null
        return ParseResult(Kind.YAPE_HISTORY, "yape_historial", "Historial de Yape adjunto")
    }

    fun yapeP2P(e: Email): ParseResult? {
        if (!(senderIs(e, YAPE) && has(e, "acabas de yapear"))) return null
        val t = e.body
        val r = ParseResult(Kind.EXPENSE, "yape_yapeo", account = "Yape", accountDetail = "Yape",
            counterparty = field(t, "Nombre del Beneficiario"), externalRef = opNumber(t))
        whenOf(t, e, listOf("Fecha y Hora de la operación", "Fecha y hora"), r)
        val amt = amountAfterLabel(t, listOf("Monto de yapeo*", "Monto de yapeo", "Monto")) ?: return missing(r, "el monto ('Monto de yapeo')")
        r.money(amt)
        return r
    }

    fun yapeMerchant(e: Email): ParseResult? {
        if (!(senderIs(e, YAPE) && hasAny(e, "fue exitoso", "yapeaste tu promo", "fue exitosa"))) return null
        if (hasAny(e, "afiliacion")) return null
        val t = e.body
        val m = Regex("tu pago en (.+?) fue exitoso", RegexOption.IGNORE_CASE).find(e.flatBody)
            ?: Regex("Tu compra de (.+?) fue exitosa", RegexOption.IGNORE_CASE).find(e.subject + " " + e.flatBody)
        val merchant = field(t, "Comercio").ifEmpty { m?.groupValues?.get(1)?.trim() ?: "" }.replace("&#39;", "'").replace("&apos;", "'")
        val r = ParseResult(Kind.EXPENSE, "yape_pago_comercio", account = "Yape", accountDetail = "Yape",
            counterparty = merchant, counterpartyType = "business", externalRef = opNumber(t))
        whenOf(t, e, listOf("Fecha y hora", "Fecha"), r)
        if (norm(merchant) == "yape promos") r.flags += "posible_duplicado_promo"
        val promo = valueAfter(t, listOf("¡Yapeaste tu promo!"), 80)
        if (promo.isNotEmpty()) r.description = promo.split(Regex("\\s+[A-Z]{2}\\d{5,}"))[0]
        val amt = amountAfterLabel(t, listOf("Monto total")) ?: return missing(r, "el monto ('Monto total')")
        r.money(amt)
        return r
    }

    fun yapeService(e: Email): ParseResult? {
        if (!(senderIs(e, YAPE) && hasAny(e, "servicio fue yapeado", "yapeo de servicio"))) return null
        val t = e.body
        val r = ParseResult(Kind.EXPENSE, "yape_pago_servicio", account = "Yape", accountDetail = "Yape",
            counterparty = field(t, "Empresa"), counterpartyType = "business", description = field(t, "Servicio"),
            externalRef = opNumber(t), fixedHint = true)
        whenOf(t, e, listOf("Fecha y hora"), r)
        val amt = amountAfterLabel(t, listOf("Monto total")) ?: return missing(r, "el monto ('Monto total')")
        r.money(amt)
        return r
    }

    fun yapeRecharge(e: Email): ParseResult? {
        if (!(senderIs(e, YAPE) && has(e, "recarga") && hasAny(e, "confirmada", "numero recargado"))) return null
        val t = e.body
        var amt = amountAfterLabel(t, listOf("Monto total", "Monto recargado", "Monto"))
        if (amt == null) {
            // El correo de recarga empieza con el monto: "S/ 6 Número recargado ..."
            Regex("^\\s*(S/\\.?)\\s*(\\d+(?:\\.\\d{1,2})?)\\s+N[úu]mero recargado", RegexOption.IGNORE_CASE).find(e.flatBody)?.let {
                amt = Money(it.groupValues[2].toDouble(), "PEN")
            }
        }
        val op = field(t, "Operadora")
        val r = ParseResult(Kind.EXPENSE, "yape_recarga", account = "Yape", accountDetail = "Yape",
            counterparty = "Recarga $op".trim(), counterpartyType = "business", description = "Recarga de celular", externalRef = opNumber(t))
        whenOf(t, e, listOf("Fecha"), r)
        if (amt == null) return missing(r, "el monto de la recarga")
        r.money(amt)
        return r
    }

    // ------------------------------------------------------------- BBVA y genérico
    fun bbvaStatement(e: Email): ParseResult? {
        if (senderIs(e, "bbva.com.pe", "bbva.pe") && hasAny(e, "estado de cuenta")) return ParseResult(Kind.IGNORE, "bbva_estado_cuenta", "Estado de cuenta (no es un movimiento)")
        return null
    }

    private val GENERIC_EXPENSE = listOf(
        "realizaste un consumo", "realizaste una compra", "compra realizada", "consumo realizado", "realizaste un pago",
        "pago realizado", "realizaste una transferencia", "transferencia realizada", "cargo en tu cuenta", "se realizo un cargo",
        "consumo con tu tarjeta", "compra con tu tarjeta",
    )

    /** Bancos sin plantilla propia: solo si hay frase de gasto Y monto etiquetado, y siempre pide confirmación. */
    fun generic(e: Email): ParseResult? {
        val h = norm(e.subject) + " " + e.nbody.take(1500)
        if (GENERIC_EXPENSE.none { h.contains(it) }) return null
        val r = ParseResult(Kind.EXPENSE, "generico", account = bankName(e.fromAddr),
            counterparty = valueAfter(e.body, listOf("Comercio", "Establecimiento", "Empresa", "Beneficiario", "Enviado a", "Destino")),
            externalRef = opNumber(e.body))
        r.flags += "plantilla_generica"
        whenOf(e.body, e, listOf("Fecha y hora", "Fecha de operación", "Fecha"), r)
        val amt = amountAfterLabel(e.body, listOf("Monto total", "Total del consumo", "Importe total", "Monto", "Importe", "Total"))
            ?: return missing(r, "el monto")
        r.money(amt)
        return r
    }

    fun bankName(from: String): String {
        val f = from.lowercase()
        listOf("yape" to "Yape", "bcp" to "BCP", "bbva" to "BBVA", "interbank" to "Interbank", "scotiabank" to "Scotiabank",
            "ripley" to "Banco Ripley", "falabella" to "Banco Falabella", "banbif" to "BanBif", "pichincha" to "Pichincha")
            .forEach { (n, name) -> if (f.contains(n)) return name }
        return Regex("@([\\w.-]+)").find(f)?.groupValues?.get(1) ?: f
    }

    /** Orden: lo más específico primero. */
    private val ALL: List<(Email) -> ParseResult?> = listOf(
        ::yapeHistory, ::bcpIgnore, ::yapeIgnore, ::bbvaStatement, ::bcpCardPayment, ::bcpRefund, ::bcpYapeReceived,
        ::bcpSavings, ::bcpServicePayment, ::bcpTransfer, ::bcpCardPurchase, ::yapeP2P, ::yapeService, ::yapeRecharge,
        ::yapeMerchant, ::generic,
    )

    fun parse(e: Email): ParseResult {
        for (p in ALL) p(e)?.let { return it }
        return ParseResult(Kind.UNKNOWN, "ninguno", "Correo de un banco que no coincide con ningún tipo conocido", account = bankName(e.fromAddr))
    }
}
