package com.nuvio.tv.core.streams

import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBadge
import com.google.re2j.Pattern
import com.google.re2j.Matcher
import com.google.re2j.PatternSyntaxException
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNames

const val STREAM_BADGE_IMPORT_LIMIT = 3
private const val MAX_BADGE_FILTERS = 512
private const val MAX_BADGE_PATTERN_LENGTH = 2_048
private const val MAX_BADGE_PATTERN_COST = 65_536
private const val MAX_BADGE_PACK_COST = 262_144
private const val MAX_BADGE_CANDIDATE_LENGTH = 32_768

@Serializable
data class StreamBadgeRules(
    val imports: List<StreamBadgeImport> = emptyList()
) {
    val hasImport: Boolean
        get() = imports.isNotEmpty()

    val activeImport: StreamBadgeImport?
        get() = imports.firstOrNull { it.isActive } ?: imports.firstOrNull()

    val enabledFilterCount: Int
        get() = activeImport?.enabledFilterCount ?: 0

    fun normalized(): StreamBadgeRules {
        val normalizedImports = mutableListOf<StreamBadgeImport>()
        imports.forEach { import ->
            val normalizedUrl = import.sourceUrl.trim()
            if (normalizedUrl.isBlank() || import.filters.isEmpty()) return@forEach
            val normalizedImport = import.copy(sourceUrl = normalizedUrl)
            val existingIndex = normalizedImports.indexOfFirst { it.sourceUrl.equals(normalizedUrl, ignoreCase = true) }
            if (existingIndex >= 0) {
                normalizedImports[existingIndex] = normalizedImport
            } else if (normalizedImports.size < STREAM_BADGE_IMPORT_LIMIT) {
                normalizedImports += normalizedImport
            }
        }
        if (normalizedImports.isEmpty()) return copy(imports = emptyList())
        val activeIndex = normalizedImports.indexOfFirst { it.isActive }.takeIf { it >= 0 } ?: 0
        return copy(
            imports = normalizedImports.mapIndexed { index, import ->
                import.copy(isActive = index == activeIndex)
            }
        )
    }

    fun upsert(import: StreamBadgeImport, activate: Boolean = true): StreamBadgeRules {
        val normalizedUrl = import.sourceUrl.trim()
        if (normalizedUrl.isBlank()) return normalized()
        val normalizedImport = import.copy(sourceUrl = normalizedUrl, isActive = activate)
        val replaced = imports.map { existing ->
            if (existing.sourceUrl.equals(normalizedUrl, ignoreCase = true)) normalizedImport else existing
        }
        val nextImports = if (imports.any { it.sourceUrl.equals(normalizedUrl, ignoreCase = true) }) {
            replaced
        } else {
            imports + normalizedImport
        }
        val activeImports = if (activate) {
            nextImports.map { existing ->
                existing.copy(isActive = existing.sourceUrl.equals(normalizedUrl, ignoreCase = true))
            }
        } else {
            nextImports
        }
        return copy(imports = activeImports).normalized()
    }

    fun setActiveSource(sourceUrl: String): StreamBadgeRules {
        val normalizedUrl = sourceUrl.trim()
        if (normalizedUrl.isBlank() || imports.none { it.sourceUrl.equals(normalizedUrl, ignoreCase = true) }) {
            return normalized()
        }
        return copy(
            imports = imports.map { import ->
                import.copy(isActive = import.sourceUrl.equals(normalizedUrl, ignoreCase = true))
            }
        ).normalized()
    }

    fun forPlayer(sourceUrl: String): StreamBadgeRules =
        if (sourceUrl.isBlank()) normalized() else setActiveSource(sourceUrl)

    fun removeSource(sourceUrl: String): StreamBadgeRules =
        copy(imports = imports.filterNot { it.sourceUrl.equals(sourceUrl.trim(), ignoreCase = true) }).normalized()

    override fun toString(): String =
        "StreamBadgeRules(imports=${imports.size}, activeFilters=$enabledFilterCount, groups=${imports.sumOf { it.groups.size }})"
}

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class StreamBadgeImport(
    val sourceUrl: String = "",
    val filters: List<StreamBadgeFilter> = emptyList(),
    val groups: List<StreamBadgeGroup> = emptyList(),
    @EncodeDefault
    @SerialName("isActive")
    @JsonNames("active")
    val isActive: Boolean = true
) {
    val enabledFilterCount: Int
        get() = filters.count { it.isEnabled }

    override fun toString(): String =
        "StreamBadgeImport(sourceUrl=$sourceUrl, filters=${filters.size}, groups=${groups.size}, isActive=$isActive)"
}

