package app.misgastos.core

import org.json.JSONArray
import org.json.JSONObject

/** Un movimiento. direction "out" = gasto, "in" = ingreso (no se cuenta, sirve para detectar pases de dinero). */
data class Tx(
    var id: Long,
    var source: String, // email | yape | manual
    var direction: String,
    var account: String,
    var accountDetail: String,
    var amount: Double,
    var currency: String,
    var occurredAt: String, // "yyyy-MM-dd HH:mm:ss" hora local
    var counterparty: String,
    var counterpartyType: String, // person | business | self | unknown
    var description: String,
    /** En qué fue (categoría del usuario). null = sin clasificar. */
    var categoryId: Long? = null,
    var categorySource: String = "", // "" | auto | persona | ia | user
    /** Fijo (se repite cada mes) o variable. */
    var fixed: Boolean = false,
    var fixedSource: String = "auto", // auto | user
    var status: String = "pending", // pending | confirmed | excluded
    var excludeReason: String = "",
    var flags: MutableSet<String> = mutableSetOf(),
    var externalRef: String = "",
    var dedupeKey: String? = null,
    var emailId: String? = null,
    var fixedHint: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("source", source).put("direction", direction).put("account", account)
        .put("accountDetail", accountDetail).put("amount", amount).put("currency", currency).put("occurredAt", occurredAt)
        .put("counterparty", counterparty).put("counterpartyType", counterpartyType).put("description", description)
        .put("categoryId", categoryId ?: JSONObject.NULL).put("categorySource", categorySource).put("status", status)
        .put("fixed", fixed).put("fixedSource", fixedSource)
        .put("excludeReason", excludeReason).put("flags", JSONArray(flags.sorted())).put("externalRef", externalRef)
        .put("dedupeKey", dedupeKey ?: JSONObject.NULL).put("emailId", emailId ?: JSONObject.NULL).put("fixedHint", fixedHint)

    companion object {
        fun fromJson(o: JSONObject) = Tx(
            id = o.getLong("id"), source = o.optString("source"), direction = o.optString("direction", "out"),
            account = o.optString("account"), accountDetail = o.optString("accountDetail"), amount = o.getDouble("amount"),
            currency = o.optString("currency", "PEN"), occurredAt = o.getString("occurredAt"),
            counterparty = o.optString("counterparty"), counterpartyType = o.optString("counterpartyType", "unknown"),
            description = o.optString("description"),
            categoryId = if (o.isNull("categoryId")) null else o.optLong("categoryId"),
            categorySource = o.optString("categorySource", ""), status = o.optString("status", "pending"),
            fixed = o.optBoolean("fixed"), fixedSource = o.optString("fixedSource", "auto"),
            excludeReason = o.optString("excludeReason"),
            flags = o.optJSONArray("flags")?.let { a -> (0 until a.length()).map { a.getString(it) }.toMutableSet() } ?: mutableSetOf(),
            externalRef = o.optString("externalRef"),
            dedupeKey = if (o.isNull("dedupeKey")) null else o.optString("dedupeKey"),
            emailId = if (o.isNull("emailId")) null else o.optString("emailId"),
            fixedHint = o.optBoolean("fixedHint"),
        )
    }
}

data class Category(var id: Long, var name: String) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name)
}

/** Registro de cada correo leído: qué se decidió y por qué. El cuerpo solo se guarda si hay que revisarlo. */
data class EmailRec(
    val id: String,
    val from: String,
    val subject: String,
    val receivedAt: String?,
    var decision: String, // expense | not_expense | income | ignore | import | duplicate | unknown
    var reason: String,
    var txId: Long? = null,
    var body: String = "",
    var parsedAmount: Double? = null,
    var parsedCurrency: String = "PEN",
    var parsedCounterparty: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("from", from).put("subject", subject)
        .put("receivedAt", receivedAt ?: JSONObject.NULL).put("decision", decision).put("reason", reason)
        .put("txId", txId ?: JSONObject.NULL).put("body", body).put("parsedAmount", parsedAmount ?: JSONObject.NULL)
        .put("parsedCurrency", parsedCurrency).put("parsedCounterparty", parsedCounterparty)

    companion object {
        fun fromJson(o: JSONObject) = EmailRec(
            o.getString("id"), o.optString("from"), o.optString("subject"),
            if (o.isNull("receivedAt")) null else o.optString("receivedAt"), o.optString("decision"), o.optString("reason"),
            if (o.isNull("txId")) null else o.optLong("txId"), o.optString("body"),
            if (o.isNull("parsedAmount")) null else o.optDouble("parsedAmount"), o.optString("parsedCurrency", "PEN"),
            o.optString("parsedCounterparty"),
        )
    }
}

