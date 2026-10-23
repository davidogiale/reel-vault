package com.reelvault.app

import android.content.Context
import android.net.Uri

enum class Kind { VIDEO, IMAGE, EMBED }

/** A video or photo that lives on the phone (read from Android's media library). */
data class MediaEntry(
    val id: Long,
    val uri: Uri,
    val kind: Kind,
    val name: String,
    val size: Long,
    val durationMs: Long,
    val bucket: String,
    val relativePath: String,
    val dateAdded: Long,
)

/** One page in the feed: a phone item, a resolved link, or something shared into the app. */
data class Clip(
    val key: String,
    val kind: Kind,
    val uri: Uri?,
    val handle: String,
    val caption: String,
    val tag: String,
    val embedUrl: String? = null,
    val link: String? = null,
    val cover: String? = null,
    val entry: MediaEntry? = null,
    val savable: Boolean = false,
    val saving: Boolean = false,
)

fun MediaEntry.toClip(): Clip = Clip(
    key = "m:$id",
    kind = kind,
    uri = uri,
    handle = bucket,
    caption = name,
    tag = buildString {
        append(if (kind == Kind.VIDEO) "Video" else "Photo")
        if (kind == Kind.VIDEO && durationMs > 0) append(" · ").append(fmtDuration(durationMs))
        append(" · ").append(fmtSize(size))
    },
    entry = this,
)

fun fmtDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

fun fmtSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
    else -> "${(bytes / 1024).coerceAtLeast(1)} KB"
}

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dpi(v: Int): Int = (v * resources.displayMetrics.density).toInt()
