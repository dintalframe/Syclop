package com.nuvio.tv.core.debrid

import com.nuvio.tv.domain.model.Stream
import java.util.Locale

/** Display/search admission only. Provider pack claims are not selected-episode proof. */
object EpisodeSourcePolicy {
    private const val MAX_RELEASE_TEXT = 4_096
    private val episodeTypes = setOf("series", "tv", "show", "anime")
    private val seasonRange = Regex("(?<![a-z0-9])s([0-9]{1,3})\\s*[-–—~]\\s*s?([0-9]{1,3})(?![a-z0-9])", RegexOption.IGNORE_CASE)
    private val namedSeasonRange = Regex("(?<![a-z0-9])seasons?[ ._-]*([0-9]{1,3})\\s*[-–—~]\\s*(?:seasons?[ ._-]*)?([0-9]{1,3})(?![a-z0-9])", RegexOption.IGNORE_CASE)
    private val completeSeason = Regex("(?<![a-z0-9])(?:complete[ ._-]*(?:seasons?|series)|(?:seasons?[ ._-]*[0-9]{1,3}|s[0-9]{1,3})[ ._-]*(?:complete|pack|batch)|all[ ._-]*seasons?)(?![a-z])", RegexOption.IGNORE_CASE)
    private val qualifiedBatch = Regex("(?:[\\[(]\\s*batch\\s*[\\])]|(?:^|\\s)[-–—]\\s*batch(?:$|[ ._\\[(]))", RegexOption.IGNORE_CASE)
    private val episodeRange = Regex("(?<![a-z0-9])(?:s[0-9]{1,3})?e([0-9]{1,4})\\s*[-–—~+]\\s*e?([0-9]{1,4})(?![a-z0-9])", RegexOption.IGNORE_CASE)
    private val bracketEpisodeRange = Regex("[\\[(]\\s*([0-9]{1,3})\\s*[-–—~]\\s*([0-9]{1,3})\\s*[\\])]", RegexOption.IGNORE_CASE)
    private val namedEpisodeRange = Regex("(?<![a-z0-9])(?:episodes?|eps?)[ ._-]*([0-9]{1,4})\\s*[-–—~]\\s*(?:e(?:ps?)?[ ._-]*)?([0-9]{1,4})(?![a-z0-9])", RegexOption.IGNORE_CASE)
    private val seasonEpisode = Regex("(?<![a-z0-9])s([0-9]{1,3})e([0-9]{1,4})(?![0-9])", RegexOption.IGNORE_CASE)
    private val repeatedEpisode = Regex("(?<![a-z0-9])(?:s[0-9]{1,3})?(?:e[0-9]{1,4}){2,}(?![a-z0-9])", RegexOption.IGNORE_CASE)
    private val episodeToken = Regex("e([0-9]{1,4})", RegexOption.IGNORE_CASE)
    private val seasonMarker = Regex("(?<![a-z0-9])(?:s[0-9]{1,3}|season[ ._-]*[0-9]{1,3})(?![a-z0-9])", RegexOption.IGNORE_CASE)
    private val singleEpisode = Regex("(?<![a-z0-9])(?:s[0-9]{1,3}e[0-9]{1,4}|[0-9]{1,3}x[0-9]{1,4}|(?:e|ep|episode)[ ._-]*[0-9]{1,4})(?![a-z0-9])", RegexOption.IGNORE_CASE)
    private val releaseAbsoluteEpisode = Regex("[-–—]\\s*[0-9]{1,3}(?:v[0-9])?(?=$|[ ._(\\[])", RegexOption.IGNORE_CASE)

    /** The caller supplies catalog/request context; release fields never decide request type. */
    fun isEpisodeRequest(mediaType: String?, episode: Int?): Boolean =
        mediaType?.lowercase(Locale.ROOT) in episodeTypes && episode != null && episode >= 0

    fun filter(streams: List<Stream>, episodeRequest: Boolean): List<Stream> =
        if (episodeRequest) streams.filterNot(::isReportedPack) else streams

    fun isReportedPack(stream: Stream): Boolean {
        val resolve = stream.clientResolve
        val raw = resolve?.stream?.raw
        val parsed = raw?.parsed
        // Requested resolve.season/episode and fileIdx are deliberately not release evidence.
        if (parsed?.seasons.orEmpty().asSequence().filter { it >= 0 }.distinct().take(2).count() > 1 ||
            parsed?.episodes.orEmpty().asSequence().filter { it >= 0 }.distinct().take(2).count() > 1) return true
        // Addon name is a separate field; the primary source name can itself declare a pack.
        if (stream.name?.take(MAX_RELEASE_TEXT)?.let(::hasExplicitPackText) == true) return true
        val releases = listOfNotNull(stream.title, stream.description,
            stream.behaviorHints?.filename, resolve?.torrentName, resolve?.filename,
            raw?.torrentName, raw?.filename, parsed?.rawTitle).filter(String::isNotBlank)
            .ifEmpty { listOfNotNull(stream.name) }.map { it.take(MAX_RELEASE_TEXT) }
        if (releases.any(::hasExplicitPackText)) return true
        // A standalone season marker is a season release unless another release field
        // explicitly names one episode. These are provider claims, not episode verification.
        if (parsed?.episodes.orEmpty().any { it >= 0 } || releases.any(::hasSingleEpisodeClue)) return false
        return parsed?.seasons.orEmpty().any { it >= 0 } || releases.any(seasonMarker::containsMatchIn)
    }

    /** Used before Express row/subpage budgets, without constructing or resolving a stream. */
    fun hasReportedPackText(vararg releases: String?): Boolean {
        val texts = releases.mapNotNull { it?.take(MAX_RELEASE_TEXT) }
        if (texts.any(::hasExplicitPackText)) return true
        return texts.none(::hasSingleEpisodeClue) && texts.any(seasonMarker::containsMatchIn)
    }

    private fun hasSingleEpisodeClue(text: String): Boolean =
        singleEpisode.containsMatchIn(text) || releaseAbsoluteEpisode.containsMatchIn(text)

    private fun hasExplicitPackText(text: String): Boolean =
        completeSeason.containsMatchIn(text) || qualifiedBatch.containsMatchIn(text) ||
            listOf(seasonRange, namedSeasonRange, episodeRange, bracketEpisodeRange, namedEpisodeRange)
                .any { range -> range.findAll(text).any { it.groupValues[1].toInt() != it.groupValues[2].toInt() } } ||
            seasonEpisode.findAll(text).map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }.distinct().take(2).count() > 1 ||
            repeatedEpisode.findAll(text).any { match -> episodeToken.findAll(match.value)
                .map { it.groupValues[1].toInt() }.distinct().take(2).count() > 1 }
}
