package com.onda.player

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Busca música y videos en el teléfono:
 *  1) MediaStore (incluye Descargas, Música, WhatsApp, etc. con el permiso de medios)
 *  2) Carpetas del almacenamiento no indexadas, si se dio "acceso a todos los archivos"
 *  3) Carpetas elegidas a mano con el selector del sistema (respaldo)
 * Y sirve esos archivos a la página con soporte de "Range" para poder adelantar y retroceder.
 */
object MediaScan {
    const val HOST = "https://appassets.androidplatform.net"
    private const val MAX_ITEMS = 60000

    private val AUD = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "wma")
    private val VID = setOf("mp4", "m4v", "webm", "mkv", "mov", "3gp")

    fun kindOf(name: String): String? {
        val e = name.substringAfterLast('.', "").lowercase()
        return when {
            e in AUD -> "audio"
            e in VID -> "video"
            else -> null
        }
    }

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "ogg", "oga", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "wma" -> "audio/x-ms-wma"
        "mp4", "m4v", "mov" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "3gp" -> "video/3gpp"
        else -> "application/octet-stream"
    }

    private fun root(): String = Environment.getExternalStorageDirectory().absolutePath

    fun hasAllFiles(ctx: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            ctx.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    fun scan(ctx: Context): String {
        val out = JSONArray()
        val seen = HashSet<String>()
        try { queryStore(ctx, true, out, seen) } catch (_: Exception) {}
        try { queryStore(ctx, false, out, seen) } catch (_: Exception) {}
        try { if (hasAllFiles(ctx)) walkStorage(out, seen) } catch (_: Exception) {}
        try { walkTrees(ctx, out, seen) } catch (_: Exception) {}
        return out.toString()
    }

    private fun item(
        id: String, kind: String, name: String, title: String, artist: String,
        durMs: Long, addedSec: Long, path: String, url: String
    ): JSONObject = JSONObject().apply {
        put("id", id); put("kind", kind); put("name", name); put("title", title)
        put("artist", artist); put("duration", durMs); put("added", addedSec)
        put("path", path); put("url", url)
    }

    private fun queryStore(ctx: Context, audio: Boolean, out: JSONArray, seen: HashSet<String>) {
        val base = if (audio) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val durCol = if (audio) MediaStore.Audio.Media.DURATION else MediaStore.Video.Media.DURATION
        val pathCol = if (Build.VERSION.SDK_INT >= 29) MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.MediaColumns.DATA
        val cols = ArrayList<String>().apply {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.TITLE)
            add(durCol)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(pathCol)
            if (audio) add(MediaStore.Audio.Media.ARTIST)
        }
        val sel = if (audio)
            "${MediaStore.Audio.Media.IS_ALARM}=0 AND ${MediaStore.Audio.Media.IS_NOTIFICATION}=0 AND ${MediaStore.Audio.Media.IS_RINGTONE}=0"
        else null
        val r = root()
        ctx.contentResolver.query(base, cols.toTypedArray(), sel, null, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val iTitle = c.getColumnIndexOrThrow(MediaStore.MediaColumns.TITLE)
            val iDur = c.getColumnIndexOrThrow(durCol)
            val iAdd = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val iPath = c.getColumnIndexOrThrow(pathCol)
            val iArt = if (audio) c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST) else -1
            while (c.moveToNext()) {
                if (out.length() >= MAX_ITEMS) break
                val id = c.getLong(iId)
                val name = c.getString(iName) ?: continue
                val raw = c.getString(iPath) ?: ""
                val dir: String
                val full: String
                if (Build.VERSION.SDK_INT >= 29) {
                    dir = raw.trimEnd('/')
                    full = "$r/$raw$name"
                } else {
                    val parent = File(raw).parent ?: ""
                    dir = parent.removePrefix(r).trim('/')
                    full = raw
                }
                seen.add(full)
                val uri = ContentUris.withAppendedId(base, id)
                out.put(
                    item(
                        (if (audio) "a" else "v") + id,
                        if (audio) "audio" else "video",
                        name,
                        c.getString(iTitle) ?: "",
                        if (iArt >= 0) (c.getString(iArt) ?: "") else "",
                        c.getLong(iDur),
                        c.getLong(iAdd),
                        dir,
                        "$HOST/c/" + Uri.encode(uri.toString())
                    )
                )
            }
        }
    }

    /** Recorre Descargas, Música, etc. buscando archivos que MediaStore todavía no indexó. */
    private fun walkStorage(out: JSONArray, seen: HashSet<String>) {
        val rootDir = File(root())
        val stack = ArrayDeque<Pair<File, Int>>()
        stack.add(Pair(rootDir, 0))
        while (stack.isNotEmpty() && out.length() < MAX_ITEMS) {
            val (dir, depth) = stack.removeLast()
            val kids = dir.listFiles() ?: continue
            if (kids.any { it.name == ".nomedia" }) continue
            for (f in kids) {
                val n = f.name
                if (f.isDirectory) {
                    if (n.startsWith(".")) continue
                    if (depth == 0 && n == "Android") continue
                    if (depth < 10) stack.add(Pair(f, depth + 1))
                } else {
                    val kind = kindOf(n) ?: continue
                    val p = f.absolutePath
                    if (seen.contains(p)) continue
                    seen.add(p)
                    val rel = (f.parent ?: "").removePrefix(root()).trim('/')
                    out.put(
                        item(
                            "f" + Integer.toHexString(p.hashCode()) + p.length,
                            kind, n, "", "", 0L, f.lastModified() / 1000, rel,
                            "$HOST/f/" + Uri.encode(p)
                        )
                    )
                }
            }
        }
    }

    /** Carpetas elegidas con el selector del sistema (permiso persistente). */
    private fun walkTrees(ctx: Context, out: JSONArray, seen: HashSet<String>) {
        for (perm in ctx.contentResolver.persistedUriPermissions) {
            if (!perm.isReadPermission) continue
            val tree = perm.uri
            val treeId = try { DocumentsContract.getTreeDocumentId(tree) } catch (_: Exception) { continue }
            val baseName = treeId.substringAfter(':', "").ifEmpty { "Almacenamiento" }
            val onPrimary = treeId.startsWith("primary:")
            walkDoc(ctx, tree, treeId, baseName, onPrimary, out, seen, 0)
        }
    }

    private fun walkDoc(
        ctx: Context, tree: Uri, docId: String, path: String, onPrimary: Boolean,
        out: JSONArray, seen: HashSet<String>, depth: Int
    ) {
        if (depth > 10 || out.length() >= MAX_ITEMS) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        data class Doc(val id: String, val name: String, val mime: String, val mod: Long)
        val list = ArrayList<Doc>()
        ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                list.add(Doc(c.getString(0) ?: continue, c.getString(1) ?: "", c.getString(2) ?: "", c.getLong(3)))
            }
        }
        if (list.any { it.name == ".nomedia" }) return
        for (d in list) {
            if (out.length() >= MAX_ITEMS) return
            if (d.mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                if (d.name.startsWith(".")) continue
                walkDoc(ctx, tree, d.id, "$path/${d.name}", onPrimary, out, seen, depth + 1)
            } else {
                val kind = kindOf(d.name) ?: continue
                if (onPrimary && seen.contains("${root()}/$path/${d.name}")) continue
                val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, d.id)
                out.put(
                    item(
                        "t" + Integer.toHexString(docUri.toString().hashCode()),
                        kind, d.name, "", "", 0L, d.mod / 1000, path,
                        "$HOST/c/" + Uri.encode(docUri.toString())
                    )
                )
            }
        }
    }

    /* ---------- Servidor de archivos para la página (con Range) ---------- */

    private fun plain(code: Int, reason: String): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", code, reason, emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))

    /** Convierte una dirección de la página (/c/... o /f/...) en el Uri real para el reproductor nativo. */
    fun toUri(url: String): Uri? {
        val path = Uri.parse(url).encodedPath ?: return null
        if (path.length < 4) return null
        val raw = Uri.decode(path.substring(3))
        if (path.startsWith("/c/")) {
            val u = Uri.parse(raw)
            return if (u.scheme == "content") u else null
        }
        if (path.startsWith("/f/")) {
            val f = File(raw)
            val root = Environment.getExternalStorageDirectory().canonicalPath
            return if (f.canonicalPath.startsWith("$root/")) Uri.fromFile(f) else null
        }
        return null
    }

    /** Miniatura de un video (/t/c/<uri> o /t/f/<ruta>). Se guarda en caché. */
    private fun thumb(ctx: Context, ep: String): WebResourceResponse {
        val inner = ep.substring(2)
        val cache = File(ctx.cacheDir, "thumbs").apply { mkdirs() }
        val cf = File(cache, Integer.toHexString(inner.hashCode()) + ".jpg")
        try {
            if (!cf.exists() || cf.length() == 0L) {
                var bmp: android.graphics.Bitmap? = null
                val raw = Uri.decode(inner.substring(3))
                if (inner.startsWith("/c/")) {
                    val uri = Uri.parse(raw)
                    if (Build.VERSION.SDK_INT >= 29) {
                        try { bmp = ctx.contentResolver.loadThumbnail(uri, android.util.Size(360, 202), null) } catch (_: Exception) {}
                    }
                    if (bmp == null) {
                        val r = android.media.MediaMetadataRetriever()
                        try { r.setDataSource(ctx, uri); bmp = r.getFrameAtTime(1_000_000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC) }
                        finally { try { r.release() } catch (_: Exception) {} }
                    }
                } else {
                    val f = File(raw)
                    val rootCanon = Environment.getExternalStorageDirectory().canonicalPath
                    if (!f.canonicalPath.startsWith("$rootCanon/")) return plain(403, "Forbidden")
                    val r = android.media.MediaMetadataRetriever()
                    try { r.setDataSource(f.absolutePath); bmp = r.getFrameAtTime(1_000_000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC) }
                    finally { try { r.release() } catch (_: Exception) {} }
                }
                if (bmp == null) return plain(404, "Not Found")
                val w = 360
                val h = (w * bmp.height / bmp.width.coerceAtLeast(1)).coerceAtLeast(1)
                val sc = android.graphics.Bitmap.createScaledBitmap(bmp, w, h, true)
                java.io.FileOutputStream(cf).use { sc.compress(android.graphics.Bitmap.CompressFormat.JPEG, 78, it) }
            }
            return WebResourceResponse("image/jpeg", null, 200, "OK", mapOf("Cache-Control" to "max-age=86400"), java.io.FileInputStream(cf))
        } catch (e: Exception) {
            return plain(404, "Not Found")
        }
    }

    fun serve(ctx: Context, req: WebResourceRequest): WebResourceResponse? {
        val ep = req.url.encodedPath ?: return null
        if (ep.startsWith("/t/")) return thumb(ctx, ep)
        if (ep.length < 4) return plain(404, "Not Found")
        val isFile = ep.startsWith("/f/")
        val raw = Uri.decode(ep.substring(3))
        var pfd: ParcelFileDescriptor? = null
        try {
            val mime: String
            if (isFile) {
                val f = File(raw)
                val rootCanon = Environment.getExternalStorageDirectory().canonicalPath
                if (!f.canonicalPath.startsWith("$rootCanon/")) return plain(403, "Forbidden")
                pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
                mime = mimeOf(f.name)
            } else {
                val uri = Uri.parse(raw)
                if (uri.scheme != "content") return plain(403, "Forbidden")
                pfd = ctx.contentResolver.openFileDescriptor(uri, "r")
                var m = ctx.contentResolver.getType(uri)
                if (m == null || m == "application/octet-stream" || m == "video/quicktime") {
                    var nm = ""
                    try {
                        ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                            if (c.moveToFirst()) nm = c.getString(0) ?: ""
                        }
                    } catch (_: Exception) {}
                    val g = if (nm.isNotEmpty()) mimeOf(nm) else "application/octet-stream"
                    m = if (m == "video/quicktime") "video/mp4" else g
                }
                mime = m ?: "application/octet-stream"
            }
            if (pfd == null) return plain(404, "Not Found")

            val total = pfd.statSize
            val range = req.requestHeaders.entries.firstOrNull { it.key.equals("range", true) }?.value
            var start = 0L
            var end = total - 1
            var partial = false
            if (range != null && total > 0) {
                val m = Regex("bytes=(\\d*)-(\\d*)").find(range)
                if (m != null) {
                    val a = m.groupValues[1]
                    val b = m.groupValues[2]
                    if (a.isEmpty() && b.isNotEmpty()) {
                        start = (total - b.toLong()).coerceAtLeast(0)
                    } else if (a.isNotEmpty()) {
                        start = a.toLong()
                        if (b.isNotEmpty()) end = minOf(b.toLong(), total - 1)
                    }
                    partial = true
                }
            }
            // Al adelantar casi hasta el final el reproductor pide un rango pasado del final:
            // en vez de dar error (416) se entrega el último byte, así no se corta la canción.
            if (total > 0 && start > end) {
                start = total - 1
                end = total - 1
            }
            val fis = FileInputStream(pfd.fileDescriptor)
            if (start > 0) fis.channel.position(start)
            val len = if (total > 0) end - start + 1 else -1L
            val headers = HashMap<String, String>()
            headers["Accept-Ranges"] = "bytes"
            if (len >= 0) headers["Content-Length"] = len.toString()
            if (partial) headers["Content-Range"] = "bytes $start-$end/$total"
            val stream = LimitedStream(fis, pfd, len)
            return WebResourceResponse(
                mime, null,
                if (partial) 206 else 200,
                if (partial) "Partial Content" else "OK",
                headers, stream
            )
        } catch (e: Exception) {
            try { pfd?.close() } catch (_: Exception) {}
            return plain(404, "Not Found")
        }
    }

    private class LimitedStream(
        private val src: FileInputStream,
        private val pfd: ParcelFileDescriptor,
        private var left: Long
    ) : InputStream() {
        override fun read(): Int {
            if (left == 0L) return -1
            val r = src.read()
            if (r >= 0 && left > 0) left--
            return r
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left == 0L) return -1
            val n = if (left < 0) len else minOf(len.toLong(), left).toInt()
            val r = src.read(b, off, n)
            if (r > 0 && left > 0) left -= r
            return r
        }

        override fun available(): Int = try { src.available() } catch (_: Exception) { 0 }

        override fun close() {
            try { src.close() } catch (_: Exception) {}
            try { pfd.close() } catch (_: Exception) {}
        }
    }
}
