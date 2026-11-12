package com.nuvio.tv.core.debrid

import com.nuvio.tv.domain.model.StreamClientResolve

internal fun <T> selectDebridFile(
    files: List<T>,
    resolve: StreamClientResolve,
    season: Int?,
    episode: Int?,
    path: (T) -> String,
    isPlayable: (T) -> Boolean,
    size: (T) -> Long,
    animeProof: com.nuvio.tv.core.anime.NativeAnimeEpisodeProof? = null,
    turkishBroadcastEpisode: TurkishBroadcastEpisode? = null,
): T? {
    if (animeProof != null) return selectNativeAnimeFile(files, resolve, path, isPlayable, animeProof)
    val playable = files.filter(isPlayable)
    if (playable.isEmpty()) return null

    val requestedSeason = season ?: resolve.season
    val requestedEpisode = episode ?: resolve.episode
    if (requestedSeason != null || requestedEpisode != null) {
        val broadcast = turkishBroadcastEpisode?.takeIf {
            it.matchesContext(resolve.mediaId ?: it.videoId, requestedSeason, requestedEpisode, resolve.mediaType)
        }
        return selectEpisodeFile(files, resolve, requestedSeason, requestedEpisode, path, isPlayable, broadcast)
    }

    val names = listOfNotNull(resolve.filename, resolve.stream?.raw?.filename)
        .map { it.normalizedPath() }
        .filter { it.isNotBlank() }
        .distinct()
    for (name in names) {
        val matches = playable.matchingFiles(name, path)
        if (matches.isNotEmpty()) return matches.singleOrNull()
    }

    if (names.isNotEmpty()) return null

    resolve.fileIdx?.let { index ->
        return files.getOrNull(index)?.takeIf(isPlayable)
    }

    return playable.maxByOrNull(size)
}

/** An episode request must select one actual leaf; provider hints never override its coordinates. */
private fun <T> selectEpisodeFile(files: List<T>, resolve: StreamClientResolve, season: Int?, episode: Int?,
    path: (T) -> String, isPlayable: (T) -> Boolean, broadcast: TurkishBroadcastEpisode?): T? {
    if (season == null || season < 0 || episode == null || episode <= 0) return null
    val indexed = files.withIndex().toList()
    val names = listOfNotNull(resolve.filename, resolve.stream?.raw?.filename)
        .map { it.normalizedPath() }.filter(String::isNotBlank).distinct()
    var selected: IndexedValue<T>? = null
    for (name in names) {
        val matches = indexed.matchingFiles(name) { path(it.value) }
        if (matches.isEmpty()) continue // A missing pack-level hint may still have one exact leaf.
        val target = matches.singleOrNull() ?: return null
        if (!isPlayable(target.value) || !path(target.value).matchesSingleEpisode(season, episode, broadcast)) return null
        if (selected != null && selected.index != target.index) return null
        selected = target
    }
    selected?.let { return it.value }
    // Torrent indices are not consistent across services. They cannot select another episode
    // or disambiguate multiple encodes without an actual matching filename.
    return indexed.filter { isPlayable(it.value) && path(it.value).matchesSingleEpisode(season, episode, broadcast) }
        .singleOrNull()?.value
}

/** Every explicit hint must identify the same actual proof-matching playable file. */
private fun <T> selectNativeAnimeFile(files: List<T>, resolve: StreamClientResolve, path: (T) -> String,
    isPlayable: (T) -> Boolean, proof: com.nuvio.tv.core.anime.NativeAnimeEpisodeProof): T? {
    val playable = files.withIndex().filter { isPlayable(it.value) }
    if (playable.isEmpty()) return null
    val names = listOfNotNull(resolve.filename, resolve.stream?.raw?.filename).map { it.normalizedPath() }
    if (names.any { it.isBlank() || it.length > 4_096 }) return null
    var selected: IndexedValue<T>? = null
    for (name in names.distinct()) {
        val target = playable.matchingFiles(name) { path(it.value) }.singleOrNull() ?: return null
        if (selected != null && selected.index != target.index) return null
        selected = target
    }
    resolve.fileIdx?.let { index ->
        val target = playable.singleOrNull { it.index == index } ?: return null
        if (selected != null && selected.index != target.index) return null
        selected = target
    }
    val target = selected ?: playable.filter { proof.matchesFile(path(it.value)) }.singleOrNull() ?: return null
    return target.value.takeIf { proof.matchesFile(path(it)) }
}

private fun String.normalizedPath(): String = trim().replace('\\', '/').removePrefix("/")

