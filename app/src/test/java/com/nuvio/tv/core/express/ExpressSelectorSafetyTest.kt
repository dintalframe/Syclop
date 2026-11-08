package com.nuvio.tv.core.express

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpressSelectorSafetyTest {
    @Test
    fun regularExpressionSelectorsAreRejectedBeforeParsingProviderPages() {
        val selectors = listOf(
            "div:matches(^(a|aa)+b$)",
            "div:matchesOwn(^(a|aa)+b$)",
            "div:matchesWholeText(^(a|aa)+b$)",
            "div:matchesWholeOwnText(^(a|aa)+b$)",
            "div:MaTcHeSoWn(^(a|aa)+b$)",
            "div[data-title~=(a|aa)+b]"
        )
        for (selector in selectors) {
            for (provider in listOf(definition(row = selector), definition(title = selector))) {
                assertFalse("Unsafe selector was admitted", ExpressParser.supported(provider))
                assertTrue(ExpressParser.parse(provider, "<div>" + "a".repeat(3_200) + "X</div>").isEmpty())
            }
        }
    }

    @Test
    fun selectorsHaveLengthAndNestingLimitsForRowsAndFields() {
        val selectors = listOf(
            "div:has(".repeat(3_200) + "span" + ")".repeat(3_200),
            "div:not(".repeat(9) + "span" + ")".repeat(9),
            "." + "a".repeat(600)
        )
        for (selector in selectors) {
            assertFalse(ExpressParser.supported(definition(row = selector)))
            assertFalse(ExpressParser.supported(definition(title = selector)))
        }
    }

    @Test
    fun normalCombinatorsAttributesAndStructuralSelectorsStillReadSourceMetadata() {
        val provider = definition(
            row = "table > tbody > tr.result:not(.hidden):has(a[href])",
            title = "td:nth-child(2) a:last-child"
        )
        provider.getAsJsonObject("html_parser").addProperty("url", "row.querySelector('a[href^=\"magnet:\"]').getAttribute('href')")
        assertTrue(ExpressParser.supported(provider))
        val row = ExpressParser.parse(provider, """<table><tr class="result"><td><a href="magnet:?xt=urn:btih:example">Link</a></td><td><a>Ignore</a><a>Episode.mkv</a></td></tr></table>""").single()
        assertEquals("Episode.mkv", row.title)
        assertEquals("magnet:?xt=urn:btih:example", row.url)
    }

    @Test
    fun quotedAttributeTextIsNotTreatedAsASelectorOperator() {
        val provider = definition(row = "div[data-note=':matches(ordinary text)~=literal']", title = "span")
        assertTrue(ExpressParser.supported(provider))
        assertEquals("Title", ExpressParser.parse(provider,
            """<div data-note=":matches(ordinary text)~=literal"><span>Title</span></div>""").single().title)
    }

    @Test
    fun malformedSelectorsAreRejectedAtConfigurationAdmission() {
        for (selector in listOf("", "div:not(", "div[data-title=\"unterminated]", "div:unknown(value)")) {
            assertFalse(ExpressParser.supported(definition(row = selector)))
        }
    }

    @Test
    fun jsonFieldPathsHaveTheSameExpressionSizeBound() {
        val provider = JsonObject().apply {
            add("json_format", JsonObject().apply { addProperty("hash", "a".repeat(1_024)) })
        }
        assertFalse(ExpressParser.supported(provider))
        assertTrue(ExpressParser.parse(provider, "{}").isEmpty())
    }

    private fun definition(row: String = "div", title: String = "span"): JsonObject = JsonObject().apply {
        add("html_parser", JsonObject().apply {
            addProperty("row", "doc.querySelectorAll('$row')")
            addProperty("title", "row.querySelector('$title').textContent")
        })
    }
}
