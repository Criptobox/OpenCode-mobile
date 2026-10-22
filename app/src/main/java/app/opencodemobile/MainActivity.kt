package app.opencodemobile

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.HttpAuthHandler
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {
    private lateinit var rt: OcRuntime
    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var setup: View
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var retry: Button
    private lateinit var bar: ProgressBar
    private lateinit var pct: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val logBuf = StringBuilder()
    private val starting = AtomicBoolean(false)
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private val webBg = Color.parseColor("#050D1A")

    private fun mimeOf(n: String) = when {
        n.endsWith(".html") -> "text/html"
        n.endsWith(".js") -> "application/javascript"
        n.endsWith(".css") -> "text/css"
        n.endsWith(".svg") -> "image/svg+xml"
        n.endsWith(".png") -> "image/png"
        n.endsWith(".json") -> "application/json"
        else -> "application/octet-stream"
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, r: Int) =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Permite inspeccionar la interfaz desde chrome://inspect (clave para diagnosticar pantallas vacías).
        WebView.setWebContentsDebuggingEnabled(true)
        rt = OcRuntime(this)
        root = FrameLayout(this)
        setup = buildSetup()
        web = buildWeb()
        root.addView(setup, ViewGroup.LayoutParams(-1, -1))
        root.addView(web, ViewGroup.LayoutParams(-1, -1))
        setContentView(root)
        start()
    }

    private fun buildSetup(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(56), dp(20), dp(24))
            setBackgroundColor(getColor(R.color.bg))
        }
        col.addView(TextView(this).apply {
            text = "OpenCode"; textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(getColor(R.color.text))
        })
        col.addView(TextView(this).apply {
            text = "Modelos gratis de OpenCode Zen, directo en tu teléfono"; textSize = 14f
            setTextColor(getColor(R.color.muted))
        })
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(getColor(R.color.card), 24)
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        status = TextView(this).apply {
            text = "Preparando…"; textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(getColor(R.color.text))
        }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; isIndeterminate = true
            progressTintList = ColorStateList.valueOf(getColor(R.color.accent))
            progressBackgroundTintList = ColorStateList.valueOf(getColor(R.color.bg))
        }
        pct = TextView(this).apply { text = ""; textSize = 12f; setTextColor(getColor(R.color.muted)) }
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 11f
            setTextColor(getColor(R.color.muted))
        }
        scroll = ScrollView(this).apply { addView(logView) }
        retry = Button(this).apply {
            text = "Reintentar"; visibility = View.GONE
            setTextColor(Color.WHITE)
            background = rounded(getColor(R.color.accent), 16)
            setOnClickListener { start() }
        }
        card.addView(status)
        card.addView(bar, LinearLayout.LayoutParams(-1, dp(12)).apply { topMargin = dp(14) })
        card.addView(pct, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6); bottomMargin = dp(10) })
        card.addView(scroll, LinearLayout.LayoutParams(-1, dp(200)))
        card.addView(retry, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(12) })
        col.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
        return col
    }

    private fun jsonResp(code: Int, body: String) = WebResourceResponse(
        "application/json", "utf-8", code, if (code in 200..299) "OK" else "Error",
        mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(body.toByteArray())
    )

    private fun zenCatalog(): WebResourceResponse = try {
        // Proxy del catálogo oficial por el origen local para evitar CORS.
        val conn = URL("https://opencode.ai/zen/v1/models").openConnection() as HttpURLConnection
        conn.connectTimeout = 12000
        conn.readTimeout = 12000
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", "OpenCode-Mobile/0.5")
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.readBytes() ?: "{}".toByteArray()
        conn.disconnect()
        jsonResp(if (code in 200..299) 200 else 502, String(body))
    } catch (e: Exception) {
        val o = JSONObject().put("error", "official_catalog_unavailable").put("message", e.message.orEmpty())
        jsonResp(200, o.toString()) // 200 a propósito: la interfaz lee el campo "error" y falla de forma segura
    }

    private fun buildWeb() = WebView(this).apply {
        visibility = View.GONE
        setBackgroundColor(webBg)
        overScrollMode = View.OVER_SCROLL_NEVER
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.setSupportZoom(false)
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                w: WebView, cb: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams
            ): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = cb
                return try {
                    startActivityForResult(params.createIntent(), 7); true
                } catch (e: Exception) { fileCb = null; false }
            }
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                Log.d("OCWeb", "${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                return true
            }
        }
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url
                val path = u.path ?: return null
                if (u.host != "127.0.0.1") return null
                if (path == "/m/zen-models") return zenCatalog()
                if (path.startsWith("/m/")) {
                    val name = path.removePrefix("/m/").ifEmpty { "index.html" }
                    return try {
                        WebResourceResponse(
                            mimeOf(name), "utf-8", 200, "OK",
                            mapOf("Cache-Control" to "no-cache"), assets.open("ui/$name")
                        )
                    } catch (e: Exception) {
                        // Antes devolvía null y el servidor respondía con otra interfaz distinta.
                        WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream("404".toByteArray()))
                    }
                }
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val h = request.url.host
                if (h == "127.0.0.1" || h == "localhost") return false
                try { startActivity(Intent(Intent.ACTION_VIEW, request.url)) } catch (_: Exception) { }
                return true
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) ui.post { fail(RuntimeException("No se pudo cargar la interfaz: ${error.description}")) }
            }

            private var authCount = 0
            private var authSince = 0L

            /** El servidor local exige contraseña: se responde automáticamente solo para 127.0.0.1 (con tope anti-bucle). */
            override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String) {
                val now = System.currentTimeMillis()
                if (now - authSince > 10_000) { authSince = now; authCount = 0 }
                if (OcRuntime.SECURE_SERVER && host == "127.0.0.1" && ++authCount <= 30) {
                    handler.proceed(OcRuntime.SERVER_USER, rt.serverPassword())
                } else handler.cancel()
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                ui.post { rebuildWeb() }
                return true // evita que Android cierre toda la app cuando el renderer del WebView muere
            }
        }
    }

    private fun rebuildWeb() {
        root.removeView(web)
        web.destroy()
        web = buildWeb()
        root.addView(web, ViewGroup.LayoutParams(-1, -1))
        show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 7) {
            fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCb = null
        } else super.onActivityResult(requestCode, resultCode, data)
    }

    private fun appendLog(line: String) = ui.post {
        logBuf.append(line).append('\n')
        if (logBuf.length > 4000) logBuf.delete(0, logBuf.length - 4000)
        logView.text = logBuf.toString()
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun setStatus(s: String) = ui.post { status.text = s }

    /** Barras del sistema: oscuras con la interfaz web, según tema con la pantalla de instalación. */
    private fun applyBars(webUi: Boolean) {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val c = if (webUi) webBg else getColor(R.color.bg)
        window.statusBarColor = c
        window.navigationBarColor = c
        val light = !webUi && !night
        window.decorView.systemUiVisibility =
            if (light) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
    }

    private fun showSetup() {
        web.visibility = View.GONE
        setup.visibility = View.VISIBLE
        applyBars(false)
    }

    private fun start() {
        if (!starting.compareAndSet(false, true)) return
        showSetup()
        retry.visibility = View.GONE
        bar.visibility = View.VISIBLE
        bar.isIndeterminate = true
        Thread {
            try {
                ui.post { window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                RuntimeService.gaveUp = false
                startForegroundService(Intent(this, RuntimeService::class.java))
                if (!rt.isInstalled()) {
                    rt.install({ p, msg ->
                        ui.post {
                            bar.isIndeterminate = false
                            bar.progress = p
                            status.text = msg
                            pct.text = "${p / 10}%"
                        }
                    }, { appendLog(it) })
                }
                ui.post { bar.isIndeterminate = true; pct.text = "" }
                setStatus("Iniciando OpenCode…")
                startForegroundService(Intent(this, RuntimeService::class.java))
                val t0 = System.currentTimeMillis()
                while (!rt.serverReady()) {
                    if (RuntimeService.gaveUp) error("OpenCode se cerró varias veces al arrancar")
                    if (System.currentTimeMillis() - t0 > 180_000) error("El servidor no respondió en 3 minutos")
                    Thread.sleep(500)
                }
                ui.post { show() }
            } catch (e: Throwable) {
                ui.post { fail(e) }
            } finally {
                starting.set(false)
            }
        }.start()
    }

    private fun fail(e: Throwable) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showSetup()
        status.text = "Algo falló"
        bar.visibility = View.GONE
        retry.visibility = View.VISIBLE
        appendLog("ERROR: ${e.message}\n${rt.tailLog(15)}")
    }

    private fun show() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setup.visibility = View.GONE
        web.visibility = View.VISIBLE
        applyBars(true)
        web.loadUrl("http://127.0.0.1:${rt.port}/m/")
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        if (!prefs.getBoolean("battery_asked", false)) {
            prefs.edit().putBoolean("battery_asked", true).apply()
            val pm = getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                } catch (_: Exception) { }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Si Android mató el servidor mientras la app estaba en segundo plano, se reinicia solo al volver.
        if (web.visibility == View.VISIBLE && !starting.get()) {
            Thread { if (!rt.serverReady()) ui.post { start() } }.start()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.visibility == View.VISIBLE && web.canGoBack()) web.goBack() else moveTaskToBack(true)
    }

    override fun onDestroy() {
        fileCb?.onReceiveValue(null); fileCb = null
        web.destroy()
        super.onDestroy()
    }
}
