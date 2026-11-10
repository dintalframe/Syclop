package com.nuvio.tv.core.anime

import org.junit.Assert.*
import org.junit.Test

class NativeAnimeTitleIdentityTest {
    @Test fun `punctuation and spaced year groups cannot erase an adjoining reboot contradiction`() {
        for ((year, other) in listOf(2011 to 1999, 1999 to 2011)) {
            val known = requireNotNull(NativeAnimeTitleIdentity.create(11061, "Hunter x Hunter ($year)", emptyList()))
            fun qualifiers(value: Int): List<String> {
                val fullwidth = value.toString().map { ('０'.code + (it - '0')).toChar() }.joinToString("")
                return listOf(": ($value)", " ( $value )", "— [$value]", "– [ $value ]",
                    "： （ $value ）", "— ［　$value　］", "： （　$fullwidth　）")
            }
            for (qualifier in qualifiers(year))
                assertTrue(qualifier, known.matchesRelease("Hunter x Hunter$qualifier S01E01.mkv"))
            for (qualifier in qualifiers(other))
                assertFalse(qualifier, known.matchesRelease("Hunter x Hunter$qualifier S01E01.mkv"))
            assertTrue(known.matchesRelease("[Group ( $other )] Hunter x Hunter S01E01.mkv"))
            assertTrue(known.matchesRelease("[$other] Hunter x Hunter S01E01.mkv"))
            assertTrue(known.matchesRelease("Hunter x Hunter S01E01 : ( $other ).mkv"))
            assertTrue(known.matchesRelease("Hunter x Hunter S01E01.$other.01.02.mkv"))
        }
    }

    @Test fun `mixed release qualifiers and contradictory trusted years fail closed`() {
        val known = requireNotNull(NativeAnimeTitleIdentity.create(11061, "Hunter x Hunter (2011)", emptyList()))
        for (name in listOf("Hunter x Hunter (2011) (1999) S01E01.mkv",
            "Hunter x Hunter 2011 [1999] S01E01.mkv", "Hunter x Hunter [1999] (2011) S01E01.mkv",
            "Hunter x Hunter (2011): ( 1999 ) S01E01.mkv",
            "Hunter x Hunter [ 2011 ]—［　１９９９　］ S01E01.mkv"))
            assertFalse(name, known.matchesRelease(name))
        val ambiguous = requireNotNull(NativeAnimeTitleIdentity.create(11061, "Hunter x Hunter (2011)",
            listOf("Hunter x Hunter (1999)")))
        assertTrue(ambiguous.matchesRelease("Hunter x Hunter S01E01.mkv"))
        assertFalse(ambiguous.matchesRelease("Hunter x Hunter (2011) S01E01.mkv"))
        assertFalse(ambiguous.matchesRelease("Hunter x Hunter (1999) S01E01.mkv"))
    }

    @Test fun `invalid title aliases cannot establish years and numeric complete titles stay complete`() {
        val known = requireNotNull(NativeAnimeTitleIdentity.create(11061, "Hunter x Hunter (2011)",
            listOf("Anime (1999)", "Season 2 (1999)", "Bad\nAlias (1999)")))
        assertTrue(known.matchesRelease("Hunter x Hunter (2011) S01E01.mkv"))
        val numeric = requireNotNull(NativeAnimeTitleIdentity.create(1, "Blade Runner 2049", emptyList()))
        assertTrue(numeric.matchesRelease("Blade Runner 2049 (2017).mkv"))
        assertFalse(numeric.matchesRelease("Blade Runner (2049).mkv"))
        assertEquals(listOf("Blade Runner 2049"), numeric.queryTitles())
    }

