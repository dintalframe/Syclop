package com.nuvio.tv.data.mal

import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

data class MalConfiguration(val clientId: String = "", val redirectUri: String = "")
data class MalScope(val profileId: Int, val generation: Long)
data class MalTokens(val accessToken: String, val refreshToken: String, val expiresAtMs: Long) {
    override fun toString() = "MalTokens([redacted], expiresAtMs=$expiresAtMs)"
}
data class MalAccountState(
    val scope: MalScope = MalScope(1, 0),
    val configuration: MalConfiguration = MalConfiguration(),
    val connected: Boolean = false,
    val username: String? = null,
    val automaticProgress: Boolean = true
)

data class MalAuthorizationSession(
    val authorizationUrl: String,
    val redirectUri: String,
    val expiresAtMs: Long,
    val localCallbackListening: Boolean,
    val completing: Boolean = false
) {
    override fun toString() = "MalAuthorizationSession([redacted], expiresAtMs=$expiresAtMs, localCallbackListening=$localCallbackListening, completing=$completing)"
}

enum class MalListStatus(val wireValue: String, val label: String) {
    WATCHING("watching", "Watching"),
    COMPLETED("completed", "Completed"),
    ON_HOLD("on_hold", "On hold"),
    DROPPED("dropped", "Dropped"),
    PLAN_TO_WATCH("plan_to_watch", "Plan to watch");
    companion object {
        fun fromWire(value: String?) = entries.firstOrNull { it.wireValue == value } ?: PLAN_TO_WATCH
    }
}

data class MalAnime(
    val id: Long,
    val title: String,
    val pictureUrl: String? = null,
    val totalEpisodes: Int = 0,
    val mediaType: String? = null,
    val status: MalListStatus = MalListStatus.PLAN_TO_WATCH,
    val watchedEpisodes: Int = 0,
    val score: Int = 0,
    val onList: Boolean = false,
    val startDate: String? = null,
    val synopsis: String? = null,
    val alternativeTitles: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val averageEpisodeSeconds: Int? = null,
    val isRewatching: Boolean = false,
    val timesRewatched: Int = 0,
    val rewatchValue: Int = 0,
    val listUpdatedAtMs: Long = 0L
)
data class MalLibrary(val items: List<MalAnime> = emptyList(), val truncated: Boolean = false)
data class MalPage(val items: List<MalAnime>, val hasMore: Boolean)
data class MalLibraryState(
    val scope: MalScope = MalScope(1, 0),
    val items: List<MalAnime> = emptyList(),
    val loading: Boolean = false,
    val truncated: Boolean = false,
    val message: String? = null
)

/** Null fields are omitted from PATCH, preserving ratings, progress and unrelated list attributes. */
data class MalListEdit(
    val status: MalListStatus? = null,
    val watched: Int? = null,
    val score: Int? = null,
    val isRewatching: Boolean? = null,
    val timesRewatched: Int? = null,
    val rewatchValue: Int? = null
) {
    fun fields(): Map<String, String> {
        require(watched == null || watched >= 0)
        require(score == null || score in 0..10)
        require(timesRewatched == null || timesRewatched in 0..100_000)
        require(rewatchValue == null || rewatchValue in 0..5)
        return buildMap {
            status?.let { put("status", it.wireValue) }
            watched?.let { put("num_watched_episodes", it.toString()) }
            score?.let { put("score", it.toString()) }
            isRewatching?.let { put("is_rewatching", it.toString()) }
            timesRewatched?.let { put("num_times_rewatched", it.toString()) }
            rewatchValue?.let { put("rewatch_value", it.toString()) }
        }
    }

    companion object {
        fun changes(original: MalAnime, status: MalListStatus, watched: Int, score: Int,
            rewatching: Boolean, timesRewatched: Int, rewatchValue: Int): MalListEdit = MalListEdit(
            status.takeIf { !original.onList || it != original.status },
            watched.takeIf { !original.onList || it != original.watchedEpisodes },
            score.takeIf { it != original.score },
            rewatching.takeIf { it != original.isRewatching },
            timesRewatched.takeIf { it != original.timesRewatched },
            rewatchValue.takeIf { it != original.rewatchValue }
        )
    }
}

