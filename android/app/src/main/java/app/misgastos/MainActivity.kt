package app.misgastos

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.webkit.WebViewAssetLoader
import java.util.concurrent.Executors

/** Una sola pantalla: la interfaz web local (assets/web) con el motor detrás del puente. */
class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private lateinit var bridge: Bridge
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pageReady = false
    private var pendingShared: String? = null
    private val bg = Executors.newSingleThreadExecutor()

    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        fileCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
        fileCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        // La app no se dibuja debajo de la barra de estado (hora, cámara) ni de la barra de navegación.
        val root = android.widget.FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.WHITE); addView(web) }
        setContentView(root)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            androidx.core.view.WindowInsetsCompat.CONSUMED
        }
        androidx.core.view.WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        val assets = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
        }
        bridge = Bridge(this, web)
        web.addJavascriptInterface(bridge, "AndroidBridge")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assets.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.host == "appassets.androidplatform.net") return false
                // Enlaces externos (página de contraseñas de Google) se abren en el navegador.
                startActivity(Intent(Intent.ACTION_VIEW, url))
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                pendingShared?.let { deliverShared(it) }
                pendingShared = null
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                val accept = params.acceptTypes.firstOrNull { it.contains("json") }
                pickFile.launch(if (accept != null) "application/json" else "*/*")
                return true
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })
        web.loadUrl("https://appassets.androidplatform.net/assets/web/index.html")
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Al abrir la app, lee correos nuevos si pasó más de media hora desde la última vez.
        bg.execute {
            val api = MisGastosApp.api(this)
            val last = api.ledger.data.settings.lastSync
            val stale = last == null || runCatching {
                java.time.Duration.between(java.time.LocalDateTime.parse(last.replace(' ', 'T')), java.time.LocalDateTime.now()).toMinutes() > 30
            }.getOrDefault(true)
            if (api.hasEmail() && stale) {
                runCatching { api.syncEmail() }.onSuccess { bridge.js("window.MG_refresh && MG_refresh()") }
            }
        }
    }

    /** "Abrir con" / "Compartir" un Excel de Yape: se lee y se manda a la pantalla de importar. */
    private fun handleIntent(intent: Intent?) {
        val shared: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        val uri: Uri = shared ?: return
        bg.execute {
            val bytes = runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() ?: return@execute
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            runOnUiThread { if (pageReady) deliverShared(b64) else pendingShared = b64 }
        }
    }

    private fun deliverShared(b64: String) = web.evaluateJavascript("MG_sharedFile('$b64')", null)
}
