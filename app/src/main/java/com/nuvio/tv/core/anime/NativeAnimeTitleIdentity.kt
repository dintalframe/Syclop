package com.nuvio.tv.core.anime

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Collections
import java.util.Locale

/**
 * Declared-title identity from one validated exact-MAL mapping, not content authentication.
 * Provider names/queries cannot construct this object. No fuzzy title or external-ID mapping.
 */
class NativeAnimeTitleIdentity private constructor(
    val malId: Long,
    normalizedTitles: Set<String>
) {
    private val titles: Set<String> = Collections.unmodifiableSet(LinkedHashSet(normalizedTitles))
    internal val fingerprint: String = MessageDigest.getInstance("SHA-256")
        .digest(("native-anime-title-v1|$malId|" + titles.sorted().joinToString("|") { "${it.length}:$it" })
            .toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** Title must occur outside release-group brackets and before any episode label. */
    fun matchesRelease(value: String): Boolean {
        if (value.isBlank() || value.length > MAX_RELEASE || value.any(Char::isISOControl)) return false
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
        if (normalized.length > MAX_RELEASE * 4) return false
        val basename = normalized.replace('\\', '/').substringAfterLast('/')
        val visibleTitle = outsideBrackets(basename) ?: return false
        // Keep original positions while masking balanced groups. A leading release group may
        // contain an episode number, but a later episode label cannot make its appended subtitle
        // establish the franchise (including a parenthesized SxxEyy/E label).
        val firstVisibleToken = visibleTitle.indexOfFirst(Char::isLetterOrDigit)
        val boundary = episodeLabel.findAll(basename).firstOrNull { match ->
            visibleTitle[match.range.first] != ' ' || (firstVisibleToken >= 0 && firstVisibleToken < match.range.first)
        }?.range?.first
        val beforeEpisode = boundary?.let { visibleTitle.substring(0, it) } ?: visibleTitle
        val release = " ${normalize(beforeEpisode)} "
        return titles.any { release.contains(" $it ") }
    }

    override fun toString(): String = "NativeAnimeTitleIdentity(validated=true)"

    companion object {
        private const val MAX_RELEASE = 4_096
        private const val MAX_TITLE = 512
        private val words = Regex("[\\p{L}\\p{N}]+")
        private val apostrophe = Regex("['\u2019\u02bc]")
        private val episodeLabel = Regex("(?<![a-z0-9])(?:s[0-9]{1,3}[ ._-]*e[0-9]{1,5}|[0-9]{1,3}x[0-9]{1,5}|e[0-9]{1,5}|-\\s*[0-9]{1,5})(?![a-z0-9])", RegexOption.IGNORE_CASE)
        private val terminalYear = Regex("\\s*\\((?:19|20)[0-9]{2}\\)$")
        private val terminalSeason = Regex("(?:[ .:_-]+s[0-9]{1,3}|[ .:_-]+season[ ._-]*[0-9]{1,3}|[ .:_-]+[0-9]{1,3}(?:st|nd|rd|th)?[ ._-]+season)$", RegexOption.IGNORE_CASE)
        private val generic = setOf("a", "an", "the", "and", "of", "to", "no", "wa", "ga",
            "anime", "animation", "show", "series", "season", "episode", "movie", "tv", "part", "ova", "ona", "special")

        /** Internal only: caller must supply titles from its validated exact-MAL metadata. */
        internal fun create(malId: Long, canonical: String, aliases: List<String>): NativeAnimeTitleIdentity? {
            if (malId !in 1..Int.MAX_VALUE.toLong() || aliases.size > 127) return null
            val variants = linkedSetOf<String>()
            for (raw in listOf(canonical) + aliases) {
                if (raw.isBlank() || raw.length > MAX_TITLE || raw.any(Char::isISOControl)) continue
                val title = Normalizer.normalize(raw, Normalizer.Form.NFKC).trim()
                if (title.length > MAX_TITLE) continue
                // Only explicit terminal season notation or a parenthesized release year
                // is removed. No colon-prefix, arbitrary number/token subset or fuzzy match.
                val withoutYear = terminalYear.replace(title, "").trim()
                for (variant in listOf(title, withoutYear, terminalSeason.replace(title, ""),
                    terminalSeason.replace(withoutYear, ""))) {
                    val normalized = normalize(variant)
                    val tokens = normalized.split(' ').filter(String::isNotBlank)
                    val meaningful = tokens.filter { it !in generic && it.any(Char::isLetter) }
                    if (meaningful.isEmpty() || meaningful.sumOf(String::length) < 4) continue
                    variants += normalized
                }
            }
            return variants.takeIf { it.isNotEmpty() }?.let { NativeAnimeTitleIdentity(malId, it) }
        }

        private fun normalize(value: String): String = words.findAll(apostrophe.replace(
            Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT), ""))
            .joinToString(" ") { it.value }

        private fun outsideBrackets(value: String): String? {
            val stack = ArrayDeque<Char>()
            val result = StringBuilder(value.length)
            for (character in value) when (character) {
                '[', '(' -> {
                    if (stack.size >= 4) return null
                    stack.addLast(character)
                    result.append(' ')
                }
                ']', ')' -> {
                    val expected = if (character == ']') '[' else '('
                    if (stack.removeLastOrNull() != expected) return null
                    result.append(' ')
                }
                else -> result.append(if (stack.isEmpty()) character else ' ')
            }
            return result.toString().takeIf { stack.isEmpty() }
        }
    }
}
