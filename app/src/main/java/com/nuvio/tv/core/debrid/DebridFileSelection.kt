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
): T? {
    if (animeProof != null) return selectNativeAnimeFile(files, resolve, path, isPlayable, animeProof)
    val playable = files.filter(isPlayable)
    if (playable.isEmpty()) return null

    val names = listOfNotNull(resolve.filename, resolve.stream?.raw?.filename)
        .map { it.normalizedPath() }
        .filter { it.isNotBlank() }
        .distinct()
    for (name in names) {
        val matches = playable.matchingFiles(name, path)
        if (matches.isNotEmpty()) return matches.singleOrNull()
    }

    val episodePattern = buildEpisodePattern(season ?: resolve.season, episode ?: resolve.episode)
    if (episodePattern != null) {
        val matches = playable.filter {
            episodePattern.containsMatchIn(path(it).normalizedPath().substringAfterLast('/'))
        }
        if (matches.isNotEmpty()) return matches.singleOrNull()
    }

    if (names.isNotEmpty() || episodePattern != null) return null

    resolve.fileIdx?.let { index ->
        return files.getOrNull(index)?.takeIf(isPlayable)
    }

    return playable.maxByOrNull(size)
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

private fun buildEpisodePattern(season: Int?, episode: Int?): Regex? {
    if (season == null || episode == null) return null
    return Regex(
        "(?<![a-z0-9])(?:s0*${season}e0*${episode}|0*${season}x0*${episode})(?![0-9])",
        RegexOption.IGNORE_CASE,
    )
}

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
