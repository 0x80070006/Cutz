package fr.cutz.util

import java.net.URI

/**
 * Pure-JVM URL validation (android.util.Patterns is not available in local unit tests
 * and accepts too much). Accepts http/https with a host; a missing scheme gets https.
 */
object UrlValidator {
    private val HOST = Regex("^(localhost|[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+|\\[[0-9A-Fa-f:.]+])$")

    /** @return the normalised URL, or null if invalid. */
    fun normalize(input: String): String? {
        var s = input.trim()
        if (s.isEmpty() || s.any { it.isWhitespace() }) return null
        if (!s.contains("://")) s = "https://$s"
        val uri = runCatching { URI(s) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host ?: return null
        if (!HOST.matches(host)) return null
        return s
    }

    fun isValid(input: String) = normalize(input) != null
}
