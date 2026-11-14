package app.misgastos.dev

import app.misgastos.core.Api
import app.misgastos.core.IncomingEmail
import app.misgastos.core.Secrets
import app.misgastos.core.Storage
import app.misgastos.core.YapeExcel
import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Servidor de desarrollo: sirve la misma interfaz de la app (assets/web) y expone el
 * motor en /api/<método>. Sirve para probar la UI en un navegador sin compilar el APK.
 *
 *   DATA_FILE=/tmp/datos.json gradle :devserver:run
 *   (opcional) SEED_EMAILS_DIR=... SEED_YAPE_XLSX=... para cargar datos de prueba.
 */
fun main() {
    val dataFile = File(System.getenv("DATA_FILE") ?: "dev-data.json")
    val web = File(System.getenv("WEB_DIR") ?: "app/src/main/assets/web")
    val secrets = mutableMapOf<String, String?>()
    val api = Api(
        object : Storage {
            override fun load() = if (dataFile.exists()) dataFile.readText() else null
            override fun save(json: String) = dataFile.writeText(json)
            private val mails = app.misgastos.core.MailDir(File(dataFile.absoluteFile.parentFile, "mail"))
            override fun saveMail(id: String, html: String) = mails.save(id, html)
            override fun hasMail(id: String) = mails.has(id)
            // En desarrollo, los correos de ejemplo se leen de la carpeta de semillas.
            override fun loadMail(id: String): String? = mails.load(id) ?: System.getenv("SEED_EMAILS_DIR")
                ?.let { File(it, "$id.json") }?.takeIf { it.exists() }?.let { JSONObject(it.readText()).optString("htmlBody") }
        },
        object : Secrets {
            override fun get(name: String) = secrets[name] ?: System.getenv("SECRET_" + name.uppercase())
            override fun set(name: String, value: String?) { secrets[name] = value }
        },
        System.getenv("DEVICE_LANG") ?: "es",
    )
    seed(api)
    val port = (System.getenv("PORT") ?: "8790").toInt()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
    server.createContext("/") { ex ->
        val path = ex.requestURI.path
        if (path.startsWith("/api/")) {
            val body = ex.requestBody.readBytes().decodeToString()
            val out = api.call(path.removePrefix("/api/"), body).toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            ex.sendResponseHeaders(200, out.size.toLong()); ex.responseBody.use { it.write(out) }
            return@createContext
        }
        val f = File(web, if (path == "/") "index.html" else path.removePrefix("/"))
        if (!f.isFile || !f.canonicalPath.startsWith(web.canonicalPath)) { ex.sendResponseHeaders(404, -1); ex.close(); return@createContext }
        val type = when (f.extension) { "html" -> "text/html"; "css" -> "text/css"; "js" -> "text/javascript"; "svg" -> "image/svg+xml"; "woff2" -> "font/woff2"; else -> "application/octet-stream" }
        ex.responseHeaders.add("Content-Type", "$type; charset=utf-8")
        val bytes = f.readBytes()
        ex.sendResponseHeaders(200, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
    }
    server.start()
    println("Mis Gastos (desarrollo) en http://127.0.0.1:$port")
}

private fun seed(api: Api) {
    val dir = System.getenv("SEED_EMAILS_DIR")?.let { File(it) }
    if (dir != null && dir.isDirectory && api.ledger.data.emails.isEmpty()) {
        for (f in dir.listFiles()!!.filter { it.name.endsWith(".json") }) {
            val m = JSONObject(f.readText())
            val rec = OffsetDateTime.parse(m.getString("date")).atZoneSameInstant(ZoneId.of("America/Lima")).toLocalDateTime()
            api.ledger.processEmail(IncomingEmail(m.getString("id"), m.getString("sender"), m.getString("subject"),
                m.optString("htmlBody"), m.optString("plaintextBody"), rec), refresh = false)
        }
    }
    System.getenv("SEED_YAPE_XLSX")?.let { p ->
        if (api.ledger.data.txs.none { it.source == "yape" }) api.ledger.importYape(YapeExcel.parse(File(p).readBytes()))
    }
    api.ledger.refreshAll()
    api.save()
}
