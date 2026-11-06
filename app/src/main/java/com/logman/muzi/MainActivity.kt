package com.logman.muzi

import android.app.Activity
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import android.content.ClipData
import android.content.ContentUris
import androidx.core.content.FileProvider
import java.io.ByteArrayInputStream
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.InputStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.util.UUID

class MainActivity : Activity() {
    companion object {
        @Volatile var current: MainActivity? = null
    }
    private lateinit var root: FrameLayout
    private var splash: ImageView? = null
    private var pageReady = false
    private var minTimePassed = false
    private lateinit var webView: WebView
    private val filePickerRequest = 4102
    private val prefs by lazy { getSharedPreferences("muzi_library", MODE_PRIVATE) }
    private val musicDir by lazy { File(filesDir, "music").apply { mkdirs() } }
    private val coverDir by lazy { File(filesDir, "covers").apply { mkdirs() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = this
        webView = WebView(this)
        root = FrameLayout(this)
        root.addView(webView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setContentView(root)
        if (savedInstanceState == null) showSplash()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 77)
        }
        if (Build.VERSION.SDK_INT >= 35) {
            webView.setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
                WindowInsets.CONSUMED
            }
        }

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.allowContentAccess = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.settings.setSupportZoom(false)
        webView.settings.builtInZoomControls = false
        webView.settings.displayZoomControls = false
        webView.settings.textZoom = 100
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.isHapticFeedbackEnabled = false
        webView.setOnLongClickListener { true }
        webView.addJavascriptInterface(MuziBridge(), "MuziNative")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pageReady = true
                hideSplashIfReady()
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val req = request ?: return null
                val path = req.url.path ?: return null
                return try {
                    when {
                        path == "/app/index.html" -> WebResourceResponse("text/html", "UTF-8", 200, "OK",
                            mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-cache"), assets.open("index.html"))
                        path.startsWith("/scancover/") -> serveScanCover(path.substringAfter("/scancover/").substringBefore('/'))
                        path.startsWith("/scan/") -> serveScanned(req, path.substringAfter("/scan/").substringBefore('/'))
                        path.startsWith("/covers/") || path.startsWith("/music/") -> serveLocal(req, path)
                        else -> null
                    }
                } catch (_: Exception) { null }
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                if (fileChooserParams?.acceptTypes?.any { it.contains("image") } == true) {
                    imageCallback?.onReceiveValue(null)
                    imageCallback = filePathCallback
                    val pick = Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "image/*"
                    }
                    return try {
                        startActivityForResult(pick, imageRequest)
                        true
                    } catch (_: Exception) {
                        imageCallback = null
                        filePathCallback?.onReceiveValue(null)
                        false
                    }
                }
                filePathCallback?.onReceiveValue(null)
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "audio/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                return try {
                    startActivityForResult(intent, filePickerRequest)
                    true
                } catch (_: Exception) {
                    Toast.makeText(this@MainActivity, "Unable to open audio picker", Toast.LENGTH_SHORT).show()
                    false
                }
            }
        }

        if (savedInstanceState == null) webView.loadUrl("https://appassets.androidplatform.net/app/index.html")
        else webView.restoreState(savedInstanceState)
    }

    inner class MuziBridge {
        @JavascriptInterface fun getSavedTracks(): String = synchronized(prefs) {
            prefs.getString("tracks", "[]") ?: "[]"
        }

        @JavascriptInterface fun setPlaying(playing: Boolean, title: String?, artist: String?) {
            runOnUiThread {
                if (!playing && !PlaybackService.running) return@runOnUiThread
                val i = Intent(this@MainActivity, PlaybackService::class.java)
                    .setAction(PlaybackService.ACTION_UPDATE)
                    .putExtra("playing", playing)
                    .putExtra("title", title ?: "")
                    .putExtra("artist", artist ?: "")
                try {
                    if (Build.VERSION.SDK_INT >= 26 && playing) startForegroundService(i) else startService(i)
                } catch (_: Exception) { }
            }
        }

        @JavascriptInterface fun scanDevice() { startScan() }

        @JavascriptInterface fun shareTrack(key: String) { runOnUiThread { shareTrackNow(key) } }

        @JavascriptInterface fun removeTrack(id: String) {
            val all = readTracks()
            val updated = JSONArray()
            for (i in 0 until all.length()) {
                val item = all.optJSONObject(i) ?: continue
                if (item.optString("key") == id) {
                    item.optString("fileName").takeIf { it.isNotBlank() }?.let { File(musicDir, it).delete() }
                    File(coverDir, "$id.jpg").delete()
                }
                else updated.put(item)
            }
            prefs.edit().putString("tracks", updated.toString()).apply()
        }
    }


    private fun notFound() = WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), "Not found".byteInputStream())

    private fun rangeResponse(req: WebResourceRequest, mime: String, length: Long, open: () -> InputStream): WebResourceResponse {
        val range = req.requestHeaders["Range"]
        if (range != null && range.startsWith("bytes=") && length > 0) {
            val parts = range.removePrefix("bytes=").split('-', limit = 2)
            val start = parts[0].toLongOrNull() ?: 0L
            val end = parts.getOrNull(1)?.toLongOrNull()?.coerceAtMost(length - 1) ?: (length - 1)
            val count = (end - start + 1).coerceAtLeast(0)
            val stream = open()
            var left = start
            while (left > 0) { val n = stream.skip(left); if (n <= 0) break; left -= n }
            return WebResourceResponse(mime, null, 206, "Partial Content",
                mapOf("Access-Control-Allow-Origin" to "*", "Accept-Ranges" to "bytes", "Content-Range" to "bytes $start-$end/$length", "Content-Length" to count.toString()), stream)
        }
        return WebResourceResponse(mime, null, 200, "OK",
            mapOf("Access-Control-Allow-Origin" to "*", "Accept-Ranges" to "bytes", "Content-Length" to length.toString()), open())
    }

    private fun serveLocal(req: WebResourceRequest, path: String): WebResourceResponse {
        val isCover = path.startsWith("/covers/")
        val id = path.substringAfter(if (isCover) "/covers/" else "/music/").substringBefore('/')
        val base = if (isCover) coverDir else musicDir
        val file = File(base, id)
        if (!file.isFile || !file.canonicalPath.startsWith(base.canonicalPath + File.separator)) return notFound()
        val mime = if (isCover) "image/jpeg" else when (file.extension.lowercase()) {
            "mp3" -> "audio/mpeg"
            "m4a", "mp4" -> "audio/mp4"
            "aac" -> "audio/aac"
            "wav" -> "audio/wav"
            "ogg", "oga", "opus" -> "audio/ogg"
            "flac" -> "audio/flac"
            "weba" -> "audio/webm"
            else -> "application/octet-stream"
        }
        return rangeResponse(req, mime, file.length()) { FileInputStream(file) }
    }

    private fun serveScanned(req: WebResourceRequest, idStr: String): WebResourceResponse {
        val id = idStr.toLongOrNull() ?: return notFound()
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
        val mime = contentResolver.getType(uri)?.takeIf { it.startsWith("audio/") || it == "application/ogg" } ?: "audio/mpeg"
        val length = contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: return notFound()
        return rangeResponse(req, mime, length) { contentResolver.openInputStream(uri) ?: throw java.io.IOException("open failed") }
    }


    private fun serveScanCover(idStr: String): WebResourceResponse {
        val id = idStr.toLongOrNull() ?: return notFound()
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(this, uri)
            val pic = r.embeddedPicture ?: return notFound()
            WebResourceResponse("image/jpeg", null, 200, "OK",
                mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "max-age=86400"), ByteArrayInputStream(pic))
        } catch (_: Exception) { notFound() } finally { try { r.release() } catch (_: Exception) {} }
    }

    private fun mimeForExt(ext: String) = when (ext.lowercase()) {
        "mp3" -> "audio/mpeg"; "m4a", "mp4" -> "audio/mp4"; "aac" -> "audio/aac"; "wav" -> "audio/wav"
        "ogg", "oga", "opus" -> "audio/ogg"; "flac" -> "audio/flac"; "weba" -> "audio/webm"; else -> "audio/*"
    }

    private fun shareTrackNow(key: String) {
        try {
            val uri: Uri
            val mime: String
            var title = ""
            val all = readTracks()
            var item: JSONObject? = null
            for (i in 0 until all.length()) { val o = all.optJSONObject(i); if (o?.optString("key") == key) { item = o; break } }
            if (item == null) { Toast.makeText(this, "This song can't be shared", Toast.LENGTH_SHORT).show(); return }
            title = item.optString("title")
            if (key.startsWith("ms-")) {
                uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, key.removePrefix("ms-").toLong())
                mime = contentResolver.getType(uri) ?: "audio/*"
            } else {
                val src = File(musicDir, item.optString("fileName"))
                val dir = File(cacheDir, "share").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val clean = item.optString("name").replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { src.name }
                val out = File(dir, clean)
                src.copyTo(out, overwrite = true)
                uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", out)
                mime = mimeForExt(src.extension)
            }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, title)
                clipData = ClipData.newRawUri(title, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Share song"))
        } catch (_: Exception) {
            Toast.makeText(this, "Unable to share this song", Toast.LENGTH_SHORT).show()
        }
    }

    // ---- scan the whole phone for music (MediaStore) ----
    private var scanning = false
    private val imageRequest = 4103
    private var imageCallback: ValueCallback<Array<Uri>>? = null
    private fun audioPermission() =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    private fun hasAudioPermission() =
        Build.VERSION.SDK_INT < 23 || checkSelfPermission(audioPermission()) == PackageManager.PERMISSION_GRANTED

    fun startScan() {
        runOnUiThread {
            if (hasAudioPermission()) doScan() else requestPermissions(arrayOf(audioPermission()), 78)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 78) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) doScan()
            else webView.evaluateJavascript("window.muziScanDone&&window.muziScanDone(-1);", null)
        }
    }

    private fun doScan() {
        if (scanning) return
        scanning = true
        Thread {
            var total = 0
            try {
                val saved = readTracks()
                val known = HashSet<String>()
                for (i in 0 until saved.length()) known.add(saved.optJSONObject(i)?.optString("key") ?: "")
                val A = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                val proj = arrayOf(
                    MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DISPLAY_NAME
                )
                val sel = "${MediaStore.Audio.Media.IS_RINGTONE}=0 AND ${MediaStore.Audio.Media.IS_NOTIFICATION}=0 AND " +
                    "${MediaStore.Audio.Media.IS_ALARM}=0 AND ${MediaStore.Audio.Media.DURATION}>=30000"
                var batch = JSONArray()
                fun flush() {
                    if (batch.length() == 0) return
                    val js = "window.muziAddNativeTracks&&window.muziAddNativeTracks($batch);"
                    batch = JSONArray()
                    runOnUiThread { webView.evaluateJavascript(js, null) }
                }
                contentResolver.query(A, proj, sel, null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { c ->
                    val now = System.currentTimeMillis()
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        val key = "ms-$id"
                        if (!known.add(key)) continue
                        val display = c.getString(5) ?: "audio-$id"
                        val artist = c.getString(2)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Unknown artist"
                        val album = c.getString(3)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "On this phone"
                        val item = JSONObject()
                            .put("key", key).put("fileName", "").put("scanned", true).put("name", display)
                            .put("title", c.getString(1)?.takeIf { it.isNotBlank() } ?: display.substringBeforeLast('.'))
                            .put("artist", artist).put("album", album)
                            .put("dur", c.getLong(4) / 1000.0).put("cover", "")
                            .put("stamp", now + total)
                            .put("url", "https://appassets.androidplatform.net/scan/$id")
                        saved.put(item); batch.put(item); total++
                        if (batch.length() >= 200) flush()
                    }
                }
                flush()
                synchronized(prefs) { prefs.edit().putString("tracks", saved.toString()).apply() }
            } catch (_: Exception) {
                total = -2
            } finally { scanning = false }
            runOnUiThread { webView.evaluateJavascript("window.muziScanDone&&window.muziScanDone($total);", null) }
        }.start()
    }

    fun runCommand(cmd: String) {
        runOnUiThread { webView.evaluateJavascript("window.muziCmd&&window.muziCmd('$cmd');", null) }
    }

    private fun showSplash() {
        val iv = ImageView(this).apply {
            setImageResource(R.drawable.splash)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundResource(R.color.splash_bg)
            isClickable = true
        }
        splash = iv
        root.addView(iv, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        val h = Handler(Looper.getMainLooper())
        h.postDelayed({ minTimePassed = true; hideSplashIfReady() }, 1600)
        h.postDelayed({ pageReady = true; minTimePassed = true; hideSplashIfReady() }, 7000)
    }

    private fun hideSplashIfReady() {
        val iv = splash ?: return
        if (!pageReady || !minTimePassed) return
        splash = null
        iv.animate().alpha(0f).setDuration(400).withEndAction { root.removeView(iv) }.start()
    }

    private fun readTracks(): JSONArray = try { JSONArray(prefs.getString("tracks", "[]")) } catch (_: Exception) { JSONArray() }

    private fun importUri(uri: Uri): JSONObject? {
        return try {
            val displayName = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: "audio-${System.currentTimeMillis()}.mp3"
            val extension = displayName.substringAfterLast('.', "mp3").lowercase().replace(Regex("[^a-z0-9]"), "").take(8).ifBlank { "mp3" }
            val id = UUID.randomUUID().toString()
            val fileName = "$id.$extension"
            val destination = File(musicDir, fileName)
            contentResolver.openInputStream(uri)?.use { input -> destination.outputStream().use { output -> input.copyTo(output) } } ?: return null

            val retriever = MediaMetadataRetriever()
            val metadata = try {
                retriever.setDataSource(destination.absolutePath)
                val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    ?: displayName.substringBeforeLast('.')
                val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_AUTHOR)
                    ?: "Unknown artist"
                val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "On this phone"
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val cover = retriever.embeddedPicture
                if (cover != null) File(coverDir, "$id.jpg").writeBytes(cover)
                JSONObject().put("title", title).put("artist", artist).put("album", album)
                    .put("dur", duration / 1000.0)
                    .put("cover", if (cover != null) "https://appassets.androidplatform.net/covers/$id.jpg" else "")
            } finally { try { retriever.release() } catch (_: Exception) {} }

            val item = JSONObject()
                .put("key", id)
                .put("fileName", fileName)
                .put("name", displayName)
                .put("title", metadata.optString("title"))
                .put("artist", metadata.optString("artist"))
                .put("album", metadata.optString("album"))
                .put("dur", metadata.optDouble("dur", 0.0))
                .put("cover", metadata.optString("cover"))
                .put("stamp", System.currentTimeMillis())
                .put("url", "https://appassets.androidplatform.net/music/$fileName")
            item
        } catch (_: Exception) { null }
    }

    @Deprecated("Deprecated in Android API, retained for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == imageRequest) {
            imageCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            imageCallback = null
            return
        }
        if (requestCode != filePickerRequest) return
        val uris = if (resultCode == Activity.RESULT_OK && data != null) {
            val clip = data.clipData
            when {
                clip != null -> (0 until clip.itemCount).map { clip.getItemAt(it).uri }
                data.data != null -> listOf(data.data!!)
                else -> emptyList()
            }
        } else emptyList()

        if (uris.isEmpty()) return
        Toast.makeText(this, "Importing ${uris.size} track(s)…", Toast.LENGTH_SHORT).show()
        Thread {
            val saved = readTracks()
            val added = JSONArray()
            uris.forEach { uri ->
                val item = importUri(uri) ?: return@forEach
                saved.put(item)
                added.put(item)
            }
            synchronized(prefs) { prefs.edit().putString("tracks", saved.toString()).apply() }
            val script = "window.muziAddNativeTracks && window.muziAddNativeTracks(${added});"
            runOnUiThread { webView.evaluateJavascript(script, null) }
        }.start()
    }

    override fun onBackPressed() {
        webView.evaluateJavascript(
            "(function(){ if (location.hash) { if (window.muziShowView) window.muziShowView('play'); else location.hash=''; return 'handled'; } return 'exit'; })();"
        ) { result -> if (result == "\"exit\"") { if (PlaybackService.running) moveTaskToBack(true) else super@MainActivity.onBackPressed() } }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (current === this) current = null
        if (isFinishing) stopService(Intent(this, PlaybackService::class.java))
        webView.destroy()
        super.onDestroy()
    }
}
