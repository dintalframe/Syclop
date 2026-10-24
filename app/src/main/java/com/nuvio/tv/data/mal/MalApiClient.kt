package com.nuvio.tv.data.mal

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.io.IOException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class MalApiException(val statusCode: Int) : Exception("MyAnimeList request failed (HTTP $statusCode).")

/** Dedicated HTTPS client without request/response logging or shared authentication interceptors. */
@Singleton
class MalApiClient internal constructor(
    private val client: OkHttpClient,
    private val apiUrl: String,
    private val tokenUrl: String
) {
    @Inject constructor() : this(OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build(), API_URL, TOKEN_URL)
    private val json = Json { ignoreUnknownKeys = true }

    fun authorizationUrl(configuration: MalConfiguration, state: String, verifier: String): String =
        "https://myanimelist.net/v1/oauth2/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", configuration.clientId)
            .addQueryParameter("redirect_uri", configuration.redirectUri)
            .addQueryParameter("state", state)
            .addQueryParameter("code_challenge", verifier)
            .addQueryParameter("code_challenge_method", "plain").build().toString()

    suspend fun exchange(configuration: MalConfiguration, code: String, verifier: String): MalTokens {
        val body = FormBody.Builder().add("client_id", configuration.clientId)
            .add("grant_type", "authorization_code").add("code", code)
            .add("redirect_uri", configuration.redirectUri).add("code_verifier", verifier).build()
        return tokens(request(Request.Builder().url(tokenUrl).post(body).build()), null)
    }

    suspend fun refresh(configuration: MalConfiguration, previous: MalTokens): MalTokens {
        val body = FormBody.Builder().add("client_id", configuration.clientId)
            .add("grant_type", "refresh_token").add("refresh_token", previous.refreshToken).build()
        return tokens(request(Request.Builder().url(tokenUrl).post(body).build()), previous.refreshToken)
    }

    suspend fun username(token: String): String = get("users/@me", token).string("name")
        ?.takeIf(String::isNotBlank) ?: error("MyAnimeList did not return an account name.")

    suspend fun library(token: String): MalLibrary {
        val result = mutableListOf<MalAnime>()
        var next = true
        var page = 0
        while (next && page < 20) {
            val root = get("users/@me/animelist", token, mapOf(
                "limit" to "100", "offset" to (page * 100).toString(), "sort" to "list_updated_at",
                "fields" to "list_status,$ANIME_FIELDS"
            ))
            root["data"]?.jsonArray.orEmpty().forEach { item ->
                val obj = item.jsonObject
                val node = obj["node"]?.jsonObject ?: return@forEach
                result += anime(node, obj["list_status"]?.jsonObject)
            }
            next = !root["paging"]?.jsonObject?.string("next").isNullOrBlank()
            page++
        }
        return MalLibrary(result.distinctBy(MalAnime::id), truncated = next)
    }

    suspend fun detail(id: Long, token: String): MalAnime {
        require(id > 0)
        val root = get("anime/$id", token, mapOf("fields" to "$ANIME_FIELDS,my_list_status"))
        return anime(root, root["my_list_status"]?.jsonObject)
    }

    suspend fun search(query: String, token: String): List<MalAnime> {
        require(query.trim().length >= 3) { "Enter at least three characters." }
        val root = get("anime", token, mapOf("q" to query.trim(), "limit" to "10", "fields" to "$ANIME_FIELDS,my_list_status"))
        return root["data"]?.jsonArray.orEmpty().mapNotNull { element ->
            element.jsonObject["node"]?.jsonObject?.let { anime(it, it["my_list_status"]?.jsonObject) }
        }
    }

    /** Public metadata uses the application's own client ID, never a provider's account key. */
    suspend fun publicDetail(id: Long, clientId: String): MalAnime {
        require(id > 0)
        return anime(publicGet("anime/$id", clientId, mapOf("fields" to ANIME_FIELDS)), null)
    }

    suspend fun publicSearch(query: String, offset: Int, clientId: String): MalPage {
        require(query.trim().length >= 3 && query.length <= 200 && offset >= 0)
        return page(publicGet("anime", clientId, mapOf("q" to query.trim(), "limit" to PAGE_SIZE.toString(),
            "offset" to offset.toString(), "fields" to ANIME_FIELDS)))
    }

    suspend fun ranking(offset: Int, clientId: String): MalPage {
        require(offset >= 0)
        return page(publicGet("anime/ranking", clientId, mapOf("ranking_type" to "all",
            "limit" to PAGE_SIZE.toString(), "offset" to offset.toString(), "fields" to ANIME_FIELDS)))
    }

    suspend fun libraryPage(status: MalListStatus, offset: Int, token: String): MalPage {
        require(offset >= 0)
        return page(get("users/@me/animelist", token, mapOf("status" to status.wireValue,
            "sort" to "list_updated_at", "limit" to PAGE_SIZE.toString(), "offset" to offset.toString(),
            "fields" to "list_status,$ANIME_FIELDS")))
    }

    private fun page(root: JsonObject): MalPage = MalPage(
        items = root["data"]?.jsonArray.orEmpty().mapNotNull { element ->
            val entry = element.jsonObject
            entry["node"]?.jsonObject?.let { anime(it, entry["list_status"] as? JsonObject) }
        }.distinctBy(MalAnime::id),
        hasMore = !root["paging"]?.jsonObject?.string("next").isNullOrBlank()
    )

    private suspend fun publicGet(path: String, clientId: String, parameters: Map<String, String>): JsonObject {
        require(clientId.matches(Regex("[A-Za-z0-9_-]{8,128}"))) { "Syclop's MyAnimeList application is not configured." }
        val url = "$apiUrl/$path".toHttpUrl().newBuilder().also { builder ->
            parameters.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        }.build()
        return request(Request.Builder().url(url).header("X-MAL-CLIENT-ID", clientId).build())
    }

    suspend fun update(id: Long, status: MalListStatus, watched: Int, token: String): Unit {
        edit(id, MalListEdit(status = status, watched = watched), token)
    }

    suspend fun edit(id: Long, edit: MalListEdit, token: String) {
        require(id > 0)
        val fields = edit.fields()
        require(fields.isNotEmpty()) { "No MyAnimeList values were changed." }
        val body = FormBody.Builder().also { builder -> fields.forEach { (key, value) -> builder.add(key, value) } }.build()
        request(Request.Builder().url("$apiUrl/anime/$id/my_list_status")
            .header("Authorization", "Bearer $token").patch(body).build())
    }

    suspend fun remove(id: Long, token: String) {
        require(id > 0)
        try {
            request(Request.Builder().url("$apiUrl/anime/$id/my_list_status")
                .header("Authorization", "Bearer $token").delete().build())
        } catch (error: MalApiException) {
            // Official MAL DELETE is idempotent in effect but reports 404 when already absent.
            if (error.statusCode != 404) throw error
        }
    }

    private suspend fun get(path: String, token: String, parameters: Map<String, String> = emptyMap()): JsonObject {
        val url = "$apiUrl/$path".toHttpUrl().newBuilder().also { builder ->
            parameters.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        }.build()
        return request(Request.Builder().url(url).header("Authorization", "Bearer $token").build())
    }

    private suspend fun request(request: Request): JsonObject = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(IOException("MyAnimeList network request failed."))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (!continuation.isActive) return
                        if (!it.isSuccessful) throw MalApiException(it.code)
                        val body = it.body ?: throw IOException("MyAnimeList returned an empty response.")
                        val bytes = body.byteStream().use { input ->
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (output.size() <= MAX_RESPONSE_BYTES) {
                                val read = input.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_BYTES + 1 - output.size()))
                                if (read < 0) break
                                output.write(buffer, 0, read)
                            }
                            output.toByteArray()
                        }
                        if (bytes.size > MAX_RESPONSE_BYTES) throw IOException("MyAnimeList returned too much data.")
                        val parsed = if (bytes.isEmpty()) JsonObject(emptyMap()) else {
                            val value = json.parseToJsonElement(bytes.toString(Charsets.UTF_8))
                            if (value is JsonObject) value else if (value.toString() == "null") JsonObject(emptyMap())
                            else throw IOException("MyAnimeList returned an unsupported response.")
                        }
                        if (continuation.isActive) continuation.resume(parsed)
                    }
                } catch (failure: Exception) {
                    // Parser/transport exception messages may contain payload snippets. Never propagate them.
                    if (continuation.isActive) continuation.resumeWithException(if (failure is MalApiException) failure
                        else IOException("MyAnimeList response could not be read."))
                }
            }
        })
    }

    private fun tokens(root: JsonObject, previousRefresh: String?): MalTokens {
        val access = root.string("access_token")?.takeIf(String::isNotBlank)
            ?: error("MyAnimeList did not return an access token.")
        val refresh = root.string("refresh_token")?.takeIf(String::isNotBlank) ?: previousRefresh
            ?: error("MyAnimeList did not return a refresh token.")
        val seconds = root["expires_in"]?.jsonPrimitive?.longOrNull?.coerceIn(1, 31_536_000)
            ?: error("MyAnimeList did not return the token lifetime.")
        return MalTokens(access, refresh, System.currentTimeMillis() + seconds * 1000)
    }

    private fun anime(node: JsonObject, list: JsonObject?): MalAnime = MalAnime(
        id = node["id"]?.jsonPrimitive?.longOrNull ?: error("Anime ID is missing."),
        title = node.string("title") ?: "Anime",
        pictureUrl = node["main_picture"]?.jsonObject?.let { it.string("large") ?: it.string("medium") },
        totalEpisodes = node["num_episodes"]?.jsonPrimitive?.intOrNull ?: 0,
        mediaType = node.string("media_type"),
        status = MalListStatus.fromWire(list?.string("status")),
        watchedEpisodes = list?.get("num_episodes_watched")?.jsonPrimitive?.intOrNull ?: 0,
        score = list?.get("score")?.jsonPrimitive?.intOrNull ?: 0,
        onList = list != null,
        startDate = node.string("start_date"),
        synopsis = node.string("synopsis"),
        alternativeTitles = (node["alternative_titles"] as? JsonObject)?.let { titles ->
            listOfNotNull(titles.string("en"), titles.string("ja")) +
                (titles["synonyms"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }
        }.orEmpty().filter(String::isNotBlank).distinct(),
        genres = (node["genres"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.string("name") },
        averageEpisodeSeconds = node["average_episode_duration"]?.jsonPrimitive?.intOrNull,
        isRewatching = list?.get("is_rewatching")?.jsonPrimitive?.booleanOrNull ?: false,
        timesRewatched = list?.get("num_times_rewatched")?.jsonPrimitive?.intOrNull ?: 0,
        rewatchValue = list?.get("rewatch_value")?.jsonPrimitive?.intOrNull ?: 0,
        listUpdatedAtMs = list?.string("updated_at")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L
    )

    private fun JsonObject.string(key: String): String? = get(key)?.jsonPrimitive?.contentOrNull

    private companion object {
        const val TOKEN_URL = "https://myanimelist.net/v1/oauth2/token"
        const val API_URL = "https://api.myanimelist.net/v2"
        const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        const val PAGE_SIZE = 100
        const val ANIME_FIELDS = "num_episodes,media_type,main_picture,start_date,synopsis,alternative_titles,genres,average_episode_duration"
    }
}
