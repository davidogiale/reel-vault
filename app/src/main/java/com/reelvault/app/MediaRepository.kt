package com.reelvault.app

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Reads, moves, deletes and saves the phone's real videos and photos through Android's media library. */
object MediaRepository {
    const val APP_FOLDER = "Reel Vault"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    fun load(ctx: Context): List<MediaEntry> {
        val files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DATE_ADDED,
        )
        val selection = "(${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}" +
            " OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE})" +
            " AND ${MediaStore.MediaColumns.SIZE} > 0"
        val out = ArrayList<MediaEntry>()
        ctx.contentResolver.query(files, projection, selection, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val iType = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val iDur = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
            val iBucket = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            val iRel = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            val iDate = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val isVideo = c.getInt(iType) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val base = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                out += MediaEntry(
                    id = id,
                    uri = ContentUris.withAppendedId(base, id),
                    kind = if (isVideo) Kind.VIDEO else Kind.IMAGE,
                    name = c.getString(iName) ?: "Untitled",
                    size = c.getLong(iSize),
                    durationMs = if (isVideo) c.getLong(iDur) else 0L,
                    bucket = c.getString(iBucket)?.takeIf { it.isNotBlank() } ?: "Other",
                    relativePath = c.getString(iRel) ?: "",
                    dateAdded = c.getLong(iDate),
                )
            }
        }
        return out
    }

    /**
     * Where a file goes when added to [folder]. Reuses the folder's existing location when Android
     * allows that file type there; otherwise creates Movies/<folder> (videos) or Pictures/<folder> (photos).
     */
    fun targetPath(kind: Kind, folder: String, existingPaths: List<String>): String {
        val roots = if (kind == Kind.VIDEO) listOf("DCIM/", "Movies/") else listOf("DCIM/", "Pictures/")
        return existingPaths.firstOrNull { p -> roots.any { p.startsWith(it) } }
            ?: ((if (kind == Kind.VIDEO) "Movies/" else "Pictures/") + folder + "/")
    }

    fun move(ctx: Context, uri: Uri, relativePath: String) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath) }
        val rows = ctx.contentResolver.update(uri, values, null, null)
        if (rows == 0) throw IOException("Android didn't move the file")
    }

    fun saveFromUrl(ctx: Context, url: String, baseName: String): Uri {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", UA)
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
        }
        try {
            val code = conn.responseCode
            if (code >= 400) throw IOException("the video host answered $code")
            return saveStream(ctx, conn.inputStream, "$baseName.mp4", "video/mp4")
        } finally {
            conn.disconnect()
        }
    }

    fun saveFromUri(ctx: Context, src: Uri, baseName: String): Uri {
        val mime = ctx.contentResolver.getType(src) ?: "video/mp4"
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: if (mime.startsWith("image")) "jpg" else "mp4"
        val input = ctx.contentResolver.openInputStream(src) ?: throw IOException("couldn't read the shared file")
        return saveStream(ctx, input, "$baseName.$ext", mime)
    }

    private fun saveStream(ctx: Context, input: InputStream, displayName: String, mime: String): Uri {
        val isVideo = !mime.startsWith("image")
        val collection = if (isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, (if (isVideo) "Movies/" else "Pictures/") + APP_FOLDER + "/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(collection, values) ?: throw IOException("phone storage refused the file")
        try {
            input.use { inp ->
                (resolver.openOutputStream(uri) ?: throw IOException("phone storage refused the file")).use { out ->
                    inp.copyTo(out, 64 * 1024)
                }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    fun displayName(ctx: Context, uri: Uri): String? = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    /** A safe folder or file name, or null when nothing usable is left. */
    fun cleanName(raw: String): String? =
        raw.replace(Regex("""[\\/:*?"<>|\r\n\t#]"""), " ").replace(Regex("""\s+"""), " ").trim().trim('.').take(60)
            .takeIf { it.isNotBlank() }
}