private fun <T> List<T>.matchingFiles(name: String, path: (T) -> String): List<T> {
    for (ignoreCase in listOf(false, true)) {
        val matches = filter {
            val filePath = path(it).normalizedPath()
            filePath.equals(name, ignoreCase = ignoreCase) ||
                (name.contains('/') && filePath.endsWith("/$name", ignoreCase = ignoreCase))
        }
        if (matches.isNotEmpty()) return matches
    }
    val basename = name.substringAfterLast('/')
    for (ignoreCase in listOf(false, true)) {
        val matches = filter {
            path(it).normalizedPath().substringAfterLast('/').equals(basename, ignoreCase = ignoreCase)
        }
        if (matches.isNotEmpty()) return matches
    }
    return emptyList()
}

internal fun TurkishBroadcastEpisode.matchesFile(path: String): Boolean = path.matchesSingleEpisode(season, episode, this)

internal fun TurkishBroadcastEpisode.matchesBroadcastLabel(path: String): Boolean =
    path.matchesSingleEpisode(season, episode, this, requireBroadcastLabel = true)

private fun String.matchesSingleEpisode(season: Int, episode: Int, broadcast: TurkishBroadcastEpisode?,
    requireBroadcastLabel: Boolean = false): Boolean {
    val basename = normalizedPath().substringAfterLast('/')
    if (basename.isBlank() || basename.length > 4_096 || basename.any(Char::isISOControl) ||
        TurkishEpisodeLabels.hasCollection(basename) || TurkishEpisodeLabels.hasClip(basename)) return false
    val file = TurkishEpisodeLabels.normalizeCoordinates(basename)
    val label = broadcast?.let { TurkishEpisodeLabels.findBroadcastLabel(file, it) }
    if (requireBroadcastLabel && label == null) return false
    val relativeFile = file.withoutLabel(label)
    val pairs = episodeCoordinates.findAll(relativeFile).take(2).toList()
    if (pairs.size > 1 || (pairs.isEmpty() && label == null)) return false
    val pair = pairs.singleOrNull()
    if (pair != null && (pair.groupValues[1].ifEmpty { pair.groupValues[3] }.toIntOrNull() != season ||
        pair.groupValues[2].ifEmpty { pair.groupValues[4] }.toIntOrNull() != episode || relativeFile.hasEpisodeContinuation(pair))) return false
    if (label != null && file.withoutLabel(pair).hasEpisodeContinuation(label)) return false
    val remainder = relativeFile.withoutLabel(pair)
    return !additionalEpisodeLabel.containsMatchIn(remainder) &&
        !TurkishEpisodeLabels.hasSeason(remainder) && !TurkishEpisodeLabels.hasEpisode(remainder)
}

private fun String.withoutLabel(match: MatchResult?): String =
    if (match == null) this else replaceRange(match.range, " ".repeat(match.value.length))

private fun String.hasEpisodeContinuation(label: MatchResult): Boolean =
    episodeContinuation.find(substring(label.range.last + 1))?.groupValues?.get(1)
        ?.let { !episodeQualitySuffix.matches(it) } ?: false

internal val episodeCoordinates = Regex(
    "(?<![a-z0-9])(?:s([0-9]{1,10})e([0-9]{1,10})|([0-9]{1,10})x([0-9]{1,10}))(?![a-z0-9])",
    RegexOption.IGNORE_CASE)
private val episodeContinuation = Regex(
    "^(?:[ ._-]+|[~+&]|to)[ ._-]*(?:(?:s[0-9]+e|e)[ ._-]*)?([0-9]+[a-z0-9]*)(?![a-z0-9])",
    RegexOption.IGNORE_CASE)
private val episodeQualitySuffix = Regex(
    "(?:360|480|576|720|1080|1440|2160|4320)p|(?:8|10|12)bit|(?:24|25|30|50|60|120)fps",
    RegexOption.IGNORE_CASE)
// After masking the sole pair, a residual E05E06/S02E05E06 is still another
// coordinate claim; requiring a trailing word boundary would hide those chains.
private val additionalEpisodeLabel = Regex(
    "(?<![a-z0-9])(?:s[0-9]+|season[ ._-]*[0-9]+|(?:e|ep|episode)[ ._-]*[0-9]+|[0-9]+x[0-9]+)",
    RegexOption.IGNORE_CASE)

internal fun String.hasDebridVideoExtension(): Boolean = videoExtensions.any { endsWith(it, ignoreCase = true) }

private val videoExtensions = setOf(
    ".mp4",
    ".mkv",
    ".webm",
    ".avi",
    ".mov",
    ".m4v",
    ".ts",
    ".m2ts",
    ".wmv",
    ".flv",
)
