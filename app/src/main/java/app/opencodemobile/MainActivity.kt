package app.opencodemobile

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

class MainActivity : Activity() {
    private lateinit var rt: OcRuntime
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

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, r: Int) =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rt = OcRuntime(this)
        val root = FrameLayout(this)
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

    private fun buildWeb() = WebView(this).apply {
        visibility = View.GONE
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true)
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val h = request.url.host
                if (h == "127.0.0.1" || h == "localhost") return false
                startActivity(Intent(Intent.ACTION_VIEW, request.url))
                return true
            }
        }
    }

    private fun appendLog(line: String) = ui.post {
        logBuf.append(line).append('\n')
        if (logBuf.length > 4000) logBuf.delete(0, logBuf.length - 4000)
        logView.text = logBuf.toString()
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun setStatus(s: String) = ui.post { status.text = s }

    private fun start() {
        retry.visibility = View.GONE
        bar.visibility = View.VISIBLE
        Thread {
            try {
                ui.post { window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
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
                var tries = 0
                while (!rt.serverUp() && tries < 120) { Thread.sleep(500); tries++ }
                if (!rt.serverUp()) error("El servidor no respondió")
                ui.post { show() }
            } catch (e: Throwable) {
                ui.post { fail(e) }
            }
        }.start()
    }

    private fun fail(e: Throwable) {
        status.text = "Algo falló"
        bar.visibility = View.GONE
        retry.visibility = View.VISIBLE
        val tail = if (rt.logFile.exists()) rt.logFile.readLines().takeLast(15).joinToString("\n") else ""
        appendLog("ERROR: ${e.message}\n$tail")
    }

    private fun show() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setup.visibility = View.GONE
        web.visibility = View.VISIBLE
        web.loadUrl("http://127.0.0.1:${rt.port}")
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

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.visibility == View.VISIBLE && web.canGoBack()) web.goBack() else moveTaskToBack(true)
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
