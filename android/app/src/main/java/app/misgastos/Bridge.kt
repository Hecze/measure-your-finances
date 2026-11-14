package app.misgastos

import android.content.Intent
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Puente entre la interfaz (JavaScript) y el motor. Cada llamada corre en un hilo de fondo
 * y la respuesta vuelve a la página con MG_resolve(id, json).
 */
class Bridge(private val activity: MainActivity, private val web: WebView) {
    private val pool = Executors.newFixedThreadPool(2)
    private val api get() = MisGastosApp.api(activity)

    @JavascriptInterface
    fun call(id: Int, method: String, args: String) {
        pool.execute {
            val result = try {
                when (method) {
                    "sync" -> wrap {
                        api.syncEmail { i, n -> js("window.MG_progress && MG_progress($i,$n)") }
                    }
                    "native.share" -> wrap { share(JSONObject(args)); null }
                    else -> api.call(method, args)
                }
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("error", e.message ?: "error").toString()
            }
            js("MG_resolve($id, ${JSONObject.quote(result)})")
        }
    }

    private fun wrap(block: () -> Any?): String = try {
        JSONObject().put("ok", true).put("data", block() ?: JSONObject.NULL).toString()
    } catch (e: Exception) {
        JSONObject().put("ok", false).put("error", e.message ?: "error").toString()
    }

    fun js(code: String) = activity.runOnUiThread { web.evaluateJavascript(code, null) }

    /** Comparte la copia de seguridad como archivo (para guardarla en Drive, enviarla, etc.). */
    private fun share(a: JSONObject) {
        val dir = File(activity.cacheDir, "exports").apply { mkdirs() }
        val f = File(dir, a.optString("name", "mis-gastos-backup.json"))
        f.writeText(a.getString("text"))
        val uri = FileProvider.getUriForFile(activity, activity.packageName + ".files", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.runOnUiThread { activity.startActivity(Intent.createChooser(send, null)) }
    }
}
