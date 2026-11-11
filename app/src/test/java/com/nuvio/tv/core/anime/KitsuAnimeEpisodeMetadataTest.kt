package com.nuvio.tv.core.anime

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class KitsuAnimeEpisodeMetadataTest {
    @Test
    fun `linked decoder accepts exact AniList or MAL corroboration`() {
        val aniList = fixture().withMapping("anilist/anime", "206814")
        assertEquals(393369L, requireNotNull(decodeLinkedKitsuAnimeEpisodes(aniList, 50497, 63367, 206814))
            .episodes.getValue(1).id)
        val mal = fixture().withMapping("myanimelist/anime", "63367")
        assertNotNull(decodeLinkedKitsuAnimeEpisodes(mal, 50497, 63367, null))
        mal.withMapping("animeplanet/anime", "dragon-ball-super-beerus", "351175")
        assertNotNull(decodeLinkedKitsuAnimeEpisodes(mal, 50497, 63367, null))
    }

    @Test
    fun `linked decoder refuses missing uncorroborated and conflicting mappings`() {
        assertNull(decodeLinkedKitsuAnimeEpisodes(fixture(), 50497, 63367, 206814))
        val aniList = fixture().withMapping("anilist/anime", "206814")
        assertNull(decodeLinkedKitsuAnimeEpisodes(aniList, 50497, 63367, null))
        assertNull(decodeLinkedKitsuAnimeEpisodes(aniList, 50497, 63367, 206815))
        aniList.withMapping("myanimelist/anime", "63368", "351175")
        assertNull(decodeLinkedKitsuAnimeEpisodes(aniList, 50497, 63367, 206814))
        val mal = fixture().withMapping("myanimelist/anime", "63367")
            .withMapping("anilist/anime", "206815", "351175")
        assertNull(decodeLinkedKitsuAnimeEpisodes(mal, 50497, 63367, 206814))
        val duplicateSite = fixture().withMapping("anilist/anime", "206814")
            .withMapping("anilist/anime", "206814", "351175")
        assertNull(decodeLinkedKitsuAnimeEpisodes(duplicateSite, 50497, 63367, 206814))
    }

    @Test
    fun `linked decoder requires each mapping to match one included resource`() {
        val unlinked = fixture().withMapping("anilist/anime", "206814")
        unlinked.getAsJsonArray("included")[1].asJsonObject.addProperty("id", "351175")
        assertNull(decodeLinkedKitsuAnimeEpisodes(unlinked, 50497, 63367, 206814))
        val wrongType = fixture().withMapping("anilist/anime", "206814")
        wrongType.getAsJsonArray("included")[1].asJsonObject.addProperty("type", "people")
        assertNull(decodeLinkedKitsuAnimeEpisodes(wrongType, 50497, 63367, 206814))
        val repeatedId = fixture().withMapping("anilist/anime", "206814")
            .withMapping("myanimelist/anime", "63367", "351174")
        assertNull(decodeLinkedKitsuAnimeEpisodes(repeatedId, 50497, 63367, 206814))
        val repeatedIncluded = fixture().withMapping("anilist/anime", "206814")
            .withMapping("myanimelist/anime", "63367", "351175")
        repeatedIncluded.getAsJsonArray("included")[2].asJsonObject.addProperty("id", "351174")
        assertNull(decodeLinkedKitsuAnimeEpisodes(repeatedIncluded, 50497, 63367, 206814))
    }

    @Test
    fun `decodes the linked Beerus episode from the public response shape`() {
        val metadata = requireNotNull(decodeKitsuAnimeEpisodes(fixture(), 50497))

        assertEquals("Dragon Ball Super: Beerus", metadata.canonicalTitle)
        assertEquals(2026, metadata.startYear)
        assertEquals(setOf(1), metadata.episodes.keys)
        assertEquals(393369L, metadata.episodes.getValue(1).id)
        assertEquals(1, metadata.episodes.getValue(1).season)
        assertNull(metadata.episodes.getValue(1).relativeNumber)
    }

    @Test
    fun `the anime wide number is the key even when relative numbering differs`() {
        val root = fixture()
        root.episodeAttributes().addProperty("number", 12)
        root.episodeAttributes().addProperty("seasonNumber", 2)
        root.episodeAttributes().addProperty("relativeNumber", 1)

        val metadata = requireNotNull(decodeKitsuAnimeEpisodes(root, 50497))
        assertEquals(setOf(12), metadata.episodes.keys)
        assertEquals(2, metadata.episodes.getValue(12).season)
        assertEquals(1, metadata.episodes.getValue(12).relativeNumber)
    }

    @Test
    fun `relative number is optional but invalid explicit values are rejected`() {
        val missing = fixture()
        missing.episodeAttributes().remove("relativeNumber")
        assertNull(requireNotNull(decodeKitsuAnimeEpisodes(missing, 50497)).episodes.getValue(1).relativeNumber)
        for (value in listOf("0", "1.5", "1.0", "\"1\"", "\"null\"", "false", "[]", "{}", "4001")) {
            val malformed = fixture()
            malformed.episodeAttributes().add("relativeNumber", JsonParser.parseString(value))
            assertNull(value, decodeKitsuAnimeEpisodes(malformed, 50497))
        }
    }

    @Test
    fun `explicit relative coordinates are unique within each season`() {
        val root = fixture()
        root.episodeAttributes().addProperty("relativeNumber", 1)
        root.linkage().add(JsonParser.parseString("""{"type":"episodes","id":"393370"}"""))
        val secondEpisode = root.episode().deepCopy()
        secondEpisode.addProperty("id", "393370")
        val secondAttributes = secondEpisode.getAsJsonObject("attributes")
        secondAttributes.addProperty("number", 2)
        root.getAsJsonArray("included").add(secondEpisode)

        assertNull(decodeKitsuAnimeEpisodes(root, 50497))
        secondAttributes.addProperty("seasonNumber", 2)
        val metadata = requireNotNull(decodeKitsuAnimeEpisodes(root, 50497))
        assertEquals(1, metadata.episodes.getValue(1).relativeNumber)
        assertEquals(1, metadata.episodes.getValue(2).relativeNumber)
    }

    @Test
    fun `refuses another title a non-series subtype and malformed dates`() {
        assertNull(decodeKitsuAnimeEpisodes(fixture(), 50498))
        val wrongResource = fixture()
        wrongResource.getAsJsonObject("data").addProperty("type", "manga")
        assertNull(decodeKitsuAnimeEpisodes(wrongResource, 50497))
        val movie = fixture()
        movie.animeAttributes().addProperty("subtype", "movie")
        movie.animeAttributes().addProperty("showType", "movie")
        assertNull(decodeKitsuAnimeEpisodes(movie, 50497))
        val invalidDate = fixture()
        invalidDate.animeAttributes().addProperty("startDate", "2026-02-30")
        assertNull(decodeKitsuAnimeEpisodes(invalidDate, 50497))
    }

    @Test
    fun `refuses unlinked episode data and contradictory explicit media ownership`() {
        val unlinked = fixture()
        unlinked.episode().addProperty("id", "393370")
        assertNull(decodeKitsuAnimeEpisodes(unlinked, 50497))
        val wrongMedia = fixture()
        wrongMedia.episode().getAsJsonObject("relationships").getAsJsonObject("media").add(
            "data", JsonParser.parseString("""{"type":"anime","id":"50498"}""")
        )
        assertNull(decodeKitsuAnimeEpisodes(wrongMedia, 50497))
        val exactMedia = fixture()
        exactMedia.episode().getAsJsonObject("relationships").getAsJsonObject("media").add(
            "data", JsonParser.parseString("""{"type":"anime","id":"50497"}""")
        )
        assertNotNull(decodeKitsuAnimeEpisodes(exactMedia, 50497))
    }

    @Test
    fun `refuses duplicate linked IDs included IDs and anime wide coordinates`() {
        val duplicateLink = fixture()
        duplicateLink.linkage().add(duplicateLink.linkage()[0].deepCopy())
        duplicateLink.getAsJsonArray("included").add(duplicateLink.episode().deepCopy().apply {
            addProperty("id", "393370")
            getAsJsonObject("attributes").addProperty("number", 2)
        })
        assertNull(decodeKitsuAnimeEpisodes(duplicateLink, 50497))
        val duplicateIncluded = fixture()
        duplicateIncluded.linkage().add(duplicateIncluded.linkage()[0].asJsonObject.deepCopy().apply {
            addProperty("id", "393370")
        })
        duplicateIncluded.getAsJsonArray("included").add(duplicateIncluded.episode().deepCopy())
        assertNull(decodeKitsuAnimeEpisodes(duplicateIncluded, 50497))
        val duplicateCoordinate = fixture()
        val secondLink = duplicateCoordinate.linkage()[0].asJsonObject.deepCopy()
        secondLink.addProperty("id", "393370")
        duplicateCoordinate.linkage().add(secondLink)
        val secondEpisode = duplicateCoordinate.episode().deepCopy()
        secondEpisode.addProperty("id", "393370")
        duplicateCoordinate.getAsJsonArray("included").add(secondEpisode)
        assertNull(decodeKitsuAnimeEpisodes(duplicateCoordinate, 50497))
    }

    @Test
    fun `refuses special fractional range and out of bounds coordinates`() {
        for (coordinate in listOf("0", "1.5", "1.0", "\"1\"", "\"1-2\"", "4001")) {
            val root = fixture()
            root.episodeAttributes().add("number", JsonParser.parseString(coordinate))
            assertNull(coordinate, decodeKitsuAnimeEpisodes(root, 50497))
        }
        val seasonZero = fixture()
        seasonZero.episodeAttributes().addProperty("seasonNumber", 0)
        assertNull(decodeKitsuAnimeEpisodes(seasonZero, 50497))
        seasonZero.episodeAttributes().addProperty("seasonNumber", 1001)
        assertNull(decodeKitsuAnimeEpisodes(seasonZero, 50497))
    }

    private fun JsonObject.animeAttributes() = getAsJsonObject("data").getAsJsonObject("attributes")
    private fun JsonObject.episode() = getAsJsonArray("included")[0].asJsonObject
    private fun JsonObject.episodeAttributes() = episode().getAsJsonObject("attributes")
    private fun JsonObject.linkage() = getAsJsonObject("data").getAsJsonObject("relationships")
        .getAsJsonObject("episodes").getAsJsonArray("data")

    private fun JsonObject.withMapping(site: String, externalId: String, id: String = "351174"): JsonObject {
        val relationships = getAsJsonObject("data").getAsJsonObject("relationships")
        val mappingRelationship = relationships.getAsJsonObject("mappings") ?: JsonParser.parseString(
            """{"data":[]}"""
        ).asJsonObject.also { relationships.add("mappings", it) }
        mappingRelationship.getAsJsonArray("data").add(JsonParser.parseString("""{"type":"mappings","id":"$id"}"""))
        getAsJsonArray("included").add(JsonParser.parseString(
            """{"type":"mappings","id":"$id","attributes":{"externalSite":"$site","externalId":"$externalId"}}"""
        ))
        return this
    }

    private fun fixture() = JsonParser.parseString(
        """
        {
          "data": {
            "id": "50497", "type": "anime",
            "attributes": {"canonicalTitle": "Dragon Ball Super: Beerus", "startDate": "2026-10-11",
                "subtype": "TV", "showType": "TV", "episodeCount": 19},
            "relationships": {"episodes": {"data": [{"type": "episodes", "id": "393369"}]}}
          },
          "included": [{
            "id": "393369", "type": "episodes",
            "attributes": {"number": 1, "relativeNumber": null, "seasonNumber": 1, "airdate": "2026-10-11"},
            "relationships": {"media": {"links": {
                "related": "https://kitsu.io/api/edge/episodes/393369/media"
            }}}
          }]
        }
        """.trimIndent()
    ).asJsonObject
}
