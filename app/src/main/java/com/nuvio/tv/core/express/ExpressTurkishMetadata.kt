package com.nuvio.tv.core.express

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.nuvio.tv.core.debrid.TurkishBroadcastEpisode
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrl

internal fun JsonObject.isTurkishProduction(): Boolean =
    string("country")?.split(',', ';', '/', '|')?.any { country ->
        listOf("Turkey", "Türkiye", "Turkiye").any { country.trim().equals(it, ignoreCase = true) }
    } == true

internal suspend fun ExpressHttpClient.wikidataTurkishMovieTitle(imdbId: String, year: Int): String? = try {
    // Exact IMDb lookup and the current entity claims in a single request.
    val url = "https://www.wikidata.org/w/api.php".toHttpUrl().newBuilder()
        .addQueryParameter("action", "query")
        .addQueryParameter("generator", "search")
        .addQueryParameter("gsrnamespace", "0")
        .addQueryParameter("gsrlimit", "2")
        .addQueryParameter("gsrsearch", "haswbstatement:P345=$imdbId")
        .addQueryParameter("prop", "revisions")
        .addQueryParameter("rvslots", "main")
        .addQueryParameter("rvprop", "content|contentmodel")
        .addQueryParameter("format", "json")
        .addQueryParameter("formatversion", "2")
        .build()
    val response = get(url.toString(), 512 * 1024) {
        throw ExpressException("Wikidata metadata redirected.")
    }
    if (response.url.toHttpUrl() == url) verifiedTurkishMovieTitle(parseExpressObject(response.text), imdbId, year)
    else null
} catch (error: CancellationException) {
    throw error
} catch (_: Exception) {
    null
}

private fun verifiedTurkishMovieTitle(response: JsonObject, imdbId: String, year: Int): String? {
    if (response.has("continue")) return null
    val page = response.getAsJsonObject("query")?.getAsJsonArray("pages")?.singleOrNull()?.asJsonObject
        ?: return null
    val id = page.string("title")?.takeIf { it.matches(Regex("Q[1-9][0-9]{0,11}")) } ?: return null
    val slot = page.getAsJsonArray("revisions")?.singleOrNull()?.asJsonObject
        ?.getAsJsonObject("slots")?.getAsJsonObject("main") ?: return null
    if (slot.string("contentmodel") != "wikibase-item") return null
    val item = parseExpressObject(slot.string("content") ?: return null)
    if (item.string("id") != id) return null
    val claims = item.getAsJsonObject("claims") ?: return null
    if (claims.valuesFor("P345").mapNotNull { it.scalarString() }.distinct().singleOrNull() != imdbId) return null
    if (claims.valuesFor("P31").none { it.isJsonObject && it.asJsonObject.string("id") == "Q11424" }) return null
    if (claims.valuesFor("P364").none { it.isJsonObject && it.asJsonObject.string("id") == "Q256" }) return null
    if (claims.valuesFor("P577").none {
            it.isJsonObject && it.asJsonObject.string("precision")?.toIntOrNull()?.let { value -> value >= 9 } == true &&
                it.asJsonObject.string("time")?.startsWith("+$year-") == true
        }) return null
    return claims.valuesFor("P1476").mapNotNull { value ->
        value.takeIf { it.isJsonObject }?.asJsonObject?.takeIf { it.string("language") == "tr" }
            ?.string("text")?.let(::expressQueryTitle)
    }.distinct().singleOrNull()
}

private fun JsonObject.valuesFor(property: String): List<JsonElement> =
    getAsJsonArray(property)?.mapNotNull { claim ->
        claim.asJsonObject.takeIf { it.string("rank") != "deprecated" }
            ?.getAsJsonObject("mainsnak")?.getAsJsonObject("datavalue")?.get("value")
    }.orEmpty()

private val catalogOrdinal = Regex("""^episode\s+([1-9][0-9]{0,3})$""", RegexOption.IGNORE_CASE)
private val broadcastOrdinal = Regex(
    """^([1-9][0-9]{0,3})[.\s]+b[öo]l[üu]m(?:[\s(–—-]+(?:final|veda)\)?)?$""", RegexOption.IGNORE_CASE
)

internal fun JsonObject.catalogTurkishEpisodeNumbers(imdbId: String): Map<String, Int> {
    val videos = get("videos")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyMap()
    if (videos.size() > 2_000) return emptyMap()
    val claims = videos.asSequence()
        .mapNotNull { entry ->
            val episode = entry.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val season = episode.string("season")?.toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            val number = episode.string("episode")?.toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            val id = episode.string("id")?.takeIf { it == "$imdbId:$season:$number" } ?: return@mapNotNull null
            val label = episode.string("name")?.trim() ?: return@mapNotNull null
            val ordinal = (catalogOrdinal.matchEntire(label) ?: broadcastOrdinal.matchEntire(label))
                ?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            Triple(id, ordinal, season == 1 || ordinal > number)
        }.toList()
    val owners = claims.groupBy({ it.second }, { it.first })
    return claims.groupBy { it.first }.mapNotNull { (id, entries) ->
        val claim = entries.distinct().singleOrNull() ?: return@mapNotNull null
        val ordinal = claim.second
        if (claim.third && owners[ordinal]?.distinct()?.singleOrNull() == id) id to ordinal else null
    }.toMap()
}

internal suspend fun ExpressHttpClient.confirmTurkishBroadcastEpisode(
    showId: Int, proof: TurkishBroadcastEpisode
): TurkishBroadcastEpisode? = try {
    val url = "https://api.tvmaze.com/shows/$showId/episodebynumber?season=${proof.season}&number=${proof.episode}"
    val document = get(url, 128 * 1024) { throw ExpressException("TVmaze episode metadata redirected.") }
    val episode = parseExpressObject(document.text)
    val linkedShow = episode.getAsJsonObject("_links")?.getAsJsonObject("show")?.string("href")
    val ordinal = episode.string("name")?.trim()?.let(broadcastOrdinal::matchEntire)
        ?.groupValues?.get(1)?.toIntOrNull()
    proof.takeIf {
        document.url == url && (episode.string("id")?.toIntOrNull() ?: 0) > 0 &&
            (linkedShow == null || linkedShow == "https://api.tvmaze.com/shows/$showId") &&
            episode.string("season")?.toIntOrNull() == proof.season &&
            episode.string("number")?.toIntOrNull() == proof.episode && ordinal == proof.number
    }
} catch (error: CancellationException) {
    throw error
} catch (_: Exception) {
    null
}
