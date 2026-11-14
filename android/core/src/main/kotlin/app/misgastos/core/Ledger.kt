package app.misgastos.core

import app.misgastos.core.Text.counterpartyKey
import app.misgastos.core.Text.norm
import java.time.Duration
import java.time.LocalDateTime
import java.time.YearMonth

class IncomingEmail(
    val messageId: String,
    val from: String,
    val subject: String,
    val html: String = "",
    val text: String = "",
    val receivedAt: LocalDateTime? = null,
    val attachments: List<Pair<String, ByteArray>> = emptyList(),
)

/** Motor: ingesta sin duplicados, sugerencias de "no es gasto" y clasificación fijo/variable. */
class Ledger(val data: AppData, private val now: () -> LocalDateTime = { LocalDateTime.now() }) {

    private val GENERIC = setOf("yape promos", "pagoefectivo", "")

    // ================================================================ dedupe
    /**
     * La misma operación puede llegar por dos vías:
     *  - Excel de Yape vs correo "Pago exitoso" de Yape (PedidosYa...).
     *  - Excel de Yape (pago a PLIN/BIM) vs correo BCP de consumo con débito.
     *  - Correo "Tu compra de Roky's" vs "Pago en Yape Promos".
     */
    fun findDuplicate(t: Tx): Tx? {
        t.dedupeKey?.let { k -> data.txs.firstOrNull { it.dedupeKey == k }?.let { return it } }
        val dt = Text.parse(t.occurredAt)
        val window = if ("fecha_del_correo" in t.flags) 30L else 5L
        var best: Tx? = null
        for (r in data.txs) {
            if (r.direction != t.direction || r.currency != t.currency || kotlin.math.abs(r.amount - t.amount) >= 0.005) continue
            if (kotlin.math.abs(Duration.between(Text.parse(r.occurredAt), dt).toMinutes()) > window) continue
            if (t.externalRef.isNotEmpty() && r.externalRef.isNotEmpty() && r.account == t.account) {
                if (r.externalRef == t.externalRef) return r
                continue // mismo banco, distinto nº de operación: son dos operaciones
            }
            if (r.source == "yape" && t.source == "yape") continue // el Excel ya se deduplica por clave
            // Mismo monto y hora no basta: dos personas distintas pueden recibir S/ 2 en el mismo minuto.
            if (samePayee(r.counterparty, t.counterparty)) if (best == null) best = r
        }
        return best
    }

    /** ¿Es la misma contraparte escrita de dos formas? ("PLIN-DIEGO ARMANDO CAME" y "PLIN - DIEGO ARMANDO CAMEJO ORTIZ"). */
    private fun samePayee(x: String, y: String): Boolean {
        val a = norm(x)
        val b = norm(y)
        if (a in GENERIC || b in GENERIC) return true
        if (Text.similarity(a, b) >= 0.6) return true
        val ta = counterpartyKey(x).split(" ").filter { it.length >= 4 }.toSet()
        val tb = counterpartyKey(y).split(" ").filter { it.length >= 4 }.toSet()
        return (ta intersect tb).isNotEmpty()
    }

    private fun mergeInto(existing: Tx, new: Tx): Boolean {
        var changed = false
        if (new.description.isNotBlank() && existing.description.isBlank()) { existing.description = new.description; changed = true }
        if (new.counterparty.isNotBlank() && norm(existing.counterparty) in GENERIC) { existing.counterparty = new.counterparty; changed = true }
        // El Excel trae el nombre completo; el correo del BCP lo recorta.
        if (new.source == "yape" && existing.source == "email" && new.counterparty.length > existing.counterparty.length &&
            Text.similarity(new.counterparty, existing.counterparty) >= 0.6) { existing.counterparty = new.counterparty; changed = true }
        return changed
    }

    enum class AddState { NEW, DUPLICATE, MERGED }

    fun add(t: Tx, dryRun: Boolean = false): Pair<AddState, Tx> {
        t.counterpartyType = guessType(t.counterparty, t.counterpartyType)
        findDuplicate(t)?.let { dup ->
            if (dryRun) return (if (t.description.isNotBlank() && dup.description.isBlank()) AddState.MERGED else AddState.DUPLICATE) to dup
            return (if (mergeInto(dup, t)) AddState.MERGED else AddState.DUPLICATE) to dup
        }
        if (dryRun) return AddState.NEW to t
        if (t.direction == "out" && t.counterpartyType == "self" && t.status != "excluded") {
            t.status = "excluded"
            t.excludeReason = t.excludeReason.ifEmpty { "Transferencia a ti mismo" }
        }
        if (t.direction == "out" && t.status != "excluded") {
            data.notCounted.firstOrNull { matches(it.pattern, t.counterparty) }?.let {
                t.status = "excluded"
                t.excludeReason = it.reason
            }
        }
        t.id = data.nextId++
        data.txs += t
        return AddState.NEW to t
    }

