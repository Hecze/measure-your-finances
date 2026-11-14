package app.misgastos.core

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * IA opcional para entender descripciones que el vocabulario no reconoce.
 * El usuario pone la API key de su proveedor. Nunca decide sola: solo sugiere la categoría
 * y, si no entiende el texto ("enanita"), lo deja sin clasificar para preguntarle al usuario.
 */
object Ai {
    data class Provider(val id: String, val label: String, val baseUrl: String, val defaultModel: String)

    /** Todos menos Anthropic usan el formato estándar /chat/completions. */
    val PROVIDERS = listOf(
        Provider("anthropic", "Claude (Anthropic)", "", "claude-opus-5-5"),
        Provider("openai", "OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
        Provider("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.0-flash"),
        Provider("groq", "Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile"),
        Provider("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "openrouter/auto"),
        Provider("deepseek", "DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
        Provider("custom", "Otro (compatible con OpenAI)", "", ""),
    )

    class AiException(msg: String) : Exception(msg)

    fun provider(s: Settings) = PROVIDERS.firstOrNull { it.id == s.aiProvider }
    fun enabled(s: Settings, key: String?) = s.aiProvider != "none" && provider(s) != null && !key.isNullOrBlank()
    fun model(s: Settings) = s.aiModel.ifBlank { provider(s)?.defaultModel.orEmpty() }
    fun baseUrl(s: Settings) = s.aiBaseUrl.ifBlank { provider(s)?.baseUrl.orEmpty() }

    private const val SYSTEM = """Clasificas gastos personales de una persona en Perú (soles).
Cada gasto tiene: a quién se pagó y la descripción que la persona escribió (a veces abreviada o en jerga peruana: "1/4" es un cuarto de pollo a la brasa, "chaufa" es comida, "pasaje" es transporte).
Reglas:
- Usa SOLO una categoría de la lista dada, escrita exactamente igual.
- Si el texto no permite saber en qué fue (un nombre, un apodo, "xd", "enanita"), responde null. No adivines.
- not_expense = true solo si el texto indica que no es un gasto real: cambio de efectivo ("sencillo", "monedas"), encargo, préstamo o devolución.
Responde SOLO con JSON: {"items":[{"id":<número>,"category":"<categoría o null>","not_expense":<true|false>}]}"""

    data class Suggestion(val categoryId: Long?, val notExpense: Boolean)

    fun categorize(s: Settings, apiKey: String, txs: List<Tx>, categories: List<Category>, examples: List<String>): Map<Long, Suggestion> {
        if (txs.isEmpty()) return emptyMap()
        val items = JSONArray(txs.map { JSONObject().put("id", it.id).put("a_quien", it.counterparty).put("descripcion", it.description).put("monto", it.amount) })
        val user = buildString {
            append("Categorías: ").append(categories.joinToString(", ") { it.name }).append("\n\n")
            if (examples.isNotEmpty()) append("Ejemplos que la persona ya clasificó (síguelos):\n").append(examples.takeLast(40).joinToString("\n")).append("\n\n")
            append("Gastos:\n").append(items.toString())
        }
        val text = complete(s, apiKey, SYSTEM, user, 4000)
        val json = extractJson(text) ?: throw AiException("La IA no respondió en el formato esperado.")
        val byName = categories.associateBy { Text.norm(it.name) }
        val out = mutableMapOf<Long, Suggestion>()
        val arr = json.optJSONArray("items") ?: return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optLong("id", -1)
            if (id < 0 || txs.none { it.id == id }) continue
            val cat = if (o.isNull("category")) null else byName[Text.norm(o.optString("category"))]?.id
            out[id] = Suggestion(cat, o.optBoolean("not_expense"))
        }
        return out
    }

    /** Prueba la conexión con una pregunta mínima. */
    fun test(s: Settings, apiKey: String): String = complete(s, apiKey, "Responde solo con la palabra OK.", "OK?", 50)

    fun complete(s: Settings, apiKey: String, system: String, user: String, maxTokens: Int): String =
        if (s.aiProvider == "anthropic") anthropic(s, apiKey, system, user, maxTokens) else openAiCompatible(s, apiKey, system, user, maxTokens)

    private fun anthropic(s: Settings, apiKey: String, system: String, user: String, maxTokens: Int): String {
        val client = AnthropicOkHttpClient.builder().apiKey(apiKey).build()
        try {
            val params = MessageCreateParams.builder()
                .model(model(s))
                .maxTokens(maxTokens.toLong())
                .system(system)
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                .addUserMessage(user)
                .build()
            val msg = client.messages().create(params)
            if (msg.stopReason().orElse(null) == StopReason.REFUSAL) throw AiException("El modelo no quiso responder.")
            return msg.content().mapNotNull { b -> b.text().orElse(null)?.text() }.joinToString("")
        } catch (e: AnthropicServiceException) {
            throw AiException("Anthropic respondió ${e.statusCode()}: revisa tu API key y el modelo.")
        } finally {
            client.close()
        }
    }

    private fun openAiCompatible(s: Settings, apiKey: String, system: String, user: String, maxTokens: Int): String {
        val base = baseUrl(s).trimEnd('/')
        if (base.isBlank()) throw AiException("Falta la URL del proveedor.")
        val body = JSONObject()
            .put("model", model(s))
            .put("max_tokens", maxTokens)
            .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
        val conn = (URL("$base/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) throw AiException("El proveedor respondió $code: revisa tu API key y el modelo.")
            return JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
        } catch (e: java.io.IOException) {
            throw AiException("No se pudo conectar con el proveedor de IA.")
        } finally {
            conn.disconnect()
        }
    }

    fun extractJson(text: String): JSONObject? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try { JSONObject(text.substring(start, end + 1)) } catch (_: Exception) { null }
    }
}