    @Test fun `movie title matcher without episode label preserves correct unqualified and unknown year contracts`() {
        val movie = requireNotNull(NativeAnimeTitleIdentity.create(1, "Synthetic Anime Film (2011)", emptyList()))
        assertTrue(movie.matchesRelease("Synthetic Anime Film (2011) [1080p].mkv"))
        assertTrue(movie.matchesRelease("[Group 1999] Synthetic Anime Film [1080p].mkv"))
        assertFalse(movie.matchesRelease("Synthetic Anime Film (1999) [1080p].mkv"))
        assertFalse(movie.matchesRelease("Synthetic Anime Film 1999 1080p.mkv"))
        assertFalse(movie.matchesRelease("Synthetic Anime Film (2011)/Other Film (2011).mkv"))
        val unknown = requireNotNull(NativeAnimeTitleIdentity.create(1, "Synthetic Anime Film", emptyList()))
        assertTrue(unknown.matchesRelease("Synthetic Anime Film.mkv"))
        // A title match with absent year metadata cannot assert that 1999 is contradicted.
        assertTrue(unknown.matchesRelease("Synthetic Anime Film (1999).mkv"))
    }

    @Test fun `trusted reboot years reject contrary pre episode qualifiers in both directions`() {
        for ((year, other) in listOf(2011 to 1999, 1999 to 2011)) {
            val reboot = requireNotNull(NativeAnimeTitleIdentity.create(11061, "Hunter x Hunter ($year)", emptyList()))
            for (name in listOf("Hunter x Hunter ($year) S01E01.mkv", "Hunter x Hunter $year S01E01.mkv",
                "Hunter x Hunter S01E01.mkv", "[Group $other] Hunter x Hunter S01E01.mkv",
                "Hunter x Hunter S01E01.$other.01.02.mkv", "Pack $other/Hunter x Hunter S01E01.mkv"))
                assertTrue(name, reboot.matchesRelease(name))
            for (name in listOf("Hunter x Hunter ($other) S01E01.mkv", "Hunter x Hunter [$other] S01E01.mkv",
                "Hunter x Hunter $other S01E01.mkv", "Hunter.x.Hunter.$other.S01E01.mkv",
                "Hunter x Hunter ($year)/Hunter x Hunter ($other) S01E01.mkv"))
                assertFalse(name, reboot.matchesRelease(name))
        }
        val nfkc = requireNotNull(NativeAnimeTitleIdentity.create(11061, "Ｈｕｎｔｅｒ ｘ Ｈｕｎｔｅｒ （２０１１）", emptyList()))
        assertTrue(nfkc.matchesRelease("Ｈｕｎｔｅｒ ｘ Ｈｕｎｔｅｒ （２０１１） Ｓ０１Ｅ０１.mkv"))
        assertFalse(nfkc.matchesRelease("Ｈｕｎｔｅｒ ｘ Ｈｕｎｔｅｒ （１９９９） Ｓ０１Ｅ０１.mkv"))
    }

    @Test fun `year evidence survives unqualified aliases and binds fingerprint without changing query cap`() {
        val known = requireNotNull(NativeAnimeTitleIdentity.create(136, "Hunter x Hunter", listOf("ハンター×ハンター (1999)")))
        val unknown = requireNotNull(NativeAnimeTitleIdentity.create(136, "Hunter x Hunter", emptyList()))
        assertTrue(known.matchesRelease("Hunter x Hunter S01E01.mkv"))
        assertFalse(known.matchesRelease("Hunter x Hunter (2011) S01E01.mkv"))
        assertTrue(unknown.matchesRelease("Hunter x Hunter S01E01.mkv"))
        val qualified = requireNotNull(NativeAnimeTitleIdentity.create(136, "Hunter x Hunter (1999)", emptyList()))
        assertNotEquals(unknown.fingerprint, qualified.fingerprint)
        assertEquals(unknown.queryTitles(), qualified.queryTitles())
        assertTrue(known.queryTitles().size <= 3)
        val plainMetadata = requireNotNull(NativeAnimeTitleIdentity.create(11061,
            "Hunter x Hunter", listOf("Hunter x Hunter 2011")))
        assertFalse(plainMetadata.matchesRelease("Hunter x Hunter (1999) S01E01.mkv"))
    }

    private val identity = requireNotNull(NativeAnimeTitleIdentity.create(59978,
        "Sousou no Frieren 2nd Season", listOf("Frieren: Beyond Journey’s End Season 2", "葬送のフリーレン 第2期")))