@Serializable
data class StreamBadgeFilter(
    val id: String = "",
    val groupId: String = "",
    val name: String = "",
    val pattern: String = "",
    val imageURL: String = "",
    val isEnabled: Boolean = true,
    val tagColor: String = "",
    val tagStyle: String = "",
    val textColor: String = "",
    val borderColor: String = ""
)

@Serializable
data class StreamBadgeGroup(
    val id: String = "",
    val name: String = "",
    val color: String = "",
    val isExpanded: Boolean = true
)

sealed interface StreamBadgeImportResult {
    data class Success(val rules: StreamBadgeRules) : StreamBadgeImportResult
    data class Error(val message: String) : StreamBadgeImportResult
}

class StreamBadgePatternException(message: String) : IllegalArgumentException(message)

private data class BadgePattern(
    val include: Pattern,
    val prefixExclusions: List<Pattern>,
    val suffixExclusions: List<Pattern>,
    val consumesBoundary: Boolean,
    val cost: Int
)

private data class BadgePatternShape(val groups: List<IntRange>, val hasTopLevelAlternative: Boolean, val cost: Int)

private val badgeFlags = Regex("^\\(\\?[ims-]+\\)")
private val badgeRepeat = Regex("\\{([0-9]+)(?:,([0-9]*))?}")

private fun badgePatternShape(text: String): BadgePatternShape {
    if (text.length > MAX_BADGE_PATTERN_LENGTH) throw StreamBadgePatternException("Badge pattern exceeds the size limit.")
    val groups = mutableListOf<IntRange>()
    var depth = 0
    var start = 0
    var characterClass = false
    var literal = false
    var alternative = false
    var repeatCost = 1
    var index = 0
    while (index < text.length) {
        val char = text[index]
        if (char == '\\') {
            if (!characterClass && text.getOrNull(index + 1) == 'Q') literal = true
            if (text.getOrNull(index + 1) == 'E') literal = false
            index += 2
            continue
        }
        if (!literal) when (char) {
            '[' -> characterClass = true
            ']' -> characterClass = false
            '(' -> if (!characterClass) {
                if (text.startsWith("(?", index)) {
                    val flags = text.substring(index + 2).takeWhile { it.isLetter() || it == '-' }
                    if ('U' in flags || 'u' in flags) {
                        throw StreamBadgePatternException("Badge pattern uses unsupported Unicode regex flags.")
                    }
                }
                if (depth++ == 0) start = index
                if (depth > 32) throw StreamBadgePatternException("Badge pattern exceeds the nesting limit.")
            }
            ')' -> if (!characterClass) {
                depth--
                if (depth < 0) throw StreamBadgePatternException("Badge pattern has unbalanced groups.")
                if (depth == 0) groups += start..index
            }
            '|' -> if (!characterClass && depth == 0) alternative = true
            '{' -> if (!characterClass) {
                val repeat = badgeRepeat.find(text, index)?.takeIf { it.range.first == index }
                if (repeat != null) {
                    val count = (repeat.groups[2]?.value?.ifEmpty { repeat.groupValues[1] }
                        ?: repeat.groupValues[1]).toIntOrNull()
                        ?: throw StreamBadgePatternException("Badge pattern repeat exceeds the limit.")
                    val expansion = count.coerceAtLeast(1)
                    if (expansion > MAX_BADGE_PATTERN_COST / repeatCost / text.length.coerceAtLeast(1)) {
                        throw StreamBadgePatternException("Badge pattern is too complex.")
                    }
                    repeatCost *= expansion
                    index = repeat.range.last
                }
            }
        }
        index++
    }
    if (depth != 0 || characterClass || literal) throw StreamBadgePatternException("Badge pattern is incomplete.")
    return BadgePatternShape(groups, alternative, text.length * repeatCost)
}