    private fun matches(pattern: String, text: String) = pattern.isNotBlank() && norm(text).contains(norm(pattern))

    // ================================================================ tipo de contraparte
    private val BUSINESS = Regex(
        "\\b(s\\.?a\\.?c?|s\\.?r\\.?l|e\\.?i\\.?r\\.?l|sac|srl|eirl|s\\.a|inc|corp|peru|perú|store|tienda|market|restaurant|" +
            "polleria|farmacia|botica|minimarket|servicios|ingenieria|inversiones|comercial|universidad|instituto|colegio|" +
            "clinica|grifo|cinemark|cineplanet|pedidos ya|rappi|tambo|oxxo|tottus|plaza vea|metro|wong|listo|mass|vega|" +
            "izi|payu|pyu|paypal|google|apple|netflix|spotify|facebk|meta|vercel|hostinger|scribd|oculus|nintendo|bitel|claro|movistar|entel)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val MASKED_PERSON = Regex("^[A-Za-zÁÉÍÓÚáéíóúÑñ]+(?: [A-Za-zÁÉÍÓÚáéíóúÑñ]+)? [A-Za-zÁÉÍÓÚáéíóúÑñ]{1,6}\\*$")
    private val WALLET = Regex("^(PLIN|BIM|BBVA|IBK|INTERBANK|SCOTIABANK|SCOTIA|TUNKI|AGORA)\\s*-\\s*(.+)$", RegexOption.IGNORE_CASE)

    fun isSelf(name: String) = name.isNotBlank() && data.settings.ownerNames.any { Text.namesMatch(name, it) }

    fun guessType(name: String, hint: String = "unknown"): String {
        if (hint in setOf("person", "business", "self")) return hint
        val n = name.trim()
        if (n.isEmpty()) return "unknown"
        if (isSelf(n)) return "self"
        if (n.lowercase().startsWith("pagaste el servicio")) return "business"
        WALLET.find(n)?.let { return if (BUSINESS.containsMatchIn(it.groupValues[2])) "business" else "person" }
        if (MASKED_PERSON.matches(n)) return "person"
        if (BUSINESS.containsMatchIn(n) || n.contains('*')) return "business"
        if (n == n.uppercase() && n.split(" ").size >= 2) return "business"
        return "unknown"
    }

    // ================================================================ fijo / variable
    /** Palabras que delatan un gasto fijo. Ojo: "agua", "luz" o "internet" NO ("gillette más agua", "compra por internet"). */
    private val FIXED_WORDS = setOf(
        "alquiler", "renta", "pension", "mensualidad", "matricula", "colegiatura", "postpago", "seguro",
        "suscripcion", "membresia", "gimnasio", "gym", "carreras", "hab", "habitacion",
    )

    /**
     * Recalcula lo automático: fijo/variable y "en qué fue". Lo que el usuario eligió no se toca.
     * Orden para la categoría: lo que el usuario enseñó para esa contraparte o esas palabras ›
     * vocabulario › nombre del comercio › "lo usual con esta persona" (solo sugerencia).
     */
    fun reclassify() {
        val outs = data.txs.filter { it.direction == "out" && it.status != "excluded" }
        val byKey = outs.groupBy { counterpartyKey(it.counterparty) }
        val valid = data.categories.map { it.id }.toSet()
        // 1) fijo / variable
        for (t in data.txs) {
            if (t.direction != "out" || t.fixedSource == "user") continue
            val key = counterpartyKey(t.counterparty)
            t.fixed = data.fixedMemory[key] ?: isFixed(t, byKey[key].orEmpty())
        }
        // 2) categoría por texto
        for (t in data.txs) {
            if (t.direction != "out" || t.categorySource == "user" || t.categorySource == "ia") continue
            val (id, src) = categoryFromText(t)
            t.categoryId = id?.takeIf { it in valid }
            t.categorySource = if (t.categoryId != null) src else ""
        }
        // 3) personas sin descripción: lo usual con esa persona (>=2 casos y >=70%)
        val known = data.txs.filter { it.direction == "out" && it.counterpartyType == "person" && it.categoryId != null &&
            it.categorySource in setOf("user", "auto", "ia") }.groupBy { counterpartyKey(it.counterparty) }
        for (t in data.txs) {
            if (t.direction != "out" || t.counterpartyType != "person" || t.categoryId != null || t.categorySource == "user") continue
            val hist = known[counterpartyKey(t.counterparty)] ?: continue
            val top = hist.groupingBy { it.categoryId!! }.eachCount().maxByOrNull { it.value } ?: continue
            if (top.value >= 2 && top.value >= 0.7 * hist.size && top.key in valid) {
                t.categoryId = top.key
                t.categorySource = "persona"
            }
        }
    }

    /** Entrada de "Mis palabras" que aparece como palabra (o frase) completa en el texto. */
    fun glossaryFor(text: String): GlossaryEntry? {
        val hay = " " + norm(text) + " "
        return data.glossary.filter { norm(it.word).isNotEmpty() && hay.contains(" " + norm(it.word) + " ") }
            .maxByOrNull { norm(it.word).length } // la frase más específica gana
    }

    /** "sencillo", "monedas"...: el usuario dijo que nunca son gasto. Solo afecta pendientes (no pisa lo que el usuario decidió). */
    private fun applyGlossaryExclusion(t: Tx) {
        if (t.direction != "out" || t.status != "pending" || t.description.isBlank()) return
        val g = glossaryFor(t.description) ?: return
        if (g.notExpense) {
            t.status = "excluded"
            t.excludeReason = g.reason.ifEmpty { "glossary" }
        }
    }

    fun categoryFromText(t: Tx): Pair<Long?, String> {
        val key = counterpartyKey(t.counterparty)
        if (t.counterpartyType != "person") data.memory[key]?.let { return it to "auto" }
        if (t.description.isNotBlank()) {
            // Mis palabras (explícitas) mandan sobre el vocabulario.
            glossaryFor(t.description)?.categoryId?.let { return it to "auto" }
            Vocabulary.guess(t.description)?.let { data.categoryIdForDefault(it) }?.let { return it to "auto" }
        }
        if (t.counterpartyType == "person") data.memory[key]?.let { return it to "auto" }
        if (t.counterpartyType != "person") {
            val name = t.counterparty.replace(Regex("\\([^)]*\\)"), " ")
            Vocabulary.guess(name)?.let { data.categoryIdForDefault(it) }?.let { return it to "auto" }
        }
        return null to ""
    }

    private fun isFixed(t: Tx, same: List<Tx>): Boolean {
        if (t.fixedHint) return true
        val words = norm(t.description + " " + t.counterparty).split(" ").toSet()
        if (words.any { it in FIXED_WORDS }) return true
        if (counterpartyKey(t.counterparty).isEmpty() || t.amount < 5) return false
        // Recurrente: misma contraparte en 3+ meses, ~1 vez por mes, con monto parecido (±12%).
        // Con 2 meses no basta: ir al cine o pedir delivery dos veces no lo vuelve fijo.
        val similar = same.filter { kotlin.math.abs(it.amount - t.amount) <= 0.12 * t.amount + 0.01 }
        val months = similar.groupBy { it.occurredAt.substring(0, 7) }
        return months.size >= 3 && months.values.all { it.size <= 2 }
    }

    // ================================================================ sugerencias
    private val NOT_EXPENSE_WORDS = listOf(
        "cambio", "vuelto", "efectivo", "devolucion", "presto", "prestamo", "te devuelvo", "encargo", "para que",
        "de parte de", "pasame", "deposito", "me diste", "me dio", "le debo", "adelanto",
    )

    fun findPassthrough(t: Tx): Tx? {
        if (t.direction != "out") return null
        val dt = Text.parse(t.occurredAt)
        return data.txs.filter {
            it.direction == "in" && it.counterpartyType != "self" && it.amount in (t.amount * 0.97)..(t.amount * 1.03)
        }.filter {
            val d = Text.parse(it.occurredAt)
            !d.isAfter(dt) && Duration.between(d, dt).toHours() < 6
        }.maxByOrNull { it.occurredAt }
    }

    private fun stripWallet(n: String) = n.replace(Regex("^\\s*(PLIN|BIM|BBVA|IBK|INTERBANK|SCOTIABANK|SCOTIA|BCP|TUNKI|AGORA)\\s*-\\s*", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s*\\([^)]*\\)\\s*$"), "").trim()

    data class Reciprocal(val counterparty: String, val n: Int, val total: Double)

    /** ¿Esta persona también te envía dinero? (te yapean y tú les transfieres a otro banco). */
    fun findReciprocal(t: Tx): Reciprocal? {
        if (t.direction != "out" || t.counterpartyType == "business") return null
        if (t.description.isNotBlank() && "revisar_transferencia" !in t.flags) return null
        val name = stripWallet(t.counterparty)
        if (norm(name).length < 4) return null
        val dt = Text.parse(t.occurredAt)
        val ins = data.txs.filter { it.direction == "in" && kotlin.math.abs(Duration.between(Text.parse(it.occurredAt), dt).toDays()) <= 45 }
        return ins.groupBy { it.counterparty }.entries.firstOrNull { (cp, _) -> Text.namesMatch(name, stripWallet(cp)) && !isSelf(cp) }
            ?.let { (cp, list) -> Reciprocal(cp, list.size, list.sumOf { it.amount }) }
    }

    fun refreshFlags(t: Tx) {
        if (t.direction != "out") return
        t.flags.removeAll(setOf("pase_de_dinero", "persona_tambien_te_envia", "posible_cambio_o_encargo", "sin_descripcion"))
        val desc = " " + norm(t.description) + " "
        if (t.description.isNotBlank() && NOT_EXPENSE_WORDS.any { desc.contains(" ${norm(it)} ") }) t.flags += "posible_cambio_o_encargo"
        if (t.counterpartyType == "person" && t.description.isBlank()) t.flags += "sin_descripcion"
        if (findPassthrough(t) != null) t.flags += "pase_de_dinero"
        if (findReciprocal(t) != null) t.flags += "persona_tambien_te_envia"
    }

    fun refreshAll() {
        data.txs.forEach { applyGlossaryExclusion(it) }
        reclassify()
        data.txs.forEach { refreshFlags(it) }
    }

    // ================================================================ correo
    data class EmailResult(val decision: String, val reason: String, val txState: AddState? = null, val imported: Map<String, Int>? = null)

    fun processEmail(m: IncomingEmail, refresh: Boolean = true): EmailResult {
        if (data.emails.containsKey(m.messageId)) return EmailResult("already", "")
        val e = Email(m.from, m.subject, m.html, m.text, m.receivedAt)
        val r: ParseResult = if (data.ignoredSubjects.any { norm(m.subject).contains(norm(it)) })
            ParseResult(Kind.IGNORE, "regla", "Marcaste correos como este como 'no es un movimiento'")
        else Parsers.parse(e)
        val rec = EmailRec(m.messageId, m.from, m.subject, m.receivedAt?.let { Text.fmt(it) }, r.kind.name.lowercase(), r.reason,
            parsedAmount = r.amount, parsedCurrency = r.currency, parsedCounterparty = r.counterparty)
        data.emails[m.messageId] = rec
        when (r.kind) {
            Kind.UNKNOWN -> rec.body = e.body.take(8000)
            Kind.YAPE_HISTORY -> {
                val total = mutableMapOf<String, Int>()
                var any = false
                for ((name, bytes) in m.attachments) {
                    if (!name.lowercase().endsWith(".xlsx")) continue
                    try {
                        importYape(YapeExcel.parse(bytes), refresh = false).forEach { (k, v) -> total[k] = (total[k] ?: 0) + v }
                        any = true
                    } catch (ex: YapeExcelException) {
                        rec.reason = ex.message ?: "Excel ilegible"
                    }
                }
                rec.decision = "import"
                if (any) rec.reason = "Historial de Yape importado: ${total["gasto_nuevo"] ?: 0} gastos nuevos"
                if (refresh) refreshAll()
                return EmailResult("import", rec.reason, imported = total)
            }
            Kind.EXPENSE, Kind.INCOME, Kind.NOT_EXPENSE -> if (r.amount != null && r.occurredAt != null) {
                val t = Tx(0, "email", if (r.kind == Kind.INCOME) "in" else "out", r.account, r.accountDetail, r.amount!!, r.currency,
                    Text.fmt(r.occurredAt!!), r.counterparty, r.counterpartyType, r.description,
                    status = if (r.kind == Kind.NOT_EXPENSE) "excluded" else "pending",
                    excludeReason = if (r.kind == Kind.NOT_EXPENSE) r.reason else "",
                    flags = r.flags.toMutableSet(), externalRef = r.externalRef, emailId = m.messageId, fixedHint = r.fixedHint)
                val (state, tx) = add(t)
                rec.txId = tx.id
                if (state != AddState.NEW) rec.decision = "duplicate"
                if (refresh) refreshAll()
                return EmailResult(rec.decision, r.reason, state)
            }
            else -> {}
        }
        return EmailResult(rec.decision, r.reason)
    }

    // ================================================================ Yape
    /** En el Excel, el Origen de PAGASTE eres tú: así se detectan transferencias a ti mismo sin configurar nada. */
    private fun learnOwner(rows: List<YapeRow>) {
        rows.filter { it.direction == "out" && it.origin.isNotBlank() }.groupingBy { it.origin }.eachCount()
            .entries.sortedByDescending { it.value }.take(2).forEach { (name, _) ->
                if (data.settings.ownerNames.none { norm(it) == norm(name) }) data.settings.ownerNames += name
            }
    }

    fun yapeRowToTx(r: YapeRow): Tx {
        var cp = r.counterparty
        var detail = "Yape"
        Regex("^(PLIN|BIM)\\s*-\\s*", RegexOption.IGNORE_CASE).find(cp)?.let { detail = "Yape a ${it.groupValues[1].uppercase()}" }
        var fixedHint = false
        if (cp.lowercase().startsWith("pagaste el servicio")) {
            // 'Pagaste el servicio de JUAN' + mensaje 'Bitel - Postpago Bitel Soles'
            cp = r.message.split(" - ").firstOrNull()?.trim().orEmpty().ifEmpty { cp }
            // Recargas (celular, tarjeta del Metropolitano) son variables aunque Yape las llame "servicio".
            fixedHint = !Text.norm(r.message).contains("recarga")
        }
        return Tx(0, "yape", r.direction, "Yape", detail, r.amount, "PEN", Text.fmt(r.occurredAt), cp, "unknown", r.message,
            dedupeKey = r.dedupeKey(), fixedHint = fixedHint)
    }

    fun importYape(rows: List<YapeRow>, dryRun: Boolean = false, refresh: Boolean = true): Map<String, Int> {
        if (!dryRun) learnOwner(rows)
        val out = mutableMapOf<String, Int>()
        // Primero los ingresos, para detectar pases de dinero.
        for (r in rows.sortedWith(compareBy({ it.direction != "in" }, { it.occurredAt }))) {
            val (state, _) = add(yapeRowToTx(r), dryRun)
            val k = (if (r.direction == "in") "ingreso_" else "gasto_") + when (state) {
                AddState.NEW -> "nuevo"; AddState.DUPLICATE -> "repetido"; AddState.MERGED -> "completado"
            }
            out[k] = (out[k] ?: 0) + 1
        }
        if (!dryRun && refresh) refreshAll()
        return out
    }

    // ================================================================ consultas
    fun pen(t: Tx) = if (t.currency == "USD") t.amount * data.settings.usdToPen else t.amount

    fun counted(ym: YearMonth): List<Tx> {
        val p = ym.toString()
        return data.txs.filter { it.direction == "out" && it.status != "excluded" && it.occurredAt.startsWith(p) }
    }

    fun lastYape(): String? = data.txs.filter { it.source == "yape" }.maxOfOrNull { it.occurredAt }

    private val REVIEW_FLAGS = setOf("ia_no_gasto", "pase_de_dinero", "persona_tambien_te_envia", "posible_cambio_o_encargo", "revisar_transferencia",
        "intermediario_pago", "plantilla_generica", "posible_duplicado_promo")

    /** Pendientes: lo que necesita al usuario. Lo dudoso primero; luego pagos a personas sin descripción (45 días). */
    fun queue(): List<Tx> {
        // Solo se piden descripciones desde el mes actual: lo anterior ya pasó y no se pregunta.
        val since = Text.fmt(now().withDayOfMonth(1).toLocalDate().atStartOfDay())
        val pend = data.txs.filter { it.direction == "out" && it.status == "pending" }.sortedByDescending { it.occurredAt }
        val flagged = pend.filter { t -> t.flags.any { it in REVIEW_FLAGS } }
        val noDesc = pend.filter { it !in flagged && it.counterpartyType == "person" && it.description.isBlank() && it.occurredAt >= since }
        return flagged + noDesc
    }

    fun unknownEmails(): List<EmailRec> = data.emails.values.filter { it.decision == "unknown" }.sortedByDescending { it.receivedAt }

    /** Una pista para la UI, como código + parámetros (la UI la traduce). */
    fun hint(t: Tx): Pair<String, Map<String, String>>? {
        fun s(v: Double) = "%,.2f".format(java.util.Locale.US, v)
        if ("pase_de_dinero" in t.flags) findPassthrough(t)?.let {
            return "passthrough" to mapOf("who" to it.counterparty, "amount" to s(it.amount), "time" to it.occurredAt.substring(11, 16))
        }
        if ("persona_tambien_te_envia" in t.flags) findReciprocal(t)?.let {
            return "reciprocal" to mapOf("who" to it.counterparty, "amount" to s(it.total))
        }
        if ("posible_cambio_o_encargo" in t.flags || "ia_no_gasto" in t.flags) return "cash" to mapOf("text" to t.description)
        if ("revisar_transferencia" in t.flags) return "transfer" to emptyMap()
        if ("intermediario_pago" in t.flags) return "pagoefectivo" to emptyMap()
        if ("plantilla_generica" in t.flags) return "generic" to emptyMap()
        if ("posible_duplicado_promo" in t.flags) return "duplicate" to emptyMap()
        return null
    }

    /** Cambios del usuario sobre un gasto. Aprende cuando corresponde. */
    fun update(id: Long, description: String? = null, categoryId: Long? = null, clearCategory: Boolean = false,
               fixed: Boolean? = null, status: String? = null, excludeReason: String? = null, remember: Boolean = false,
               rememberWord: Boolean = false) {
        val t = data.txs.firstOrNull { it.id == id } ?: return
        val key = counterpartyKey(t.counterparty)
        description?.let {
            if (it.trim() != t.description) {
                t.description = it.trim().take(300)
                if (t.categorySource == "ia" || t.categorySource == "persona") t.categorySource = ""
            }
        }
        if (clearCategory) { t.categoryId = null; t.categorySource = "user" }
        if (categoryId != null && data.categories.any { it.id == categoryId }) {
            t.categoryId = categoryId
            t.categorySource = "user"
            // Para comercios se recuerda la contraparte (siempre es el mismo tipo de gasto).
            if ((t.counterpartyType != "person" || remember) && key.isNotEmpty()) data.memory[key] = categoryId
            if (rememberWord) addGlossary(t.description, categoryId, false, "")
        }
        fixed?.let {
            t.fixed = it
            t.fixedSource = "user"
            if (key.isNotEmpty()) data.fixedMemory[key] = it
        }
        status?.let { st ->
            if (st in setOf("pending", "confirmed", "excluded")) {
                t.status = st
                t.excludeReason = if (st == "excluded") excludeReason.orEmpty() else ""
                if (st == "excluded" && rememberWord) addGlossary(t.description, null, true, excludeReason.orEmpty())
                if (st == "excluded" && remember && t.counterparty.isNotBlank()) {
                    data.notCounted += NotCountedRule(stripWallet(t.counterparty).ifEmpty { t.counterparty }, excludeReason ?: "")
                }
            }
        }
        refreshAll()
    }

    /** Agrega (o reemplaza) una entrada de "Mis palabras" con el texto tal cual lo escribió el usuario. */
    fun addGlossary(word: String, categoryId: Long?, notExpense: Boolean, reason: String) {
        val w = word.trim()
        if (w.isEmpty()) return
        data.glossary.removeIf { norm(it.word) == norm(w) }
        data.glossary += GlossaryEntry(w, categoryId, notExpense, reason)
    }

    /** Resultados de la IA: solo se aplican a lo que el usuario no decidió. */
    fun applyAi(results: Map<Long, Long?>) {
        for ((id, cat) in results) {
            val t = data.txs.firstOrNull { it.id == id } ?: continue
            if (t.categorySource == "user" || cat == null || data.categories.none { it.id == cat }) continue
            t.categoryId = cat
            t.categorySource = "ia"
        }
    }

    /** Gastos que la IA podría entender: tienen texto pero el vocabulario no los reconoce. */
    fun aiCandidates(limit: Int = 60): List<Tx> = data.txs.filter {
        it.direction == "out" && it.status != "excluded" && it.categorySource == "" &&
            (it.description.isNotBlank() || it.counterpartyType == "business")
    }.sortedByDescending { it.occurredAt }.take(limit)
}
