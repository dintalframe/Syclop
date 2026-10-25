package com.nuvio.tv.data.catalog

import com.nuvio.tv.domain.model.ContentType
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class RatedKidsCatalogTest {
    @Test fun `public discovery advertises no Home rows or accounts and missing app registration sends no request`() = runBlocking {
        val h = Harness()
        val addon = h.catalog.addon.first()!!
        assertEquals(RatedKidsCatalog.ADDON_ID, addon.id)
        assertTrue(addon.catalogs.all { !it.showInHome && it.hasExplicitShowInHome })
        assertEquals(listOf("family-movies", "family-shows", "education-shows"), addon.catalogs.take(3).map { it.id })
        assertEquals(setOf("catalog"), addon.resources.map { it.name }.toSet())
        h.source.configured = false
        assertNull(h.catalog.addon.first())
        assertTrue(runCatching { h.load() }.isFailure)
        assertTrue(h.source.calls.isEmpty())
    }
    @Test fun `same page requests coalesce and cached TTL refreshes only on demand`() = runBlocking {
        val h = Harness(); val waiting = CompletableDeferred<Unit>()
        h.source.respond = { _, _ -> waiting.await(); h.defaultPage() }
        val first = async(Dispatchers.Default) { h.load() }
        val second = async(Dispatchers.Default) { h.load() }
        withTimeout(3_000) { while (h.source.calls.isEmpty()) yield() }
        waiting.complete(Unit)
        assertEquals(first.await(), second.await())
        assertEquals(1, h.source.calls.size)
        h.load(); assertEquals(1, h.source.calls.size)
        h.clock.addAndGet(RatedKidsCatalog.CACHE_TTL_MS + 1)
        assertEquals(1, h.source.calls.size)
        h.load(); assertEquals(2, h.source.calls.size)
    }
    @Test fun `fixed cursor uses raw page stride even if only one item passed rating checks and stops at eight pages`() = runBlocking {
        val h = Harness()
        val first = h.load()
        assertEquals(1, first.items.size); assertEquals(25, first.nextSkip)
        val last = h.load(skip = 175)
        assertFalse(last.hasMore); assertEquals(200, last.nextSkip)
        assertEquals(listOf(1, 8), h.source.calls.map { it.second })
        for (skip in listOf(-1, 1, 200, Int.MAX_VALUE)) assertTrue(runCatching { h.load(skip = skip) }.isFailure)
        assertTrue(runCatching { h.catalog.catalog(ContentType.SERIES, "family-movies", 0, emptyMap()) }.isFailure)
        assertTrue(runCatching { h.load(extra = mapOf("search" to "private")) }.isFailure)
        assertTrue(runCatching { h.load(extra = mapOf("skip" to "25")) }.isFailure)
        assertTrue(runCatching { h.load(extra = mapOf("skip" to "NaN")) }.isFailure)
        assertEquals(2, h.source.calls.size)
    }
    @Test fun `caller cancellation and profile flow change cancel actual suspended page work`() = runBlocking {
        for (profileSwitch in listOf(false, true)) {
            val h = Harness(); val entered = CompletableDeferred<Unit>(); val exited = CompletableDeferred<Unit>()
            h.source.respond = { _, _ -> try { entered.complete(Unit); awaitCancellation() } finally { exited.complete(Unit) } }
            val pending = async(Dispatchers.Default) { runCatching { h.load() } }
            withTimeout(3_000) { entered.await() }
            if (profileSwitch) { h.revision.incrementAndGet(); h.profile.set(2); h.changes.value = 2 }
            else pending.cancel()
            withTimeout(3_000) { exited.await() }
            if (profileSwitch) assertTrue(withTimeout(3_000) { pending.await().exceptionOrNull() } is CancellationException)
            else pending.join()
        }
    }
    @Test fun `conflated away back profile revision cannot publish or warm cache with late result`() = runBlocking {
        val h = Harness()
        h.source.respond = { _, _ -> h.revision.addAndGet(2); h.defaultPage() }
        assertTrue(runCatching { h.load() }.exceptionOrNull() is CancellationException)
        h.source.respond = { _, _ -> h.defaultPage() }
        h.load(); assertEquals(2, h.source.calls.size)
    }
    @Test fun `upstream page exceeding twenty five records is rejected without caching`() = runBlocking {
        val h = Harness()
        h.source.respond = { _, _ -> RatedKidsPage(List(26) { h.defaultPage().items.single() }, true) }
        repeat(2) { assertTrue(runCatching { h.load() }.isFailure) }
        assertEquals(2, h.source.calls.size)
    }
    @Test fun `public source concurrency never exceeds two across different feeds`() = runBlocking {
        val h = Harness(); val release = CompletableDeferred<Unit>()
        val active = AtomicInteger(); val maximum = AtomicInteger(); val entered = CompletableDeferred<Unit>()
        h.source.respond = { _, _ ->
            val count = active.incrementAndGet(); maximum.updateAndGet { maxOf(it, count) }
            if (count == 2) entered.complete(Unit)
            try { release.await(); h.defaultPage() } finally { active.decrementAndGet() }
        }
        val pending = listOf("family-movies", "family-shows", "education-shows").map { id ->
            async(Dispatchers.Default) { h.catalog.catalog(if (id == "family-movies") ContentType.MOVIE else ContentType.SERIES, id, 0, emptyMap()) }
        }
        withTimeout(3_000) { entered.await() }
        assertEquals(2, h.source.calls.size)
        release.complete(Unit); withTimeout(3_000) { pending.awaitAll() }
        assertEquals(2, maximum.get())
    }
    private class Harness {
        val profile = AtomicInteger(1); val revision = AtomicLong(1); val changes = MutableStateFlow(1); val clock = AtomicLong(1)
        val source = FakeSource().apply { respond = { _, _ -> defaultPage() } }
        val catalog = RatedKidsCatalog(source, profile::get, revision::get, changes, clock::get)
        fun defaultPage() = RatedKidsPage(listOf(RatedKidsCatalogCodec.movie(RatedKidsCatalogCodecTest.movie(), RatedKidsCatalogCodecTest.descriptor("family-movies"))!!), true)
        suspend fun load(skip: Int = 0, extra: Map<String, String> = emptyMap()) = catalog.catalog(ContentType.MOVIE, "family-movies", skip, extra)
    }
    private class FakeSource : RatedKidsPageSource {
        override var configured = true
        val calls = CopyOnWriteArrayList<Pair<String, Int>>()
        lateinit var respond: suspend (RatedKidsDescriptor, Int) -> RatedKidsPage
        override suspend fun page(descriptor: RatedKidsDescriptor, page: Int): RatedKidsPage {
            calls += descriptor.id to page
            return respond(descriptor, page)
        }
    }
}