/**
 * "Mis palabras": reglas explícitas del usuario. Si la descripción contiene exactamente
 * esta palabra o frase, se aplica la categoría o se marca como "no es gasto".
 * Nada se deduce por su cuenta: solo lo que el usuario escribió aquí.
 */
data class GlossaryEntry(val word: String, val categoryId: Long?, val notExpense: Boolean, val reason: String = "") {
    fun toJson(): JSONObject = JSONObject().put("word", word).put("categoryId", categoryId ?: JSONObject.NULL)
        .put("notExpense", notExpense).put("reason", reason)

    companion object {
        fun fromJson(o: JSONObject) = GlossaryEntry(o.getString("word"), if (o.isNull("categoryId")) null else o.optLong("categoryId"),
            o.optBoolean("notExpense"), o.optString("reason"))
    }
}

data class NotCountedRule(val pattern: String, val reason: String) {
    fun toJson(): JSONObject = JSONObject().put("pattern", pattern).put("reason", reason)
}

class Settings {
    var email: String = ""
    var imapHost: String = "imap.gmail.com"
    var daysBack: Int = 120
    var ownerNames: MutableList<String> = mutableListOf()
    var usdToPen: Double = 3.75
    var lastSync: String? = null
    var lastSyncResult: String = ""
    var lastSyncOk: Boolean = true
    var senders: MutableList<String> = DEFAULT_SENDERS.toMutableList()
    var language: String = "auto" // auto | es | en
    var aiProvider: String = "none" // none | anthropic | openai | gemini | groq | openrouter | deepseek | custom
    var aiModel: String = ""
    var aiBaseUrl: String = ""

    fun toJson(): JSONObject = JSONObject().put("email", email).put("imapHost", imapHost).put("daysBack", daysBack)
        .put("ownerNames", JSONArray(ownerNames)).put("usdToPen", usdToPen).put("lastSync", lastSync ?: JSONObject.NULL)
        .put("lastSyncResult", lastSyncResult).put("lastSyncOk", lastSyncOk).put("senders", JSONArray(senders))
        .put("language", language).put("aiProvider", aiProvider).put("aiModel", aiModel).put("aiBaseUrl", aiBaseUrl)

    companion object {
        /** Remitentes que se leen. Ningún otro correo se descarga. */
        val DEFAULT_SENDERS = listOf(
            "notificaciones@yape.pe", "notificaciones@notificacionesbcp.com.pe", "bbva.com.pe", "bbva.pe",
            "interbank.pe", "netinterbank.com.pe", "scotiabank.com.pe", "bancoripley.com.pe", "bancofalabella.pe", "banbif.com.pe",
        )

        fun fromJson(o: JSONObject?): Settings {
            val s = Settings()
            if (o == null) return s
            s.email = o.optString("email")
            s.imapHost = o.optString("imapHost", "imap.gmail.com")
            s.daysBack = o.optInt("daysBack", 120)
            s.ownerNames = o.optJSONArray("ownerNames")?.let { a -> (0 until a.length()).map { a.getString(it) }.toMutableList() } ?: mutableListOf()
            s.usdToPen = o.optDouble("usdToPen", 3.75)
            s.lastSync = if (o.isNull("lastSync")) null else o.optString("lastSync")
            s.lastSyncResult = o.optString("lastSyncResult")
            s.lastSyncOk = o.optBoolean("lastSyncOk", true)
            o.optJSONArray("senders")?.let { a -> s.senders = (0 until a.length()).map { a.getString(it) }.toMutableList() }
            s.language = o.optString("language", "auto")
            s.aiProvider = o.optString("aiProvider", "none")
            s.aiModel = o.optString("aiModel")
            s.aiBaseUrl = o.optString("aiBaseUrl")
            return s
        }
    }
}

