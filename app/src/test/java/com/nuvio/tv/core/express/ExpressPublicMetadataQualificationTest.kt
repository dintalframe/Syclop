package com.nuvio.tv.core.express

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.mal.MalMetadataService
import com.nuvio.tv.data.remote.api.TmdbApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit public metadata observations, never a provider/playback or coverage test. */
class ExpressPublicMetadataQualificationTest {
    @Test fun frozenCorpusMetadataThroughActualResolver() {
        assumeTrue("Explicit public metadata fixture not selected.", env("SYCLOP_PUBLIC_METADATA_FIXTURE") == "1")
        val source = Path.of(requiredEnv("SYCLOP_PUBLIC_METADATA_SOURCE_ROOT")).toRealPath()
        val corpusPath = Path.of(requiredEnv("SYCLOP_PUBLIC_METADATA_CORPUS")).toRealPath()
        val output = Path.of(requiredEnv("SYCLOP_PUBLIC_METADATA_REPORT")).toAbsolutePath().normalize()
        require(Files.isDirectory(output.parent) && !Files.exists(output)) { "Use a new report in an existing owned directory." }
        require(output.fileName.toString() == "result.json") { "The explicit report name must be result.json." }
        val bindings = SOURCE_HASHES.mapValues { (path, expected) ->
            sha(Files.readAllBytes(source.resolve(path))).also { require(it == expected) { "Metadata source changed: $path" } }
        }
        val fixtureHash = sha(Files.readAllBytes(source.resolve(FIXTURE_PATH)))
        require(fixtureHash == requiredEnv("SYCLOP_PUBLIC_METADATA_FIXTURE_SHA256")) { "Fixture source changed." }
        val corpusBytes = Files.readAllBytes(corpusPath)
        require(sha(corpusBytes) == CORPUS_SHA256) { "The frozen corpus changed." }
        val corpus = JsonParser.parseString(corpusBytes.toString(Charsets.UTF_8)).asJsonObject
        val entries = corpus.getAsJsonArray("entries").map { it.asJsonObject }
        require(entries.size == 36 && entries.map { it["case_id"].asString }.toSet().size == 36)
        val inputs = entries.filter { !it.getAsJsonObject("app_ids")["imdb"].isJsonNull }.map(::input)
        require(inputs.map { it.id }.toSet() == CASE_IDS && inputs.size == 24)
        require(inputs.count { it.type == "movie" } == 14 && inputs.count { it.type == "series" && it.season != null } == 9)
        require(inputs.single { it.id == "TR10" }.let { it.season == null && it.episode == null })

        val api = mockk<TmdbApi>()
        val mal = mockk<MalMetadataService>()
        val tmdbCalls = AtomicInteger()
        val malCalls = AtomicInteger()
        coEvery { api.findByExternalId(any(), any(), any()) } coAnswers {
            tmdbCalls.incrementAndGet(); throw AssertionError("TMDB must not be called.")
        }
        coEvery { mal.sourceMetadata(any()) } coAnswers {
            malCalls.incrementAndGet(); throw AssertionError("MAL must not be called.")
        }
        val tmdb = TmdbService(api, "")
        val rateLimited = AtomicBoolean(false)
        val results = mutableListOf<Map<String, Any?>>()
        val report = linkedMapOf<String, Any?>(
            "schema" to 1, "scope" to "actual-resolver-public-metadata-only", "startedAt" to Instant.now().toString(),
            "corpusSha256" to CORPUS_SHA256, "sourceHashes" to bindings, "fixtureSourceSha256" to fixtureHash,
            "coreCorpusTitles" to 36, "eligibleMetadataAttempts" to 23, "coordinateAbstentionControls" to 1,
            "animePipelineNotAttempted" to 12, "tmdbCalls" to 0, "malCalls" to 0, "providerQueries" to 0,
            "accountCalls" to 0, "grantOrPlaybackCalls" to 0, "caseDeadlineMs" to 30_000,
            "networkCallCeiling" to 41, "maximumCallsPerMovie" to 1, "maximumCallsPerSeries" to 3,
            "transport" to "isolated HTTPS; production timeout values; connection retry disabled; no cookies or application credentials",
            "bodyDigests" to "bounded decoded body samples; complete only when at or below the endpoint cap; raw bodies are not retained",
            "latencyClaim" to "single cold observations with bounded capture overhead; no p95 or device claim",
            "results" to results, "observationCompleted" to false, "coverageClaim" to "NONE")
        try {
            runBlocking {
                for (input in inputs) {
                    if (rateLimited.get() && input.id != "TR10") {
                        results += mapOf("caseId" to input.id, "status" to "NOT_ATTEMPTED_RATE_LIMIT_STOP")
                        continue
                    }
                    val trace = mutableListOf<MutableMap<String, Any?>>()
                    val seen = mutableSetOf<String>()
                    val networkSeen = mutableSetOf<String>()
                    var cinemetaSucceeded = false
                    var showRedirectObserved = false
                    val cinemeta = "https://v3-cinemeta.strem.io/meta/${input.type}/${input.imdb}.json"
                    val lookup = "https://api.tvmaze.com/lookup/shows?imdb=${input.imdb}"
                    val show = input.tvmaze?.let { "https://api.tvmaze.com/shows/$it" }
                    val allowed = setOfNotNull(cinemeta, lookup.takeIf { input.type == "series" }, show)
                    val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
                        .callTimeout(12, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
                        .retryOnConnectionFailure(false).cookieJar(CookieJar.NO_COOKIES).addInterceptor { chain ->
                            val request = chain.request()
                            val url = request.url.toString()
                            val attempt = linkedMapOf<String, Any?>("targetSha256" to sha(url.toByteArray()),
                                "endpoint" to url.takeIf { it in allowed }, "networkCallStarted" to false)
                            synchronized(trace) { trace += attempt }
                            val refused = when {
                                rateLimited.get() -> "RATE_LIMIT_STOP"
                                request.method != "GET" || request.url.scheme != "https" || request.url.port != 443 -> "REQUEST_SHAPE"
                                request.header("Authorization") != null || request.header("Cookie") != null ||
                                    request.header("Proxy-Authorization") != null -> "CREDENTIAL_HEADER"
                                url !in allowed -> "ENDPOINT_NOT_ALLOWED"
                                !seen.add(url) -> "REPEATED_ENDPOINT"
                                url != cinemeta && !cinemetaSucceeded -> "CATALOG_NOT_RESOLVED"
                                url == show && !showRedirectObserved -> "UNOBSERVED_SHOW_REDIRECT"
                                seen.size > 3 -> "REQUEST_CEILING"
                                else -> null
                            }
                            if (refused != null) {
                                synchronized(trace) { attempt["refused"] = refused }
                                throw IOException("Public metadata fixture boundary refused.")
                            }
                            val began = System.nanoTime()
                            try {
                                synchronized(trace) { attempt["networkCallStarted"] = true }
                                val response = chain.proceed(request)
                                try {
                                    if (response.code == 429) rateLimited.set(true)
                                    synchronized(trace) { attempt["status"] = response.code }
                                    if (url == cinemeta && response.code == 200) cinemetaSucceeded = true
                                    val target = response.header("Location")?.let(request.url::resolve)
                                    if (url == lookup && response.code in 300..399 && target?.toString() == show) showRedirectObserved = true
                                    val cap = if (url == cinemeta) 2 * 1024 * 1024 else 512 * 1024
                                    val sample = response.peekBody(cap.toLong() + 1).bytes()
                                    val projection = if (url == cinemeta && response.code == 200 && sample.size <= cap) {
                                        runCatching {
                                            val meta = JsonParser.parseString(sample.toString(Charsets.UTF_8)).asJsonObject
                                                .getAsJsonObject("meta")
                                            listOf("id", "imdb_id", "type", "name", "year", "releaseInfo").associateWith { key ->
                                                meta?.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.take(256)
                                            }
                                        }.getOrNull()
                                    } else null
                                    synchronized(trace) {
                                        attempt["bodySampleBytes"] = sample.size; attempt["bodySampleSha256"] = sha(sample)
                                        attempt["bodySampleComplete"] = sample.size <= cap; attempt["catalogFields"] = projection
                                        attempt["redirectObserved"] = target != null
                                        attempt["redirectTargetSha256"] = target?.toString()?.toByteArray()?.let(::sha)
                                        attempt["redirectTargetAllowed"] = target?.toString()?.let { it in allowed }
                                    }
                                    response
                                } catch (failure: Exception) {
                                    response.close()
                                    throw failure
                                }
                            } catch (failure: Exception) {
                                synchronized(trace) { attempt["failureClass"] = failure.javaClass.simpleName }
                                throw failure
                            } finally {
                                synchronized(trace) { attempt["durationMs"] = (System.nanoTime() - began) / 1_000_000 }
                            }
                        }.addNetworkInterceptor { chain ->
                            val attempt = synchronized(trace) { trace.last() }
                            if (!networkSeen.add(chain.request().url.toString())) {
                                synchronized(trace) { attempt["automaticRetryRefused"] = true }
                                throw IOException("Automatic public metadata retry refused.")
                            }
                            synchronized(trace) { attempt["networkAttempts"] = 1 }
                            chain.proceed(chain.request()).also { response ->
                                if (response.code == 429) rateLimited.set(true)
                                synchronized(trace) { attempt["status"] = response.code }
                            }
                        }.build()
                    val began = System.nanoTime()
                    val observation = linkedMapOf<String, Any?>("caseId" to input.id, "imdb" to input.imdb,
                        "requestedType" to input.type, "workYear" to input.year, "season" to input.season,
                        "episode" to input.episode, "coordinateEvidence" to if (input.season == null) "NONE" else "FROZEN_TVMAZE_AIRED_ASSERTION_ONLY",
                        "predeclaredYearConflict" to (input.id in setOf("TR04", "IN06", "IN09")))
                    try {
                        val result = withTimeout(30_000) {
                            ExpressMetadataResolver(ExpressHttpClient(client), tmdb, mal)
                                .resolve(ExpressMediaRequest(input.type, input.imdb, input.season, input.episode))
                        }
                        assertNotEquals("TR10 must abstain before a catalog request", "TR10", input.id)
                        assertEquals(input.imdb, result.imdbId); assertEquals(input.type, result.type)
                        assertEquals(input.season, result.season); assertEquals(input.episode, result.episode)
                        assertNull(result.absoluteNumber); assertNull(result.nativeAnimeIdentity); assertNull(result.nativeAnimeEpisode)
                        val queries = result.queryTitles()
                        assertTrue(queries.isNotEmpty() && queries.size <= 3 && queries.distinct() == queries)
                        assertTrue(queries.all { it.isNotBlank() && it.length <= 256 && it.none(Char::isISOControl) })
                        val catalogType = synchronized(trace) {
                            (trace.firstOrNull { it["endpoint"] == cinemeta }?.get("catalogFields") as? Map<*, *>)?.get("type")
                        }
                        observation["catalogType"] = catalogType
                        observation["status"] = when {
                            catalogType == null -> "METADATA_TYPE_UNKNOWN"
                            catalogType != input.type -> "METADATA_TYPE_CONFLICT"
                            result.year == null -> "METADATA_YEAR_UNKNOWN"
                            result.year == input.year -> "METADATA_RESOLVED"
                            else -> "METADATA_YEAR_CONFLICT"
                        }
                        observation["title"] = result.title.take(256); observation["catalogYear"] = result.year
                        observation["optionalAlias"] = result.originalTitle
                        observation["optionalAliasStatus"] = when {
                            result.originalTitle != null -> "RESOLVED_FOR_SAME_IDENTITY"
                            input.type == "movie" -> "TMDB_NOT_CONFIGURED_NO_KEYLESS_MOVIE_FALLBACK"
                            else -> "NOT_RESOLVED"
                        }
                        observation["queryTitles"] = queries
                    } catch (_: TimeoutCancellationException) {
                        observation["status"] = "HARNESS_DEADLINE"
                    } catch (failure: ExpressException) {
                        if (input.id == "TR10") {
                            assertEquals("Verified season and episode metadata is required.", failure.message)
                            assertTrue(synchronized(trace) { trace.isEmpty() })
                            observation["status"] = "EXPECTED_PRE_NETWORK_COORDINATE_ABSTENTION"
                        } else {
                            observation["status"] = when {
                                rateLimited.get() -> "RATE_LIMITED"
                                failure.message == "Catalog metadata did not match the requested IMDb ID." -> "METADATA_ID_MISMATCH"
                                failure is ExpressHttpException -> "HTTP_${failure.failure.name}"
                                else -> "METADATA_REJECTED"
                            }
                            observation["failureClass"] = failure.javaClass.simpleName
                        }
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (failure: Exception) {
                        observation["status"] = "METADATA_EXCEPTION"
                        observation["failureClass"] = failure.javaClass.simpleName
                    } finally {
                        client.dispatcher.cancelAll(); client.connectionPool.evictAll()
                        client.dispatcher.executorService.shutdown()
                        assertTrue("Owned metadata client did not stop", client.dispatcher.executorService.awaitTermination(2, TimeUnit.SECONDS))
                        observation["durationMs"] = (System.nanoTime() - began) / 1_000_000
                        observation["requests"] = synchronized(trace) { trace.map { it.toMap() } }
                        results += observation
                    }
                }
            }
            coVerify(exactly = 0) { api.findByExternalId(any(), any(), any()) }
            coVerify(exactly = 0) { mal.sourceMetadata(any()) }
            assertEquals(24, results.size)
            assertEquals("TR10 must complete its required pre-network abstention control",
                "EXPECTED_PRE_NETWORK_COORDINATE_ABSTENTION", results.single { it["caseId"] == "TR10" }["status"])
            report["observationCompleted"] = true
            report["rateLimitStop"] = rateLimited.get()
            report["allEligibleMetadataMatchedWorkYear"] = results.count { it["status"] == "METADATA_RESOLVED" } == 23
        } catch (failure: Throwable) {
            report["fatalFailureClass"] = failure.javaClass.simpleName
            throw failure
        } finally {
            report["tmdbCalls"] = tmdbCalls.get(); report["malCalls"] = malCalls.get()
            report["finishedAt"] = Instant.now().toString()
            Files.write(output, (GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(report) + "\n").toByteArray(Charsets.UTF_8), CREATE_NEW, WRITE)
        }
    }

    private data class Input(val id: String, val imdb: String, val type: String, val year: Int,
        val season: Int?, val episode: Int?, val tvmaze: Int?)

    private fun input(entry: JsonObject): Input {
        val id = entry["case_id"].asString
        val ids = entry.getAsJsonObject("app_ids")
        val movie = entry["work_type"].asString == "MOVIE"
        val coordinates = if (movie) null else entry.getAsJsonArray("samples").map { it.asJsonObject }
            .single { it["sample_id"].asString == "$id-first" }["app_coordinates"]?.takeUnless { it.isJsonNull }?.asJsonObject
        val imdb = ids["imdb"].asString.also { require(it.matches(Regex("tt[0-9]+"))) }
        return Input(id, imdb, if (movie) "movie" else "series", entry["year"].asInt,
            coordinates?.get("season")?.asInt, coordinates?.get("episode")?.asInt, ids["tvmaze"]?.takeUnless { it.isJsonNull }?.asInt)
    }

    private fun env(name: String) = System.getenv(name)
    private fun requiredEnv(name: String) = requireNotNull(env(name)?.takeIf(String::isNotBlank)) { "Set $name explicitly." }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val CORPUS_SHA256 = "b8db3bcd42840456197d9a41adb564f7d26750fd74f33a408800312c0e454851"
        const val FIXTURE_PATH = "app/src/test/java/com/nuvio/tv/core/express/ExpressPublicMetadataQualificationTest.kt"
        val CASE_IDS = ((1..12).map { "TR%02d".format(it) } + (1..12).map { "IN%02d".format(it) }).toSet()
        val SOURCE_HASHES = mapOf(
            "app/src/main/java/com/nuvio/tv/core/express/ExpressMedia.kt" to "772a131050fe8dab6a965d222b3314a3d26c866505cdebced76b713cbfef45f7",
            "app/src/main/java/com/nuvio/tv/core/express/ExpressHttpClient.kt" to "fd15f57379a00884d118b6a78df0c5dcad52785562dc82521796c392573fda93",
            "app/src/main/java/com/nuvio/tv/core/tmdb/TmdbService.kt" to "a27024eacc26487f9c8d91a7e98d54c54bc411c340a9c5439eb8d1fa5bd7c715")
    }
}