private fun compileBadgePattern(text: String, remainingCost: Int = MAX_BADGE_PACK_COST): BadgePattern {
    val shape = badgePatternShape(text)
    if (shape.cost > remainingCost) throw StreamBadgePatternException("Badge pack is too complex.")
    val flags = badgeFlags.find(text)?.value.orEmpty()
    var body = text.removePrefix(flags)
    val prefixExclusions = mutableListOf<String>()
    val suffixExclusions = mutableListOf<String>()
    if (body.startsWith('^')) {
        var offset = 1
        val groups = badgePatternShape(body).groups
        while (body.startsWith("(?!.*", offset)) {
            val group = groups.firstOrNull { it.first == offset }
                ?: throw StreamBadgePatternException("Badge pattern has an unsupported assertion.")
            prefixExclusions += body.substring(offset + 3, group.last)
            offset = group.last + 1
        }
        if (offset > 1) body = "^" + body.substring(offset)
    }
    while (true) {
        val group = badgePatternShape(body).groups.lastOrNull()
        if (group == null || group.last != body.lastIndex || !body.startsWith("(?!.*", group.first)) break
        suffixExclusions += body.substring(group.first + 3, group.last)
        body = body.substring(0, group.first)
    }
    val consumesBoundary = body.endsWith("(?= |$)")
    if (consumesBoundary) body = body.removeSuffix("(?= |$)") + "(?: |$)"
    if ((prefixExclusions.isNotEmpty() || suffixExclusions.isNotEmpty() || consumesBoundary) &&
        badgePatternShape(body).hasTopLevelAlternative) {
        throw StreamBadgePatternException("Badge pattern has an unsupported assertion around alternatives.")
    }
    return try {
        BadgePattern(
            include = Pattern.compile(flags + body),
            prefixExclusions = prefixExclusions.map { Pattern.compile(flags + it) },
            suffixExclusions = suffixExclusions.map { Pattern.compile(flags + it) },
            consumesBoundary = consumesBoundary,
            cost = shape.cost
        )
    } catch (_: PatternSyntaxException) {
        throw StreamBadgePatternException("Badge pattern uses unsupported syntax. Use regular expressions without backreferences or additional lookarounds.")
    }
}

fun StreamBadgeRules.validatePatterns() {
    var remainingCost = MAX_BADGE_PACK_COST
    imports.forEach { imported ->
        if (imported.filters.size > MAX_BADGE_FILTERS) throw StreamBadgePatternException("Badge pack has too many filters.")
        imported.filters.filter { it.name.isNotBlank() && it.pattern.isNotBlank() }.forEach {
            remainingCost -= compileBadgePattern(it.pattern, remainingCost).cost
        }
    }
}

internal object StreamBadgeRulesParser {
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun parse(sourceUrl: String, payload: String): StreamBadgeImport {
        val decoded = try {
            json.decodeFromString<StreamBadgePayload>(payload)
        } catch (error: SerializationException) {
            throw IllegalArgumentException("Invalid badge JSON: ${error.message.orEmpty()}")
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid badge JSON: ${error.message.orEmpty()}")
        }

        val filters = decoded.filters.mapNotNull { filter ->
            val name = filter.name.orEmpty().trim()
            val pattern = filter.pattern.orEmpty().trim()
            if (name.isBlank() || pattern.isBlank()) {
                return@mapNotNull null
            }

            StreamBadgeFilter(
                id = filter.id.orEmpty(),
                groupId = filter.groupId.orEmpty(),
                name = name,
                pattern = pattern,
                imageURL = filter.imageURL.orEmpty(),
                isEnabled = filter.isEnabled ?: true,
                tagColor = filter.tagColor.orEmpty(),
                tagStyle = filter.tagStyle.orEmpty(),
                textColor = filter.textColor.orEmpty(),
                borderColor = filter.borderColor.orEmpty()
            )
        }

        if (filters.isEmpty()) {
            throw IllegalArgumentException("Badge import did not contain any usable filters.")
        }

        val groups = decoded.groups.map { group ->
            StreamBadgeGroup(
                id = group.id.orEmpty(),
                name = group.name.orEmpty(),
                color = group.color.orEmpty(),
                isExpanded = group.isExpanded ?: true
            )
        }

        return StreamBadgeImport(
            sourceUrl = sourceUrl.trim(),
            filters = filters,
            groups = groups
        ).also { StreamBadgeRules(imports = listOf(it)).validatePatterns() }
    }
}

