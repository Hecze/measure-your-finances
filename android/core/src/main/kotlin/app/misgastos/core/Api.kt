package app.misgastos.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.YearMonth
import java.util.Base64

/** Dónde se guarda el estado (Android: archivo privado; desarrollo: archivo local). */
interface Storage {
    fun load(): String?
    fun save(json: String)
    /** Correo original (HTML tal cual llegó), para "Ver la fuente". Aparte del JSON de datos. */
    fun saveMail(id: String, html: String) {}
    fun loadMail(id: String): String? = null
}

/** Contraseñas y API keys: fuera del JSON de datos (Android: almacenamiento cifrado). */
interface Secrets {
    fun get(name: String): String?
    fun set(name: String, value: String?)
}

/**
 * Puente JSON entre la interfaz (WebView) y el motor. Mismo código en Android y en el
 * servidor de desarrollo. Cada método recibe y devuelve JSON.
 */
class Api(private val storage: Storage, private val secrets: Secrets, private val deviceLanguage: String = "es",
          private val now: () -> LocalDateTime = { LocalDateTime.now() }) {

    val ledger: Ledger = Ledger(AppData.fromJson(storage.load()), now)
    private val lock = Any()

    init {
        ledger.data.seedCategories(english = language() == "en")
        ledger.migrate()
    }

    fun language(): String = ledger.data.settings.language.takeIf { it == "es" || it == "en" } ?: if (deviceLanguage.startsWith("en")) "en" else "es"

    fun save() = synchronized(lock) { storage.save(ledger.data.toJson()) }

    fun call(method: String, argsJson: String?): String {
        val args = if (argsJson.isNullOrBlank()) JSONObject() else JSONObject(argsJson)
        return try {
            // Lo que usa la red va sin el candado de datos tomado (la app no se congela).
            val data = when (method) {
                "sync" -> syncEmail()
                "email.source" -> emailSource(args.getLong("txId"))
                "ai.run" -> aiRun()
                "ai.test" -> { Ai.test(ledger.data.settings, secrets.get("ai") ?: throw Exception("Falta la API key")); "ok" }
                else -> synchronized(lock) { dispatch(method, args) }
            }
            JSONObject().put("ok", true).put("data", data ?: JSONObject.NULL).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: e.javaClass.simpleName).toString()
        }
    }

    private fun mutated(): Any? { storage.save(ledger.data.toJson()); return null }

    private fun dispatch(m: String, a: JSONObject): Any? = when (m) {
        "boot" -> JSONObject().put("lang", language()).put("hasEmail", hasEmail())
        "home" -> home(a.optString("ym").ifBlank { null })
        "list" -> list(a.optString("ym").ifBlank { null }, a.optString("group", "all"))
        "queue" -> queue()
        "tx.update" -> {
            ledger.update(
                a.getLong("id"),
                description = if (a.has("description")) a.optString("description") else null,
                categoryId = if (a.has("categoryId") && !a.isNull("categoryId")) a.getLong("categoryId") else null,
                clearCategory = a.has("categoryId") && a.isNull("categoryId"),
                fixed = if (a.has("fixed")) a.getBoolean("fixed") else null,
                status = a.optString("status").ifBlank { null },
                excludeReason = a.optString("reason"),
                remember = a.optBoolean("remember"),
                rememberWord = a.optBoolean("rememberWord"),
                amount = if (a.has("amount")) a.getDouble("amount") else null,
                installments = if (a.has("installments")) a.getInt("installments") else null,
            )
            mutated()
            ledger.data.txs.firstOrNull { it.id == a.getLong("id") }?.let { txView(it) }
        }
        "tx.add" -> {
            val amount = a.getDouble("amount")
            require(amount > 0) { "amount" }
            val t = Tx(0, "manual", "out", "Efectivo", "", amount, "PEN", Text.fmt(now()), a.optString("counterparty"), "unknown",
                a.optString("description"), status = "confirmed")
            ledger.add(t)
            ledger.refreshAll()
            mutated()
        }
        "payee.rename" -> { val n = ledger.rename(a.getLong("id"), a.optString("name")); mutated(); n }
        "tx.delete" -> { ledger.data.txs.removeIf { it.id == a.getLong("id") && it.source == "manual" }; mutated() }
        "categories" -> JSONArray(ledger.data.categories.map { c ->
            JSONObject().put("id", c.id).put("name", c.name).put("fixed", c.fixed).put("n", ledger.data.txs.count { it.categoryId == c.id })
        })
        "category.save" -> {
            val name = a.getString("name").trim().take(40)
            require(name.isNotEmpty()) { "name" }
            val fixed = a.optBoolean("fixed", false)
            if (a.has("id") && !a.isNull("id")) ledger.data.categories.firstOrNull { it.id == a.getLong("id") }?.let { c ->
                c.name = name
                if (a.has("fixed")) c.fixed = fixed
            }
            else ledger.data.categories += Category((ledger.data.categories.maxOfOrNull { it.id } ?: 99) + 1, name, fixed)
            ledger.refreshAll()
            mutated()
        }
        "category.delete" -> {
            val id = a.getLong("id")
            ledger.data.categories.removeIf { it.id == id }
            ledger.data.txs.filter { it.categoryId == id }.forEach { it.categoryId = null; it.categorySource = "" }
            ledger.data.memory.entries.removeIf { it.value == id }
            ledger.refreshAll()
            mutated()
        }
        "glossary" -> JSONArray(ledger.data.glossary.map { it.toJson() })
        "glossary.save" -> {
            ledger.addGlossary(a.getString("word"), if (a.has("categoryId") && !a.isNull("categoryId")) a.getLong("categoryId") else null,
                a.optBoolean("notExpense"), a.optString("reason"))
            ledger.refreshAll()
            mutated()
        }
        "glossary.delete" -> {
            ledger.data.glossary.removeIf { Text.norm(it.word) == Text.norm(a.getString("word")) }
            ledger.refreshAll()
            mutated()
        }
        "settings" -> settings()
        "sources" -> sources()
        "source.save" -> {
            val key = a.getString("key")
            if (a.has("icon")) a.optString("icon").takeIf { it.isNotBlank() }?.let { ledger.data.sourceIcons[key] = it } ?: ledger.data.sourceIcons.remove(key)
            if (a.has("image")) a.optString("image").takeIf { it.startsWith("data:image/") }?.let {
                require(it.length <= 400_000) { "image_too_big" }
                ledger.data.sourceImages[key] = it
            } ?: ledger.data.sourceImages.remove(key)
            if (a.has("name")) a.optString("name").trim().take(30).takeIf { it.isNotBlank() }?.let { ledger.data.sourceNames[key] = it } ?: ledger.data.sourceNames.remove(key)
            mutated()
        }
        "settings.save" -> {
            val s = ledger.data.settings
            if (a.has("language")) s.language = a.getString("language")
            if (a.has("email")) s.email = a.getString("email").trim()
            if (a.has("usdToPen")) s.usdToPen = a.getDouble("usdToPen")
            if (a.has("aiProvider")) s.aiProvider = a.getString("aiProvider")
            if (a.has("aiModel")) s.aiModel = a.getString("aiModel").trim()
            if (a.has("aiBaseUrl")) s.aiBaseUrl = a.getString("aiBaseUrl").trim()
            if (a.has("daysBack")) s.daysBack = a.getInt("daysBack").coerceIn(7, 730)
            mutated()
        }
        "secret.set" -> { secrets.set(a.getString("name"), a.optString("value").ifBlank { null }); null }
        "rule.delete" -> {
            val i = a.getInt("index")
            if (a.getString("type") == "notCounted") ledger.data.notCounted.removeAt(i) else ledger.data.ignoredSubjects.removeAt(i)
            mutated()
        }
        "email.get" -> ledger.data.emails[a.getString("id")]?.let { e ->
            e.toJson().put("parsedAmount", e.parsedAmount ?: JSONObject.NULL)
        }
        "email.decide" -> emailDecide(a)
        "emails" -> JSONArray(ledger.data.emails.values.sortedByDescending { it.receivedAt }.take(300).map {
            JSONObject().put("id", it.id).put("subject", it.subject).put("from", it.from).put("receivedAt", it.receivedAt ?: "")
                .put("decision", it.decision).put("reason", it.reason)
        })
        "import.preview" -> importYape(a.getString("base64"), dryRun = true)
        "import.commit" -> importYape(a.getString("base64"), dryRun = false)
        "ai.providers" -> JSONArray(Ai.PROVIDERS.map { JSONObject().put("id", it.id).put("label", it.label).put("model", it.defaultModel).put("baseUrl", it.baseUrl) })
        "export" -> ledger.data.toJson()
        "backup.restore" -> {
            val d = AppData.fromJson(a.getString("json"))
            ledger.data.txs.clear(); ledger.data.txs += d.txs
            ledger.data.categories.clear(); ledger.data.categories += d.categories
            ledger.data.emails.clear(); ledger.data.emails += d.emails
            ledger.data.notCounted.clear(); ledger.data.notCounted += d.notCounted
            ledger.data.ignoredSubjects.clear(); ledger.data.ignoredSubjects += d.ignoredSubjects
            ledger.data.memory.clear(); ledger.data.memory += d.memory
            ledger.data.fixedMemory.clear(); ledger.data.fixedMemory += d.fixedMemory
            ledger.data.aliases.clear(); ledger.data.aliases += d.aliases
            ledger.data.glossary.clear(); ledger.data.glossary += d.glossary
            ledger.data.nextId = maxOf(d.nextId, (d.txs.maxOfOrNull { it.id } ?: 0) + 1)
            mutated()
        }
        else -> throw IllegalArgumentException("Método desconocido: $m")
    }

    fun hasEmail() = ledger.data.settings.email.isNotBlank() && !secrets.get("imap").isNullOrBlank()

    // ------------------------------------------------------------------ vistas
    /** Enlace al correo exacto en Gmail (búsqueda por Message-ID). Null si no vino de un correo leído por IMAP. */
    fun mailUrl(emailId: String?): String? {
        val id = emailId?.trim()?.removePrefix("<")?.removeSuffix(">") ?: return null
        if (!id.contains("@")) return null
        val enc = { v: String -> java.net.URLEncoder.encode(v, "UTF-8").replace("+", "%20") }
        val user = ledger.data.settings.email
        return "https://mail.google.com/mail/" + (if (user.isNotBlank()) "?authuser=" + enc(user) else "") + "#search/" + enc("rfc822msgid:$id")
    }

    /** De dónde salió la plata: yape | credit | debit | account | cash | other, el banco y los 4 últimos dígitos. */
    fun sourceOf(t: Tx): Triple<String, String, String> {
        val d = Text.stripAccents(t.accountDetail.lowercase())
        val last4 = Regex("\\*+\\s*(\\d{4})").find(t.accountDetail)?.groupValues?.get(1) ?: ""
        val kind = when {
            t.source == "manual" -> "cash"
            t.account.equals("Yape", true) -> "yape"
            d.contains("credito") -> "credit"
            d.contains("debito") -> "debit"
            d.startsWith("cuenta") || d.contains("banca") -> "account"
            else -> "other"
        }
        return Triple(kind, t.account, last4)
    }

    fun sourceKey(t: Tx): String = sourceOf(t).let { (k, b, l) -> "$k:$b:$l" }

    fun srcJson(t: Tx): JSONObject {
        val (kind, bank, last4) = sourceOf(t)
        val key = "$kind:$bank:$last4"
        return JSONObject().put("key", key).put("kind", kind).put("bank", bank).put("last4", last4)
            .put("icon", ledger.data.sourceIcons[key] ?: JSONObject.NULL).put("name", ledger.data.sourceNames[key] ?: JSONObject.NULL)
            .put("image", ledger.data.sourceImages[key] ?: JSONObject.NULL)
    }

    /** Medios de pago detectados (tarjetas, Yape, cuentas), con cuántos pagos tiene cada uno. */
    private fun sources(): JSONArray {
        val outs = ledger.data.txs.filter { it.direction == "out" && it.status != "excluded" }
        return JSONArray(outs.groupBy { sourceKey(it) }.entries.sortedByDescending { it.value.size }.map { (_, list) ->
            srcJson(list.first()).put("n", list.size)
        })
    }

    fun txView(t: Tx): JSONObject {
        val o = JSONObject()
            .put("id", t.id).put("amount", t.amount).put("currency", t.currency).put("who", ledger.displayName(t))
            .put("original", t.counterparty)
            .put("when", t.occurredAt).put("account", t.account).put("accountDetail", t.accountDetail).put("description", t.description)
            .put("categoryId", t.categoryId ?: JSONObject.NULL).put("categorySource", t.categorySource)
            .put("fixed", t.fixed).put("fixedSource", t.fixedSource).put("status", t.status).put("excludeReason", t.excludeReason)
            .put("type", t.counterpartyType).put("source", t.source).put("direction", t.direction)
            .put("installments", t.installments).put("installment", t.installmentLabel)
            .put("credit", t.accountDetail.contains("Crédito"))
            .put("amountPen", Math.round(ledger.pen(t) * 100) / 100.0) // vista unificada en soles
        mailUrl(t.emailId)?.let { o.put("mailUrl", it) }
        o.put("ref", t.externalRef)
        o.put("src", srcJson(t))
        ledger.hint(t)?.let { (code, params) -> o.put("hint", JSONObject().put("code", code).put("params", JSONObject(params))) }
        return o
    }

    private fun ym(s: String?): YearMonth = s?.let { runCatching { YearMonth.parse(it) }.getOrNull() } ?: YearMonth.from(now())

    private fun home(ymStr: String?): JSONObject {
        val ym = ym(ymStr)
        val rows = ledger.counted(ym)
        val prev = ledger.counted(ym.minusMonths(1))
        val total = rows.sumOf { ledger.pen(it) }
        val prevTotal = prev.sumOf { ledger.pen(it) }
        val q = queue()
        return JSONObject()
            .put("ym", ym.toString()).put("prevYm", ym.minusMonths(1).toString())
            .put("nextYm", if (ym < YearMonth.from(now())) ym.plusMonths(1).toString() else JSONObject.NULL)
            .put("total", total).put("prevTotal", prevTotal)
            .put("fixed", rows.filter { it.fixed }.sumOf { ledger.pen(it) }).put("fixedN", rows.count { it.fixed })
            .put("variable", rows.filter { !it.fixed }.sumOf { ledger.pen(it) }).put("variableN", rows.count { !it.fixed })
            .put("count", rows.size).put("pending", q.length())
            .put("noDesc", rows.count { it.counterpartyType == "person" && it.description.isBlank() })
            .put("hasEmail", hasEmail()).put("hasData", ledger.data.txs.isNotEmpty())
            .put("cats", byCategory(rows))
            .put("top", JSONArray(rows.sortedByDescending { ledger.pen(it) }.take(3).map { txView(it) }))
    }

    /** En qué se fue: total por categoría (todas, fijas y variables), de mayor a menor; sin clasificar al final. */
    private fun byCategory(rows: List<Tx>): JSONArray {
        val byCat = rows.groupBy { it.categoryId }
        val out = ledger.data.categories.mapNotNull { c ->
            byCat[c.id]?.let { JSONObject().put("id", c.id).put("name", c.name).put("total", it.sumOf { t -> ledger.pen(t) }).put("n", it.size) }
        }.sortedByDescending { it.getDouble("total") }.toMutableList()
        byCat[null]?.let { out += JSONObject().put("id", JSONObject.NULL).put("total", it.sumOf { t -> ledger.pen(t) }).put("n", it.size) }
        return JSONArray(out)
    }

    private fun list(ymStr: String?, group: String): JSONObject {
        val ym = ym(ymStr)
        val all = ledger.monthTxs(ym, includeExcluded = true) // incluye la cuota del mes de compras en cuotas
        val rows = when {
            group == "fixed" -> all.filter { it.fixed }
            group == "variable" -> all.filter { !it.fixed }
            group == "none" -> all.filter { !it.fixed && it.categoryId == null }
            group.startsWith("cat:") -> all.filter { it.categoryId == group.removePrefix("cat:").toLongOrNull() }
            else -> all
        }
        val counted = rows.filter { it.status != "excluded" }
        val sorted = if (group == "fixed") rows.sortedByDescending { ledger.pen(it) } else rows.sortedByDescending { it.occurredAt }
        val o = JSONObject().put("ym", ym.toString()).put("group", group)
            .put("total", counted.sumOf { ledger.pen(it) }).put("items", JSONArray(sorted.map { txView(it) }))
        if (group == "variable") {
            // En qué se fue lo variable: categorías con su total (sin clasificar al final).
            val byCat = counted.groupBy { it.categoryId }
            val cats = ledger.data.categories.mapNotNull { c ->
                byCat[c.id]?.let { JSONObject().put("id", c.id).put("name", c.name).put("total", it.sumOf { t -> ledger.pen(t) }).put("n", it.size) }
            }.sortedByDescending { it.getDouble("total") }
            o.put("breakdown", JSONArray(cats))
            byCat[null]?.let { o.put("unclassified", JSONObject().put("total", it.sumOf { t -> ledger.pen(t) }).put("n", it.size)) }
        }
        return o
    }

    private fun queue(): JSONArray {
        val out = JSONArray()
        val last = ledger.lastYape()
        if (last == null || java.time.Duration.between(Text.parse(last), now()).toDays() > 10) {
            out.put(JSONObject().put("kind", "yape").put("last", last?.take(10) ?: JSONObject.NULL).put("auto", hasEmail()))
        }
        ledger.unknownEmails().take(30).forEach {
            out.put(JSONObject().put("kind", "email").put("id", it.id).put("subject", it.subject).put("receivedAt", it.receivedAt ?: ""))
        }
        ledger.queue().forEach { out.put(txView(it).put("kind", "tx")) }
        return out
    }

    private fun settings(): JSONObject {
        val s = ledger.data.settings
        return s.toJson().put("lang", language())
            .put("hasPassword", !secrets.get("imap").isNullOrBlank()).put("hasAiKey", !secrets.get("ai").isNullOrBlank())
            .put("notCounted", JSONArray(ledger.data.notCounted.map { it.toJson() }))
            .put("ignoredSubjects", JSONArray(ledger.data.ignoredSubjects))
            .put("lastYape", ledger.lastYape() ?: JSONObject.NULL)
            .put("emailsRead", ledger.data.emails.size)
    }

    private fun emailDecide(a: JSONObject): Any? {
        val e = ledger.data.emails[a.getString("id")] ?: throw Exception("No existe")
        when (val action = a.getString("action")) {
            "ignore" -> {
                e.decision = "ignore"; e.reason = "user"; e.body = ""
                if (a.optBoolean("similar")) ledger.data.ignoredSubjects += e.subject.take(60)
            }
            "expense", "not_expense", "income" -> {
                val amount = a.getDouble("amount")
                val whenStr = a.optString("when").ifBlank { e.receivedAt ?: Text.fmt(now()) }
                val t = Tx(0, "email", if (action == "income") "in" else "out", Parsers.bankName(e.from), "", amount,
                    a.optString("currency", "PEN"), whenStr, a.optString("counterparty"), "unknown", a.optString("description"),
                    status = if (action == "not_expense") "excluded" else "confirmed", emailId = e.id)
                val (_, tx) = ledger.add(t)
                e.decision = action; e.reason = "user"; e.txId = tx.id; e.body = ""
                ledger.refreshAll()
            }
            else -> throw Exception("acción $action")
        }
        return mutated()
    }

    private fun importYape(b64: String, dryRun: Boolean): JSONObject {
        val rows = YapeExcel.parse(Base64.getDecoder().decode(b64))
        val r = ledger.importYape(rows, dryRun = dryRun)
        if (!dryRun) mutated()
        return JSONObject(r as Map<*, *>).put("rows", rows.size)
            .put("from", rows.minOfOrNull { it.occurredAt }?.let { Text.fmt(it) } ?: JSONObject.NULL)
            .put("to", rows.maxOfOrNull { it.occurredAt }?.let { Text.fmt(it) } ?: JSONObject.NULL)
    }

    /** IA: categoriza lo que el vocabulario no entendió. Corre solo si el usuario configuró una key. */
    fun aiRun(): JSONObject {
        val key = secrets.get("ai")
        val (cands, examples, cats, settings) = synchronized(lock) {
            if (!Ai.enabled(ledger.data.settings, key)) return JSONObject().put("n", 0)
            val ex = ledger.data.txs.filter { it.categorySource == "user" && it.description.isNotBlank() && it.categoryId != null }
                .map { t -> "${t.counterparty} | ${t.description} => ${ledger.data.categories.firstOrNull { it.id == t.categoryId }?.name}" }
            Quad(ledger.aiCandidates().map { it.copy(flags = it.flags.toMutableSet()) }, ex, ledger.data.categories.map { it.copy() }, ledger.data.settings)
        }
        if (cands.isEmpty()) return JSONObject().put("n", 0)
        val res = Ai.categorize(settings, key!!, cands, cats, examples) // red, sin candado
        synchronized(lock) {
            ledger.applyAi(res.mapValues { it.value.categoryId })
            res.filter { it.value.notExpense }.keys.forEach { id -> ledger.data.txs.firstOrNull { it.id == id }?.flags?.add("ia_no_gasto") }
            ledger.refreshAll()
            storage.save(ledger.data.toJson())
        }
        return JSONObject().put("n", res.count { it.value.categoryId != null })
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    /** Lee Gmail: descarga sin candado, procesa con candado. */
    private fun sourceHtml(m: IncomingEmail): String =
        m.html.ifBlank { "<pre style=\"white-space:pre-wrap;font:14px sans-serif\">" + m.text.replace("&", "&amp;").replace("<", "&lt;") + "</pre>" }

    /**
     * El correo original de un gasto, tal cual llegó. Si no está guardado (correos leídos con versiones
     * anteriores), se baja de Gmail en ese momento, solo ese correo.
     */
    fun emailSource(txId: Long): JSONObject {
        val (emailId, s) = synchronized(lock) { ledger.data.txs.firstOrNull { it.id == txId }?.emailId to ledger.data.settings }
        if (emailId == null) throw Exception("no_source")
        val o = JSONObject().put("gmailUrl", mailUrl(emailId) ?: JSONObject.NULL)
        storage.loadMail(emailId)?.let { return o.put("html", it) }
        val pass = secrets.get("imap")
        if (s.email.isBlank() || pass.isNullOrBlank()) return o.put("html", JSONObject.NULL)
        val m = Imap.fetchOne(s.imapHost, s.email, pass, emailId) ?: return o.put("html", JSONObject.NULL)
        val html = sourceHtml(m)
        runCatching { storage.saveMail(emailId, html) }
        return o.put("html", html)
    }

    fun syncEmail(onProgress: (Int, Int) -> Unit = { _, _ -> }): JSONObject {
        val pass = secrets.get("imap")
        val (s, known) = synchronized(lock) { ledger.data.settings to ledger.data.emails.keys.toSet() }
        if (s.email.isBlank() || pass.isNullOrBlank()) throw Exception("no_email")
        var newExp = 0; var unknown = 0; var imported = 0
        // Cada tanda se procesa y se guarda apenas llega (si se corta a la mitad, lo leído queda).
        val onBatch: (List<IncomingEmail>) -> Unit = { batch ->
            batch.forEach { m -> runCatching { storage.saveMail(m.messageId, sourceHtml(m)) } }
            synchronized(lock) {
                for (m in batch) {
                    val r = ledger.processEmail(m, refresh = false)
                    when (r.decision) {
                        "expense" -> if (r.txState == Ledger.AddState.NEW) newExp++
                        "unknown" -> unknown++
                        "import" -> imported += r.imported?.get("gasto_nuevo") ?: 0
                    }
                }
                ledger.refreshAll()
                storage.save(ledger.data.toJson())
            }
        }
        val mails = try {
            Imap.fetch(s.imapHost, s.email, pass, s.senders, s.daysBack, known, onProgress, onBatch)
        } catch (e: Exception) {
            // Mensaje claro: "auth|..." (contraseña), "connect|..." (red/servidor) o "read|..." (al leer los correos).
            val msg = if (e is Imap.ImapException) e.message ?: "error" else "read|" + (e.javaClass.simpleName + ": " + (e.message ?: "")).take(200)
            synchronized(lock) {
                ledger.refreshAll()
                s.lastSync = Text.fmt(now()); s.lastSyncOk = false; s.lastSyncResult = msg
                storage.save(ledger.data.toJson())
            }
            throw Exception(msg)
        }
        synchronized(lock) {
            ledger.refreshAll()
            s.lastSync = Text.fmt(now()); s.lastSyncOk = true
            s.lastSyncResult = JSONObject().put("read", mails.size).put("new", newExp + imported).put("unknown", unknown).toString()
            storage.save(ledger.data.toJson())
        }
        return JSONObject().put("read", mails.size).put("new", newExp + imported).put("unknown", unknown).put("failed", Imap.failed)
    }
}
