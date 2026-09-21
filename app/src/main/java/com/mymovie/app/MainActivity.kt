package com.mymovie.app

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView

    // प्लेयर बिना चले बंद हो जाए (लिंक वीडियो नहीं निकला) तो पुराने प्लेयर पर लौट जाओ
    private val playerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == PlayerActivity.RESULT_FAILED) {
                web.evaluateJavascript("window.__mmFallback && window.__mmFallback();", null)
            }
        }

    inner class Bridge {
        @JavascriptInterface
        fun play(url: String?, title: String?, sub: String?) {
            runOnUiThread {
                val i = Intent(this@MainActivity, PlayerActivity::class.java)
                i.putExtra("url", url ?: "")
                i.putExtra("title", title ?: "")
                i.putExtra("sub", sub ?: "")
                playerLauncher.launch(i)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        val hook = buildHook()

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowFileAccess = false
            allowContentAccess = false
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.addJavascriptInterface(Bridge(), "NativePlayer")

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

            override fun onPageFinished(view: WebView, url: String?) {
                view.evaluateJavascript(hook, null)
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) {
                    web.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    // hook.js में शीट का लिंक index.html से अपने आप उठाकर डालता है
    private fun buildHook(): String {
        val js = assets.open("hook.js").bufferedReader().use { it.readText() }
        val html = try {
            assets.open("index.html").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            ""
        }
        val sheet = Regex("""SHEET_URL\s*=\s*['"]([^'"]+)['"]""")
            .find(html)?.groupValues?.get(1) ?: ""
        return js.replace("__SHEET__", JSONObject.quote(sheet))
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