internal object StreamBadgeMatcher {
    fun compile(rules: StreamBadgeRules): List<CompiledStreamBadgeFilter> {
        if (!rules.hasImport) return emptyList()
        var remainingCost = MAX_BADGE_PACK_COST
        return rules.normalized().imports.filter { it.isActive }.flatMap { import ->
            if (import.filters.size > MAX_BADGE_FILTERS) return@flatMap emptyList()
            import.filters.mapNotNull { filter ->
                if (!filter.isEnabled || filter.name.isBlank() || filter.pattern.isBlank()) {
                    return@mapNotNull null
                }
                val pattern = try {
                    compileBadgePattern(filter.pattern, remainingCost)
                } catch (_: StreamBadgePatternException) { return@mapNotNull null }
                remainingCost -= pattern.cost
                CompiledStreamBadgeFilter(
                    name = filter.name,
                    badge = StreamBadge(
                        name = filter.name,
                        imageURL = filter.imageURL,
                        tagColor = filter.tagColor,
                        tagStyle = filter.tagStyle,
                        textColor = filter.textColor,
                        borderColor = filter.borderColor
                    ),
                    pattern = pattern.include,
                    prefixExclusions = pattern.prefixExclusions,
                    suffixExclusions = pattern.suffixExclusions,
                    consumesBoundary = pattern.consumesBoundary,
                    // Extract a short literal hint from the pattern for fast pre-screening.
                    // If the candidate doesn't contain this literal (case-insensitive),
                    // we skip the expensive regex entirely.
                    literalHint = extractLiteralHint(pattern.include.pattern())
                )
            }
        }
    }

    private fun extractLiteralHint(pattern: String): String? {
        // For simple patterns without regex metacharacters, use the whole thing.
        val metaChars = "\\[](){}*+?|^$."
        if (pattern.length >= 2 && pattern.none { it in metaChars }) {
            return pattern.lowercase()
        }
        if ('|' in pattern) return null
        val stripped = pattern
            .replace("\\b", "")
            .replace("(?i)", "")
            .replace("(?:", "")
            .replace("(", "")
            .replace(")", "")
        if (stripped.length >= 2 && stripped.none { it in metaChars }) {
            return stripped.lowercase()
        }
        return null
    }

    fun matchedNames(stream: Stream, rules: StreamBadgeRules): List<String> =
        matchedNames(stream, compile(rules))

    fun matchedNames(stream: Stream, filters: List<CompiledStreamBadgeFilter>): List<String> {
        return matchedBadges(stream, filters).map { it.name }
    }

    fun matchedBadges(stream: Stream, filters: List<CompiledStreamBadgeFilter>): List<StreamBadge> {
        if (filters.isEmpty()) return emptyList()
        return matchedBadges(badgeMatchCandidates(stream), filters)
    }

    fun matchedBadges(candidates: List<String>, filters: List<CompiledStreamBadgeFilter>): List<StreamBadge> {
        if (filters.isEmpty() || candidates.isEmpty()) return emptyList()

        val matched = linkedMapOf<String, StreamBadge>()
        // Reuse a single Matcher instance per filter across all candidates.
        filters.forEach { filter ->
            if (matchesAnyCandidateReusable(filter, candidates)) {
                val key = filter.badge.dedupeKey()
                if (key !in matched) matched[key] = filter.badge
            }
        }
        return matched.values.toList()
    }

    fun matchedBadgesPooled(
        stream: Stream,
        filters: List<CompiledStreamBadgeFilter>,
        pool: HashMap<String, StreamBadge>
    ): List<StreamBadge> {
        if (filters.isEmpty()) return emptyList()
        val candidates = badgeMatchCandidates(stream)
        if (candidates.isEmpty()) return emptyList()

        // Fast path: collect matches without allocating a map when possible.
        var firstMatch: StreamBadge? = null
        var matched: LinkedHashMap<String, StreamBadge>? = null

        filters.forEach { filter ->
            if (matchesAnyCandidateReusable(filter, candidates)) {
                val key = filter.badge.dedupeKey()
                val pooled = pool.getOrPut(key) { filter.badge }
                when {
                    firstMatch == null && matched == null -> {
                        firstMatch = pooled
                    }
                    matched == null -> {
                        // Second match — promote to map.
                        val firstKey = firstMatch!!.dedupeKey()
                        if (key == firstKey) return@forEach // duplicate
                        matched = LinkedHashMap<String, StreamBadge>(4).also {
                            it[firstKey] = firstMatch!!
                            it[key] = pooled
                        }
                    }
                    else -> {
                        matched!!.putIfAbsent(key, pooled)
                    }
                }
            }
        }

        return when {
            matched != null -> matched!!.values.toList()
            firstMatch != null -> listOf(firstMatch!!)
            else -> emptyList()
        }
    }

