package org.vizsdr.app

import android.annotation.SuppressLint
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var lastBackPressTime = 0L

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        webView = WebView(this)
        setContentView(webView)
        enableImmersiveFullscreen()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowFileAccess = true
            allowContentAccess = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
        }

        webView.addJavascriptInterface(AndroidAudioBridge(), "AndroidBridge")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                // Vérifie d'abord si le doigt était sur un lien (HitTestResult)
                val href = view?.hitTestResult?.extra
                if (!href.isNullOrBlank() && connectIfKiwiUrl(href)) {
                    return false
                }

                val tempWebView = WebView(this@MainActivity)
                tempWebView.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean {
                        val url = req?.url?.toString() ?: return true
                        connectIfKiwiUrl(url)
                        tempWebView.destroy()
                        return true
                    }

                    override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                        if (url != null && connectIfKiwiUrl(url)) {
                            tempWebView.stopLoading()
                            tempWebView.destroy()
                        }
                    }
                }
                val transport = resultMsg?.obj as? WebView.WebViewTransport
                transport?.webView = tempWebView
                resultMsg?.sendToTarget()
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                return connectIfKiwiUrl(url)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (url != null && connectIfKiwiUrl(url)) {
                    view?.stopLoading()
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val now = System.currentTimeMillis()
                if (now - lastBackPressTime < 2000L) {
                    finish()
                } else {
                    lastBackPressTime = now
                    webView.evaluateJavascript(
                        "document.querySelectorAll('.modal-backdrop').forEach(m => m.classList.remove('open'));",
                        null
                    )
                    Toast.makeText(this@MainActivity, "Appuyez encore pour quitter VizSDR", Toast.LENGTH_SHORT).show()
                }
            }
        })

        webView.loadUrl("file:///android_asset/www/index.html")
    }

    private fun isMapOrSystemHost(host: String): Boolean {
        val h = host.lowercase()
        return h.contains("linkfanel.net")
            || h.contains("openstreetmap")
            || h.contains("arcgisonline")
            || h.contains("cartocdn")
            || h.contains("google")
            || h.contains("gstatic")
            || h.contains("leaflet")
            || h.contains("jquery")
            || h.contains("cloudflare")
    }

    private fun connectIfKiwiUrl(url: String): Boolean {
        if (url.startsWith("file://") || url == "about:blank" || url.startsWith("data:") || url.startsWith("javascript:")) {
            return false
        }
        val uri = Uri.parse(url)
        val host = uri.host ?: return false
        if (isMapOrSystemHost(host)) return false

        val port = if (uri.port != -1) ":${uri.port}" else ":8073"
        val schemePrefix = if (uri.scheme == "https") "https://" else ""
        val target = "$schemePrefix$host$port"

        runOnUiThread {
            Toast.makeText(this, "Récepteur capturé : $host$port", Toast.LENGTH_SHORT).show()
            webView.evaluateJavascript(
                """
                (function() {
                    var f = document.getElementById('kiwimap-frame');
                    if (f) f.src = 'http://rx.linkfanel.net/';
                    if (window.onAndroidKiwiSelected) window.onAndroidKiwiSelected('$target');
                })();
                """.trimIndent(),
                null
            )
        }
        return true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersiveFullscreen()
    }

    private fun enableImmersiveFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )
        }
    }

    inner class AndroidAudioBridge {
        @JavascriptInterface
        fun saveAudioFile(base64Data: String, filename: String, mimeType: String) {
            try {
                val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, filename)
                        put(MediaStore.Downloads.MIME_TYPE, mimeType)
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/VizSDR")
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    uri?.let {
                        contentResolver.openOutputStream(it)?.use { out -> out.write(bytes) }
                    }
                } else {
                    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "VizSDR")
                    if (!dir.exists()) dir.mkdirs()
                    FileOutputStream(File(dir, filename)).use { out -> out.write(bytes) }
                }
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Enregistré dans Téléchargements/VizSDR/$filename", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Erreur sauvegarde audio : ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
