package com.reelvault.app

import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

/** Turns TikTok, X, YouTube Shorts and Instagram Reels links into something the feed can play. */
object LinkResolver {
    class LinkError(message: String) : Exception(message)

    enum class Platform(val label: String) {
        TIKTOK("TikTok"), X("X"), YOUTUBE("YouTube Shorts"), INSTAGRAM("Instagram Reels")
    }

    data class Parsed(val platform: Platform, val link: String, val id: String?)

    data class Resolved(
        val platform: Platform,
        val link: String,
        val videoUrl: String?,
        val embedUrl: String?,
        val cover: String?,
        val caption: String?,
        val author: String?,
    ) {
        fun toClip(): Clip = Clip(
            key = "link:$link",
            kind = if (videoUrl != null) Kind.VIDEO else Kind.EMBED,
            uri = videoUrl?.let(Uri::parse),
            embedUrl = embedUrl,
            link = link,
            cover = cover,
            handle = author ?: platform.label,
            caption = caption.orEmpty(),
            tag = if (videoUrl != null) "${platform.label} · not saved yet" else "${platform.label} · plays in their player",
            savable = videoUrl != null,
        )
    }

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    fun parse(text: String): Parsed? {
        val urls = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE).findAll(text).map { it.value }.toMutableList()
        if (urls.isEmpty() && Regex("""^[\w.-]+\.\w{2,}/""").containsMatchIn(text.trim())) urls += "https://" + text.trim()
        for (raw in urls) {
            val cleaned = raw.trimEnd(')', '.', ',', '!', '?')
            val uri = runCatching { URI(cleaned) }.getOrNull() ?: continue
            val full = uri.host?.lowercase() ?: continue
            val host = full.removePrefix("www.").removePrefix("m.").removePrefix("mobile.")
            val path = uri.path.orEmpty()

            if (full == "tiktok.com" || full.endsWith(".tiktok.com")) return Parsed(Platform.TIKTOK, cleaned, null)

            if (host in setOf("x.com", "twitter.com", "fxtwitter.com", "vxtwitter.com", "fixupx.com")) {
                Regex("""/status(?:es)?/(\d+)""").find(path)?.let { return Parsed(Platform.X, cleaned, it.groupValues[1]) }
            }
            if (host == "youtube.com" || host == "youtu.be" || host == "music.youtube.com") {
                val id = Regex("""/(?:shorts|embed|live)/([\w-]{11})""").find(path)?.groupValues?.get(1)
                    ?: (if (host == "youtu.be") Regex("""^/([\w-]{11})""").find(path)?.groupValues?.get(1) else null)
                    ?: uri.rawQuery?.split("&")?.firstOrNull { it.startsWith("v=") }?.substringAfter("v=")
                if (id != null && Regex("""^[\w-]{11}$""").matches(id)) return Parsed(Platform.YOUTUBE, cleaned, id)
            }
            if (host == "instagram.com") {
                Regex("""/(?:reel|reels|p|tv)/([\w-]+)""").find(path)?.let {
                    return Parsed(Platform.INSTAGRAM, cleaned, it.groupValues[1])
                }
            }
        }
        return null
    }

    /** Blocking network call: run off the main thread. */
    fun resolve(text: String): Resolved {
        val p = parse(text) ?: throw LinkError("Paste a TikTok, Instagram Reel, YouTube Short or X (Twitter) video link.")
        return when (p.platform) {
            Platform.TIKTOK -> tiktok(p)
            Platform.X -> x(p)
            Platform.YOUTUBE -> Resolved(
                p.platform, p.link, null,
                "https://www.youtube.com/embed/${p.id}?autoplay=1&playsinline=1&loop=1&playlist=${p.id}&rel=0",
                "https://i.ytimg.com/vi/${p.id}/hqdefault.jpg", "YouTube Short", null,
            )
            Platform.INSTAGRAM -> Resolved(
                p.platform, p.link, null, "https://www.instagram.com/reel/${p.id}/embed/", null, "Instagram Reel", null,
            )
        }
    }

    private fun tiktok(p: Parsed): Resolved {
        val j = getJson("https://www.tikwm.com/api/?hd=1&url=" + URLEncoder.encode(p.link, "UTF-8"))
        if (j.optInt("code", -1) != 0) {
            val msg = j.optString("msg").takeIf { it.isNotBlank() }
            throw LinkError(if (msg != null) "TikTok: $msg" else "Couldn't find a video at that TikTok link.")
        }
        val d = j.getJSONObject("data")
        fun abs(s: String?) = s?.takeIf { it.isNotBlank() }?.let { if (it.startsWith("http")) it else "https://www.tikwm.com$it" }
        val video = abs(d.optString("hdplay")) ?: abs(d.optString("play"))
            ?: throw LinkError("That TikTok is a photo slideshow, not a video.")
        val author = d.optJSONObject("author")?.optString("unique_id")?.takeIf { it.isNotBlank() }?.let { "@$it" }
        return Resolved(p.platform, p.link, video, null, abs(d.optString("cover")), d.optString("title").takeIf { it.isNotBlank() }, author)
    }

    private fun x(p: Parsed): Resolved {
        val j = getJson("https://api.fxtwitter.com/i/status/${p.id}")
        val tweet = j.optJSONObject("tweet") ?: throw LinkError("Couldn't find that post on X. It may be private or deleted.")
        val videos = tweet.optJSONObject("media")?.optJSONArray("videos")
        if (videos == null || videos.length() == 0) throw LinkError("That post on X has no video.")
        var video = videos.getJSONObject(0)
        for (i in 0 until videos.length()) {
            val v = videos.getJSONObject(i)
            if (v.optString("type") == "video") { video = v; break }
        }
        val url = video.optString("url").takeIf { it.isNotBlank() } ?: throw LinkError("That post on X has no video.")
        val author = tweet.optJSONObject("author")?.optString("screen_name")?.takeIf { it.isNotBlank() }?.let { "@$it" }
        return Resolved(
            p.platform, p.link, url, null, video.optString("thumbnail_url").takeIf { it.isNotBlank() },
            tweet.optString("text").takeIf { it.isNotBlank() }, author,
        )
    }

    private fun getJson(url: String): JSONObject {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/json")
            connectTimeout = 15_000
            readTimeout = 20_000
        }
        try {
            val code = c.responseCode
            if (code == 404) throw LinkError("Couldn't find that video. It may be private or deleted.")
            if (code >= 400) throw LinkError("The video service answered $code. Try again in a moment.")
            return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }
}
