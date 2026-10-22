package app.opencodemobile

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.Manifest
import android.content.pm.PackageManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import androidx.core.content.FileProvider
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
import android.webkit.JavascriptInterface
import android.widget.Toast
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
import java.io.File
import java.io.FileOutputStream
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
    private var speechRecognizer: SpeechRecognizer? = null
    private var voicePending = false
    private val webBg = Color.parseColor("#050D1A")
    private val updatePrefs by lazy { getSharedPreferences("app_updates", MODE_PRIVATE) }
    private val updateChannel = "app_updates"
    private val updateRepo = "Criptobox/OpenCode-mobile"

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
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 730)
        }
        ui.postDelayed({ checkAppUpdate(updateRepo, true) }, 8000)
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
        addJavascriptInterface(object {
            @JavascriptInterface fun taskFinished(title: String) { ui.post { RuntimeService.notifyTaskFinished(this@MainActivity, title.take(100)) } }
            @JavascriptInterface fun startVoiceInput() { ui.post { startVoiceInputNative() } }
            @JavascriptInterface fun stopVoiceInput() { ui.post { stopVoiceInputNative() } }
            @JavascriptInterface fun checkAppUpdateNow() {
                checkAppUpdate(updateRepo, false)
            }
            @JavascriptInterface fun installAppUpdate() {
                Thread { checkThenDownloadAndInstall(updateRepo) }.start()
            }
            @JavascriptInterface fun updateOpenCode() {
                Thread {
                    try {
                        RuntimeService.stopForUpdate(this@MainActivity)
                        rt.updateOpenCode { msg -> ui.post { Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show() } }
                        ui.post { Toast.makeText(this@MainActivity, "Actualización lista; reiniciando servidor…", Toast.LENGTH_LONG).show(); startService(Intent(this@MainActivity, RuntimeService::class.java)); web.reload() }
                    } catch (e: Exception) {
                        ui.post { Toast.makeText(this@MainActivity, "No se pudo actualizar: ${e.message ?: "error"}", Toast.LENGTH_LONG).show(); startService(Intent(this@MainActivity, RuntimeService::class.java)) }
                    }
                }.start()
            }
        }, "OpenCodeNative")
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


    private fun voiceResult(text: String, error: Boolean = false) {
        val safe = JSONObject.quote(text)
        ui.post { if (::web.isInitialized) web.evaluateJavascript("window.onNativeVoiceResult && window.onNativeVoiceResult($safe, $error)", null) }
    }

    private fun startVoiceInputNative() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            voiceResult("El reconocimiento de voz no está disponible en este dispositivo.", true); return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            voicePending = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 81)
            return
        }
        stopVoiceInputNative()
        try {
            val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer = recognizer
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { voiceResult("Escuchando…") }
                override fun onBeginningOfSpeech() { }
                override fun onRmsChanged(rmsdB: Float) { }
                override fun onBufferReceived(buffer: ByteArray?) { }
                override fun onEndOfSpeech() { voiceResult("Procesando voz…") }
                override fun onError(error: Int) {
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "No entendí lo que dijiste. Inténtalo otra vez."
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No se detectó voz. Inténtalo otra vez."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permiso de micrófono denegado."
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "El servicio de voz necesita conexión."
                        else -> "Falló el reconocimiento de voz (código $error)."
                    }
                    voiceResult(msg, true); stopVoiceInputNative()
                }
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (text.isBlank()) voiceResult("No se reconoció texto. Inténtalo otra vez.", true) else voiceResult(text)
                    stopVoiceInputNative()
                }
                override fun onPartialResults(partialResults: Bundle?) { }
                override fun onEvent(eventType: Int, params: Bundle?) { }
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "es-ES")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Habla para dictar tu mensaje")
            }
            recognizer.startListening(intent)
        } catch (e: Exception) {
            voiceResult("No se pudo iniciar el micrófono: ${e.message ?: "error"}", true)
            stopVoiceInputNative()
        }
    }

    private fun stopVoiceInputNative() {
        try { speechRecognizer?.cancel(); speechRecognizer?.destroy() } catch (_: Exception) { }
        speechRecognizer = null
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 81) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted && voicePending) startVoiceInputNative() else voiceResult("Se necesita permiso de micrófono para dictar.", true)
            voicePending = false
        }
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

    private fun versionParts(value: String): List<Int> {
        val match = Regex("[0-9]+(?:\\.[0-9]+)*").find(value) ?: return emptyList()
        return match.value.split('.').mapNotNull { it.toIntOrNull() }
    }

    private fun isRemoteVersionNewer(remote: String, local: String, localCode: Int): Boolean {
        // CI releases are tagged build-N; compare that N with Android's installed versionCode.
        val buildNumber = Regex("(?:^|[-/])build-(\\d+)$", RegexOption.IGNORE_CASE)
            .find(remote.trim())?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (buildNumber != null) return buildNumber > localCode
        val a = versionParts(remote)
        val b = versionParts(local)
        if (a.isEmpty() || b.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val av = a.getOrElse(i) { 0 }
            val bv = b.getOrElse(i) { 0 }
            if (av != bv) return av > bv
        }
        return false
    }

    private fun normalizeRepo(value: String): String? {
        val raw = value.trim().removePrefix("https://github.com/").removeSuffix(".git").trim('/')
        val parts = raw.split('/')
        return if (parts.size == 2 && parts.all { it.matches(Regex("[A-Za-z0-9_.-]+")) }) raw else null
    }

    private fun checkAppUpdate(repoInput: String, notify: Boolean) {
        Thread {
            try {
                val repo = normalizeRepo(repoInput) ?: return@Thread
                val conn = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
                conn.connectTimeout = 10000; conn.readTimeout = 12000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("User-Agent", "OpenCode-Mobile")
                val code = conn.responseCode
                if (code !in 200..299) error("GitHub respondió HTTP $code")
                val release = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                conn.disconnect()
                val assets = release.optJSONArray("assets") ?: org.json.JSONArray()
                var apkUrl: String? = null
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    if (a.optString("name").endsWith(".apk", true)) { apkUrl = a.optString("browser_download_url"); break }
                }
                val tag = release.optString("tag_name", "")
                val packageInfo = packageManager.getPackageInfo(packageName, 0)
                val localVersion = packageInfo.versionName ?: "0.0.0"
                val localCode = if (android.os.Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode.toInt() else packageInfo.versionCode
                updatePrefs.edit().putLong("last_check", System.currentTimeMillis()).apply()
                if (apkUrl != null && isRemoteVersionNewer(tag, localVersion, localCode)) {
                    updatePrefs.edit().putString("latest_apk", apkUrl).putString("latest_tag", tag).apply()
                    ui.post {
                        web.evaluateJavascript("window.onAppUpdateAvailable && window.onAppUpdateAvailable(${JSONObject.quote(tag)})", null)
                        if (notify) postUpdateNotification(tag)
                    }
                } else {
                    ui.post { web.evaluateJavascript("window.onAppUpdateResult && window.onAppUpdateResult('La aplicación está actualizada' )", null) }
                }
            } catch (e: Exception) {
                Log.w("OCUpdate", "No se pudo comprobar actualizaciones", e)
                ui.post { web.evaluateJavascript("window.onAppUpdateResult && window.onAppUpdateResult(${JSONObject.quote("No se pudo comprobar: ${e.message ?: "error de red"}")})", null) }
            }
        }.start()
    }

    private fun updateUiProgress(percent: Int, message: String) {
        val safe = JSONObject.quote(message)
        ui.post { web.evaluateJavascript("window.onAppUpdateProgress && window.onAppUpdateProgress($percent,$safe)", null) }
    }

    private fun checkThenDownloadAndInstall(repo: String) {
        updateUiProgress(0, "Buscando la versión más reciente…")
        try {
            val normalized = normalizeRepo(repo) ?: error("Repositorio de actualizaciones no válido")
            val conn = URL("https://api.github.com/repos/$normalized/releases/latest").openConnection() as HttpURLConnection
            conn.connectTimeout = 10000; conn.readTimeout = 12000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "OpenCode-Mobile")
            if (conn.responseCode !in 200..299) error("No se pudo consultar el lanzamiento oficial")
            val release = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            conn.disconnect()
            val assets = release.optJSONArray("assets") ?: org.json.JSONArray()
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                if (asset.optString("name").endsWith(".apk", true)) { apkUrl = asset.optString("browser_download_url"); break }
            }
            val tag = release.optString("tag_name", "versión nueva")
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            val localVersion = packageInfo.versionName ?: "0.0.0"
            val localCode = if (android.os.Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode.toInt() else packageInfo.versionCode
            if (apkUrl.isNullOrBlank() || !isRemoteVersionNewer(tag, localVersion, localCode)) {
                updateUiProgress(100, "Ya tienes la última versión instalada.")
                return
            }
            updatePrefs.edit().putString("latest_apk", apkUrl).putString("latest_tag", tag).apply()
            updateUiProgress(2, "Descargando $tag…")
            downloadAndInstallApk(repo, apkUrl)
        } catch (e: Exception) {
            updateUiProgress(0, "Actualización fallida: ${e.message ?: "error de conexión"}")
        }
    }

    private fun postUpdateNotification(tag: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(updateChannel, "Actualizaciones de la aplicación", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(this, 73, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, updateChannel)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Nueva versión de OpenCode Mobile")
            .setContentText("La versión $tag está disponible. Abre la aplicación para actualizar.")
            .setContentIntent(open).setAutoCancel(true).build()
        nm.notify(7301, n)
    }

    private fun downloadAndInstallApk(repo: String, apkUrl: String? = null) {
        try {
            val url = apkUrl ?: updatePrefs.getString("latest_apk", null) ?: error("No se encontró una APK en el lanzamiento")
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000; conn.readTimeout = 30000
            conn.setRequestProperty("User-Agent", "OpenCode-Mobile")
            val total = conn.contentLengthLong
            val file = File(cacheDir, "opencode-mobile-update.apk.part")
            var done = 0L
            conn.inputStream.use { input -> FileOutputStream(file).use { output ->
                val buf = ByteArray(64 * 1024); var n: Int; var last = 0L
                while (input.read(buf).also { n = it } >= 0) {
                    if (n == 0) continue
                    output.write(buf, 0, n); done += n
                    val now = System.currentTimeMillis()
                    if (now - last > 180) {
                        last = now
                        val pct = if (total > 0) (5 + (done * 90 / total).toInt()).coerceIn(5, 95) else 5
                        updateUiProgress(pct, if (total > 0) "Descargando actualización · ${done / 1024} / ${total / 1024} KB" else "Descargando actualización · ${done / 1024} KB")
                    }
                }
                output.flush()
            } }
            conn.disconnect()
            if (file.length() < 1024 * 1024) { file.delete(); error("La descarga parece incompleta; inténtalo de nuevo") }
            val apk = File(cacheDir, "opencode-mobile-update.apk")
            if (apk.exists()) apk.delete()
            if (!file.renameTo(apk)) error("No se pudo preparar el instalador")
            updateUiProgress(100, "Descarga completa. Abriendo el instalador de Android…")
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
            ui.post {
                try {
                    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                } catch (e: Exception) {
                    web.evaluateJavascript("window.onAppUpdateResult && window.onAppUpdateResult(${JSONObject.quote("No se pudo abrir el instalador: ${e.message ?: "error"}")})", null)
                }
            }
        } catch (e: Exception) {
            updateUiProgress(0, "Error: ${e.message ?: "no se pudo descargar"}")
        }
    }

    override fun onResume() {
        super.onResume()
        val last = updatePrefs.getLong("last_check", 0L)
        if (System.currentTimeMillis() - last > 6 * 60 * 60 * 1000L) ui.postDelayed({ checkAppUpdate(updateRepo, true) }, 1500)
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
        stopVoiceInputNative()
        web.destroy()
        super.onDestroy()
    }
}
