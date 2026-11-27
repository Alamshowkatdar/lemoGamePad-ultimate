package com.alamshowkat.lemogamepad

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Best-effort GitHub release check for the Android app.
 *
 * It runs on a background thread, never installs anything, and deliberately
 * treats every network/parsing failure as "no update" so pairing and playing
 * keep working offline.
 */
internal object ReleaseChecker {

    internal data class Release(
        val tag: String,
        val version: String,
        val name: String,
        val url: String,
    )

    private const val API_URL =
        "https://api.github.com/repos/Alamshowkatdar/lemoGamePad-ultimate/releases/latest"
    private const val RELEASES_URL =
        "https://github.com/Alamshowkatdar/lemoGamePad-ultimate/releases"
    private const val TIMEOUT_MS = 6_000

    private val tagPattern = Regex("^[A-Za-z0-9._-]+$")
    private val numberPattern = Regex("\\d+")

    private fun numbers(value: String): List<Int> {
        val found = numberPattern.findAll(value).map { it.value.toInt() }.toList()
        return found.ifEmpty { listOf(0) }
    }

    /** True when [latest] is a newer release than [current]. */
    fun isNewer(latest: String, current: String): Boolean {
        val a = numbers(latest)
        val b = numbers(current)
        for (index in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(index) { 0 }
            val right = b.getOrElse(index) { 0 }
            if (left != right) return left > right
        }
        return false
    }

    /** Fetch the latest published release, returning null unless it is newer. */
    fun latest(current: String): Release? {
        val connection = URL(API_URL).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "lemoGamePad-android")
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null

            val body = connection.inputStream.use { stream ->
                stream.bufferedReader().use { it.readText() }
            }
            val json = JSONObject(body)
            val tag = json.optString("tag_name", "").trim()
            if (tag.isEmpty() || !tagPattern.matches(tag) || !isNewer(tag, current)) return null

            val version = tag.removePrefix("v")
            val name = json.optString("name", "").trim().ifEmpty { "v$version" }
            Release(
                tag = tag,
                version = version,
                name = name,
                url = "$RELEASES_URL/tag/$tag",
            )
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
