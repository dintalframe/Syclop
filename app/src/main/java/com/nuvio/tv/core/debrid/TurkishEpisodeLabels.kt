package com.nuvio.tv.core.debrid

import java.text.Normalizer

internal object TurkishEpisodeLabels {
    private const val START = "(?<![\\p{L}\\p{N}])"
    private const val END = "(?![\\p{L}\\p{N}])"
    private const val NUMBER = "[0-9]{1,4}"
    private const val SEPARATOR = "[\\s._-]+"
    private const val RANGE_SEPARATOR = "[\\s._]*[-–—~+&][\\s._]*"
    private const val SEASON = "sezon"
    private const val EPISODE = "b[oö]l[uü]m"

    private val season = Regex("$START($NUMBER)$SEPARATOR$SEASON$END", RegexOption.IGNORE_CASE)
    private val episode = Regex("$START($NUMBER)$SEPARATOR$EPISODE$END", RegexOption.IGNORE_CASE)
    private val relativeSeason = Regex("$START(?:s|season)[ ._-]*($NUMBER)$END", RegexOption.IGNORE_CASE)
    private val relativeEpisode = Regex("$START(?:e|ep|episode)[ ._-]*($NUMBER)$END", RegexOption.IGNORE_CASE)
    private val coordinates = Regex(
        "$START($NUMBER)$SEPARATOR$SEASON$SEPARATOR($NUMBER)$SEPARATOR$EPISODE$END",
        RegexOption.IGNORE_CASE)
    private val collection = Regex(
        "$START(?:(?:t[uü]m|b[uü]t[uü]n)$SEPARATOR(?:sezon(?:lar)?|${EPISODE}ler)|" +
            "komple${SEPARATOR}sezon|(?:$SEASON|$EPISODE)${SEPARATOR}paket(?:i)?)$END",
        RegexOption.IGNORE_CASE)
    private val ranges = listOf(SEASON, EPISODE).flatMap { label ->
        listOf(
            Regex("$START($NUMBER)(?:$SEPARATOR$label)?$RANGE_SEPARATOR($NUMBER)" +
                "$SEPARATOR$label(?:lar|ler)?$END", RegexOption.IGNORE_CASE),
            Regex("$START($NUMBER)$SEPARATOR$label$RANGE_SEPARATOR($NUMBER)$END" +
                "(?!$SEPARATOR(?:$SEASON|$EPISODE)$END)", RegexOption.IGNORE_CASE),
        )
    }
    private val clip = Regex("$START(?:fragman(?:[ıi])?|[oö]zet(?:i)?)$END", RegexOption.IGNORE_CASE)
    private val alternateEdition = Regex("$START(?:(?:part|par[cç]a|k[ıi]s[ıi]m)[0-9]*|international|uluslararas[ıi])$END",
        RegexOption.IGNORE_CASE)
    private val titleMarks = Regex("(?<=\\p{IsLatin})\\p{M}+")
    private val titleSeparators = Regex("[^\\p{L}\\p{N}]+")
    private val year = Regex("(?:18|19|20|21)[0-9]{2}")

    fun hasSeason(text: String): Boolean = season.containsMatchIn(text)
    fun hasEpisode(text: String): Boolean = episode.containsMatchIn(text)
    fun hasClip(text: String): Boolean = clip.containsMatchIn(text)
    fun hasCollection(text: String): Boolean = collection.containsMatchIn(text) || ranges.any { range ->
        range.findAll(text).any { it.groupValues[1].toInt() != it.groupValues[2].toInt() }
    }

    fun hasConflictingCoordinates(text: String): Boolean {
        val seasons = season.findAll(text).map { it.groupValues[1].toInt() }.toList()
        val episodes = episode.findAll(text).map { it.groupValues[1].toInt() }.toList()
        if (seasons.isEmpty() && episodes.isEmpty()) return false
        val standard = episodeCoordinates.findAll(text).toList()
        val relativeSeasons = relativeSeason.findAll(text).map { it.groupValues[1].toInt() }
        val relativeEpisodes = relativeEpisode.findAll(text).map { it.groupValues[1].toInt() }
        return (seasons + relativeSeasons + standard.mapNotNull { it.groupValues[1].ifEmpty { it.groupValues[3] }.toIntOrNull() })
            .distinct().size > 1 ||
            (episodes + relativeEpisodes + standard.mapNotNull { it.groupValues[2].ifEmpty { it.groupValues[4] }.toIntOrNull() })
                .distinct().size > 1
    }

    fun normalizeCoordinates(text: String): String = coordinates.replace(text) {
        "S${it.groupValues[1]}E${it.groupValues[2]}"
    }

    fun findBroadcastLabel(file: String, proof: TurkishBroadcastEpisode): MatchResult? {
        val label = episode.findAll(file).singleOrNull() ?: return null
        if (label.groupValues[1].toInt() != proof.number ||
            alternateEdition.containsMatchIn(file.substring(label.range.last + 1))) return null
        val title = titleKey(proof.originalTitle)
        if (title.isEmpty()) return null
        val prefix = titleKey(episodeCoordinates.replace(file.substring(0, label.range.first), " "))
        if (prefix == title) return label
        if (!prefix.startsWith("$title ")) return null
        val releaseYear = prefix.removePrefix("$title ").takeIf(year::matches)?.toIntOrNull()
        return label.takeIf { releaseYear != null && proof.premiereYear != null && releaseYear >= proof.premiereYear }
    }

    private fun titleKey(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(titleMarks, "").replace('ı', 'i').lowercase().replace(titleSeparators, " ").trim()
}
