package com.xiaofeng.theater

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var downloadManager: DownloadManager? = null
    private val downloadQueue = mutableMapOf<Long, Int>() // downloadId -> epIdx

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        webView = findViewById(R.id.webview)

        WebView.setWebContentsDebuggingEnabled(true)
        webView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    Log.i("XiaoFeng", "WebView loaded: $url")
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                    Log.i("XiaoFeng", "JS: ${msg?.message()}")
                    return true
                }
            }
            addJavascriptInterface(AndroidBridge(), "androidBridge")
            loadUrl("file:///android_asset/index.html")
        }
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun fetchApi(url: String): String? {
            return try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                val text = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                text
            } catch (e: Exception) {
                Log.e("XiaoFeng", "fetchApi: ${e.message}")
                null
            }
        }

        @JavascriptInterface
        fun playVideo(url: String, title: String, epIdx: Int, epTitles: String, epUrls: String) {
            runOnUiThread {
                val intent = Intent(this@MainActivity, PlayerActivity::class.java).apply {
                    putExtra("url", url)
                    putExtra("title", title)
                    putExtra("epIdx", epIdx)
                    putExtra("epTitles", epTitles.split("|||").toTypedArray())
                    putExtra("epUrls", epUrls.split("|||").toTypedArray())
                }
                startActivity(intent)
            }
        }

        // 下载单集
        @JavascriptInterface
        fun downloadVideo(url: String, title: String, epTitle: String, epIdx: Int) {
            runOnUiThread {
                startDownload(url, title, epTitle, epIdx)
            }
        }

        // 下载全部
        @JavascriptInterface
        fun downloadAll(title: String, epTitles: String, epUrls: String) {
            runOnUiThread {
                val titles = epTitles.split("|||")
                val urls = epUrls.split("|||")
                titles.forEachIndexed { idx, epTitle ->
                    if (idx < urls.size) {
                        startDownload(urls[idx], title, epTitle, idx)
                        // 每集之间稍作延迟
                        Thread.sleep(500)
                    }
                }
            }
        }
    }

    private fun startDownload(url: String, title: String, epTitle: String, epIdx: Int) {
        val safeTitle = sanitizeFileName(title)
        val safeEp = sanitizeFileName(epTitle)
        val ext = if (url.endsWith(".mp4")) ".mp4" else ".ts"
        val filename = "${safeTitle}_${safeEp}${ext}"
        val taskId = "${title}_${epTitle}"

        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("${title} - ${epTitle}")
            .setDescription("小风剧场下载中...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_MOVIES, "小风剧场/$filename")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)

        try {
            val downloadId = downloadManager?.enqueue(request)
            if (downloadId != null) {
                downloadQueue[downloadId] = epIdx
                // 通知 JS 下载进度
                webView.post {
                    webView.loadUrl("javascript:onDownloadProgress('${taskId}', '${sanitizeJsString(title + " " + epTitle)}', 'downloading', 0, 0, 0)")
                }
            }
        } catch (e: Exception) {
            Log.e("XiaoFeng", "Download failed: ${e.message}")
            webView.post {
                webView.loadUrl("javascript:onDownloadProgress('${taskId}', '${sanitizeJsString(title + " " + epTitle)}', 'error', 0, 0, 0)")
            }
        }
    }

    @JavascriptInterface
    fun openDownloadDir() {
        runOnUiThread {
            val uri = Uri.parse(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES).toString() + "/小风剧场")
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "*/*")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try { startActivity(intent) } catch (e: Exception) { Log.e("XiaoFeng", "Open dir failed: ${e.message}") }
        }
    }

    private fun sanitizeJsString(s: String): String {
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"")
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
    }
}
