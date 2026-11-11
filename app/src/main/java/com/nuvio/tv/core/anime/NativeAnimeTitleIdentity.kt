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
    normalizedTitles: Set<String>,
    queryTitles: List<String>,
    years: Set<Int>
) {
    private val titles: Set<String> = Collections.unmodifiableSet(LinkedHashSet(normalizedTitles))
    private val searchTitles: List<String> = Collections.unmodifiableList(ArrayList(queryTitles))
    private val trustedYears: Set<Int> = Collections.unmodifiableSet(LinkedHashSet(years))
    internal val verifiedReleaseYear: Int? get() = trustedYears.singleOrNull()
    internal val fingerprint: String = MessageDigest.getInstance("SHA-256")
        .digest(("native-anime-title-v2|$malId|${trustedYears.sorted().joinToString(",")}|" + titles.sorted().joinToString("|") { "${it.length}:$it" })
            .toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** The same immutable exact-ID title set is used for searching and selected-file proof. */
    internal fun queryTitles(): List<String> = searchTitles

    /** Title must occur outside release-group brackets and before any episode label. */
    fun matchesRelease(value: String): Boolean = matchRelease(value).accepted

    internal enum class Match(val accepted: Boolean) {
        NO_MATCH(false), UNQUALIFIED(true), SUPPORTED_YEAR(true), YEAR_CONFLICT(false)
    }

    /** Complete mode is reserved for the independently episode-bound parent title. */
    internal fun matchRelease(value: String, complete: Boolean = false, strictEdition: Boolean = false): Match {
        if (value.isBlank() || value.length > MAX_RELEASE || value.any(Char::isISOControl)) return Match.NO_MATCH
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
        if (normalized.length > MAX_RELEASE * 4) return Match.NO_MATCH
        val basename = normalized.replace('\\', '/').substringAfterLast('/')
        val visibleTitle = outsideBrackets(basename) ?: return Match.NO_MATCH
        // Keep original positions while masking balanced groups. A leading release group may
        // contain an episode number, but a later episode label cannot make its appended subtitle
        // establish the franchise (including a parenthesized SxxEyy/E label).
        val boundary = episodeBoundary(basename, visibleTitle)
        val end = boundary ?: basename.length
        val tokens = positionedWords.findAll(visibleTitle.substring(0, end)).toList()
        val releaseWords = tokens.map { normalize(it.value) }
        var result = Match.NO_MATCH
        for (title in titles) {
            val family = title.split(' ')
            for (start in 0..(tokens.size - family.size)) {
                if (releaseWords[start] != family.first()) continue
                if (releaseWords.subList(start, start + family.size) != family) continue
                var suffix = tokens[start + family.size - 1].range.last + 1
                if (strictEdition && editionMarker.findAll(basename).any {
                    it.range.first < tokens[start].range.first || it.range.last >= suffix
                }) continue
                val releaseYears = linkedSetOf<Int>()
                while (suffix < end) {
                    val qualifier = adjoiningYear.find(basename.substring(suffix, end)) ?: break
                    releaseYears += qualifier.groupValues.drop(1).first { it.isNotEmpty() }.toInt()
                    suffix += qualifier.value.length
                }
                if (complete && (start != 0 || normalize(visibleTitle.substring(suffix, end)).isNotEmpty())) continue
                if (releaseYears.isNotEmpty() && trustedYears.isNotEmpty()) {
                    if (trustedYears.size != 1 || releaseYears != trustedYears) return Match.YEAR_CONFLICT
                    result = Match.SUPPORTED_YEAR
                } else if (result == Match.NO_MATCH) result = Match.UNQUALIFIED
            }
        }
        return result
    }

    internal fun scopedEpisodeSuffix(value: String, requireYear: Boolean): String? {
        val match = matchRelease(value, complete = true, strictEdition = true)
        if (!match.accepted || (requireYear && match != Match.SUPPORTED_YEAR)) return null
        val basename = Normalizer.normalize(value, Normalizer.Form.NFKC).replace('\\', '/').substringAfterLast('/')
        val visible = outsideBrackets(basename) ?: return null
        return episodeBoundary(basename, visible)?.let(basename::substring)
    }

    override fun toString(): String = "NativeAnimeTitleIdentity(validated=true)"

    companion object {
        private const val MAX_RELEASE = 4_096
        private const val MAX_TITLE = 512
        private val words = Regex("[\\p{L}\\p{N}]+")
        private val positionedWords = Regex("[\\p{L}\\p{N}]+(?:['\u2019\u02bc][\\p{L}\\p{N}]+)*")
        private val apostrophe = Regex("['\u2019\u02bc]")
        private val episodeLabel = Regex("(?<![a-z0-9])(?:s[0-9]{1,3}[ ._-]*e[0-9]{1,5}|[0-9]{1,3}x[0-9]{1,5}|e[0-9]{1,5}|-\\s*[0-9]{1,5})(?![a-z0-9])", RegexOption.IGNORE_CASE)
        private val editionMarker = Regex("(?<![a-z0-9])(?:(?:season|part|cour|s)[ ._-]*[0-9]{1,3}|[0-9]{1,3}(?:st|nd|rd|th)?[ ._-]+(?:season|part|cour))(?![a-z0-9])", RegexOption.IGNORE_CASE)
        private val terminalYear = Regex("\\s*\\((?:19|20)[0-9]{2}\\)$")
        private val plainTerminalYear = Regex("[ ._-]+((?:19|20)[0-9]{2})$")
        // Match punctuation discarded by title tokenization, without crossing words or an
        // unrelated bracket group to find a later date. Bracket interiors allow Unicode spaces.
        private val adjoiningYear = Regex("^[^\\p{L}\\p{N}()\\[\\]]*(?:\\([\\p{Z}\\s]*((?:19|20)[0-9]{2})[\\p{Z}\\s]*\\)|\\[[\\p{Z}\\s]*((?:19|20)[0-9]{2})[\\p{Z}\\s]*\\]|((?:19|20)[0-9]{2})(?![\\p{L}\\p{N}]))")
        private val terminalSeason = Regex("(?:[ .:_-]+s[0-9]{1,3}|[ .:_-]+season[ ._-]*[0-9]{1,3}|[ .:_-]+[0-9]{1,3}(?:st|nd|rd|th)?[ ._-]+season)$", RegexOption.IGNORE_CASE)
        private val generic = setOf("a", "an", "the", "and", "of", "to", "no", "wa", "ga",
            "anime", "animation", "show", "series", "season", "episode", "movie", "tv", "part", "ova", "ona", "special")

        /** Internal only: caller must supply titles from its validated exact-MAL metadata. */
        internal fun create(malId: Long, canonical: String, aliases: List<String>,
            preserveEdition: Boolean = false): NativeAnimeTitleIdentity? {
            if (malId !in 1..Int.MAX_VALUE.toLong() || aliases.size > 127) return null
            val variants = linkedSetOf<String>()
            val queryCandidates = linkedMapOf<String, String>()
            val validTitles = mutableListOf<String>()
            val years = linkedSetOf<Int>()
            for (raw in listOf(canonical) + aliases) {
                if (raw.isBlank() || raw.length > MAX_TITLE || raw.any(Char::isISOControl)) continue
                val title = Normalizer.normalize(raw, Normalizer.Form.NFKC).trim()
                if (title.length > MAX_TITLE) continue
                // Only explicit terminal season notation or a parenthesized release year
                // is removed. No colon-prefix, arbitrary number/token subset or fuzzy match.
                val withoutYear = terminalYear.replace(title, "").trim()
                val searchTitle = if (preserveEdition) withoutYear else terminalSeason.replace(withoutYear, "").trim()
                if (meaningful(normalize(withoutYear))) {
                    validTitles += title
                    terminalYear.find(title)?.value?.filter(Char::isDigit)?.toIntOrNull()?.let(years::add)
                }
                val spellings = if (preserveEdition) listOf(title, withoutYear)
                    else listOf(title, withoutYear, terminalSeason.replace(title, ""), searchTitle)
                for (variant in spellings) {
                    val normalized = normalize(variant)
                    if (!meaningful(normalized)) continue
                    variants += normalized
                    // Prefer one narrow season/year-free spelling per title family. Never
                    // manufacture a colon prefix, fuzzy spelling or token-subset alias.
                    if (variant == searchTitle && variant.length <= 256) queryCandidates.putIfAbsent(normalized, variant)
                }
            }
            // A bare numeric title suffix is not itself year evidence (e.g. Blade Runner 2049).
            // A separate complete trusted spelling must establish the year-free family first.
            val completeTitles = validTitles.map(::normalize).toSet()
            for (title in validTitles) plainTerminalYear.find(title)?.let { qualifier ->
                if (normalize(title.substring(0, qualifier.range.first)) in completeTitles)
                    years += qualifier.groupValues[1].toInt()
            }
            return variants.takeIf { it.isNotEmpty() }?.let {
                NativeAnimeTitleIdentity(malId, it, diverseQueries(queryCandidates.values.toList()), years)
            }
        }

        private fun meaningful(value: String): Boolean = value.split(' ')
            .filter { it !in generic && it.any(Char::isLetter) }.sumOf(String::length) >= 4

        /** Keep separate Latin title families and a Japanese-script spelling ahead of duplicates. */
        private fun diverseQueries(candidates: List<String>): List<String> {
            if (candidates.size <= 3) return candidates
            val selected = linkedSetOf(candidates.first())
            val native = candidates.firstOrNull(::hasJapaneseScript)
            candidates.firstOrNull { it !in selected && !hasJapaneseScript(it) }?.let(selected::add)
            native?.let(selected::add)
            candidates.forEach { if (selected.size < 3) selected += it }
            return selected.take(3)
        }

        private fun hasJapaneseScript(value: String): Boolean = value.any { character ->
            Character.UnicodeScript.of(character.code) in setOf(
                Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA)
        }

        private fun normalize(value: String): String = words.findAll(apostrophe.replace(
            Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT), ""))
            .joinToString(" ") { it.value }

        private fun episodeBoundary(basename: String, visible: String): Int? {
            val firstVisible = visible.indexOfFirst(Char::isLetterOrDigit)
            return episodeLabel.findAll(basename).firstOrNull {
                visible[it.range.first] != ' ' || (firstVisible >= 0 && firstVisible < it.range.first)
            }?.range?.first
        }

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
