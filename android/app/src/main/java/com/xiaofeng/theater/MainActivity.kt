package com.xiaofeng.theater

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var downloadManager: DownloadManager? = null
    private val activeDownloads = mutableMapOf<Long, DownloadTask>()
    private var downloadReceiverRegistered = false

    data class DownloadTask(
        val taskId: String,
        val name: String,
        var status: String = "queued",
        var progress: Int = 0,
        var downloaded: Long = 0,
        var total: Long = 0,
        var speed: Long = 0
    )

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        registerDownloadReceiver()

        webView = findViewById(R.id.webview)
        WebView.setWebContentsDebuggingEnabled(true)
        webView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    Log.i("XiaoFeng", "WebView loaded")
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

    override fun onDestroy() {
        if (downloadReceiverRegistered) {
            try { unregisterReceiver(downloadReceiver) } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            val task = synchronized(activeDownloads) { activeDownloads[id] } ?: return
            val query = DownloadManager.Query().setFilterById(id)
            val cursor = downloadManager?.query(query)
            if (cursor != null && cursor.moveToFirst()) {
                val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                if (statusIdx >= 0) {
                    when (cursor.getInt(statusIdx)) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            task.status = "done"; task.progress = 100
                            notifyJs()
                        }
                        DownloadManager.STATUS_FAILED -> {
                            task.status = "error"
                            notifyJs()
                        }
                    }
                }
            }
            cursor?.close()
        }
    }

    private fun registerDownloadReceiver() {
        if (!downloadReceiverRegistered) {
            registerReceiver(downloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
            downloadReceiverRegistered = true
        }
    }

    @Synchronized
    private fun notifyJs() {
        val sb = StringBuilder("[")
        synchronized(activeDownloads) {
            activeDownloads.values.forEachIndexed { i, t ->
                if (i > 0) sb.append(",")
                sb.append("{\"taskId\":\"${jsStr(t.taskId)}\",\"name\":\"${jsStr(t.name)}\",\"status\":\"${t.status}\",\"progress\":${t.progress},\"downloaded\":${t.downloaded},\"total\":${t.total},\"speed\":\"${formatSpeed(t.speed)}\"}")
            }
        }
        sb.append("]")
        val json = sb.toString()
        webView.post {
            // 使用单引号包裹避免转义问题
            val js = "javascript:onDownloadBatchUpdate('${json.replace("\\", "\\\\").replace("'", "\\'")}')"
            webView.evaluateJavascript(js, null)
        }
    }

    private fun jsStr(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun formatSpeed(bytesPerSec: Long): String {
        if (bytesPerSec <= 0) return ""
        if (bytesPerSec < 1024) return "$bytesPerSec B/s"
        if (bytesPerSec < 1024 * 1024) return "${bytesPerSec / 1024} KB/s"
        return String.format("%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))
    }

    private fun safeName(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()

    inner class AndroidBridge {
        @JavascriptInterface
        fun fetchApi(url: String): String? = try {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.connectTimeout = 15000; conn.readTimeout = 15000
            val text = conn.inputStream.bufferedReader().readText()
            conn.disconnect(); text
        } catch (e: Exception) { null }

        @JavascriptInterface
        fun playVideo(url: String, title: String, epIdx: Int, epTitles: String, epUrls: String) {
            runOnUiThread {
                val intent = Intent(this@MainActivity, PlayerActivity::class.java).apply {
                    putExtra("url", url); putExtra("title", title); putExtra("epIdx", epIdx)
                    putExtra("epTitles", epTitles.split("|||").toTypedArray())
                    putExtra("epUrls", epUrls.split("|||").toTypedArray())
                }
                startActivity(intent)
            }
        }

        @JavascriptInterface
        fun downloadVideo(url: String, title: String, epTitle: String, epIdx: Int) {
            runOnUiThread {
                val safeTitle = safeName(title); val safeEp = safeName(epTitle)
                val ext = if (url.endsWith(".mp4")) ".mp4" else ".ts"
                val filename = "${safeTitle}_${safeEp}${ext}"
                val taskId = "${safeTitle}_${safeEp}"

                val task = DownloadTask(taskId, "$title · $epTitle", "queued")
                val request = DownloadManager.Request(Uri.parse(url))
                    .setTitle("$title · $epTitle")
                    .setDescription("小风剧场下载中...")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_MOVIES, "小风剧场/$filename")
                    .setAllowedOverMetered(true).setAllowedOverRoaming(false)

                try {
                    val id = downloadManager!!.enqueue(request)
                    synchronized(activeDownloads) { activeDownloads[id] = task }
                    task.status = "downloading"
                    notifyJs()

                    Thread {
                        var lastBytes = 0L; var lastTime = System.currentTimeMillis()
                        while (task.status == "downloading") {
                            val query = DownloadManager.Query().setFilterById(id)
                            val cursor = downloadManager?.query(query)
                            if (cursor != null && cursor.moveToFirst()) {
                                val bi = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                                val ti = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                                if (bi >= 0) {
                                    val bytes = cursor.getLong(bi)
                                    val total = if (ti >= 0) cursor.getLong(ti) else 0L
                                    val now = System.currentTimeMillis()
                                    val interval = now - lastTime
                                    if (interval > 500) {
                                        task.speed = if (interval > 0) ((bytes - lastBytes) * 1000) / interval else 0
                                        lastBytes = bytes; lastTime = now
                                    }
                                    task.downloaded = bytes; task.total = total
                                    task.progress = if (total > 0) ((bytes * 100) / total).toInt() else 0
                                    notifyJs()
                                }
                            }
                            cursor?.close()
                            Thread.sleep(500)
                        }
                    }.start()
                } catch (e: Exception) {
                    task.status = "error"
                    notifyJs()
                }
            }
        }

        @JavascriptInterface
        fun openDownloadDir() {
            runOnUiThread {
                val dir = java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "小风剧场")
                if (!dir.exists()) dir.mkdirs()
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.fromFile(dir), "resource/folder")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try { startActivity(intent) } catch (_: Exception) {
                    try {
                        val intent2 = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(Uri.fromFile(dir), "*/*")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(intent2)
                    } catch (_: Exception) {}
                }
            }
        }
    }
}