/** Pure protocol rules shared with tests. No Android state or credentials are logged. */
object MalRules {
    const val CALLBACK_PORT = 17893
    const val CALLBACK_PATH = "/mal/callback"
    const val AUTHORIZATION_DURATION_MS = 5 * 60 * 1000L
    const val COMPLETION_PERCENT = 80.0

    fun randomUrlSafe(bytes: Int, random: SecureRandom = SecureRandom()): String {
        val data = ByteArray(bytes).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data)
    }

    fun validateConfiguration(configuration: MalConfiguration) {
        require(configuration.clientId.matches(Regex("[A-Za-z0-9_-]{8,128}"))) {
            "Enter the client ID from your own MyAnimeList app registration."
        }
        val uri = runCatching { URI(configuration.redirectUri) }.getOrNull()
        require(uri != null && !uri.scheme.isNullOrBlank() && uri.rawFragment == null && uri.rawQuery == null) {
            "Enter the exact redirect URI registered with MyAnimeList, without query parameters."
        }
        require(uri!!.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null) {
            "Use the registered HTTP or HTTPS redirect URI."
        }
    }

    fun isLocalCallback(configuration: MalConfiguration): Boolean {
        val uri = runCatching { URI(configuration.redirectUri) }.getOrNull() ?: return false
        return uri.scheme == "http" && uri.port == CALLBACK_PORT && uri.path == CALLBACK_PATH
    }

    fun callbackCode(returnedUri: String, expectedUri: String, expectedState: String, localReturnUri: String? = null): String {
        require(returnedUri.length <= 8192 && expectedState.length <= 2048)
        val actual = URI(returnedUri.trim())
        val expected = buildList {
            add(URI(expectedUri))
            if (localReturnUri != null) {
                require(MalRelayState.isRegisteredRedirect(expectedUri) && MalRelayState.isPrivateReturnUri(localReturnUri))
                add(URI(localReturnUri))
            }
        }
        require(expected.any { actual.scheme == it.scheme && actual.rawAuthority == it.rawAuthority &&
            actual.rawPath == it.rawPath } && actual.rawFragment == null) { "The callback URI does not match this connection." }
        val query = actual.rawQuery.orEmpty().split('&').filter(String::isNotBlank).map {
            val name = URLDecoder.decode(it.substringBefore('='), "UTF-8")
            name to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
        }
        require(query.map { it.first }.distinct().size == query.size) { "The callback contains duplicate parameters." }
        val values = query.toMap()
        require(values["error"] == null) { "MyAnimeList authorization was cancelled or denied." }
        require(MessageDigest.isEqual(values["state"].orEmpty().toByteArray(), expectedState.toByteArray())) {
            "The callback state is invalid. Start a new connection."
        }
        return values["code"].orEmpty().also {
            require(it.isNotBlank() && it.length <= 4096 && !it.contains('\n') && !it.contains('\r')) {
                "The callback does not contain a valid authorization code."
            }
        }
    }

    fun completedEpisode(progress: Double, season: Int?, episode: Int?, isMovie: Boolean, total: Int): Int? {
        if (!progress.isFinite() || progress < COMPLETION_PERCENT || progress > 100.0) return null
        // MAL entries are season-specific. Never guess how season 2 maps to another MAL entry.
        if (season != null && season != 1) return null
        val watched = episode?.takeIf { it > 0 } ?: if (isMovie) 1 else return null
        if (total > 0 && watched > total) return null
        return watched
    }

    fun progressUpdate(current: MalAnime, completedEpisode: Int): Pair<MalListStatus, Int>? {
        if (completedEpisode <= current.watchedEpisodes) return null
        val status = if (current.isRewatching) current.status else if (current.totalEpisodes > 0 && completedEpisode >= current.totalEpisodes) {
            MalListStatus.COMPLETED
        } else MalListStatus.WATCHING
        return status to completedEpisode
    }
}