    private fun matchesAnyCandidateReusable(
        filter: CompiledStreamBadgeFilter,
        candidates: List<String>
    ): Boolean {
        val hint = filter.literalHint
        val matcher = filter.threadLocalMatcher.get() ?: return false
        val prefixMatchers = filter.prefixMatchers.get()
        val suffixMatchers = filter.suffixMatchers.get()
        for (candidate in candidates) {
            if (candidate.length > MAX_BADGE_CANDIDATE_LENGTH) continue
            // Fast pre-screen: skip regex if the literal hint isn't present.
            if (hint != null && !containsIgnoreCase(candidate, hint)) continue
            matcher.reset(candidate)
            var assertionBudget = candidate.length.coerceAtLeast(1).toLong() * 16
            while (matcher.find()) {
                val start = matcher.start()
                val end = matcher.end() - if (filter.consumesBoundary && matcher.end() > start && candidate[matcher.end() - 1] == ' ') 1 else 0
                val assertionCost = prefixMatchers.size.toLong() * (candidate.length - start) +
                    suffixMatchers.size.toLong() * (candidate.length - end)
                if (assertionCost > assertionBudget) break
                assertionBudget -= assertionCost
                val prefixExcluded = prefixMatchers.any { it.reset(candidate.substring(start)).lookingAt() }
                val suffixExcluded = suffixMatchers.any { it.reset(candidate.substring(end)).lookingAt() }
                if (!prefixExcluded && !suffixExcluded) return true
            }
        }
        return false
    }

    /**
     * Case-insensitive contains check without allocating a lowercase copy.
     * Scans for [hint] (already lowercase) within [text] using char-by-char comparison.
     */
    private fun containsIgnoreCase(text: String, hint: String): Boolean {
        val hintLen = hint.length
        val textLen = text.length
        if (hintLen > textLen) return false
        val limit = textLen - hintLen
        outer@ for (i in 0..limit) {
            for (j in 0 until hintLen) {
                val tc = text[i + j].lowercaseChar()
                if (tc != hint[j]) continue@outer
            }
            return true
        }
        return false
    }

    private fun badgeMatchCandidates(stream: Stream): List<String> {
        val resolve = stream.clientResolve
        val raw = resolve?.stream?.raw
        val parsed = raw?.parsed
        val candidates = listOfNotNull(
            raw?.filename,
            resolve?.filename,
            stream.behaviorHints?.filename,
            stream.debridCacheStatus?.cachedName,
            raw?.torrentName,
            resolve?.torrentName,
            stream.name,
            stream.title,
            stream.description,
            parsed?.rawTitle,
            parsed?.parsedTitle,
            parsed?.resolution,
            parsed?.quality,
            parsed?.codec,
            parsed?.edition,
            parsed?.audio?.joinToString(" "),
            parsed?.channels?.joinToString(" "),
            parsed?.hdr?.joinToString(" "),
            parsed?.group,
            stream.addonName
        )
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        return if (candidates.size <= 1) {
            candidates
        } else {
            candidates + candidates.joinToString(" ")
        }
    }
}

class CompiledStreamBadgeFilter(
    val name: String,
    val badge: StreamBadge,
    val pattern: Pattern,
    val literalHint: String?,
    private val prefixExclusions: List<Pattern> = emptyList(),
    private val suffixExclusions: List<Pattern> = emptyList(),
    internal val consumesBoundary: Boolean = false
) {
    /**
     * Per-thread reusable Matcher. [Matcher.reset] is called before each use
     * to rebind to a new input without allocating a new Matcher object.
     * ThreadLocal ensures thread-safety when matching runs on Dispatchers.Default
     * (which may use multiple threads from the coroutine pool).
     */
    internal val threadLocalMatcher: ThreadLocal<Matcher> = ThreadLocal.withInitial {
        pattern.matcher("")
    }
    internal val prefixMatchers: ThreadLocal<List<Matcher>> = ThreadLocal.withInitial {
        prefixExclusions.map { it.matcher("") }
    }
    internal val suffixMatchers: ThreadLocal<List<Matcher>> = ThreadLocal.withInitial {
        suffixExclusions.map { it.matcher("") }
    }
}

internal fun StreamBadge.dedupeKey(): String =
    imageURL.takeIf { it.isNotBlank() } ?: name

@Serializable
private data class StreamBadgePayload(
    val filters: List<StreamBadgeFilterPayload> = emptyList(),
    val groups: List<StreamBadgeGroupPayload> = emptyList()
)

@Serializable
private data class StreamBadgeFilterPayload(
    val id: String? = null,
    val groupId: String? = null,
    val name: String? = null,
    val pattern: String? = null,
    val imageURL: String? = null,
    val isEnabled: Boolean? = null,
    val tagColor: String? = null,
    val tagStyle: String? = null,
    val textColor: String? = null,
    val borderColor: String? = null,
    val type: String? = null
)

@Serializable
private data class StreamBadgeGroupPayload(
    val id: String? = null,
    val name: String? = null,
    val color: String? = null,
    val isExpanded: Boolean? = null
)