/** Todo el estado de la app. Se guarda como un único JSON en el almacenamiento privado del teléfono. */
class AppData {
    var nextId: Long = 1
    val txs: MutableList<Tx> = mutableListOf()
    /** Categorías de "en qué fue". Se crean con nombres en el idioma del usuario (ver seedCategories). */
    val categories: MutableList<Category> = mutableListOf()
    val emails: MutableMap<String, EmailRec> = linkedMapOf()
    val notCounted: MutableList<NotCountedRule> = mutableListOf()
    val ignoredSubjects: MutableList<String> = mutableListOf()
    /** contraparte normalizada -> categoría elegida por el usuario */
    val memory: MutableMap<String, Long> = mutableMapOf()
    /** contraparte normalizada -> fijo/variable elegido por el usuario */
    val fixedMemory: MutableMap<String, Boolean> = mutableMapOf()
    /** Mis palabras (explícitas). Por defecto: "sencillo" y "monedas" son cambio de efectivo. */
    val glossary: MutableList<GlossaryEntry> = mutableListOf(
        GlossaryEntry("sencillo", null, true, "cash"),
        GlossaryEntry("monedas", null, true, "cash"),
    )

    fun seedCategories(english: Boolean) {
        if (categories.isNotEmpty()) return
        Vocabulary.DEFAULTS.forEachIndexed { i, (es, en) -> categories += Category(100L + i, if (english) en else es) }
    }

    /** Relación categoría por defecto (nombre español del vocabulario) -> id, aunque el usuario la haya renombrado. */
    fun categoryIdForDefault(esName: String): Long? {
        val idx = Vocabulary.DEFAULTS.indexOfFirst { it.first == esName }
        val id = 100L + idx
        return if (idx >= 0 && categories.any { it.id == id }) id else null
    }
    var settings: Settings = Settings()

    fun toJson(): String = JSONObject()
        .put("version", 1).put("nextId", nextId)
        .put("txs", JSONArray(txs.map { it.toJson() }))
        .put("categories", JSONArray(categories.map { it.toJson() }))
        .put("emails", JSONArray(emails.values.map { it.toJson() }))
        .put("notCounted", JSONArray(notCounted.map { it.toJson() }))
        .put("ignoredSubjects", JSONArray(ignoredSubjects))
        .put("memory", JSONObject(memory as Map<*, *>))
        .put("fixedMemory", JSONObject(fixedMemory as Map<*, *>))
        .put("glossary", JSONArray(glossary.map { it.toJson() }))
        .put("settings", settings.toJson())
        .toString()

    companion object {
        fun fromJson(s: String?): AppData {
            val d = AppData()
            if (s.isNullOrBlank()) return d
            val o = JSONObject(s)
            d.nextId = o.optLong("nextId", 1)
            o.optJSONArray("txs")?.let { a -> for (i in 0 until a.length()) d.txs += Tx.fromJson(a.getJSONObject(i)) }
            o.optJSONArray("categories")?.let { a ->
                d.categories.clear()
                for (i in 0 until a.length()) a.getJSONObject(i).let { c -> d.categories += Category(c.getLong("id"), c.getString("name")) }
            }
            o.optJSONArray("emails")?.let { a -> for (i in 0 until a.length()) EmailRec.fromJson(a.getJSONObject(i)).let { d.emails[it.id] = it } }
            o.optJSONArray("notCounted")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { d.notCounted += NotCountedRule(it.getString("pattern"), it.optString("reason")) } }
            o.optJSONArray("ignoredSubjects")?.let { a -> for (i in 0 until a.length()) d.ignoredSubjects += a.getString(i) }
            o.optJSONObject("memory")?.let { m -> m.keys().forEach { k -> d.memory[k] = m.getLong(k) } }
            o.optJSONObject("fixedMemory")?.let { m -> m.keys().forEach { k -> d.fixedMemory[k] = m.getBoolean(k) } }
            o.optJSONArray("glossary")?.let { a ->
                d.glossary.clear()
                for (i in 0 until a.length()) d.glossary += GlossaryEntry.fromJson(a.getJSONObject(i))
            }
            d.settings = Settings.fromJson(o.optJSONObject("settings"))
            return d
        }
    }
}
