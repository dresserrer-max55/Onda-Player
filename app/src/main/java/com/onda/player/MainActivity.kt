package com.onda.player

import android.Manifest
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.util.Rational
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.view.SurfaceView
import android.widget.TextView
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/** Puente: la notificación / MediaSession le habla a la página. */
object Bridge {
    fun dispatch(action: String) {
        MainActivity.instance?.runJs("window.onNativeAction&&window.onNativeAction('$action')")
    }
}

class MainActivity : Activity() {

    companion object {
        @Volatile var instance: MainActivity? = null
        const val REQ_PERM = 1
        const val REQ_FILES = 2
        const val REQ_TREE = 3
        const val REQ_ALL = 4
        const val REQ_DEL = 5
        const val URL = "https://appassets.androidplatform.net/assets/index.html"
    }

    private lateinit var web: WebView
    private lateinit var loader: WebViewAssetLoader
    private var chooser: ValueCallback<Array<Uri>>? = null
    private var videoPlaying = false
    private var inPip = false
    @Volatile private var allowPip = true
    private lateinit var root: FrameLayout
    private lateinit var videoView: SurfaceView
    private lateinit var fsBar: LinearLayout
    private lateinit var fsClose: TextView
    private lateinit var fsPlay: ImageButton
    private lateinit var fsSeek: SeekBar
    private lateinit var fsTime: TextView
    private var fsOn = false
    private var rx = 0f
    private var ry = 0f
    private var rw = 0f
    private var rh = 0f
    private var rvis = false
    private var fsDragging = false
    private val fsHide = Runnable { if (::fsBar.isInitialized) { fsBar.visibility = View.GONE; fsClose.visibility = View.GONE } }
    private var pendingDel: Uri? = null
    private var pendingDelFile: File? = null

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            runJs("window.onNativeMediaChanged&&window.onNativeMediaChanged()")
        }
    }

    fun runJs(js: String) {
        if (!::web.isInitialized) return
        web.post { web.evaluateJavascript(js, null) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this

        web = WebView(this)
        web.setBackgroundColor(0xFF0B0B14.toInt())
        root = FrameLayout(this)
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        videoView = SurfaceView(this)
        videoView.visibility = View.GONE
        videoView.isClickable = false
        root.addView(videoView, FrameLayout.LayoutParams(1, 1))
        buildFsControls()
        setContentView(root)
        Engine.init(applicationContext)
        Engine.player?.setVideoSurfaceView(videoView)
        Engine.onTick = { pos, dur, playing -> updateFs(pos, dur, playing) }
        Engine.onSize = { layoutVideo() }

        loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportMultipleWindows(false)
        }
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.addJavascriptInterface(Js(), "PulsoNative")

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val p = request.url.encodedPath ?: ""
                if (request.url.host == "appassets.androidplatform.net" && (p.startsWith("/c/") || p.startsWith("/f/") || p.startsWith("/t/"))) {
                    return MediaScan.serve(this@MainActivity, request)
                }
                return loader.shouldInterceptRequest(request.url)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                w: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams
            ): Boolean {
                chooser?.onReceiveValue(null)
                chooser = cb
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                val mimes = ArrayList<String>()
                var any = false
                for (t in p.acceptTypes) {
                    val x = t.trim()
                    if (x.isEmpty()) continue
                    if (x.startsWith(".")) {
                        val m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(x.substring(1).lowercase())
                        if (m != null) mimes.add(m) else any = true
                    } else mimes.add(x)
                }
                if (mimes.isEmpty() || any) {
                    i.type = "*/*"
                } else if (mimes.size == 1) {
                    i.type = mimes[0]
                } else {
                    i.type = "*/*"
                    i.putExtra(Intent.EXTRA_MIME_TYPES, mimes.toTypedArray())
                }
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, p.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                return try {
                    startActivityForResult(i, REQ_FILES)
                    true
                } catch (e: Exception) {
                    chooser = null
                    cb.onReceiveValue(null)
                    false
                }
            }
        }

        web.loadUrl(URL)

        try {
            contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
            contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
        } catch (_: Exception) {
        }
    }

    /* ---------- Permisos ---------- */

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun hasPerm(): Boolean {
        if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) return true
        return if (Build.VERSION.SDK_INT >= 33) {
            granted(Manifest.permission.READ_MEDIA_AUDIO) || granted(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            granted(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun requestPerm() {
        val l = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            l.add(Manifest.permission.READ_MEDIA_AUDIO)
            l.add(Manifest.permission.READ_MEDIA_VIDEO)
            l.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            l.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT <= 28) l.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        val need = l.filter { !granted(it) }.toTypedArray()
        if (need.isEmpty()) permResult() else requestPermissions(need, REQ_PERM)
    }

    private fun hasMedia(): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            granted(Manifest.permission.READ_MEDIA_AUDIO) || granted(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            granted(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    private fun hasAll(): Boolean = Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

    private fun permState() {
        runJs("window.onNativePermState&&window.onNativePermState(${hasMedia()},${hasAll()})")
    }

    private fun permResult() {
        runJs("window.onNativePermission&&window.onNativePermission(${hasPerm()})")
        permState()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERM) permResult()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_FILES -> {
                val r = WebChromeClient.FileChooserParams.parseResult(resultCode, data)
                chooser?.onReceiveValue(r)
                chooser = null
            }
            REQ_TREE -> {
                val u = data?.data
                if (resultCode == RESULT_OK && u != null) {
                    try {
                        contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (_: Exception) {
                    }
                    runJs("window.onNativeFolderPicked&&window.onNativeFolderPicked()")
                }
            }
            REQ_ALL -> permResult()
            REQ_DEL -> {
                val ok = resultCode == RESULT_OK
                if (ok) pendingDelFile?.let { try { it.delete() } catch (_: Exception) {} }
                pendingDel = null; pendingDelFile = null
                runJs("window.onNativeDeleted&&window.onNativeDeleted($ok)")
            }
        }
    }

    /** Ajusta el video dentro del recuadro de la página SIN estirarlo (bordes negros si hace falta). */
    private fun layoutVideo() {
        try {
            if (!::videoView.isInitialized) return
            if (!rvis || rw < 2f || rh < 2f) { videoView.visibility = View.GONE; return }
            var x = rx; var y = ry; var w = rw; var h = rh
            val vs = Engine.player?.videoSize
            if (vs != null && vs.width > 0 && vs.height > 0) {
                val ar = vs.width * vs.pixelWidthHeightRatio / vs.height
                val boxAr = rw / rh
                if (ar > boxAr) { h = rw / ar; y = ry + (rh - h) / 2f }
                else { w = rh * ar; x = rx + (rw - w) / 2f }
            }
            val lp = FrameLayout.LayoutParams(Math.max(1, w.toInt()), Math.max(1, h.toInt()))
            lp.leftMargin = x.toInt()
            lp.topMargin = y.toInt()
            videoView.layoutParams = lp
            videoView.visibility = View.VISIBLE
        } catch (_: Exception) {}
    }

    /* ---------- Controles de pantalla completa (nativos, sobre el video) ---------- */

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildFsControls() {
        fsBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x99000000.toInt())
            setPadding(dp(12), dp(6), dp(16), dp(10))
            visibility = View.GONE
        }
        fsPlay = ImageButton(this).apply {
            setImageResource(R.drawable.ic_pause)
            setBackgroundColor(0)
            setOnClickListener {
                Engine.ui {
                    val p = Engine.player
                    if (p != null) {
                        if (p.playWhenReady) p.pause() else p.play()
                    }
                }
                showFs()
            }
        }
        fsSeek = SeekBar(this).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) fsTime.text = fmtMs(p.toLong())
                }
                override fun onStartTrackingTouch(s: SeekBar?) { fsDragging = true; web.removeCallbacks(fsHide) }
                override fun onStopTrackingTouch(s: SeekBar?) {
                    fsDragging = false
                    Engine.seek((s?.progress ?: 0).toLong())
                    showFs()
                }
            })
        }
        fsTime = TextView(this).apply { setTextColor(0xFFFFFFFF.toInt()); textSize = 12f; text = "0:00" }
        fsBar.addView(fsPlay, LinearLayout.LayoutParams(dp(48), dp(48)))
        fsBar.addView(fsSeek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fsBar.addView(fsTime, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(fsBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        fsClose = TextView(this).apply {
            text = "✕"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 20f
            gravity = Gravity.CENTER
            setBackgroundColor(0x99000000.toInt())
            visibility = View.GONE
            setOnClickListener { runJs("window.onNativeFsExit&&window.onNativeFsExit()") }
        }
        root.addView(fsClose, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(14), dp(14), 0) })
    }

    private fun fmtMs(ms: Long): String {
        val s = (ms / 1000).toInt()
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun showFs() {
        if (!fsOn) return
        fsBar.visibility = View.VISIBLE
        fsClose.visibility = View.VISIBLE
        web.removeCallbacks(fsHide)
        web.postDelayed(fsHide, 3500)
    }

    private fun updateFs(pos: Long, dur: Long, playing: Boolean) {
        if (!fsOn || fsDragging) return
        fsSeek.max = dur.toInt().coerceAtLeast(1)
        fsSeek.progress = pos.toInt()
        fsTime.text = fmtMs(pos) + " / " + fmtMs(dur)
        fsPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    /* ---------- Borrar archivo del teléfono ---------- */

    private fun doDelete(url: String) {
        fun fail() = runJs("window.onNativeDeleted&&window.onNativeDeleted(false)")
        try {
            val path = Uri.parse(url).encodedPath ?: return fail()
            val raw = Uri.decode(path.substring(3))
            var uri: Uri? = null
            var file: File? = null
            if (path.startsWith("/c/")) {
                uri = Uri.parse(raw)
            } else if (path.startsWith("/f/")) {
                file = File(raw)
                if (Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()) {
                    if (file.delete()) {
                        MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), null, null)
                        return runJs("window.onNativeDeleted&&window.onNativeDeleted(true)")
                    }
                }
                uri = MediaStore.Files.getContentUri("external").let { base ->
                    contentResolver.query(base, arrayOf(MediaStore.Files.FileColumns._ID),
                        MediaStore.Files.FileColumns.DATA + "=?", arrayOf(file.absolutePath), null)?.use { c ->
                        if (c.moveToFirst()) android.content.ContentUris.withAppendedId(base, c.getLong(0)) else null
                    }
                }
                if (uri == null) return fail()
            }
            if (uri == null || uri.scheme != "content") return fail()
            try {
                val n = contentResolver.delete(uri, null, null)
                if (n > 0) return runJs("window.onNativeDeleted&&window.onNativeDeleted(true)")
                return fail()
            } catch (e: SecurityException) {
                pendingDel = uri
                if (Build.VERSION.SDK_INT >= 30) {
                    val pi = MediaStore.createDeleteRequest(contentResolver, listOf(uri))
                    startIntentSenderForResult(pi.intentSender, REQ_DEL, null, 0, 0, 0)
                } else if (Build.VERSION.SDK_INT >= 29 && e is android.app.RecoverableSecurityException) {
                    startIntentSenderForResult(e.userAction.actionIntent.intentSender, REQ_DEL, null, 0, 0, 0)
                    pendingDelFile = null
                } else {
                    fail()
                }
            }
        } catch (_: Exception) {
            fail()
        }
    }

    /* ---------- Botón atrás: primero cierra paneles de la app ---------- */

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript("(window.onNativeBack&&window.onNativeBack())===true") { v ->
            if (v != "true") moveTaskToBack(true)
        }
    }

    /* ---------- Ventana flotante: SOLO para video ---------- */

    private fun pipParams(): PictureInPictureParams {
        var num = 16
        var den = 9
        val vs = Engine.player?.videoSize
        if (vs != null && vs.width > 0 && vs.height > 0) {
            val r = vs.width.toDouble() / vs.height
            if (r in 0.45..2.3) { num = vs.width; den = vs.height }
        }
        val b = PictureInPictureParams.Builder().setAspectRatio(Rational(num, den))
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(videoPlaying && allowPip)
        return b.build()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (videoPlaying && allowPip && Build.VERSION.SDK_INT in 26..30) {
            try { enterPictureInPictureMode(pipParams()) } catch (_: Exception) {}
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        runJs("window.onNativePip&&window.onNativePip($isInPictureInPictureMode)")
    }

    override fun onResume() {
        super.onResume()
        // Estado real de la ventana flotante (evita que la lista quede oculta al volver)
        inPip = if (Build.VERSION.SDK_INT >= 24) isInPictureInPictureMode else false
        runJs("window.onNativePip&&window.onNativePip($inPip)")
        // Los permisos se pueden quitar desde los ajustes de Android: se vuelve a leer el estado real
        permState()
    }

    override fun onStop() {
        super.onStop()
        // Si el usuario cierra la ventana flotante con la X, el video se pausa.
        if (inPip && videoPlaying) runJs("window.onNativeAction&&window.onNativeAction('pause')")
    }

    override fun onDestroy() {
        try { contentResolver.unregisterContentObserver(observer) } catch (_: Exception) {}
        NowPlaying.has = false
        PlaybackService.sync(applicationContext)
        instance = null
        if (isFinishing) Engine.release()
        Engine.onTick = null
        Engine.onSize = null
        try { Engine.player?.clearVideoSurface() } catch (_: Exception) {}
        try { web.destroy() } catch (_: Exception) {}
        super.onDestroy()
    }

    /* ---------- Funciones que la página puede llamar ---------- */

    private var outStream: OutputStream? = null
    private var pendingUri: Uri? = null
    private var legacyFile: File? = null
    private var saveName = ""

    inner class Js {
        @JavascriptInterface fun hasPermission(): Boolean = hasPerm()

        @JavascriptInterface fun hasMediaPerm(): Boolean = hasMedia()

        @JavascriptInterface fun hasAllPerm(): Boolean = hasAll()

        /** Android no deja a una app quitarse permisos sola: se abre la pantalla donde se desactivan. */
        @JavascriptInterface fun openPermSettings(all: Boolean) {
            runOnUiThread {
                try {
                    if (all && Build.VERSION.SDK_INT >= 30) {
                        startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                    } else {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    }
                } catch (e: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    } catch (_: Exception) {}
                }
            }
        }

        @JavascriptInterface fun requestPermission() { runOnUiThread { requestPerm() } }

        @JavascriptInterface fun requestAllFiles() {
            runOnUiThread {
                if (Build.VERSION.SDK_INT >= 30) {
                    if (Environment.isExternalStorageManager()) {
                        permResult()
                    } else {
                        try {
                            startActivityForResult(
                                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")),
                                REQ_ALL
                            )
                        } catch (e: Exception) {
                            try {
                                startActivityForResult(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION), REQ_ALL)
                            } catch (_: Exception) {}
                        }
                    }
                } else requestPerm()
            }
        }

        @JavascriptInterface fun pickFolder() {
            runOnUiThread {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                try {
                    i.putExtra(
                        DocumentsContract.EXTRA_INITIAL_URI,
                        Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload")
                    )
                } catch (_: Exception) {}
                i.addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                )
                try { startActivityForResult(i, REQ_TREE) } catch (_: Exception) {}
            }
        }

        @JavascriptInterface fun scanMedia(): String = try { MediaScan.scan(this@MainActivity) } catch (e: Exception) { "[]" }

        @JavascriptInterface fun setNowPlaying(json: String) {
            try {
                val o = JSONObject(json)
                val has = o.optBoolean("has")
                val title = o.optString("title")
                if (title != NowPlaying.title) NowPlaying.art = null
                NowPlaying.title = title
                NowPlaying.artist = o.optString("artist")
                NowPlaying.playing = o.optBoolean("playing")
                NowPlaying.video = o.optBoolean("video")
                NowPlaying.posMs = o.optLong("pos")
                NowPlaying.durMs = o.optLong("dur")
                NowPlaying.has = has
                val v = has && NowPlaying.video && NowPlaying.playing
                runOnUiThread {
                    if (v != videoPlaying) {
                        videoPlaying = v
                        if (Build.VERSION.SDK_INT >= 31) {
                            try { setPictureInPictureParams(pipParams()) } catch (_: Exception) {}
                        }
                    }
                }
                PlaybackService.sync(applicationContext)
            } catch (_: Exception) {}
        }

        @JavascriptInterface fun isInPip(): Boolean = inPip
        @JavascriptInterface fun hasEngine(): Boolean = true

        /* --- reproductor nativo --- */
        @JavascriptInterface fun nLoad(url: String, startMs: Double, lid: Double) {
            Engine.lid = lid.toInt()
            val u = MediaScan.toUri(url)
            if (u == null) {
                Engine.push("error", 1, "BAD_URI", "Dirección no válida")
                return
            }
            Engine.load(u, startMs.toLong())
        }
        @JavascriptInterface fun nPlay() { Engine.play() }
        @JavascriptInterface fun nPause() { Engine.pause() }
        @JavascriptInterface fun nSeek(ms: Double) { Engine.seek(ms.toLong()) }
        @JavascriptInterface fun nStop() { Engine.stop() }
        @JavascriptInterface fun nVolume(v: Double) { Engine.volume(v.toFloat()) }
        @JavascriptInterface fun nRate(r: Double) { Engine.rate(r.toFloat()) }
        @JavascriptInterface fun nGain(mb: Double) { Engine.gain(mb.toInt()) }
        @JavascriptInterface fun nControls() { runOnUiThread { showFs() } }
        @JavascriptInterface fun enterPip() {
            runOnUiThread {
                try { enterPictureInPictureMode(pipParams()) } catch (_: Exception) {}
            }
        }

        /** Coloca la superficie de video encima del recuadro de la página (en píxeles CSS). */
        @JavascriptInterface fun nRect(x: Double, y: Double, w: Double, h: Double, vis: Boolean) {
            runOnUiThread {
                val d = resources.displayMetrics.density
                rx = (x * d).toFloat(); ry = (y * d).toFloat()
                rw = (w * d).toFloat(); rh = (h * d).toFloat()
                rvis = vis
                layoutVideo()
            }
        }

        /** Pantalla completa real: oculta las barras del sistema y gira si el video es horizontal. */
        @JavascriptInterface fun setFullscreen(on: Boolean, landscape: Boolean) {
            runOnUiThread {
                fsOn = on
                if (on) showFs() else { fsBar.visibility = View.GONE; fsClose.visibility = View.GONE }
                try {
                    requestedOrientation = if (on && landscape) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    if (Build.VERSION.SDK_INT >= 30) {
                        val c = window.insetsController
                        if (on) {
                            c?.hide(android.view.WindowInsets.Type.systemBars())
                            c?.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        } else {
                            c?.show(android.view.WindowInsets.Type.systemBars())
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        window.decorView.systemUiVisibility = if (on)
                            (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
                        else View.SYSTEM_UI_FLAG_VISIBLE
                    }
                } catch (_: Exception) {}
            }
        }

        /** Abre el archivo con otra app de videos (respaldo si la página no puede reproducirlo). */
        @JavascriptInterface fun openExternal(url: String) {
            runOnUiThread {
                try {
                    val path = Uri.parse(url).encodedPath ?: return@runOnUiThread
                    val raw = Uri.decode(path.substring(3))
                    var uri: Uri? = null
                    if (path.startsWith("/c/")) {
                        uri = Uri.parse(raw)
                    } else if (path.startsWith("/f/")) {
                        val base = MediaStore.Files.getContentUri("external")
                        uri = contentResolver.query(base, arrayOf(MediaStore.Files.FileColumns._ID),
                            MediaStore.Files.FileColumns.DATA + "=?", arrayOf(raw), null)?.use { c ->
                            if (c.moveToFirst()) android.content.ContentUris.withAppendedId(base, c.getLong(0)) else null
                        }
                    }
                    if (uri == null) return@runOnUiThread
                    val mime = contentResolver.getType(uri) ?: "video/*"
                    val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(Intent.createChooser(i, "Abrir con"))
                } catch (_: Exception) {}
            }
        }

        @JavascriptInterface fun setOptions(json: String) {
            try {
                val o = JSONObject(json)
                allowPip = o.optBoolean("pip", true)
                runOnUiThread {
                    if (Build.VERSION.SDK_INT >= 31) {
                        try { setPictureInPictureParams(pipParams()) } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
        }

        /** Abre una búsqueda de Google en el navegador del teléfono. */
        @JavascriptInterface fun openSearch(query: String) {
            runOnUiThread {
                try {
                    val u = Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))
                    startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {
                }
            }
        }

        /** Texto copiado en el portapapeles (para pegar la letra). */
        @JavascriptInterface fun getClipboard(): String {
            val box = arrayOfNulls<String>(1)
            val latch = java.util.concurrent.CountDownLatch(1)
            runOnUiThread {
                try {
                    val cm = getSystemService(android.content.ClipboardManager::class.java)
                    val c = cm.primaryClip
                    if (c != null && c.itemCount > 0) box[0] = c.getItemAt(0).coerceToText(this@MainActivity)?.toString()
                } catch (_: Exception) {
                }
                latch.countDown()
            }
            try { latch.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Exception) {}
            return box[0] ?: ""
        }

        /** Borra de verdad el archivo del teléfono (Android pide confirmación si hace falta). */
        @JavascriptInterface fun deleteMedia(url: String) {
            runOnUiThread { doDelete(url) }
        }

        @JavascriptInterface fun setArtworkFor(dataUrl: String, title: String) {
            if (title != NowPlaying.title) return
            setArtwork(dataUrl)
        }

        @JavascriptInterface fun setArtwork(dataUrl: String) {
            try {
                val bytes = Base64.decode(dataUrl.substringAfter(","), Base64.DEFAULT)
                NowPlaying.art = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                PlaybackService.instance?.refresh(true)
            } catch (_: Exception) {}
        }

        /* Guardar un archivo nuevo (por ejemplo un tramo recortado) en Música/Pulso */
        @JavascriptInterface fun saveBegin(name: String, mime: String) {
            try {
                outStream?.close()
                outStream = null; pendingUri = null; legacyFile = null
                saveName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                if (Build.VERSION.SDK_INT >= 29) {
                    val cv = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, saveName)
                        put(MediaStore.MediaColumns.MIME_TYPE, mime)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "Music/Onda")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    pendingUri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cv)
                    outStream = pendingUri?.let { contentResolver.openOutputStream(it) }
                } else {
                    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Onda")
                    dir.mkdirs()
                    legacyFile = File(dir, saveName)
                    outStream = FileOutputStream(legacyFile)
                }
            } catch (_: Exception) {}
        }

        @JavascriptInterface fun saveChunk(b64: String) {
            try { outStream?.write(Base64.decode(b64, Base64.DEFAULT)) } catch (_: Exception) {}
        }

        @JavascriptInterface fun saveEnd(): String {
            try {
                outStream?.close()
                outStream = null
                if (Build.VERSION.SDK_INT >= 29) {
                    pendingUri?.let {
                        val cv = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                        contentResolver.update(it, cv, null, null)
                    }
                } else {
                    legacyFile?.let { MediaScannerConnection.scanFile(this@MainActivity, arrayOf(it.absolutePath), null, null) }
                }
            } catch (_: Exception) {}
            return "Música/Onda/$saveName"
        }
    }
}
