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
        var speed: Long = 0,
        var lastBytes: Long = 0,
        var lastTime: Long = 0
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

    override fun onDestroy() {
        if (downloadReceiverRegistered) {
            try { unregisterReceiver(downloadReceiver) } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    // 监听下载完成
    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            val task = activeDownloads[id] ?: return
            val query = DownloadManager.Query().setFilterById(id)
            val cursor = downloadManager?.query(query)
            if (cursor != null && cursor.moveToFirst()) {
                val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                if (statusIdx >= 0) {
                    val status = cursor.getInt(statusIdx)
                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            task.status = "done"
                            task.progress = 100
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

    private fun notifyJs() {
        val arr = activeDownloads.values.map { t ->
            """{"taskId":"${t.taskId}","name":"${jsEscape(t.name)}","status":"${t.status}","progress":${t.progress},"downloaded":${t.downloaded},"total":${t.total},"speed":"${formatSpeed(t.speed)}"}"""
        }.joinToString(",", "[", "]")
        webView.post { webView.loadUrl("javascript:onDownloadBatchUpdate('${jsEscape(arr)}')") }
    }

    private fun jsEscape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("'", "\\'")

    private fun formatSpeed(bytesPerSec: Long): String {
        if (bytesPerSec < 1024) return "$bytesPerSec B/s"
        if (bytesPerSec < 1024 * 1024) return "${bytesPerSec / 1024} KB/s"
        return String.format("%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun fetchApi(url: String): String? = try {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
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

        @JavascriptInterface
        fun downloadVideo(url: String, title: String, epTitle: String, epIdx: Int) {
            runOnUiThread {
                val safeTitle = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
                val safeEp = epTitle.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
                val ext = if (url.endsWith(".mp4")) ".mp4" else ".ts"
                val filename = "${safeTitle}_${safeEp}${ext}"
                val taskId = "${safeTitle}_${safeEp}"

                val task = DownloadTask(taskId, "$title · $epTitle", "queued")
                activeDownloads[taskId.hashCode().toLong()] = task

                val request = DownloadManager.Request(Uri.parse(url))
                    .setTitle("$title · $epTitle")
                    .setDescription("小风剧场")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_MOVIES, "小风剧场/$filename")
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(false)

                try {
                    val id = downloadManager?.enqueue(request) ?: return@runOnUiThread
                    activeDownloads[id] = task
                    task.status = "downloading"
                    notifyJs()

                    // 轮询进度
                    Thread {
                        var lastBytes = 0L
                        var lastTime = System.currentTimeMillis()
                        while (task.status == "downloading") {
                            val query = DownloadManager.Query().setFilterById(id)
                            val cursor = downloadManager?.query(query)
                            if (cursor != null && cursor.moveToFirst()) {
                                val bytesIdx = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                                val totalIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                                if (bytesIdx >= 0) {
                                    val bytes = cursor.getLong(bytesIdx)
                                    val total = if (totalIdx >= 0) cursor.getLong(totalIdx) else 0
                                    val now = System.currentTimeMillis()
                                    val interval = now - lastTime
                                    if (interval > 500) {
                                        task.speed = ((bytes - lastBytes) * 1000) / interval
                                        lastBytes = bytes
                                        lastTime = now
                                    }
                                    task.downloaded = bytes
                                    task.total = total
                                    task.progress = if (total > 0) ((bytes * 100) / total).toInt() else 0
                                    notifyJs()
                                }
                            }
                            cursor?.close()
                            Thread.sleep(500)
                        }
                    }.start()
                } catch (e: Exception) {
                    Log.e("XiaoFeng", "Download failed: ${e.message}")
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
                val uri = Uri.fromFile(dir)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "resource/folder")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (intent.resolveActivity(packageManager) != null) {
                    startActivity(intent)
                } else {
                    intent.setDataAndType(uri, "*/*")
                    try { startActivity(intent) } catch (_: Exception) {}
                }
            }
        }

        @JavascriptInterface
        fun getDownloadTasksJson(): String {
            val arr = activeDownloads.values.map { t ->
                """{"taskId":"${t.taskId}","name":"${jsEscape(t.name)}","status":"${t.status}","progress":${t.progress},"downloaded":${t.downloaded},"total":${t.total},"speed":"${formatSpeed(t.speed)}"}"""
            }.joinToString(",", "[", "]")
            return arr
        }
    }
}
