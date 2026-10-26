package com.nuvio.tv.core.anime

import org.junit.Assert.*
import org.junit.Test

class NativeAnimeTitleIdentityTest {
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