    @Test fun `only exact trusted titles and narrowly stripped terminal seasons match`() {
        for (name in listOf("[Group] Sousou.no.Frieren.S02E01.1080p.mkv",
            "Sousou_no_Frieren_2nd_Season - 29 [1080p].mkv",
            "Frieren.Beyond.Journeys.End.E29.mkv", "葬送のフリーレン 第2期 S02E01.mkv",
            "[E29 Group] Sousou no Frieren [01-28] [1080p].mkv")) {
            assertTrue(name, identity.matchesRelease(name))
        }
        assertFalse(identity.matchesRelease("Frieren.S02E01.mkv")) // No invented colon-prefix alias.
        assertFalse(identity.matchesRelease("Sousou.Frieren.S02E01.mkv")) // No dropped token/fuzzy match.
        assertFalse(identity.matchesRelease("SousounoFrieren.S02E01.mkv")) // No guessed word joins.
    }

    @Test fun `unrelated show coordinates release group and episode subtitle cannot establish franchise`() {
        for (name in listOf("Night.on.Earth.S02E01.mkv", "Welcome.to.Derry.E29.mkv",
            "[Sousou no Frieren] Night.On.Earth.E29.mkv",
            "Night.On.Earth.S02E01.Sousou.no.Frieren.mkv",
            "Sousou no Frieren/Welcome.to.Derry.E29.mkv",
            "[Group (Sousou no Frieren)] Night.On.Earth.E29.mkv")) {
            assertFalse(name, identity.matchesRelease(name))
        }
    }

    @Test fun `whole token boundaries block title suffix collisions`() {
        val short = requireNotNull(NativeAnimeTitleIdentity.create(59978, "Frieren", emptyList()))
        assertTrue(short.matchesRelease("[Group] FRIEREN - 29.mkv"))
        assertFalse(short.matchesRelease("NotFrieren.S02E01.mkv"))
        assertFalse(short.matchesRelease("Frierenish.E29.mkv"))
        assertFalse(short.matchesRelease("Frieren\u0000.S02E01.mkv"))
        assertFalse(short.matchesRelease("[Frieren] Other.S02E01.mkv"))
    }

    @Test fun `NFKC punctuation and apostrophe normalization preserves exact words`() {
        assertTrue(identity.matchesRelease("Ｆｒｉｅｒｅｎ：Ｂｅｙｏｎｄ．Ｊｏｕｒｎｅｙ’ｓ．Ｅｎｄ．Ｓ０２Ｅ０１.mkv"))
        assertFalse(identity.matchesRelease("Frieren Beyond Journey End S02E01.mkv"))
        assertFalse(identity.matchesRelease("Frieren Beyond Journeys Ending S02E01.mkv"))
    }

    @Test fun `missing generic numeric only and oversized metadata never creates identity`() {
        for (title in listOf("", " ", "Anime", "Show", "Season 2", "Episode 29", "29", "x".repeat(513), "Frieren\nOther")) {
            assertNull(title.take(40), NativeAnimeTitleIdentity.create(59978, title, emptyList()))
        }
        assertNull(NativeAnimeTitleIdentity.create(0, "Frieren", emptyList()))
        assertNull(NativeAnimeTitleIdentity.create(Long.MAX_VALUE, "Frieren", emptyList()))
        assertNull(NativeAnimeTitleIdentity.create(59978, "Frieren", List(128) { "Frieren" }))
    }

    @Test fun `exact mapping S2 and parenthesized year qualifiers do not invent short franchise aliases`() {
        val publicTitles = requireNotNull(NativeAnimeTitleIdentity.create(59978,
            "Sousou no Frieren S2", listOf("Frieren: Beyond Journey’s End (2026)")))
        assertTrue(publicTitles.matchesRelease("[Group] Sousou.no.Frieren - 29 [1080p].mkv"))
        assertTrue(publicTitles.matchesRelease("Frieren.Beyond.Journeys.End.S02E01.mkv"))
        assertFalse(publicTitles.matchesRelease("Frieren.S02E01.mkv"))
        assertFalse(publicTitles.matchesRelease("Other.Show.S02E01.mkv"))
        val numericTitle = requireNotNull(NativeAnimeTitleIdentity.create(1, "Galaxy 999", emptyList()))
        assertFalse(numericTitle.matchesRelease("Galaxy.S02E01.mkv"))
        assertTrue(numericTitle.matchesRelease("Galaxy.999.S02E01.mkv"))
    }

