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
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import javax.net.ssl.SSLContext

class MainActivity : AppCompatActivity() {

    companion object {
        val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        })
        val sslFactory: javax.net.ssl.SSLSocketFactory by lazy {
            SSLContext.getInstance("TLS").apply {
                init(null, trustAllCerts, java.security.SecureRandom())
            }.socketFactory
        }
        val FALLBACK_IPS = mapOf("pps.vodfeiss.com" to "104.18.10.91")
        val dnsCache = mutableMapOf<String, String>()
        val poisonedHosts = mutableSetOf<String>()
    }

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
            settings.allowContentAccess = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.blockNetworkImage = false
            settings.loadsImagesAutomatically = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    Log.i("XiaoFeng", "WebView loaded")
                }
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    Log.e("XiaoFeng", "Resource error: ${request?.url} - ${error?.description}")
                }
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val url = request?.url?.toString() ?: return null
                    if (url.startsWith("https://pps.vodfeiss.com/")) return interceptImage(url)
                    return null
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
            registerReceiver(downloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_NOT_EXPORTED)
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

    // ===== 图片拦截（绕过 DNS 污染） =====
    private fun interceptImage(url: String): WebResourceResponse? {
        try {
            val parsed = URL(url)
            val host = parsed.host
            val path = if (parsed.query != null) "${parsed.path}?${parsed.query}" else parsed.path
            val port = if (parsed.port > 0) parsed.port else 443

            var resolvedIp: String? = null
            if (host in poisonedHosts) {
                resolvedIp = synchronized(dnsCache) { dnsCache[host] } ?: FALLBACK_IPS[host]
            } else {
                val systemIp = InetAddress.getByName(host).hostAddress
                if (systemIp == "127.0.0.1" || systemIp == "::1") {
                    synchronized(poisonedHosts) { poisonedHosts.add(host) }
                    resolvedIp = synchronized(dnsCache) { dnsCache[host] }
                        ?: FALLBACK_IPS[host]
                        ?: resolveViaDns(host, "8.8.8.8")
                        ?: resolveViaDns(host, "114.114.114.114")
                    if (resolvedIp != null) synchronized(dnsCache) { dnsCache[host] = resolvedIp }
                }
            }

            val connectHost = resolvedIp ?: host
            val socket = java.net.Socket(connectHost, port)
            val sslSocket = sslFactory.createSocket(socket, host, port, true) as javax.net.ssl.SSLSocket
            val params = sslSocket.sslParameters
            params.setServerNames(listOf(javax.net.ssl.SNIHostName(host)))
            sslSocket.sslParameters = params
            sslSocket.soTimeout = 15000

            val out = sslSocket.getOutputStream()
            out.write("GET $path HTTP/1.1\r\nHost: $host\r\nUser-Agent: Mozilla/5.0 (Linux; Android 14)\r\nAccept: image/*\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()

            val input = sslSocket.getInputStream()
            val headerBuf = java.io.ByteArrayOutputStream()
            var prev = 0
            while (true) {
                val b = input.read()
                if (b == -1) { sslSocket.close(); return null }
                headerBuf.write(b)
                if (prev == '\r'.code && b == '\n'.code && headerBuf.toString().endsWith("\r\n\r\n")) break
                prev = b
            }
            val headers = headerBuf.toString()
            val statusCode = headers.lines().firstOrNull()?.split(" ")?.getOrNull(1)?.toIntOrNull() ?: 0
            if (statusCode != 200) { sslSocket.close(); return null }

            val mime = headers.lines().find { it.lowercase().startsWith("content-type:") }
                ?.substringAfter(":")?.trim()?.split(";")?.firstOrNull() ?: "image/jpeg"
            val body = readHttpBody(headers, input)
            sslSocket.close()
            return WebResourceResponse(mime, "utf-8", java.io.ByteArrayInputStream(body))
        } catch (e: Exception) {
            Log.e("XiaoFeng", "interceptImage: ${e.message}")
            return null
        }
    }

    private fun resolveViaDns(domain: String, dnsServer: String): String? {
        try {
            val query = buildDnsQuery(domain)
            val socket = DatagramSocket()
            socket.soTimeout = 3000
            socket.send(DatagramPacket(query, query.size, InetAddress.getByName(dnsServer), 53))
            val buf = ByteArray(512)
            val resp = DatagramPacket(buf, buf.size)
            socket.receive(resp)
            socket.close()
            return parseDnsResponse(buf, resp.length, query.size)
        } catch (e: Exception) { return null }
    }

    private fun buildDnsQuery(domain: String): ByteArray {
        val parts = domain.split(".")
        val buf = java.io.ByteArrayOutputStream()
        buf.write(0x12); buf.write(0x34); buf.write(0x01); buf.write(0x00)
        buf.write(0x00); buf.write(0x01); buf.write(0x00); buf.write(0x00)
        buf.write(0x00); buf.write(0x00); buf.write(0x00); buf.write(0x00)
        for (part in parts) { buf.write(part.length); buf.write(part.toByteArray()) }
        buf.write(0x00); buf.write(0x00); buf.write(0x01); buf.write(0x00); buf.write(0x01)
        return buf.toByteArray()
    }

    private fun parseDnsResponse(buf: ByteArray, len: Int, queryLen: Int): String? {
        val anCount = ((buf[6].toInt() and 0xFF) shl 8) or (buf[7].toInt() and 0xFF)
        var pos = 12 + queryLen
        for (i in 0 until anCount) {
            if (pos + 12 > len) break
            if ((buf[pos].toInt() and 0xC0) == 0xC0) { pos += 2 }
            else { while (pos < len && buf[pos] != 0.toByte()) { pos += (buf[pos].toInt() and 0xFF) + 1 }; pos++ }
            if (pos + 10 > len) break
            val type = ((buf[pos].toInt() and 0xFF) shl 8) or (buf[pos + 1].toInt() and 0xFF)
            pos += 8
            val rdLen = ((buf[pos].toInt() and 0xFF) shl 8) or (buf[pos + 1].toInt() and 0xFF)
            pos += 2
            if (type == 1 && rdLen == 4 && pos + 4 <= len) {
                return "${buf[pos].toInt() and 0xFF}.${buf[pos+1].toInt() and 0xFF}.${buf[pos+2].toInt() and 0xFF}.${buf[pos+3].toInt() and 0xFF}"
            }
            pos += rdLen
        }
        return null
    }

    private fun readHttpBody(headers: String, input: java.io.InputStream): ByteArray {
        val headerLower = headers.lowercase()
        val contentLength = headerLower.lines().find { it.startsWith("content-length:") }
            ?.substringAfter(":")?.trim()?.toIntOrNull()
        val body = java.io.ByteArrayOutputStream()
        if (headerLower.lines().find { it.startsWith("transfer-encoding:") }?.contains("chunked") == true) {
            val reader = java.io.BufferedReader(java.io.InputStreamReader(input))
            while (true) {
                val sizeLine = reader.readLine() ?: break
                val chunkSize = sizeLine.trim().toIntOrNull(16) ?: break
                if (chunkSize == 0) break
                val cbuf = CharArray(chunkSize)
                var read = 0
                while (read < chunkSize) { val n = reader.read(cbuf, read, chunkSize - read); if (n == -1) break; read += n }
                body.write(String(cbuf).toByteArray())
                reader.readLine()
            }
        } else if (contentLength != null) {
            var remaining = contentLength
            val buf = ByteArray(8192)
            while (remaining > 0) { val n = input.read(buf, 0, minOf(buf.size, remaining)); if (n == -1) break; body.write(buf, 0, n); remaining -= n }
        } else {
            val buf = ByteArray(8192)
            while (true) { val n = input.read(buf); if (n == -1) break; body.write(buf, 0, n) }
        }
        return body.toByteArray()
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