    @Test fun `balanced episode parentheses preserve prior title but never admit an appended franchise subtitle`() {
        assertTrue(identity.matchesRelease("[Group] Sousou no Frieren (S02E01).mkv"))
        assertTrue(identity.matchesRelease("[E29 Group] Sousou no Frieren - 29 (S02E01).mkv"))
        assertFalse(identity.matchesRelease("Other.Show.(S02E01).Sousou.no.Frieren.mkv"))
        assertFalse(identity.matchesRelease("Other.Show.[E29].Sousou.no.Frieren.mkv"))
        assertFalse(identity.matchesRelease("Other.Show.S02E01.(Sousou no Frieren).mkv"))
        assertFalse(identity.matchesRelease("Sousou no Frieren (S02E01.mkv"))
    }

    @Test fun `identity fingerprint binds MAL and normalized complete alias set independently of order`() {
        val first = requireNotNull(NativeAnimeTitleIdentity.create(59978, "Frieren", listOf("Sousou no Frieren")))
        val reordered = requireNotNull(NativeAnimeTitleIdentity.create(59978, "Sousou.no.Frieren", listOf("FRIEREN")))
        val changed = requireNotNull(NativeAnimeTitleIdentity.create(59978, "Frieren", listOf("Other Actual Alias")))
        val otherMal = requireNotNull(NativeAnimeTitleIdentity.create(52991, "Frieren", listOf("Sousou no Frieren")))
        assertEquals(first.fingerprint, reordered.fingerprint)
        assertNotEquals(first.fingerprint, changed.fingerprint)
        assertNotEquals(first.fingerprint, otherMal.fingerprint)
        assertEquals("NativeAnimeTitleIdentity(validated=true)", first.toString())
    }

    @Test fun `query snapshot reserves room for Latin aliases and Japanese instead of season duplicates`() {
        val aliases = mutableListOf("Sousou.no.Frieren.S2", "Sousou no Frieren Season 2",
            "Frieren: Beyond Journey’s End (2026)", "葬送のフリーレン 第2期", "Another Alias")
        val snapshot = requireNotNull(NativeAnimeTitleIdentity.create(59978, "Sousou no Frieren 2nd Season", aliases))
        val expected = listOf("Sousou no Frieren", "Frieren: Beyond Journey’s End", "葬送のフリーレン 第2期")
        assertEquals(expected, snapshot.queryTitles())
        aliases.clear(); aliases += "Unrelated Remake"
        assertEquals(expected, snapshot.queryTitles())
        assertFalse(snapshot.matchesRelease("Unrelated Remake S02E01.mkv"))
        for (title in expected) assertTrue(title, snapshot.matchesRelease("$title S02E01.mkv"))
        try {
            (snapshot.queryTitles() as MutableList<String>).add("Unrelated Remake")
            fail("Query snapshot must be immutable")
        } catch (_: UnsupportedOperationException) { }
    }

    @Test fun `query snapshot never invents aliases from subtitles or untrusted query text`() {
        val identity = requireNotNull(NativeAnimeTitleIdentity.create(1, "Franchise: Subtitle Part 2",
            listOf("Franchise: Subtitle Part 2", "Bad\nAlias", "x".repeat(257))))
        assertEquals(listOf("Franchise: Subtitle Part 2"), identity.queryTitles())
        assertFalse(identity.matchesRelease("Franchise S01E01.mkv"))
        assertTrue(identity.matchesRelease("Franchise Subtitle Part 2 S01E01.mkv"))
    }
}
