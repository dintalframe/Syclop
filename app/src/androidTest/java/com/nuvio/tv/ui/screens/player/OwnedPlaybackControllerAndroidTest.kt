@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Xml
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.common.NuvioEngineConfig
import android.os.Process
import kotlinx.coroutines.Job
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.MainActivity
import com.nuvio.tv.NuvioApplication
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.backup.BackupRestoreBootstrap
import com.nuvio.tv.core.debrid.*
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.streams.ConnectedServicePlaybackGrants
import com.nuvio.tv.core.streams.SyclopPlaybackPolicy
import com.nuvio.tv.core.tracking.TrackingProviderRegistry
import com.nuvio.tv.core.util.findCause
import com.nuvio.tv.core.tracking.TrackingScrobbleCoordinator
import com.nuvio.tv.data.local.*
import com.nuvio.tv.data.mal.MalCatalog
import com.nuvio.tv.data.remote.api.RealDebridApi
import com.nuvio.tv.data.remote.dto.RealDebridAddTorrentDto
import com.nuvio.tv.data.remote.dto.RealDebridTorrentInfoDto
import com.nuvio.tv.data.remote.dto.RealDebridUnrestrictLinkDto
import com.nuvio.tv.domain.model.*
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.SubtitleRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.AssumptionViolatedException
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import retrofit2.Response

/** Explicit owned-emulator fixture: real controller/codecs, fake native API and byte transport. */
@RunWith(AndroidJUnit4::class)
class OwnedPlaybackControllerAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun nativeGrantAllowsRealControllerDecodeSeekAndRevocationStopsIt() {
        val invocationBegan = SystemClock.elapsedRealtime()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Manual fixture requires explicit owned_playback_fixture=1.",
            arguments.getString("owned_playback_fixture") == "1")
        val context = instrumentation.targetContext
        val destination = File(requireNotNull(context.getExternalFilesDir(null)), "owned-playback-fixture/result.json")
        check(!destination.exists() || destination.delete()) { "Could not remove the previous fixture report." }
        require(BuildConfig.LOCAL_ONLY_BUILD && isQemu()) { "Owned playback refuses a physical/nonlocal build." }
        requireNoAccounts(context)
        val mediaRoot = File(context.cacheDir, "owned-playback-fixture").canonicalFile
        val media = File(mediaRoot, "fixture.mp4")
        require(mediaRoot.parentFile == context.cacheDir.canonicalFile && media.canonicalFile.parentFile == mediaRoot) {
            "Owned media must remain inside its fixed cache directory."
        }
        require(media.isFile && media.length() in 1L..(16L * 1024L * 1024L)) { "Supply the bounded owned MP4 in the fixed cache path." }
        val expectedHash = arguments.getString("owned_playback_fixture_sha256").orEmpty()
        require(expectedHash.matches(Regex("[0-9a-f]{64}"))) { "Supply the owned media SHA256." }
        val bytes = media.readBytes()
        val actualHash = sha256(bytes)
        require(actualHash == expectedHash) { "Owned media hash mismatch." }
        val report = JSONObject().put("schema", 1).put("passed", false)
            .put("scope", "owned-controller-exo-byte-transport")
            .put("mediaSha256", actualHash).put("mediaBytes", bytes.size)
            .put("api", Build.VERSION.SDK_INT).put("abi", Build.SUPPORTED_ABIS.first())
            .put("appSha256", sha256(File(context.applicationInfo.sourceDir)))
            .put("speechObserved", false).put("playerScreenJourney", false)
        val nextMode = when (arguments.getString("owned_playback_next")) {
            null -> false
            "1" -> true
            else -> error("Optional Next requires explicit owned_playback_next=1.")
        }
        val readFaultMode = when (arguments.getString("owned_playback_read_fault")) {
            null -> false
            "1" -> true
            else -> error("Read fault requires explicit owned_playback_read_fault=1.")
        }
        val soakMode = when (arguments.getString("owned_playback_soak")) {
            null -> false
            "1" -> true
            else -> error("Soak requires explicit owned_playback_soak=1.")
        }
        require(listOf(nextMode, readFaultMode, soakMode).count { it } <= 1) { "Owned modes are separate qualifications." }
        if (soakMode) require(actualHash == NEXT_SOURCE_SHA256) { "Soak requires the exact owned 130s media." }
        if (readFaultMode) {
            require(actualHash == NEXT_SOURCE_SHA256) { "Read fault requires the exact owned 130s source media." }
            report.put("readFaultMode", true)
        }
        val nextBytes = if (nextMode) {
            require(actualHash == NEXT_SOURCE_SHA256) { "Next requires the exact owned source media." }
            val nextMedia = File(mediaRoot, "fixture-next.mp4")
            require(nextMedia.canonicalFile.parentFile == mediaRoot && nextMedia.isFile &&
                nextMedia.length() in 1L..(16L * 1024L * 1024L)) { "Supply bounded Next media in its fixed cache path." }
            require(arguments.getString("owned_playback_next_sha256") == NEXT_MEDIA_SHA256) { "Supply the pinned Next SHA256." }
            nextMedia.readBytes().also {
                require(sha256(it) == NEXT_MEDIA_SHA256) { "Owned Next media hash mismatch." }
                report.put("nextMode", true).put("nextMediaSha256", sha256(it)).put("nextMediaBytes", it.size)
            }
        } else null
        val source = OwnedBytes(bytes, nextBytes)
        val images = AtomicInteger()
        val soakImages = if (soakMode) OwnedSoakImages(images) else null
        val soakReport = if (soakMode) JSONObject().put("passed", false).put("status", "preparing")
            .put("nativeGraphicsBuffersEnumerated", false).also { report.put("soak", it) } else null
        var finalSoakAllocator: Allocator? = null
        val reader = ImageReader.newInstance(1280, 720, ImageFormat.PRIVATE, 6)
        val outputThread = HandlerThread("owned-controller-output").apply { start() }
        reader.setOnImageAvailableListener({ available ->
            if (soakImages == null) {
                while (true) {
                    val image = available.acquireNextImage() ?: break
                    image.close()
                    images.incrementAndGet()
                }
            } else try {
                while (true) soakImages.close(available.acquireNextImage() ?: break)
            } catch (failure: Throwable) { soakImages.failure.compareAndSet(null, failure) }
        }, Handler(outputThread.looper))
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var template: PlayerRuntimeController
                scenario.onActivity { template = ViewModelProvider(it)[PlayerViewModel::class.java].controller }
                val store = requireNotNull(template.debridSettingsDataStore)
                val profiles = field<ProfileManager>(template.playerSettingsDataStore, "profileManager")
                runBlocking { withTimeout(10_000L) { profiles.activeProfileReady.first { it } } }
                val profile = profiles.activeProfileId.value
                require(!runBlocking { store.settings.first().hasAnyApiKey }) { "Connected service found; fixture refused." }
                val addonPreferences = field<AddonPreferences>(template.addonRepository, "preferences")
                val knownDefaults = mapOf("https://v3-cinemeta.strem.io" to "com.linvo.cinemeta",
                    "https://opensubtitles-v3.strem.io" to "org.stremio.opensubtitlesv3")
                fun publicMal(addon: Addon) = addon.id == MalCatalog.ADDON_ID && addon.baseUrl == MalCatalog.BASE_URL &&
                    addon.resources.all { it.name in setOf("catalog", "meta") } &&
                    addon.catalogs.all { it.id in setOf("popular", "ranking", "search") }
                require(runBlocking {
                    addonPreferences.installedAddonConfiguration.first().ownerProfileId == profile &&
                        addonPreferences.installedUrlsForProfiles(listOf(profile)).all { it in knownDefaults } &&
                        template.addonRepository.getInstalledAddons().first().all {
                            publicMal(it) || knownDefaults[it.baseUrl] == it.id
                        }
                }) { "Unexpected installed addons or owner; fixture refused." }
                require(!template.tmdbSettingsDataStore.settings.value.enabled) { "TMDB must be disabled on the owned guest." }
                val factory = field<ProfileDataStoreFactory>(template.playerSettingsDataStore, "factory")
                val snapshots = runBlocking {
                    listOf("player_settings", "debrid_settings", "track_preference", "binge_group_cache", "addon_preferences").associateWith {
                        factory.get(profile, it).data.first()
                    }
                }
                val job = SupervisorJob()
                val scope = CoroutineScope(job + Dispatchers.Main.immediate)
                val catalog = if (nextMode) OwnedNextPlaybackCatalog(CONTENT_ID, EPISODE_TWO_ID, INPUT_URL_NEXT) else null
                val api = OwnedNativeApi(bytes.size.toLong(), nextBytes?.size?.toLong())
                var progress = OwnedProgress(template.watchProgressRepository, profile, nextMode)
                var controllerChild: Job? = if (soakMode) SupervisorJob(job) else null
                var controller: PlayerRuntimeController? = null
                try {
                    runBlocking {
                        template.addonRepository.setAddonOrder(emptyList())
                        withTimeout(10_000L) {
                            addonPreferences.installedAddonConfiguration.first {
                                it.ownerProfileId == profile && !it.hasAddons
                            }
                            template.addonRepository.getInstalledAddons().first { it.all(::publicMal) }
                        }
                        store.setCloudLibraryEnabled(false)
                        store.setConnectedServiceFallbackEnabled(false)
                        template.playerSettingsDataStore.apply {
                            setPlayerPreference(PlayerPreference.INTERNAL)
                            setInternalPlayerEngine(InternalPlayerEngine.EXOPLAYER)
                            setAutoSwitchInternalPlayerOnError(false)
                            setUseParallelConnections(false)
                            setVodCacheEnabled(false)
                            setTunnelingEnabled(false)
                            setFrameRateMatchingMode(FrameRateMatchingMode.OFF)
                            setSkipIntroEnabled(false)
                            setParentalGuideEnabled(false)
                            setUseLibass(false)
                            setStreamReuseLastLinkEnabled(false)
                            if (nextMode) {
                                setStreamAutoPlayMode(StreamAutoPlayMode.FIRST_STREAM)
                                setStreamAutoPlaySource(StreamAutoPlaySource.ALL_SOURCES)
                                setStreamAutoPlayTimeoutSeconds(0)
                                setStreamAutoPlayNextEpisodeEnabled(false)
                                setPreloadNextEpisodeSources(false)
                                setStreamAutoPlayPreferBingeGroupForNextEpisode(false)
                                setStreamAutoPlayReuseBingeGroup(false)
                            }
                        }
                        assertTrue(store.setProviderApiKeyForProfile(profile, DebridProviders.REAL_DEBRID_ID,
                            SYNTHETIC_CREDENTIAL, expectedApiKey = ""))
                        withTimeout(10_000L) { store.settings.first { it.realDebridApiKey == SYNTHETIC_CREDENTIAL } }
                    }
                    val original = template.directDebridResolver
                    val resolver = DirectDebridResolver(store,
                        field(original, "torboxResolver"),
                        RealDebridDirectDebridResolver(store, api, RealDebridFileSelector()),
                        field(original, "allDebridResolver"), field(original, "premiumizeResolver"),
                        field(original, "localDebridService"), scope)
                    val mediaFactory = PlayerMediaSourceFactory(context, source)
                    assertBlockedBeforeRead(mediaFactory, context, source)
                    val result = runBlocking {
                        withTimeout(10_000L) {
                            if (catalog == null) resolver.resolve(ownedStream(), null, null, allowFallback = false)
                            else resolver.resolve(catalog.episodeStream(INPUT_URL, 1), 1, 1, allowFallback = false,
                                nativeVideoId = CONTENT_ID, nativeMediaType = "series")
                        }
                    }
                    assertTrue("The real native resolver must mint the fixture grant.", result is DirectDebridResolveResult.Success)
                    result as DirectDebridResolveResult.Success
                    assertEquals(URL, result.url)
                    assertEquals(DebridProviders.REAL_DEBRID_ID, result.providerId)
                    assertEquals(1, api.calls.get())
                    assertTrue(ConnectedServicePlaybackGrants.registry.permits(URL))
                    assertFalse(runBlocking { resolver.isPlaybackAuthorized(URL, profile + 1) })
                    controller = onMain { createController(template, resolver, mediaFactory, progress, profile,
                        if (soakMode) CoroutineScope(requireNotNull(controllerChild) + Dispatchers.Main.immediate) else scope, catalog) }
                    var target = requireNotNull(controller)
                    scenario.onActivity { target.attachHostActivity(it) }
                    onMain {
                        target.progressWriteCoordinator.activateForeground(target.currentProgressWriteOwner())
                        target.startInitialPlaybackIfNeeded()
                    }
                    await(target, "real Exo creation") { target.exoPlayer != null }
                    onMain { requireNotNull(target.exoPlayer).apply { volume = 0f; setVideoSurface(reader.surface) } }
                    val began = SystemClock.elapsedRealtime()
                    await(target, "real video and audio output") {
                        val player = target.exoPlayer ?: return@await false
                        player.videoDecoderCounters?.ensureUpdated()
                        player.audioDecoderCounters?.ensureUpdated()
                        player.isPlaying && target.hasRenderedFirstFrame && images.get() > 0 &&
                            (player.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 0 &&
                            (player.audioDecoderCounters?.renderedOutputBufferCount ?: 0) > 0
                    }
                    val outputObservedAfterSurfaceMs = SystemClock.elapsedRealtime() - began
                    onMain {
                        val player = requireNotNull(target.exoPlayer)
                        assertEquals(MimeTypes.VIDEO_H264, player.videoFormat?.sampleMimeType)
                        assertEquals(MimeTypes.AUDIO_AAC, player.audioFormat?.sampleMimeType)
                        assertTrue(player.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO))
                        assertTrue(player.currentTracks.isTypeSelected(C.TRACK_TYPE_AUDIO))
                        target.onEvent(PlayerEvent.OnPlayPause)
                        assertFalse(player.playWhenReady)
                        target.onEvent(PlayerEvent.OnSeekTo(2_000L))
                    }
                    await(target, "seek through controller") {
                        val player = requireNotNull(target.exoPlayer)
                        player.playbackState == Player.STATE_READY && player.currentPosition in 1_750L..2_250L
                    }
                    onMain {
                        val player = requireNotNull(target.exoPlayer)
                        assertTrue(source.opens.get() > 0 && source.bytesRead.get() > 0L)
                        report.put("outputObservedAfterSurfaceMs", outputObservedAfterSurfaceMs)
                            .put("durationMs", player.duration).put("seekPositionMs", player.currentPosition)
                            .put("videoRenderedBuffers", player.videoDecoderCounters?.renderedOutputBufferCount)
                            .put("audioRenderedBuffers", player.audioDecoderCounters?.renderedOutputBufferCount)
                            .put("audioLanguageTag", player.audioFormat?.language ?: JSONObject.NULL)
                            .put("surfaceImages", images.get()).put("sourceOpens", source.opens.get())
                            .put("sourceBytesRead", source.bytesRead.get()).put("profileId", profile)
                            .put("initialDecodeSeekPassed", true)
                        assertNull(target.firstWatchCapture)
                    }
                    val retryPhases = JSONArray()
                    report.put("manualRetryPhases", retryPhases).put("realTransportFaultInjected", false)
                    await(target, "owned Turkish audio and English text discovery") {
                        val state = target.uiState.value
                        state.audioTracks.any { it.language in setOf("tr", "tur") } &&
                            state.subtitleTracks.any { it.language in setOf("en", "eng") }
                    }
                    onMain {
                        assertTrue("Continuation fixture requires non-placeholder media.", requireNotNull(target.exoPlayer).duration > 120_999L)
                        target.onEvent(PlayerEvent.OnPlayPause)
                        assertTrue(requireNotNull(target.exoPlayer).playWhenReady)
                        target.onEvent(PlayerEvent.OnSeekTo(45_000L))
                        val state = target.uiState.value
                        target.onEvent(PlayerEvent.OnSelectAudioTrack(state.audioTracks.indexOfFirst { it.language in setOf("tr", "tur") }))
                        target.onEvent(PlayerEvent.OnSelectSubtitleTrack(state.subtitleTracks.indexOfFirst { it.language in setOf("en", "eng") }))
                    }
                    await(target, "owned Turkish audio and decoded English cue at 45 seconds") {
                        val player = requireNotNull(target.exoPlayer)
                        player.isPlaying && player.currentPosition in 43_000L..47_000L &&
                            selectedFormat(player, C.TRACK_TYPE_AUDIO)?.language in setOf("tr", "tur") &&
                            player.audioFormat?.language in setOf("tr", "tur") &&
                            selectedFormat(player, C.TRACK_TYPE_TEXT)?.language in setOf("en", "eng") &&
                            cueText(player) == ENGLISH_CUE_AT_45_SECONDS
                    }
                    if (soakMode) {
                        val session = OwnedSoakSession(target, requireNotNull(controllerChild), progress)
                        runOwnedSoak(session, template, resolver, mediaFactory, profiles, profile, store, scenario,
                            job, reader, outputThread, requireNotNull(soakImages), images, requireNotNull(soakReport),
                            invocationBegan + OwnedPlaybackSoakChecks.OUTER_MS,
                            onChanged = { current ->
                                controller = current.controller
                                target = current.controller
                                progress = current.progress
                                controllerChild = current.job
                            }, checkpoint = {
                                check(destination.parentFile!!.isDirectory || destination.parentFile!!.mkdirs())
                                destination.writeText(report.toString(2) + "\n")
                            })
                        finalSoakAllocator = onMain { requireNotNull(target._loadControl).allocator }
                    } else {
                        onMain {
                            target.onEvent(PlayerEvent.OnPlayPause)
                            assertFalse(requireNotNull(target.exoPlayer).playWhenReady)
                        }
                        val textPhase = JSONObject().put("phase", "paused-tr-audio-en-text")
                        retryPhases.put(textPhase)
                        manualRetryWithSyntheticError(target, profiles, profile, reader, images, textPhase, ENGLISH_CUE_AT_45_SECONDS)
                        onMain { target.onEvent(PlayerEvent.OnDisableSubtitles) }
                        await(target, "manual subtitle Off before retry") {
                            val player = requireNotNull(target.exoPlayer)
                            !player.currentTracks.isTypeSelected(C.TRACK_TYPE_TEXT) && cueText(player).isEmpty() &&
                                target.uiState.value.selectedSubtitleTrackIndex == -1
                        }
                        val offPhase = JSONObject().put("phase", "paused-tr-audio-subtitles-off")
                        retryPhases.put(offPhase)
                        manualRetryWithSyntheticError(target, profiles, profile, reader, images, offPhase, null)
                        val sourcePhase = JSONObject().put("phase", "paused-tr-audio-subtitles-off-source-a-to-b").put("passed", false)
                        report.put("sourceChangePhase", sourcePhase)
                        assertBlockedBeforeRead(mediaFactory, context, source, URL_B)
                        val sourceB = runBlocking {
                            withTimeout(10_000L) {
                                if (catalog == null) resolver.resolveToPlayableStream(ownedStream(INPUT_URL_B), null, null, allowFallback = false,
                                    nativeVideoId = CONTENT_ID, nativeMediaType = "movie")
                                else resolver.resolveToPlayableStream(catalog.episodeStream(INPUT_URL_B, 1), 1, 1, allowFallback = false,
                                    nativeVideoId = CONTENT_ID, nativeMediaType = "series")
                            }
                        }
                        assertTrue("Source B must receive its own real native grant.", sourceB is DirectDebridPlayableResult.Success)
                        sourceB as DirectDebridPlayableResult.Success
                        assertEquals(URL_B, sourceB.stream.getStreamUrl())
                        assertEquals(2, api.calls.get())
                        assertFalse(runBlocking { resolver.isPlaybackAuthorized(URL_B, profile + 1) })
                        try {
                            sourceChangeWhilePaused(target, profiles, profile, store, reader, images, source, progress,
                                sourceB.stream, sourcePhase)
                        } finally {
                            sourcePhase.put("progressAudit", progress.snapshotAudit())
                                .put("sourceResumeReadPositionMs", progress.lastReadPositionMs.get())
                                .put("sourceBOpens", source.sourceBOpens.get())
                                .put("sourceBBytesRead", source.sourceBBytesRead.get())
                        }
                        if (readFaultMode) {
                            val faultPhase = JSONObject().put("phase", "actual-byte-read-error-paused-tr-audio-en-text-retry").put("passed", false)
                            report.put("realByteFaultPhase", faultPhase)
                            try {
                                realByteFaultThenRetry(target, profiles, profile, store, reader, images, source, faultPhase)
                            } finally {
                                source.readFaultArmed.set(false)
                                faultPhase.put("byteFaultDiagnostics", source.faultDiagnostics())
                            }
                        }
                        if (catalog != null) {
                            val nextPhase = JSONObject().put("phase", "explicit-next-e1-to-e2-cross-file-tr-audio-en-text").put("passed", false)
                            report.put("nextPhase", nextPhase)
                            assertBlockedBeforeRead(mediaFactory, context, source, URL_NEXT)
                            try {
                                explicitNext(target, profiles, profile, store, resolver, reader, images, source, progress, catalog, api, nextPhase)
                            } finally {
                                nextPhase.put("catalogAudit", catalog.snapshotAudit()).put("progressAudit", progress.snapshotAudit())
                                    .put("nextSourceOpens", source.nextOpens.get()).put("nextSourceBytesRead", source.nextBytesRead.get())
                                    .put("nextNativeCalls", api.nextCalls.get())
                            }
                        }
                    }
                    runBlocking {
                        assertTrue(store.setProviderApiKeyForProfile(profile, DebridProviders.REAL_DEBRID_ID,
                            "", expectedApiKey = SYNTHETIC_CREDENTIAL))
                    }
                    await(target, "grant revocation", allowPolicyError = true) {
                        target.exoPlayer == null && target.uiState.value.error == SyclopPlaybackPolicy.BLOCKED_MESSAGE
                    }
                    assertBlockedBeforeRead(mediaFactory, context, source)
                    assertBlockedBeforeRead(mediaFactory, context, source, URL_B)
                    if (nextMode) assertBlockedBeforeRead(mediaFactory, context, source, URL_NEXT)
                    assertNull(runBlocking { store.firstWatchObservations.first().playback })
                    report.put("grantRevoked", true).put("nativeFakeCalls", api.calls.get())
                } finally {
                    try {
                        onMain {
                            controller?.progressWriteCoordinator?.deactivateForeground()
                            controller?.stopAndRelease()
                        }
                    } finally {
                        runBlocking {
                            job.cancelAndJoin()
                            try {
                                if (soakMode) {
                                    check(controllerChild?.isCompleted == true)
                                    soakReport!!.put("finalChildJobCompleted", true)
                                    finalSoakAllocator?.let {
                                        check(it.totalBytesAllocated == 0)
                                        soakReport.put("finalAllocatorActiveZero", true)
                                        soakReport.getJSONArray("controllers").getJSONObject(23)
                                            .put("childJobCompleted", true).put("allocatorActiveZero", true)
                                    }
                                }
                            } catch (failure: Throwable) {
                                soakReport?.put("finalResourceGateFailureClass", failure.javaClass.name)
                                throw failure
                            } finally {
                                finalSoakAllocator = null
                                require(profiles.activeProfileId.value == profile) { "Owned profile changed; cleanup refuses a different owner." }
                                val currentKey = store.credentialForProfile(profile, DebridProviders.REAL_DEBRID_ID)
                                require(currentKey.isEmpty() || currentKey == SYNTHETIC_CREDENTIAL) { "Credential changed; cleanup refuses to overwrite it." }
                                if (currentKey == SYNTHETIC_CREDENTIAL) {
                                    check(store.setProviderApiKeyForProfile(profile, DebridProviders.REAL_DEBRID_ID,
                                        "", expectedApiKey = SYNTHETIC_CREDENTIAL))
                                }
                                check(!store.settings.first().hasAnyApiKey) { "Another service credential appeared; cleanup refuses to overwrite it." }
                                snapshots.forEach { (feature, before) -> factory.get(profile, feature).updateData { before } }
                                snapshots.forEach { (feature, before) ->
                                    check(factory.get(profile, feature).data.first() == before) { "Owned fixture preference cleanup did not restore its snapshot." }
                                }
                                ConnectedServicePlaybackGrants.registry.clear()
                                check(!store.settings.first().hasAnyApiKey)
                            }
                        }
                    }
                }
            }
            report.put("passed", true)
        } catch (failure: Throwable) {
            report.put("passed", false).put("failureClass", failure.javaClass.name)
            soakReport?.put("status", "failed")?.put("failureClass", failure.javaClass.name)
            throw failure
        } finally {
            try {
                try {
                    reader.setOnImageAvailableListener(null, null)
                    val drained = CountDownLatch(1)
                    check(Handler(outputThread.looper).post { drained.countDown() } && drained.await(2, TimeUnit.SECONDS)) {
                        "Owned output callbacks did not drain."
                    }
                } finally {
                    reader.close()
                    outputThread.quitSafely()
                    outputThread.join(2_000L)
                    check(!outputThread.isAlive) { "Owned output thread did not stop." }
                    soakImages?.requireBalanced()
                    if (soakReport != null) {
                        soakReport.put("finalImages", requireNotNull(soakImages).snapshot())
                        if (report.optBoolean("passed")) {
                            check(SystemClock.elapsedRealtime() - invocationBegan <= OwnedPlaybackSoakChecks.OUTER_MS)
                            soakReport.put("invocationElapsedThroughCleanupMs", SystemClock.elapsedRealtime() - invocationBegan)
                            check(soakReport.optBoolean("durationWorkloadPassed") && soakReport.optBoolean("finalChildJobCompleted") &&
                                soakReport.optBoolean("finalAllocatorActiveZero"))
                            soakReport.put("passed", true).put("status", "passed")
                        }
                    }
                }
            } catch (failure: Throwable) {
                report.put("passed", false).put("cleanupFailureClass", failure.javaClass.name)
                throw failure
            } finally {
                check(destination.parentFile!!.isDirectory || destination.parentFile!!.mkdirs())
                destination.writeText(report.toString(2) + "\n")
            }
        }
    }

    private fun manualRetryWithSyntheticError(target: PlayerRuntimeController, profiles: ProfileManager,
        profile: Int, reader: ImageReader, images: AtomicInteger, phase: JSONObject, expectedCue: String?) {
        val beforePlayer = onMain { requireNotNull(target.exoPlayer) }
        val owner = onMain { requireNotNull(target.currentProgressWriteOwner()) }
        val position = onMain { beforePlayer.currentPosition }
        val beforeAudioId = onMain { selectedFormat(beforePlayer, C.TRACK_TYPE_AUDIO)?.id }
        val beforeTextId = onMain { selectedFormat(beforePlayer, C.TRACK_TYPE_TEXT)?.id }
        val beforeImages = images.get()
        val observations = JSONArray()
        val outgoingTrackCallbacks = JSONArray()
        val outgoingListener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                outgoingTrackCallbacks.put(retryHandoffSnapshot(target, beforePlayer)
                    .put("eventTrackGroupCount", tracks.groups.size)
                    .put("eventTextSelected", tracks.isTypeSelected(C.TRACK_TYPE_TEXT)))
            }
        }
        phase.put("syntheticTerminalUiError", true).put("realTransportFault", false)
            .put("observationWindow", "first new-player observation before surface bind through READY plus 500ms")
            .put("observations", observations).put("outgoingTrackCallbacks", outgoingTrackCallbacks).put("passed", false)
        onMain {
            phase.put("before", playbackSnapshot(target))
            assertEquals(CONTENT_ID, owner.contentId)
            assertEquals(CONTENT_ID, owner.videoId)
            assertEquals(profile, owner.profileId)
            assertEquals(profile, profiles.activeProfileId.value)
            assertFalse(beforePlayer.playWhenReady)
            assertTrue(target.hasRenderedFirstFrame)
            assertTrue(ConnectedServicePlaybackGrants.registry.permits(URL))
            // Registered after the production listener, so these snapshots see its restore effects.
            beforePlayer.addListener(outgoingListener)
            try {
                target._uiState.update { it.copy(error = SYNTHETIC_RETRY_ERROR, showLoadingOverlay = false, isBuffering = false) }
                assertEquals(SYNTHETIC_RETRY_ERROR, target.uiState.value.error)
                phase.put("beforeRetryHandoff", retryHandoffSnapshot(target, beforePlayer))
                target.onEvent(PlayerEvent.OnRetry)
            } finally {
                phase.put("afterRetryDispatchHandoff", retryHandoffSnapshot(target, beforePlayer))
                beforePlayer.removeListener(outgoingListener)
            }
        }
        await(target, "manual retry creates a replacement Exo") {
            target.exoPlayer != null && target.exoPlayer !== beforePlayer
        }
        val replacement = onMain { requireNotNull(target.exoPlayer) }
        val observedText = mutableListOf<String>()
        val observedTextSelections = mutableListOf<Boolean>()
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                observedTextSelections += tracks.isTypeSelected(C.TRACK_TYPE_TEXT)
                observations.put(playbackSnapshot(target).put("event", "tracks"))
            }
            override fun onCues(cueGroup: CueGroup) {
                val text = cueGroup.cues.mapNotNull { it.text?.toString() }.joinToString("\n")
                observedText += text
                observations.put(playbackSnapshot(target).put("event", "cues").put("cueEventText", text))
            }
        }
        onMain {
            phase.put("initialNewPlayer", playbackSnapshot(target))
            assertTrue(beforePlayer.isReleased)
            assertTrue("Initial replacement position must preserve the session.", kotlin.math.abs(replacement.currentPosition - position) <= 2_000L)
            assertFalse("Retry must initially preserve pause intent.", replacement.playWhenReady)
            observedTextSelections += replacement.currentTracks.isTypeSelected(C.TRACK_TYPE_TEXT)
            observedText += cueText(replacement)
            replacement.addListener(listener)
            replacement.volume = 0f
            replacement.setVideoSurface(reader.surface)
        }
        try {
            await(target, "manual retry restores real position, tracks and cue") {
                val player = requireNotNull(target.exoPlayer)
                val textMatches = if (expectedCue == null) {
                    !player.currentTracks.isTypeSelected(C.TRACK_TYPE_TEXT) && cueText(player).isEmpty() &&
                        target.uiState.value.selectedSubtitleTrackIndex == -1
                } else {
                    selectedFormat(player, C.TRACK_TYPE_TEXT)?.language in setOf("en", "eng") && cueText(player) == expectedCue
                }
                player === replacement && player.playbackState == Player.STATE_READY && target.hasRenderedFirstFrame &&
                    images.get() > beforeImages && kotlin.math.abs(player.currentPosition - position) <= 2_000L &&
                    !player.playWhenReady && selectedFormat(player, C.TRACK_TYPE_AUDIO)?.language in setOf("tr", "tur") &&
                    player.audioFormat?.language in setOf("tr", "tur") && textMatches
            }
            val stableUntil = SystemClock.elapsedRealtime() + 500L
            do {
                onMain {
                    assertEquals(owner, target.currentProgressWriteOwner())
                    assertEquals(profile, profiles.activeProfileId.value)
                    assertEquals(URL, target.getCurrentStreamUrl())
                    assertTrue(ConnectedServicePlaybackGrants.registry.permits(URL))
                    assertFalse(replacement.playWhenReady)
                    assertTrue(target.userPausedManually)
                    assertTrue(kotlin.math.abs(replacement.currentPosition - position) <= 2_000L)
                    if (expectedCue == null) {
                        assertFalse(replacement.currentTracks.isTypeSelected(C.TRACK_TYPE_TEXT))
                        assertEquals("", cueText(replacement))
                    }
                }
                SystemClock.sleep(20L)
            } while (SystemClock.elapsedRealtime() < stableUntil)
            onMain {
                phase.put("after", playbackSnapshot(target)).put("sameOwner", target.currentProgressWriteOwner() == owner)
                    .put("grantStillValid", ConnectedServicePlaybackGrants.registry.permits(URL))
                    .put("oldPlayerReleased", beforePlayer.isReleased).put("surfaceImagesAfter", images.get())
                assertEquals(beforeAudioId, selectedFormat(replacement, C.TRACK_TYPE_AUDIO)?.id)
                if (expectedCue != null) assertEquals(beforeTextId, selectedFormat(replacement, C.TRACK_TYPE_TEXT)?.id)
                if (expectedCue == null) {
                    assertTrue("Off must stay unselected throughout the observed replacement window.", observedTextSelections.none { it })
                    assertTrue("Off must emit no text throughout the observed replacement window.", observedText.all { it.isEmpty() })
                }
                assertNull(target.firstWatchCapture)
                phase.put("passed", true)
            }
        } finally {
            onMain { replacement.removeListener(listener) }
        }
    }

    private fun sourceChangeWhilePaused(target: PlayerRuntimeController, profiles: ProfileManager,
        profile: Int, store: DebridSettingsDataStore, reader: ImageReader, images: AtomicInteger,
        source: OwnedBytes, progress: OwnedProgress, playableB: Stream, phase: JSONObject) {
        val outgoing = onMain { requireNotNull(target.exoPlayer) }
        val owner = onMain { requireNotNull(target.currentProgressWriteOwner()) }
        val origin = onMain { target.playbackOrigin }
        val account = Triple(store.activeProfileId.value, store.activeProfileSelectionRevision, store.activeCredentialRevision)
        val position = onMain { outgoing.currentPosition }
        val audioId = onMain { selectedFormat(outgoing, C.TRACK_TYPE_AUDIO)?.id }
        val imagesBefore = images.get()
        val readsBefore = progress.reads.get()
        val observations = JSONArray()
        phase.put("observationWindow", "first new-player observation before surface bind through READY plus 500ms")
            .put("observations", observations).put("syntheticTerminalUiError", false).put("realTransportFault", false)
            .put("capturedPositionMs", position).put("surfaceImagesBefore", imagesBefore)
        onMain {
            phase.put("before", playbackSnapshot(target))
            assertEquals(CONTENT_ID, owner.contentId)
            assertEquals(CONTENT_ID, owner.videoId)
            assertEquals(profile, owner.profileId)
            assertEquals(URL, target.getCurrentStreamUrl())
            assertFalse(outgoing.playWhenReady)
            assertTrue(target.userPausedManually)
            assertTrue(selectedFormat(outgoing, C.TRACK_TYPE_AUDIO)?.language in setOf("tr", "tur"))
            assertTrue(outgoing.audioFormat?.language in setOf("tr", "tur"))
            assertFalse(outgoing.currentTracks.isTypeSelected(C.TRACK_TYPE_TEXT))
            assertEquals("", cueText(outgoing))
            assertTrue(ConnectedServicePlaybackGrants.registry.permits(URL_B))
            target.onEvent(PlayerEvent.OnSourceStreamSelected(playableB))
            phase.put("afterDispatchHandoff", retryHandoffSnapshot(target, outgoing))
        }
        await(target, "source B creates a replacement Exo") {
            target.exoPlayer != null && target.exoPlayer !== outgoing
        }
        val replacement = onMain { requireNotNull(target.exoPlayer) }
        val observedText = mutableListOf<String>()
        val observedTextSelections = mutableListOf<Boolean>()
        var renderedReplacementFrame = false
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { renderedReplacementFrame = true }
            override fun onTracksChanged(tracks: Tracks) {
                observedTextSelections += tracks.isTypeSelected(C.TRACK_TYPE_TEXT)
                observations.put(playbackSnapshot(target, URL_B).put("event", "tracks"))
            }
            override fun onCues(cueGroup: CueGroup) {
                val text = cueGroup.cues.mapNotNull { it.text?.toString() }.joinToString("\n")
                observedText += text
                observations.put(playbackSnapshot(target, URL_B).put("event", "cues").put("cueEventText", text))
            }
        }
        onMain { replacement.addListener(listener) }
        try {
            onMain {
                phase.put("initialNewPlayer", playbackSnapshot(target, URL_B))
                assertTrue(outgoing.isReleased)
                assertEquals(URL_B, target.getCurrentStreamUrl())
                assertTrue("Initial source B position must preserve the session.", kotlin.math.abs(replacement.currentPosition - position) <= 2_000L)
                assertFalse("Source change must initially preserve pause intent.", replacement.playWhenReady)
                observedTextSelections += replacement.currentTracks.isTypeSelected(C.TRACK_TYPE_TEXT)
                observedText += cueText(replacement)
                replacement.volume = 0f
                replacement.setVideoSurface(reader.surface)
            }
            await(target, "source B restores actual position, Turkish input and Off") {
                replacement.videoDecoderCounters?.ensureUpdated()
                target.exoPlayer === replacement && replacement.playbackState == Player.STATE_READY &&
                    renderedReplacementFrame && target.hasRenderedFirstFrame && images.get() > imagesBefore &&
                    (replacement.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 0 &&
                    source.sourceBOpens.get() > 0 && source.sourceBBytesRead.get() > 0L &&
                    kotlin.math.abs(replacement.currentPosition - position) <= 2_000L &&
                    !replacement.playWhenReady && target.userPausedManually &&
                    selectedFormat(replacement, C.TRACK_TYPE_AUDIO)?.language in setOf("tr", "tur") &&
                    replacement.audioFormat?.language in setOf("tr", "tur") &&
                    !replacement.currentTracks.isTypeSelected(C.TRACK_TYPE_TEXT) && cueText(replacement).isEmpty() &&
                    target.uiState.value.selectedSubtitleTrackIndex == -1
            }
            val stableUntil = SystemClock.elapsedRealtime() + 500L
            do {
                onMain {
                    assertEquals(owner, target.currentProgressWriteOwner())
                    assertSame(origin, target.playbackOrigin)
                    assertEquals(profile, profiles.activeProfileId.value)
                    assertEquals(account, Triple(store.activeProfileId.value, store.activeProfileSelectionRevision, store.activeCredentialRevision))
                    assertEquals(URL_B, target.getCurrentStreamUrl())
                    assertTrue(ConnectedServicePlaybackGrants.registry.permits(URL_B))
                    assertFalse(replacement.playWhenReady)
                    assertTrue(target.userPausedManually)
                    assertTrue(kotlin.math.abs(replacement.currentPosition - position) <= 2_000L)
                    assertTrue(selectedFormat(replacement, C.TRACK_TYPE_AUDIO)?.language in setOf("tr", "tur"))
                    assertTrue(replacement.audioFormat?.language in setOf("tr", "tur"))
                    assertFalse(replacement.currentTracks.isTypeSelected(C.TRACK_TYPE_TEXT))
                    assertEquals("", cueText(replacement))
                }
                SystemClock.sleep(20L)
            } while (SystemClock.elapsedRealtime() < stableUntil)
            onMain {
                phase.put("after", playbackSnapshot(target, URL_B)).put("oldPlayerReleased", outgoing.isReleased)
                    .put("sameOwner", owner == target.currentProgressWriteOwner()).put("sameTrackingOrigin", origin === target.playbackOrigin)
                    .put("sameAccountOrigin", account == Triple(store.activeProfileId.value, store.activeProfileSelectionRevision, store.activeCredentialRevision))
                    .put("sourceResumeReadPositionMs", progress.lastReadPositionMs.get())
                    .put("newPlayerRenderedFrame", renderedReplacementFrame)
                    .put("videoRenderedBuffers", replacement.videoDecoderCounters?.renderedOutputBufferCount)
                    .put("surfaceImagesAfter", images.get())
                assertEquals(audioId, selectedFormat(replacement, C.TRACK_TYPE_AUDIO)?.id)
                val readObserved = progress.reads.get() > readsBefore
                phase.put("readObserved", readObserved)
                if (readObserved) assertTrue(kotlin.math.abs(progress.lastReadPositionMs.get() - position) <= 2_000L)
                assertTrue(observedTextSelections.none { it })
                assertTrue(observedText.all { it.isEmpty() })
                assertNull(target.firstWatchCapture)
                phase.put("passed", true)
            }
        } finally {
            onMain { replacement.removeListener(listener) }
        }
    }

    private fun explicitNext(target: PlayerRuntimeController, profiles: ProfileManager, profile: Int,
        store: DebridSettingsDataStore, resolver: DirectDebridResolver, reader: ImageReader, images: AtomicInteger,
        source: OwnedBytes, progress: OwnedProgress, catalog: OwnedNextPlaybackCatalog, api: OwnedNativeApi, phase: JSONObject) {
        await(target, "real metadata and shuffle observer derive episode 2") {
            target.uiState.value.isNextEpisodeMetadataResolved && target.playbackShuffleState != null &&
                target.uiState.value.nextEpisode?.videoId == EPISODE_TWO_ID && target.nextEpisodeVideo?.id == EPISODE_TWO_ID
        }
        onMain {
            assertFalse("Owned Next requires the actual disabled-shuffle prerequisite.", requireNotNull(target.playbackShuffleState).settings.enabled)
            assertEquals(1, target.uiState.value.nextEpisode?.season)
            assertEquals(2, target.uiState.value.nextEpisode?.episode)
            assertTrue(requireNotNull(target.uiState.value.nextEpisode).hasAired)
            assertTrue(progress.hasEpisodeRow(1))
            assertFalse("Episode 2 starts without a seeded progress row.", progress.hasEpisodeRow(2))
            val text = target.uiState.value.subtitleTracks.indexOfFirst { it.language in setOf("en", "eng") }
            assertTrue(text >= 0)
            target.onEvent(PlayerEvent.OnSelectSubtitleTrack(text))
            assertFalse(requireNotNull(target.exoPlayer).playWhenReady)
            target.onEvent(PlayerEvent.OnPlayPause)
        }
        await(target, "episode 1 plays Turkish input with the actual English cue before Next") {
            val player = requireNotNull(target.exoPlayer)
            player.isPlaying && !target.userPausedManually &&
                selectedFormat(player, C.TRACK_TYPE_AUDIO)?.language in setOf("tr", "tur") &&
                player.audioFormat?.language in setOf("tr", "tur") &&
                selectedFormat(player, C.TRACK_TYPE_TEXT)?.language in setOf("en", "eng") &&
                cueText(player) == ENGLISH_CUE_AT_45_SECONDS
        }
        val delayProof = JSONObject().put("resetObserved", false)
        phase.put("perVideoSubtitleDelay", delayProof)
        onMain {
            delayProof.put("episodeOneBeforeUiMs", target.uiState.value.subtitleDelayMs)
                .put("episodeOneBeforeAtomicUs", target.subtitleDelayUs.get())
            assertEquals(0, target.uiState.value.subtitleDelayMs)
            assertEquals(0L, target.subtitleDelayUs.get())
            target.onEvent(PlayerEvent.OnAdjustSubtitleDelay(deltaMs = 1_500, showOverlay = false))
        }
        await(target, "episode 1 actual subtitle delay adjustment") {
            target.uiState.value.subtitleDelayMs == 1_500 && target.subtitleDelayUs.get() == 1_500_000L
        }
        runBlocking { withTimeout(10_000L) {
            while (true) {
                val first = target.trackPreferenceDataStore.loadSubtitleDelayMs(CONTENT_ID)
                val second = target.trackPreferenceDataStore.loadSubtitleDelayMs(EPISODE_TWO_ID)
                delayProof.put("episodeOnePersistedMs", first ?: JSONObject.NULL)
                    .put("episodeTwoSavedPresentBeforeNext", second != null)
                    .put("episodeTwoPersistedMsBeforeNext", second ?: JSONObject.NULL)
                assertNull("Episode 2 delay must be absent without test seeding.", second)
                if (first == 1_500) break
                kotlinx.coroutines.delay(20L)
            }
        } }
        await(target, "episode 1 delay refresh restores actual Turkish and English cue") {
            val player = requireNotNull(target.exoPlayer)
            player.isPlaying && target.uiState.value.subtitleDelayMs == 1_500 && target.subtitleDelayUs.get() == 1_500_000L &&
                selectedFormat(player, C.TRACK_TYPE_AUDIO)?.language in setOf("tr", "tur") &&
                player.audioFormat?.language in setOf("tr", "tur") &&
                selectedFormat(player, C.TRACK_TYPE_TEXT)?.language in setOf("en", "eng") && cueText(player) == ENGLISH_CUE_AT_45_SECONDS
        }
        val outgoing = onMain { requireNotNull(target.exoPlayer) }
        val owner = onMain { requireNotNull(target.currentProgressWriteOwner()) }
        val origin = onMain { target.playbackOrigin }
        val account = Triple(store.activeProfileId.value, store.activeProfileSelectionRevision, store.activeCredentialRevision)
        val oldAudioId = onMain { requireNotNull(selectedFormat(outgoing, C.TRACK_TYPE_AUDIO)?.id) }
        val oldTextId = onMain { requireNotNull(selectedFormat(outgoing, C.TRACK_TYPE_TEXT)?.id) }
        val imagesBefore = images.get()
        val emptyEpisodeReadsBefore = progress.episodeTwoEmptyReads.get()
        val observations = JSONArray()
        val cues = mutableListOf<String>()
        phase.put("observations", observations).put("derivedNextMatches", true).put("shuffleDisabled", true)
            .put("episodeTwoInitiallyEmpty", true).put("realTransportFault", false).put("syntheticTerminalUiError", false)
            .put("observationWindow", "first replacement observation before surface bind through READY plus 500ms playing")
        val countdown = mutableListOf<Int>()
        phase.put("countdownSecondsObserved", JSONArray())
        val dispatchedAt = SystemClock.elapsedRealtime()
        onMain {
            assertEquals(PlayerProgressWriteOwner(profile, CONTENT_ID, "series", CONTENT_ID, 1, 1), owner)
            assertEquals(URL_B, target.getCurrentStreamUrl())
            assertEquals(0, api.nextCalls.get())
            phase.put("before", playbackSnapshot(target, URL_B).put("subtitleUiDelayMs", target.uiState.value.subtitleDelayMs)
                .put("subtitleAtomicDelayUs", target.subtitleDelayUs.get()))
            assertEquals(1_500, target.uiState.value.subtitleDelayMs)
            assertEquals(1_500_000L, target.subtitleDelayUs.get())
            target.onEvent(PlayerEvent.OnPlayNextEpisode)
        }
        await(target, "real Next selection, countdown and replacement Exo") {
            (target.uiState.value.postPlayMode as? PostPlayMode.AutoPlay)?.countdownSec?.let {
                if (countdown.lastOrNull() != it) {
                    countdown += it
                    phase.getJSONArray("countdownSecondsObserved").put(it)
                }
            }
            target.exoPlayer != null && target.exoPlayer !== outgoing
        }
        phase.put("nextDispatchToReplacementObservedMs", SystemClock.elapsedRealtime() - dispatchedAt)
        val replacement = onMain { requireNotNull(target.exoPlayer) }
        var renderedFrame = false
        var firstReadyPosition: Long? = null
        var firstReadyUiDelayMs: Int? = null
        var firstReadyAtomicDelayUs: Long? = null
        fun recordReady() {
            if (replacement.playbackState == Player.STATE_READY && firstReadyPosition == null) {
                firstReadyPosition = replacement.currentPosition
                firstReadyUiDelayMs = target.uiState.value.subtitleDelayMs
                firstReadyAtomicDelayUs = target.subtitleDelayUs.get()
                phase.put("firstReady", playbackSnapshot(target, URL_NEXT).put("isPlaying", replacement.isPlaying)
                    .put("subtitleUiDelayMs", firstReadyUiDelayMs).put("subtitleAtomicDelayUs", firstReadyAtomicDelayUs))
            }
        }
        fun trackFormats() = JSONArray().apply {
            replacement.currentTracks.groups.filter { it.type in setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_TEXT) }.forEach { group ->
                for (index in 0 until group.length) put(JSONObject().put("type", group.type)
                    .put("id", group.getTrackFormat(index).id ?: JSONObject.NULL)
                    .put("languageTag", group.getTrackFormat(index).language ?: JSONObject.NULL)
                    .put("selected", group.isTrackSelected(index)))
            }
        }
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { renderedFrame = true }
            override fun onPlaybackStateChanged(playbackState: Int) { recordReady() }
            override fun onTracksChanged(tracks: Tracks) {
                phase.put("latestTrackFormats", trackFormats())
                observations.put(playbackSnapshot(target, URL_NEXT).put("event", "tracks").put("trackFormats", trackFormats()))
            }
            override fun onCues(cueGroup: CueGroup) {
                val text = cueGroup.cues.mapNotNull { it.text?.toString() }.joinToString("\n")
                cues += text
                observations.put(playbackSnapshot(target, URL_NEXT).put("event", "cues").put("cueEventText", text))
            }
        }
        onMain {
            phase.put("initialNewPlayer", playbackSnapshot(target, URL_NEXT).put("isPlaying", replacement.isPlaying)
                .put("subtitleUiDelayMs", target.uiState.value.subtitleDelayMs).put("subtitleAtomicDelayUs", target.subtitleDelayUs.get()))
                .put("latestTrackFormats", trackFormats())
            assertEquals(0, target.uiState.value.subtitleDelayMs)
            assertEquals(0L, target.subtitleDelayUs.get())
            assertTrue(outgoing.isReleased)
            assertEquals(URL_NEXT, target.getCurrentStreamUrl())
            assertTrue("Episode 2 initial position must start at zero.", replacement.currentPosition in 0L..2_000L)
            assertFalse("Playing Next must clear pause intent.", target.userPausedManually)
            cues += cueText(replacement)
            recordReady()
            replacement.addListener(listener)
            replacement.volume = 0f
            replacement.setVideoSurface(reader.surface)
        }
        try {
            await(target, "episode 2 real frame, Turkish input, English first cue and zero start") {
                replacement.videoDecoderCounters?.ensureUpdated()
                replacement.audioDecoderCounters?.ensureUpdated()
                target.exoPlayer === replacement && replacement.playbackState == Player.STATE_READY &&
                    renderedFrame && target.hasRenderedFirstFrame && images.get() > imagesBefore &&
                    (replacement.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 0 &&
                    (replacement.audioDecoderCounters?.renderedOutputBufferCount ?: 0) > 0 &&
                    source.nextOpens.get() > 0 && source.nextBytesRead.get() > 0L &&
                    replacement.currentPosition in 0L..2_000L && replacement.isPlaying && !target.userPausedManually &&
                    selectedFormat(replacement, C.TRACK_TYPE_AUDIO)?.language in setOf("tr", "tur") &&
                    replacement.audioFormat?.language in setOf("tr", "tur") &&
                    selectedFormat(replacement, C.TRACK_TYPE_TEXT)?.language in setOf("en", "eng") &&
                    cueText(replacement) == ENGLISH_FIRST_CUE
            }
            val nextOwner = PlayerProgressWriteOwner(profile, CONTENT_ID, "series", EPISODE_TWO_ID, 1, 2)
            onMain {
                phase.put("readyOutput", playbackSnapshot(target, URL_NEXT).put("isPlaying", replacement.isPlaying)
                    .put("subtitleUiDelayMs", target.uiState.value.subtitleDelayMs).put("subtitleAtomicDelayUs", target.subtitleDelayUs.get()))
                    .put("currentSeason", target.currentSeason).put("currentEpisode", target.currentEpisode)
                    .put("videoRenderedBuffers", replacement.videoDecoderCounters?.renderedOutputBufferCount)
                    .put("audioRenderedBuffers", replacement.audioDecoderCounters?.renderedOutputBufferCount)
                assertEquals(0, requireNotNull(firstReadyUiDelayMs))
                assertEquals(0L, requireNotNull(firstReadyAtomicDelayUs))
                assertEquals(0, target.uiState.value.subtitleDelayMs)
                assertEquals(0L, target.subtitleDelayUs.get())
                assertTrue(requireNotNull(firstReadyPosition) in 0L..2_000L)
                assertEquals(nextOwner, target.currentProgressWriteOwner())
                assertNotSame(origin, target.playbackOrigin)
                assertTrue(catalog.nextRequests.get() > 0)
                assertEquals(listOf(3, 2, 1), countdown)
                assertEquals(1, api.nextCalls.get())
                assertTrue(progress.episodeTwoEmptyReads.get() > emptyEpisodeReadsBefore)
                assertEquals(ENGLISH_FIRST_CUE, cues.firstOrNull { it.isNotEmpty() })
                val audioFormats = replacement.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                    .flatMap { group -> (0 until group.length).map(group::getTrackFormat) }
                val textFormats = replacement.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
                    .flatMap { group -> (0 until group.length).map(group::getTrackFormat) }
                assertEquals(2, audioFormats.size)
                assertEquals(2, textFormats.size)
                assertTrue("Outgoing Turkish ID must now collide with English audio.", audioFormats.single { it.id == oldAudioId }.language in setOf("en", "eng"))
                assertTrue("Outgoing English ID must now collide with Turkish text.", textFormats.single { it.id == oldTextId }.language in setOf("tr", "tur"))
                assertNotEquals(oldAudioId, selectedFormat(replacement, C.TRACK_TYPE_AUDIO)?.id)
                assertNotEquals(oldTextId, selectedFormat(replacement, C.TRACK_TYPE_TEXT)?.id)
                phase.put("actualAudioIdCollision", true).put("actualTextIdCollision", true)
            }
            assertFalse(runBlocking { resolver.isPlaybackAuthorized(URL_NEXT, profile + 1) })
            val stableBegan = SystemClock.elapsedRealtime()
            val stablePosition = onMain { replacement.currentPosition }
            do {
                onMain {
                    delayProof.put("stableUiMs", target.uiState.value.subtitleDelayMs)
                        .put("stableAtomicUs", target.subtitleDelayUs.get())
                    assertEquals(0, target.uiState.value.subtitleDelayMs)
                    assertEquals(0L, target.subtitleDelayUs.get())
                    assertEquals(nextOwner, target.currentProgressWriteOwner())
                    assertEquals(profile, profiles.activeProfileId.value)
                    assertEquals(account, Triple(store.activeProfileId.value, store.activeProfileSelectionRevision, store.activeCredentialRevision))
                    assertEquals(URL_NEXT, target.getCurrentStreamUrl())
                    assertTrue(ConnectedServicePlaybackGrants.registry.permits(URL_NEXT))
                    assertTrue(replacement.isPlaying && replacement.playWhenReady)
                    assertFalse(target.userPausedManually)
                    assertTrue(selectedFormat(replacement, C.TRACK_TYPE_AUDIO)?.language in setOf("tr", "tur"))
                    assertTrue(replacement.audioFormat?.language in setOf("tr", "tur"))
                    assertTrue(selectedFormat(replacement, C.TRACK_TYPE_TEXT)?.language in setOf("en", "eng"))
                    assertEquals(ENGLISH_FIRST_CUE, cueText(replacement))
                    val elapsed = SystemClock.elapsedRealtime() - stableBegan
                    assertTrue(kotlin.math.abs(replacement.currentPosition - stablePosition - elapsed) <= 2_000L)
                }
                SystemClock.sleep(20L)
            } while (SystemClock.elapsedRealtime() - stableBegan < 500L)
            onMain {
                delayProof.put("afterUiMs", target.uiState.value.subtitleDelayMs).put("afterAtomicUs", target.subtitleDelayUs.get())
                assertEquals(0, target.uiState.value.subtitleDelayMs)
                assertEquals(0L, target.subtitleDelayUs.get())
                assertTrue("Playing episode 2 must advance.", replacement.currentPosition > stablePosition)
                assertNull(target.firstWatchCapture)
                phase.put("after", playbackSnapshot(target, URL_NEXT).put("isPlaying", replacement.isPlaying)
                    .put("subtitleUiDelayMs", target.uiState.value.subtitleDelayMs).put("subtitleAtomicDelayUs", target.subtitleDelayUs.get()))
                    .put("oldPlayerReleased", outgoing.isReleased)
                    .put("newOwnerMatches", target.currentProgressWriteOwner() == nextOwner)
                    .put("trackingVisitChanged", target.playbackOrigin !== origin).put("accountOriginMatches", true)
                    .put("newPlayerRenderedFrame", renderedFrame).put("surfaceImagesAfter", images.get())
                    .put("stablePlayingElapsedMs", SystemClock.elapsedRealtime() - stableBegan)
                    .put("stablePlayingPositionAdvanceMs", replacement.currentPosition - stablePosition).put("passed", true)
                delayProof.put("resetObserved", true)
            }
        } finally {
            onMain { replacement.removeListener(listener) }
        }
    }

    private fun realByteFaultThenRetry(target: PlayerRuntimeController, profiles: ProfileManager, profile: Int,
        store: DebridSettingsDataStore, reader: ImageReader, images: AtomicInteger, source: OwnedBytes, phase: JSONObject) {
        phase.put("faultKind", "synthetic-http-shaped-404-on-owned-byte-read").put("syntheticTerminalUiError", false)
            .put("realHttpResponseObserved", false).put("networkRecoveryObserved", false).put("inconclusive", false)
        onMain {
            val text = target.uiState.value.subtitleTracks.indexOfFirst { it.language in setOf("en", "eng") }
            assertTrue(text >= 0)
            target.onEvent(PlayerEvent.OnSelectSubtitleTrack(text))
            assertFalse(requireNotNull(target.exoPlayer).playWhenReady)
            target.onEvent(PlayerEvent.OnPlayPause)
        }
        await(target, "real-fault precondition decoded Turkish and English cue at 44 seconds") {
            val player = requireNotNull(target.exoPlayer)
            player.isPlaying && selectedFormat(player, C.TRACK_TYPE_AUDIO)?.language in setOf("tr", "tur") &&
                player.audioFormat?.language in setOf("tr", "tur") &&
                selectedFormat(player, C.TRACK_TYPE_TEXT)?.language in setOf("en", "eng") && cueText(player) == ENGLISH_CUE_AT_45_SECONDS
        }
        onMain { target.onEvent(PlayerEvent.OnPlayPause) }
        await(target, "real-fault paused idle loader") {
            val player = requireNotNull(target.exoPlayer)
            player.playbackState == Player.STATE_READY && !player.playWhenReady && !player.isLoading
        }
        val outgoing = onMain { requireNotNull(target.exoPlayer) }
        val owner = onMain { requireNotNull(target.currentProgressWriteOwner()) }
        val origin = onMain { target.playbackOrigin }
        val account = Triple(store.activeProfileId.value, store.activeProfileSelectionRevision, store.activeCredentialRevision)
        val audioId = onMain { selectedFormat(outgoing, C.TRACK_TYPE_AUDIO)?.id }
        val textId = onMain { selectedFormat(outgoing, C.TRACK_TYPE_TEXT)?.id }
        val bufferEdge = onMain { outgoing.bufferedPosition }
        onMain { phase.put("lastDecodedBeforeFault", playbackSnapshot(target, URL_B)).put("bufferedPositionBeforeFaultMs", bufferEdge) }
        fun inconclusive(reason: String): Nothing {
            phase.put("inconclusive", true).put("inconclusiveReason", reason)
            throw AssumptionViolatedException("Owned byte-fault prerequisite unavailable: $reason")
        }
        if (onMain { READ_FAULT_SEEK_MS >= outgoing.duration || READ_FAULT_SEEK_MS <= bufferEdge + 2_000L }) {
            inconclusive("110s-target-not-outside-buffer")
        }
        SystemClock.sleep(500L)
        if (onMain { outgoing.isLoading || outgoing.bufferedPosition != bufferEdge }) inconclusive("paused-buffer-edge-not-stable")
        fun assertBinding() {
            assertEquals(owner, target.currentProgressWriteOwner())
            assertSame(origin, target.playbackOrigin)
            assertEquals(profile, profiles.activeProfileId.value)
            assertEquals(account, Triple(store.activeProfileId.value, store.activeProfileSelectionRevision, store.activeCredentialRevision))
            assertEquals(URL_B, target.getCurrentStreamUrl())
            assertTrue(ConnectedServicePlaybackGrants.registry.permits(URL_B))
            assertTrue(target.userPausedManually)
        }
        val errors = JSONArray()
        val loads = JSONArray()
        val observations = JSONArray()
        var actualError: PlaybackException? = null
        var playerErrors = 0
        fun errorFields(error: Throwable) = JSONObject().put("exceptionClass", error.javaClass.name).apply {
            val http = error.findCause<HttpDataSource.InvalidResponseCodeException>()
            put("httpStatus", http?.responseCode ?: JSONObject.NULL)
            put("ownedMarkerMatches", http?.cause?.message == READ_FAULT_MARKER)
        }
        fun recordPlayerError(error: PlaybackException) {
            playerErrors++
            actualError = error
            errors.put(errorFields(error).put("errorCode", error.errorCode).put("errorCodeName", error.errorCodeName)
                .put("sourceType", (error as? ExoPlaybackException)?.type ?: JSONObject.NULL)
                .put("handoff", retryHandoffSnapshot(target, outgoing)))
        }
        val errorListener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { recordPlayerError(error) }
        }
        val analytics = object : AnalyticsListener {
            override fun onLoadError(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData, error: IOException, wasCanceled: Boolean) {
                loads.put(errorFields(error).put("wasCanceled", wasCanceled).put("bytesLoaded", loadEventInfo.bytesLoaded))
            }
        }
        val bytesBeforeArm = source.bytesRead.get()
        phase.put("playerErrors", errors).put("loadErrors", loads).put("observations", observations)
            .put("seekTargetMs", READ_FAULT_SEEK_MS).put("actualByteSourceFaultInjected", false)
        onMain {
            assertBinding()
            assertSame(outgoing, target.continuationRetryPlayer())
            assertFalse(target.parsingErrorProbeAttempted)
            assertEquals(0, target.errorRetryCount)
            assertEquals(0, target.startupRetryCount)
            outgoing.addListener(errorListener)
            outgoing.addAnalyticsListener(analytics)
            source.readFaultArmed.set(true)
            target.onEvent(PlayerEvent.OnSeekTo(READ_FAULT_SEEK_MS))
        }
        try {
            val deadline = SystemClock.elapsedRealtime() + 10_000L
            while (SystemClock.elapsedRealtime() < deadline && !onMain { actualError != null && target.uiState.value.error != null }) {
                SystemClock.sleep(20L)
            }
            val position = onMain {
                val error = requireNotNull(actualError) { "No actual Media3 error from armed byte read." }
                assertNotNull("Production listener must publish the terminal UI error.", target.uiState.value.error)
                assertEquals(1, playerErrors)
                assertEquals(1, loads.length())
                assertEquals(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, error.errorCode)
                assertEquals(2004, error.errorCode)
                assertEquals(ExoPlaybackException.TYPE_SOURCE, (error as? ExoPlaybackException)?.type)
                val http = requireNotNull(error.findCause<HttpDataSource.InvalidResponseCodeException>())
                assertEquals(404, http.responseCode)
                assertEquals(READ_FAULT_MARKER, http.cause?.message)
                assertSame(outgoing, target.exoPlayer)
                assertFalse(outgoing.isReleased)
                assertBinding()
                assertFalse(outgoing.playWhenReady)
                assertFalse(target.parsingErrorProbeAttempted)
                assertEquals(0, target.errorRetryCount)
                assertEquals(0, target.startupRetryCount)
                assertFalse(target.errorRetryJob?.isActive == true)
                assertSame("Actual terminal player must retain continuation eligibility.", outgoing, target.continuationRetryPlayer())
                assertTrue(kotlin.math.abs(outgoing.currentPosition - READ_FAULT_SEEK_MS) <= 2_000L)
                val bytes = source.faultDiagnostics()
                assertTrue(bytes.getInt("readThrows") > 0 && bytes.getInt("rangeOpensWhileArmed") > 0)
                assertTrue("Actual outside-buffer seek must open a nonzero range.", bytes.getLong("lastFaultRangeStart") > 0L)
                assertEquals(bytesBeforeArm, source.bytesRead.get())
                phase.put("actualByteSourceFaultInjected", true).put("actualMedia3SourceErrorObserved", true)
                    .put("terminal", playbackSnapshot(target, URL_B))
                    .put("terminalEligibility", retryHandoffSnapshot(target, outgoing)).put("continuationEligible", true)
                    .put("controllerAutoRetryCount", target.errorRetryCount).put("parsingProbeAttempted", target.parsingErrorProbeAttempted)
                outgoing.currentPosition
            }
            val terminalUntil = SystemClock.elapsedRealtime() + 500L
            do {
                onMain {
                    assertBinding()
                    assertSame(outgoing, target.exoPlayer)
                    assertNotNull(target.uiState.value.error)
                    assertTrue(source.readFaultArmed.get())
                    assertEquals(bytesBeforeArm, source.bytesRead.get())
                    assertEquals(1, playerErrors)
                    assertFalse(outgoing.playWhenReady)
                    assertTrue(kotlin.math.abs(outgoing.currentPosition - position) <= 2_000L)
                }
                SystemClock.sleep(20L)
            } while (SystemClock.elapsedRealtime() < terminalUntil)
            val imagesBeforeRetry = images.get()
            onMain {
                phase.put("beforeRetry", playbackSnapshot(target, URL_B)).put("successfulBytesWhileArmed", source.bytesRead.get() - bytesBeforeArm)
                source.readFaultArmed.set(false)
                target.onEvent(PlayerEvent.OnRetry)
            }
            await(target, "real-byte-error manual Retry replacement") { target.exoPlayer != null && target.exoPlayer !== outgoing }
            val replacement = onMain { requireNotNull(target.exoPlayer) }
            var renderedFrame = false
            val cues = mutableListOf<String>()
            val listener = object : Player.Listener {
                override fun onRenderedFirstFrame() { renderedFrame = true }
                override fun onPlayerError(error: PlaybackException) { recordPlayerError(error) }
                override fun onTracksChanged(tracks: Tracks) { observations.put(playbackSnapshot(target, URL_B).put("event", "tracks")) }
                override fun onCues(cueGroup: CueGroup) {
                    val text = cueGroup.cues.mapNotNull { it.text?.toString() }.joinToString("\n")
                    cues += text
                    observations.put(playbackSnapshot(target, URL_B).put("event", "cues").put("cueEventText", text))
                }
            }
            onMain { replacement.addListener(listener) }
            try {
                onMain {
                    phase.put("initialNewPlayer", playbackSnapshot(target, URL_B))
                    assertTrue(outgoing.isReleased)
                    assertTrue(kotlin.math.abs(replacement.currentPosition - position) <= 2_000L)
                    assertFalse(replacement.playWhenReady)
                    cues += cueText(replacement)
                    replacement.volume = 0f
                    replacement.setVideoSurface(reader.surface)
                }
                await(target, "real-byte-error Retry decoded output at actual seek position") {
                    replacement.videoDecoderCounters?.ensureUpdated()
                    target.exoPlayer === replacement && replacement.playbackState == Player.STATE_READY &&
                        renderedFrame && target.hasRenderedFirstFrame && images.get() > imagesBeforeRetry &&
                        (replacement.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 0 &&
                        kotlin.math.abs(replacement.currentPosition - position) <= 2_000L && !replacement.playWhenReady &&
                        selectedFormat(replacement, C.TRACK_TYPE_AUDIO)?.language in setOf("tr", "tur") &&
                        replacement.audioFormat?.language in setOf("tr", "tur") &&
                        selectedFormat(replacement, C.TRACK_TYPE_TEXT)?.language in setOf("en", "eng") &&
                        cueText(replacement) == ENGLISH_CUE_AT_110_SECONDS
                }
                val stableUntil = SystemClock.elapsedRealtime() + 500L
                do {
                    onMain {
                        assertBinding()
                        assertFalse(replacement.playWhenReady)
                        assertTrue(kotlin.math.abs(replacement.currentPosition - position) <= 2_000L)
                        assertTrue(replacement.audioFormat?.language in setOf("tr", "tur"))
                        assertTrue(selectedFormat(replacement, C.TRACK_TYPE_AUDIO)?.language in setOf("tr", "tur"))
                        assertTrue(selectedFormat(replacement, C.TRACK_TYPE_TEXT)?.language in setOf("en", "eng"))
                        assertEquals(ENGLISH_CUE_AT_110_SECONDS, cueText(replacement))
                    }
                    SystemClock.sleep(20L)
                } while (SystemClock.elapsedRealtime() < stableUntil)
                onMain {
                    assertEquals(1, playerErrors)
                    assertEquals(audioId, selectedFormat(replacement, C.TRACK_TYPE_AUDIO)?.id)
                    assertEquals(textId, selectedFormat(replacement, C.TRACK_TYPE_TEXT)?.id)
                    assertEquals(ENGLISH_CUE_AT_110_SECONDS, cues.firstOrNull { it.isNotEmpty() })
                    assertNull(target.firstWatchCapture)
                    assertEquals(MimeTypes.AUDIO_AAC, replacement.audioFormat?.sampleMimeType)
                    phase.put("after", playbackSnapshot(target, URL_B)).put("oldPlayerReleased", outgoing.isReleased)
                        .put("newPlayerRenderedFrame", renderedFrame).put("surfaceImagesAfter", images.get())
                        .put("videoRenderedBuffers", replacement.videoDecoderCounters?.renderedOutputBufferCount)
                        .put("audioInputMimeType", replacement.audioFormat?.sampleMimeType)
                        .put("successfulBytesAfterClearingFault", source.bytesRead.get() - bytesBeforeArm)
                    assertTrue(source.bytesRead.get() > bytesBeforeArm)
                    phase.put("passed", true)
                }
            } finally {
                onMain { replacement.removeListener(listener) }
            }
        } finally {
            source.readFaultArmed.set(false)
            onMain {
                outgoing.removeListener(errorListener)
                outgoing.removeAnalyticsListener(analytics)
                phase.put("playerErrorCount", playerErrors).put("loadErrorCount", loads.length())
            }
        }
    }

    private class OwnedSoakSession(var controller: PlayerRuntimeController, var job: Job, var progress: OwnedProgress)
    private data class OwnedSoakObservation(val elapsed: Long, val uptime: Long, val playerId: Int,
        val position: Long, val video: Int, val audio: Int, val images: Int, val playing: Boolean)

    private fun drainSoakOutput(thread: HandlerThread, images: OwnedSoakImages) {
        val barrier = CountDownLatch(1)
        check(Handler(thread.looper).post { barrier.countDown() } && barrier.await(2, TimeUnit.SECONDS))
        images.requireBalanced()
    }

    private fun soakAllocator(allocator: Allocator): JSONObject = JSONObject()
        .put("class", allocator.javaClass.name).put("activeBytes", allocator.totalBytesAllocated)
        .put("availableBytes", (allocator as? DefaultAllocator)?.availableBytes ?: JSONObject.NULL)
        .put("footprintBytes", (allocator as? DefaultAllocator)?.memoryFootprint ?: JSONObject.NULL)
        .put("nativeAllocationConfigured", NuvioEngineConfig.get().isNativeAllocationEnabled)

    private fun seekSoakCheckpoint(target: PlayerRuntimeController, position: Long, reader: ImageReader, images: AtomicInteger) {
        await(target, "soak real tracks discovered") {
            target.uiState.value.audioTracks.any { it.language in setOf("tr", "tur") } &&
                target.uiState.value.subtitleTracks.any { it.language in setOf("en", "eng") }
        }
        val before = images.get()
        onMain {
            val player = requireNotNull(target.exoPlayer)
            player.volume = 0f
            player.setVideoSurface(reader.surface)
            val tracks = target.uiState.value
            target.onEvent(PlayerEvent.OnSelectAudioTrack(tracks.audioTracks.indexOfFirst { it.language in setOf("tr", "tur") }))
            target.onEvent(PlayerEvent.OnSelectSubtitleTrack(tracks.subtitleTracks.indexOfFirst { it.language in setOf("en", "eng") }))
            target.onEvent(PlayerEvent.OnSeekTo(position))
        }
        val cue = if (position == 5_000L) ENGLISH_FIRST_CUE else ENGLISH_CUE_AT_45_SECONDS
        await(target, "soak actual seek cue and resumed decode") {
            val player = requireNotNull(target.exoPlayer)
            player.videoDecoderCounters?.ensureUpdated(); player.audioDecoderCounters?.ensureUpdated()
            player.isPlaying && player.playbackState == Player.STATE_READY && target.hasRenderedFirstFrame &&
                kotlin.math.abs(player.currentPosition - position) <= 2_000L && images.get() > before &&
                selectedFormat(player, C.TRACK_TYPE_AUDIO)?.language in setOf("tr", "tur") &&
                player.audioFormat?.language in setOf("tr", "tur") &&
                selectedFormat(player, C.TRACK_TYPE_TEXT)?.language in setOf("en", "eng") && cueText(player) == cue &&
                (player.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 0 &&
                (player.audioDecoderCounters?.renderedOutputBufferCount ?: 0) > 0
        }
        val decoded = onMain {
            val player = requireNotNull(target.exoPlayer)
            Triple(requireNotNull(player.videoDecoderCounters).renderedOutputBufferCount,
                requireNotNull(player.audioDecoderCounters).renderedOutputBufferCount, images.get())
        }
        await(target, "soak post-cue audio/video/image advancement") {
            val player = requireNotNull(target.exoPlayer)
            player.videoDecoderCounters?.ensureUpdated(); player.audioDecoderCounters?.ensureUpdated()
            player.isPlaying && kotlin.math.abs(player.currentPosition - position) <= 2_000L && cueText(player) == cue &&
                (player.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > decoded.first &&
                (player.audioDecoderCounters?.renderedOutputBufferCount ?: 0) > decoded.second && images.get() > decoded.third
        }
    }

    private fun runOwnedSoak(session: OwnedSoakSession, template: PlayerRuntimeController, resolver: DirectDebridResolver,
        factory: PlayerMediaSourceFactory, profiles: ProfileManager, profile: Int, store: DebridSettingsDataStore,
        scenario: ActivityScenario<MainActivity>, outerJob: Job, reader: ImageReader, outputThread: HandlerThread,
        imageBalance: OwnedSoakImages, images: AtomicInteger, phase: JSONObject, outerDeadline: Long,
        onChanged: (OwnedSoakSession) -> Unit, checkpoint: () -> Unit) {
        OwnedPlaybackSoakChecks.selfChecks()
        val pid = Process.myPid()
        fun startIdentity() = File("/proc/self/stat").readText().substringAfterLast(") ").split(' ')[19]
            .also { check(it.matches(Regex("[0-9]+"))) }
        val processStart = startIdentity()
        val appHash = sha256(File(instrumentation.targetContext.applicationInfo.sourceDir))
        val testHash = sha256(File(instrumentation.context.applicationInfo.sourceDir))
        val owner = PlayerProgressWriteOwner(profile, CONTENT_ID, "movie", CONTENT_ID, null, null)
        val account = Triple(store.activeProfileId.value, store.activeProfileSelectionRevision, store.activeCredentialRevision)
        val activeRows = JSONArray(); val settledRows = JSONArray(); val children = JSONArray()
        val settled = mutableListOf<JSONObject>()
        val peaks = JSONObject()
        fun observe(): OwnedSoakObservation = onMain {
            val target = session.controller
            val player = requireNotNull(target.exoPlayer)
            val ownerMatches = target.currentProgressWriteOwner() == owner
            val profileMatches = profiles.activeProfileId.value == profile
            val accountMatches = account == Triple(store.activeProfileId.value, store.activeProfileSelectionRevision, store.activeCredentialRevision)
            val grantValid = ConnectedServicePlaybackGrants.registry.permits(URL)
            phase.put("currentOwnerMatches", ownerMatches).put("currentProfileMatches", profileMatches)
                .put("currentAccountMatches", accountMatches).put("currentGrantValid", grantValid)
            check(ownerMatches && profileMatches && accountMatches && grantValid && target.getCurrentStreamUrl() == URL)
            check(target.uiState.value.error == null && player.playerError == null && !target.isInBackground)
            check(player.playWhenReady && !target.userPausedManually && player.playbackParameters.speed == 1f)
            check(player.isCurrentMediaItemSeekable && !player.isCurrentMediaItemLive && player.duration in 129_000L..131_000L)
            check(!target.currentPlayerSettingsForReport.nuvioPerformanceModeEnabled && !target.currentPlayerSettingsForReport.bufferEngineEnabled)
            check(!NuvioEngineConfig.get().isNativeAllocationEnabled && !NuvioExoPlayerPerformanceHelper.enabled)
            check(imageBalance.failure.get() == null && imageBalance.snapshot().getInt("outstanding") <= 1)
            check(selectedFormat(player, C.TRACK_TYPE_AUDIO)?.language in setOf("tr", "tur") && player.audioFormat?.language in setOf("tr", "tur"))
            check(selectedFormat(player, C.TRACK_TYPE_TEXT)?.language in setOf("en", "eng"))
            player.videoDecoderCounters?.ensureUpdated(); player.audioDecoderCounters?.ensureUpdated()
            check(Process.myPid() == pid && startIdentity() == processStart)
            OwnedSoakObservation(SystemClock.elapsedRealtime(), SystemClock.uptimeMillis(), System.identityHashCode(player),
                player.currentPosition, requireNotNull(player.videoDecoderCounters).renderedOutputBufferCount,
                requireNotNull(player.audioDecoderCounters).renderedOutputBufferCount, images.get(), player.isPlaying)
        }
        fun summary(index: Int): JSONObject = onMain {
            check(session.controller.currentProgressWriteOwner() == owner && profiles.activeProfileId.value == profile)
            check(account == Triple(store.activeProfileId.value, store.activeProfileSelectionRevision, store.activeCredentialRevision))
            val audit = session.progress.snapshotAudit()
            var writes = 0; var reads = 0
            for (i in 0 until audit.length()) {
                val row = audit.getJSONObject(i)
                check(row.getBoolean("profileMatches") && row.getBoolean("videoMatches"))
                if (row.getString("kind") == "production-write") writes++ else if (row.getString("kind") == "production-read") reads++
            }
            JSONObject().put("controllerIndex", index).put("progressWrites", writes).put("progressReads", reads)
                .put("progressAuditRows", audit.length()).put("ownerMatches", session.controller.currentProgressWriteOwner() == owner)
        }
        fun releaseAndSample(minute: Int): JSONObject {
            val player = onMain { requireNotNull(session.controller.exoPlayer) }
            val allocator = onMain { requireNotNull(session.controller._loadControl).allocator }
            onMain {
                check(session.controller.currentProgressWriteOwner() == owner && profiles.activeProfileId.value == profile)
                check(account == Triple(store.activeProfileId.value, store.activeProfileSelectionRevision, store.activeCredentialRevision))
                session.controller.progressWriteCoordinator.deactivateForeground()
                session.controller.stopAndRelease()
            }
            runBlocking { session.job.cancelAndJoin() }
            drainSoakOutput(outputThread, imageBalance)
            SystemClock.sleep(3_500L)
            check(session.job.isCompleted)
            onMain {
                check(player.isReleased && allocator.totalBytesAllocated == 0)
                check(NuvioExoPlayerPerformanceHelper.liveAllocator !== allocator)
                check(PlayerMediaSourceFactory.vodCachePlayheadBytesProvider == null)
            }
            val row = summary(minute / 5 - 1)
            row.put("childJobCompleted", true).put("oldPlayerReleased", true).put("allocatorActiveZero", true)
            children.put(row)
            return OwnedPlaybackSoakChecks.resources().put("minute", minute).put("allocator", soakAllocator(allocator))
                .put("images", imageBalance.snapshot()).put("childJobCompleted", true)
        }
        phase.put("guardSelfChecksPassed", true).put("status", "running").put("pid", pid).put("processStartTicks", processStart)
            .put("appSha256", appHash).put("testApkSha256", testHash).put("fixedTargetMs", OwnedPlaybackSoakChecks.DURATION_MS)
            .put("requiredHealthyMs", OwnedPlaybackSoakChecks.HEALTHY_MS).put("activeRows", activeRows)
            .put("settledRows", settledRows).put("controllers", children).put("resourcePeaks", peaks)
            .put("nativeAllocationEnabled", false).put("nativeGraphicsBuffersEnumerated", false)
        val ready = observe()
        SystemClock.sleep(1_000L)
        val start = observe()
        OwnedPlaybackSoakChecks.requireObservation(true, start.elapsed - ready.elapsed,
            start.playing && start.position > ready.position && start.video > ready.video &&
                start.audio > ready.audio && start.images > ready.images)
        onMain { check(cueText(requireNotNull(session.controller.exoPlayer)) == ENGLISH_CUE_AT_45_SECONDS) }
        var last: OwnedSoakObservation? = null
        var window: OwnedSoakObservation? = null
        var healthyMs = 0L; var plannedMs = 0L; var maxGap = 0L; var monitorCount = 0
        var seekCount = 0; var recreations = 0; var minute = 0
        var videoIncrements = 0L; var audioIncrements = 0L; var imageIncrements = 0L
        while (true) {
            val elapsed = SystemClock.elapsedRealtime() - start.elapsed
            val uptime = SystemClock.uptimeMillis() - start.uptime
            phase.put("elapsedMs", elapsed).put("uptimeMs", uptime).put("observedHealthyMs", healthyMs)
                .put("seekCheckpoints", seekCount).put("recreations", recreations).put("monitorObservations", monitorCount)
                .put("plannedTransitionMs", plannedMs).put("maxUnexpectedObservationGapMs", maxGap)
                .put("observedVideoBufferIncrements", videoIncrements).put("observedAudioBufferIncrements", audioIncrements)
                .put("observedClosedImageIncrements", imageIncrements)
            check(SystemClock.elapsedRealtime() < outerDeadline && kotlin.math.abs(elapsed - uptime) <= 5_000L)
            if (elapsed >= OwnedPlaybackSoakChecks.DURATION_MS && uptime >= OwnedPlaybackSoakChecks.DURATION_MS) break
            if (minute < 120 && elapsed >= minute * 60_000L) {
                val began = SystemClock.elapsedRealtime()
                if (minute > 0 && minute % 5 == 0) {
                    val row = releaseAndSample(minute)
                    settled += row; settledRows.put(row)
                    val result = runBlocking { withTimeout(10_000L) {
                        resolver.resolve(ownedStream(), null, null, allowFallback = false)
                    } }
                    check(result is DirectDebridResolveResult.Success && result.url == URL)
                    check(!runBlocking { resolver.isPlaybackAuthorized(URL, profile + 1) })
                    check(ConnectedServicePlaybackGrants.registry.permits(URL))
                    row.put("nativeGrantRefreshed", true).put("wrongProfileDenied", true)
                    session.progress = OwnedProgress(template.watchProgressRepository, profile)
                    check(session.progress.snapshotAudit().length() == 0)
                    session.job = SupervisorJob(outerJob)
                    session.controller = onMain { createController(template, resolver, factory, session.progress, profile,
                        CoroutineScope(session.job + Dispatchers.Main.immediate)) }
                    onChanged(session)
                    scenario.onActivity { session.controller.attachHostActivity(it) }
                    onMain {
                        session.controller.progressWriteCoordinator.activateForeground(session.controller.currentProgressWriteOwner())
                        session.controller.startInitialPlaybackIfNeeded()
                    }
                    await(session.controller, "soak fresh real Exo") { session.controller.exoPlayer != null }
                    onMain { requireNotNull(session.controller.exoPlayer).apply { volume = 0f; setVideoSurface(reader.surface) } }
                    recreations++
                }
                val position = if (minute % 2 == 0) 5_000L else 45_000L
                seekSoakCheckpoint(session.controller, position, reader, images)
                seekCount++
                val row = OwnedPlaybackSoakChecks.resources().put("minute", minute).put("seekTargetMs", position).put("seekCuePassed", true)
                onMain {
                    val player = requireNotNull(session.controller.exoPlayer)
                    row.put("allocator", soakAllocator(requireNotNull(session.controller._loadControl).allocator))
                        .put("positionMs", player.currentPosition).put("cueText", cueText(player))
                        .put("audioInputLanguageTag", player.audioFormat?.language ?: JSONObject.NULL)
                        .put("textLanguageTag", selectedFormat(player, C.TRACK_TYPE_TEXT)?.language ?: JSONObject.NULL)
                }
                OwnedPlaybackSoakChecks.caps.keys.forEach { key -> peaks.put(key, maxOf(peaks.optLong(key), row.getLong(key))) }
                activeRows.put(row)
                plannedMs += SystemClock.elapsedRealtime() - began
                last = null; window = null
                phase.put("seekCheckpoints", seekCount).put("recreations", recreations).put("plannedTransitionMs", plannedMs)
                    .put("elapsedMs", SystemClock.elapsedRealtime() - start.elapsed).put("uptimeMs", SystemClock.uptimeMillis() - start.uptime)
                if (minute % 5 == 0) checkpoint()
                minute++
            }
            val now = observe()
            monitorCount++
            last?.let { before ->
                val gap = now.elapsed - before.elapsed
                maxGap = maxOf(maxGap, gap)
                phase.put("maxUnexpectedObservationGapMs", maxGap)
                check(gap in 1L..5_000L && now.playerId == before.playerId)
                videoIncrements += (now.video - before.video).coerceAtLeast(0)
                audioIncrements += (now.audio - before.audio).coerceAtLeast(0)
                imageIncrements += (now.images - before.images).coerceAtLeast(0)
                if (now.playing && before.playing && now.position > before.position && now.video > before.video &&
                    now.audio > before.audio && now.images > before.images) {
                    healthyMs += minOf(now.elapsed - start.elapsed, OwnedPlaybackSoakChecks.DURATION_MS) -
                        minOf(before.elapsed - start.elapsed, OwnedPlaybackSoakChecks.DURATION_MS)
                }
            }
            val base = window
            if (base == null) window = now else if (now.elapsed - base.elapsed >= 5_000L) {
                OwnedPlaybackSoakChecks.requireObservation(true, maxGap, now.position > base.position &&
                    now.video > base.video && now.audio > base.audio && now.images > base.images)
                window = now
            }
            last = now
            SystemClock.sleep(1_000L)
        }
        OwnedPlaybackSoakChecks.requireDuration(SystemClock.elapsedRealtime() - start.elapsed,
            SystemClock.uptimeMillis() - start.uptime, healthyMs, seekCount, recreations)
        check(activeRows.length() == 120 && settledRows.length() == 23 && children.length() == 23)
        phase.put("medianResourceGrowth", OwnedPlaybackSoakChecks.requireGrowth(settled.drop(5)))
        children.put(summary(23))
        check(children.length() == 24 && sha256(File(instrumentation.targetContext.applicationInfo.sourceDir)) == appHash &&
            sha256(File(instrumentation.context.applicationInfo.sourceDir)) == testHash)
        phase.put("nativeGrantResolveCalls", recreations + 1).put("durationWorkloadPassed", true)
            .put("status", "duration-passed-final-cleanup-pending")
    }

    private fun selectedFormat(player: Player, type: Int): Format? {
        player.currentTracks.groups.filter { it.type == type }.forEach { group ->
            for (index in 0 until group.length) if (group.isTrackSelected(index)) return group.getTrackFormat(index)
        }
        return null
    }

    private fun cueText(player: Player) = player.currentCues.cues.mapNotNull { it.text?.toString() }.joinToString("\n")

    private fun retryHandoffSnapshot(target: PlayerRuntimeController, outgoing: Player): JSONObject {
        fun subtitleKind(selection: PlayerRuntimeController.RememberedSubtitleSelection?): String = when (selection) {
            null -> "none"
            PlayerRuntimeController.RememberedSubtitleSelection.Disabled -> "disabled"
            is PlayerRuntimeController.RememberedSubtitleSelection.Internal -> "internal"
            is PlayerRuntimeController.RememberedSubtitleSelection.Addon -> "addon"
        }
        val player = target.exoPlayer
        val pending = target.pendingEngineSwitchTrackPreference
        val explicit = target.explicitSubtitleSelectionForEngineSwitch
        val effective = target.effectiveSubtitleSelectionForEngineSwitch
        return JSONObject().put("exoPresent", player != null).put("currentExoIsOutgoing", player === outgoing)
            .put("isReleasingPlayer", target.isReleasingPlayer)
            .put("pendingSwitchPresent", pending != null)
            .put("pendingSwitchSubtitleKind", subtitleKind(pending?.preference?.subtitle))
            .put("pendingSwitchAudioLanguage", pending?.preference?.audio?.language ?: JSONObject.NULL)
            .put("pendingSwitchSourceEngine", pending?.sourceEngine?.name ?: JSONObject.NULL)
            .put("pendingSwitchStreamMatches", pending?.streamUrl == target.getCurrentStreamUrl())
            .put("persistedSubtitleKind", subtitleKind(target.persistedTrackPreference?.subtitle))
            .put("rememberedSubtitleKind", subtitleKind(target.rememberedTrackPreference?.subtitle))
            .put("explicitSubtitleKind", subtitleKind(explicit?.selection))
            .put("explicitSubtitleStreamMatches", explicit?.streamUrl == target.getCurrentStreamUrl())
            .put("effectiveSubtitleKind", subtitleKind(effective?.selection))
            .put("effectiveSubtitleStreamMatches", effective?.streamUrl == target.getCurrentStreamUrl())
            .put("hasRenderedFirstFrame", target.hasRenderedFirstFrame)
            .put("currentEngine", target.currentInternalPlayerEngine.name)
            .put("mimeType", target.currentStreamMimeType ?: JSONObject.NULL)
            .put("isTorrentStream", target.isTorrentStream).put("isInBackground", target.isInBackground)
            .put("hasError", target.uiState.value.error != null)
            .put("seekable", player?.isCurrentMediaItemSeekable ?: JSONObject.NULL)
            .put("live", player?.isCurrentMediaItemLive ?: JSONObject.NULL)
            .put("durationMs", player?.duration ?: JSONObject.NULL)
            .put("disabledTrackTypes", JSONArray(player?.trackSelectionParameters?.disabledTrackTypes?.toList().orEmpty()))
    }

    private fun playbackSnapshot(target: PlayerRuntimeController, expectedUrl: String = URL): JSONObject {
        val player = requireNotNull(target.exoPlayer)
        val audio = selectedFormat(player, C.TRACK_TYPE_AUDIO)
        val text = selectedFormat(player, C.TRACK_TYPE_TEXT)
        val textRenderers = JSONArray()
        val selector = target.trackSelector
        selector?.currentMappedTrackInfo?.let { mapped ->
            for (index in 0 until mapped.rendererCount) if (mapped.getRendererType(index) == C.TRACK_TYPE_TEXT) {
                textRenderers.put(JSONObject().put("index", index).put("disabled", selector.parameters.getRendererDisabled(index)))
            }
        }
        return JSONObject().put("positionMs", player.currentPosition).put("playWhenReady", player.playWhenReady)
            .put("userPausedManually", target.userPausedManually)
            .put("playbackState", player.playbackState).put("audioLanguageTag", audio?.language ?: JSONObject.NULL)
            .put("audioInputLanguageTag", player.audioFormat?.language ?: JSONObject.NULL)
            .put("audioTrackId", audio?.id ?: JSONObject.NULL).put("textLanguageTag", text?.language ?: JSONObject.NULL)
            .put("textTrackId", text?.id ?: JSONObject.NULL).put("textSelected", player.currentTracks.isTypeSelected(C.TRACK_TYPE_TEXT))
            .put("cueText", cueText(player)).put("profileId", target.profileId)
            .put("uiSubtitleIndex", target.uiState.value.selectedSubtitleTrackIndex)
            .put("selectedAddonPresent", target.uiState.value.selectedAddonSubtitle != null)
            .put("sidecarActive", target.isSidecarAddonSubtitleActive())
            .put("disabledTrackTypes", JSONArray(player.trackSelectionParameters.disabledTrackTypes.toList()))
            .put("trackSelectionParametersClass", player.trackSelectionParameters.javaClass.name)
            .put("textRenderers", textRenderers)
            .put("videoId", target.currentVideoId).put("sameSource", target.getCurrentStreamUrl() == expectedUrl)
    }

    private fun assertBlockedBeforeRead(factory: PlayerMediaSourceFactory, context: Context, bytes: OwnedBytes, url: String = URL) {
        val before = bytes.opens.get()
        try {
            factory.createMediaSource(context, url, emptyMap())
            fail("A missing/revoked native grant must reject even the injected data source.")
        } catch (blocked: IllegalStateException) {
            assertEquals(SyclopPlaybackPolicy.BLOCKED_MESSAGE, blocked.message)
        }
        assertEquals(before, bytes.opens.get())
    }

    private fun createController(template: PlayerRuntimeController, resolver: DirectDebridResolver,
        factory: PlayerMediaSourceFactory, progress: OwnedProgress, profile: Int, scope: CoroutineScope,
        catalog: OwnedNextPlaybackCatalog? = null): PlayerRuntimeController = PlayerRuntimeController(
        context = template.context,
        watchProgressRepository = progress,
        progressWriteAuthority = PlayerProgressWriteAuthority(),
        metaRepository = catalog?.metadata ?: object : MetaRepository by template.metaRepository {
            override fun getMetaFromAllAddons(type: String, id: String, sourceAddonBaseUrl: String?) =
                flowOf(NetworkResult.Error("Owned fixture has no external metadata."))
            override fun getCachedMeta(type: String, id: String): Meta? = null
        },
        streamRepository = catalog?.streams ?: template.streamRepository,
        addonRepository = template.addonRepository,
        pluginManager = template.pluginManager,
        subtitleRepository = object : SubtitleRepository {
            override suspend fun getSubtitles(type: String, id: String, videoId: String?, videoHash: String?,
                videoSize: Long?, filename: String?, onProgress: ((Int, Int, String?) -> Unit)?,
                onSubtitlesEmitted: ((com.nuvio.tv.core.network.NetworkResult<List<Subtitle>>) -> Unit)?) = emptyList<Subtitle>()
        },
        parentalGuideRepository = template.parentalGuideRepository,
        trackingScrobbleCoordinator = TrackingScrobbleCoordinator(TrackingProviderRegistry(emptyList())),
        traktEpisodeMappingService = template.traktEpisodeMappingService,
        skipIntroRepository = template.skipIntroRepository,
        playerSettingsDataStore = template.playerSettingsDataStore,
        deviceLocalPlayerPreferences = template.deviceLocalPlayerPreferences,
        streamLinkCacheDataStore = template.streamLinkCacheDataStore,
        streamBadgeSettingsDataStore = template.streamBadgeSettingsDataStore,
        bingeGroupCacheDataStore = template.bingeGroupCacheDataStore,
        layoutPreferenceDataStore = template.layoutPreferenceDataStore,
        episodeShufflePlayback = template.episodeShufflePlayback,
        watchedItemsPreferences = template.watchedItemsPreferences,
        trackPreferenceDataStore = template.trackPreferenceDataStore,
        audioDelayRouteDataStore = template.audioDelayRouteDataStore,
        torrentService = template.torrentService,
        torrentSettings = template.torrentSettings,
        tmdbService = template.tmdbService,
        tmdbMetadataService = template.tmdbMetadataService,
        tmdbSettingsDataStore = template.tmdbSettingsDataStore,
        directDebridResolver = resolver,
        youTubeStreamResolver = template.youTubeStreamResolver,
        directDebridStreamPreparer = DirectDebridStreamPreparer(requireNotNull(template.debridSettingsDataStore), resolver),
        cloudLibraryRepository = template.cloudLibraryRepository,
        cloudPlaybackProgressStore = template.cloudPlaybackProgressStore,
        cloudPlaybackSessionStore = template.cloudPlaybackSessionStore,
        streamBadgePresentation = template.streamBadgePresentation,
        playbackIssueReportRepository = template.playbackIssueReportRepository,
        tvRecommendationManager = template.tvRecommendationManager,
        serverPlayback = template.serverPlayback,
        serverStreams = template.serverStreams,
        party = template.party,
        profileId = profile,
        savedStateHandle = SavedStateHandle(mapOf("streamUrl" to URL, "title" to "Owned controller fixture",
            "contentId" to CONTENT_ID, "contentType" to "movie", "videoId" to CONTENT_ID,
            "filename" to "fixture.mp4", "startFromBeginning" to "true", "profileId" to profile.toString()) +
            if (catalog == null) emptyMap() else mapOf("contentType" to "series", "season" to "1", "episode" to "1",
                "episodeTitle" to "Owned episode 1", "filename" to OwnedNextPlaybackCatalog.FIRST_FILENAME)),
        scope = scope,
        firstWatchReadiness = null,
        debridSettingsDataStore = template.debridSettingsDataStore,
        playbackMediaSourceFactory = factory
    )

    /** Starts empty. Only real production playback writes populate the source-resume boundary. */
    private class OwnedProgress(private val delegate: WatchProgressRepository, private val profile: Int,
        private val nextMode: Boolean = false) : WatchProgressRepository by delegate {
        private val rows = MutableStateFlow<Map<PlayerProgressWriteOwner, WatchProgress>>(emptyMap())
        private val audit = mutableListOf<JSONObject>()
        val reads = AtomicInteger()
        val lastReadPositionMs = AtomicLong(-1L)
        val episodeTwoEmptyReads = AtomicInteger()
        fun hasEpisodeRow(episode: Int) = rows.value.keys.any { it.season == 1 && it.episode == episode }

        private fun requireOwner(contentId: String, profileId: Int) {
            check(contentId == CONTENT_ID && profileId == profile) { "Unexpected owned progress owner." }
        }
        private fun record(kind: String, progress: WatchProgress?, completed: Boolean = false) = synchronized(audit) {
            audit += JSONObject().put("sequence", audit.size + 1).put("kind", kind)
                .put("positionMs", progress?.position ?: JSONObject.NULL)
                .put("completed", completed).put("profileMatches", true)
                .put("videoMatches", progress == null || progress.videoId == CONTENT_ID || nextMode && progress.videoId == EPISODE_TWO_ID)
                .also { if (nextMode) it.put("season", progress?.season ?: JSONObject.NULL).put("episode", progress?.episode ?: JSONObject.NULL) }
        }
        fun snapshotAudit() = synchronized(audit) { JSONArray(audit.toList()) }
        override suspend fun captureLocalPlaybackScope(profileId: Int): String? {
            requireOwner(CONTENT_ID, profileId)
            return null
        }
        override suspend fun savePlaybackProgress(progress: WatchProgress, profileId: Int,
            origin: com.nuvio.tv.core.tracking.TrackingPlaybackOrigin?, completed: Boolean, syncRemote: Boolean) {
            requireOwner(progress.contentId, profileId)
            if (nextMode) {
                check(progress.contentType == "series" && progress.season == 1 &&
                    (progress.episode == 1 && progress.videoId == CONTENT_ID || progress.episode == 2 && progress.videoId == EPISODE_TWO_ID))
            } else check(progress.contentType == "movie" && progress.videoId == CONTENT_ID && progress.season == null && progress.episode == null)
            val owner = PlayerProgressWriteOwner(profileId, progress.contentId, progress.contentType,
                progress.videoId, progress.season, progress.episode)
            record("production-write", progress, completed)
            val stored = if (nextMode && completed) progress.copy(position = progress.duration.coerceAtLeast(1L),
                duration = progress.duration.coerceAtLeast(1L), progressPercent = 100f) else progress
            rows.update { it + (owner to stored) }
        }
        override fun getProgress(contentId: String) = getProgress(contentId, profile)
        override fun getProgress(contentId: String, profileId: Int) = read(contentId, profileId, null, null)
        override fun getEpisodeProgress(contentId: String, season: Int, episode: Int) =
            getEpisodeProgress(contentId, season, episode, profile)
        override fun getEpisodeProgress(contentId: String, season: Int, episode: Int, profileId: Int) =
            read(contentId, profileId, season, episode)
        private fun read(contentId: String, profileId: Int, season: Int?, episode: Int?) = rows.map { current ->
            requireOwner(contentId, profileId)
            val result = current.entries.filter { (owner, _) ->
                owner.profileId == profileId && owner.contentId == contentId &&
                    (nextMode && season == null && episode == null || owner.season == season && owner.episode == episode)
            }.maxByOrNull { it.value.lastWatched }?.value
            synchronized(audit) {
                record("production-read", result)
                if (nextMode) {
                    audit.last().put("readKind", if (season == null && episode == null) "parent-latest" else "exact-episode")
                        .put("requestedSeason", season ?: JSONObject.NULL).put("requestedEpisode", episode ?: JSONObject.NULL)
                        .put("returnedEpisode", result?.episode ?: JSONObject.NULL)
                        .put("parentLatestPositionMs", current.values.maxByOrNull { it.lastWatched }?.position ?: JSONObject.NULL)
                    if (season == 1 && episode == 2 && result == null) episodeTwoEmptyReads.incrementAndGet()
                }
            }
            reads.incrementAndGet()
            lastReadPositionMs.set(result?.position ?: -1L)
            result
        }
        override suspend fun normalizeParentContentId(parentContentId: String, videoId: String?): String {
            requireOwner(parentContentId, profile)
            check(videoId == null || videoId == CONTENT_ID || nextMode && videoId == EPISODE_TWO_ID)
            return parentContentId
        }
        override suspend fun normalizeParentContentId(parentContentId: String, videoId: String?, profileId: Int): String {
            requireOwner(parentContentId, profileId)
            return normalizeParentContentId(parentContentId, videoId)
        }
    }

    private class OwnedBytes(private val bytes: ByteArray, private val nextBytes: ByteArray? = null) : DataSource.Factory {
        val opens = AtomicInteger()
        val bytesRead = AtomicLong()
        val sourceBOpens = AtomicInteger()
        val sourceBBytesRead = AtomicLong()
        val nextOpens = AtomicInteger()
        val nextBytesRead = AtomicLong()
        val readFaultArmed = AtomicBoolean()
        private val faultFailures = AtomicInteger()
        private val armedRangeOpens = AtomicInteger()
        private val lastFaultOffset = AtomicLong(-1L)
        private val lastFaultRangeStart = AtomicLong(-1L)
        fun faultDiagnostics() = JSONObject().put("armed", readFaultArmed.get()).put("readThrows", faultFailures.get())
            .put("rangeOpensWhileArmed", armedRangeOpens.get()).put("lastFaultOffset", lastFaultOffset.get())
            .put("lastFaultRangeStart", lastFaultRangeStart.get())
        override fun createDataSource(): DataSource = object : DataSource {
            private var source = ByteArrayDataSource(bytes)
            private val listeners = mutableListOf<TransferListener>()
            private var sourceB = false
            private var next = false
            private var openedSpec: DataSpec? = null
            private var readOffset = 0L
            override fun addTransferListener(listener: TransferListener) {
                listeners += listener
                source.addTransferListener(listener)
            }
            override fun open(spec: DataSpec): Long {
                check(spec.uri.toString() in setOf(URL, URL_B) || nextBytes != null && spec.uri.toString() == URL_NEXT) { "Owned fixture URI mismatch." }
                openedSpec = spec
                readOffset = spec.position
                if (readFaultArmed.get()) armedRangeOpens.incrementAndGet()
                sourceB = spec.uri.toString() == URL_B
                next = spec.uri.toString() == URL_NEXT
                if (nextBytes != null) source = ByteArrayDataSource(if (next) nextBytes else bytes)
                    .also { data -> listeners.forEach(data::addTransferListener) }
                // Media3 requests optional ICY metadata for progressive sources.
                check(spec.httpRequestHeaders.isEmpty() || spec.httpRequestHeaders == mapOf("Icy-MetaData" to "1")) {
                    "Unexpected owned header names: ${spec.httpRequestHeaders.keys.sorted()}."
                }
                opens.incrementAndGet()
                if (sourceB) sourceBOpens.incrementAndGet()
                if (next) nextOpens.incrementAndGet()
                return source.open(spec)
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (length > 0 && readFaultArmed.get()) {
                    val spec = requireNotNull(openedSpec)
                    faultFailures.incrementAndGet()
                    lastFaultOffset.set(readOffset)
                    lastFaultRangeStart.set(spec.position)
                    throw HttpDataSource.InvalidResponseCodeException(404, "Owned synthetic read fault; no HTTP response",
                        IOException(READ_FAULT_MARKER), emptyMap(), spec, byteArrayOf())
                }
                return source.read(buffer, offset, length).also {
                    if (it > 0) {
                        readOffset += it
                        bytesRead.addAndGet(it.toLong())
                        if (sourceB) sourceBBytesRead.addAndGet(it.toLong())
                        if (next) nextBytesRead.addAndGet(it.toLong())
                    }
                }
            }
            override fun getUri() = source.uri
            override fun getResponseHeaders() = source.responseHeaders
            override fun close() = source.close()
        }
    }

    private class OwnedNativeApi(private val size: Long, private val nextSize: Long? = null) : RealDebridApi {
        val calls = AtomicInteger()
        val nextCalls = AtomicInteger()
        override suspend fun unrestrictLink(authorization: String, link: String): Response<RealDebridUnrestrictLinkDto> {
            check(authorization == "Bearer $SYNTHETIC_CREDENTIAL")
            val download = when (link) {
                INPUT_URL -> URL
                INPUT_URL_B -> URL_B
                INPUT_URL_NEXT -> if (nextSize != null) URL_NEXT else unexpected()
                else -> unexpected()
            }
            calls.incrementAndGet()
            val next = download == URL_NEXT
            if (next) nextCalls.incrementAndGet()
            val filename = if (next) OwnedNextPlaybackCatalog.NEXT_FILENAME
                else if (nextSize != null) OwnedNextPlaybackCatalog.FIRST_FILENAME else "fixture.mp4"
            return Response.success(RealDebridUnrestrictLinkDto(filename = filename, filesize = if (next) nextSize else size, download = download))
        }
        override suspend fun getUser(authorization: String): Response<ResponseBody> = unexpected()
        override suspend fun listTorrents(authorization: String, page: Int, limit: Int): Response<List<RealDebridTorrentInfoDto>> = unexpected()
        override suspend fun addMagnet(authorization: String, magnet: String): Response<RealDebridAddTorrentDto> = unexpected()
        override suspend fun getTorrentInfo(authorization: String, id: String): Response<RealDebridTorrentInfoDto> = unexpected()
        override suspend fun selectFiles(authorization: String, id: String, files: String): Response<ResponseBody> = unexpected()
        override suspend fun deleteTorrent(authorization: String, id: String): Response<ResponseBody> = unexpected()
        private fun unexpected(): Nothing = throw AssertionError("Unexpected native API call; no production delegation exists.")
    }

    private fun ownedStream(inputUrl: String = INPUT_URL) = Stream(name = "Owned fixture", title = "Owned fixture", description = null,
        url = inputUrl, ytId = null, infoHash = null, fileIdx = null, externalUrl = null,
        behaviorHints = null, addonName = "Owned fixture", addonLogo = null,
        clientResolve = StreamClientResolve("hoster", null, null, null, null, null, "fixture.mp4",
            "movie", CONTENT_ID, null, "Owned fixture", null, null, DebridProviders.REAL_DEBRID_ID, null, null, true))

    private fun await(controller: PlayerRuntimeController, stage: String, allowPolicyError: Boolean = false,
        condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (onMain {
                val error = controller.uiState.value.error
                check(error == null || allowPolicyError && error == SyclopPlaybackPolicy.BLOCKED_MESSAGE) {
                    "Owned controller failed during $stage."
                }
                condition()
            }) return
            SystemClock.sleep(20L)
        }
        fail("Owned controller timed out during $stage.")
    }

    private fun requireNoAccounts(context: Context) {
        val app = context.applicationContext as NuvioApplication
        val auth = field<AuthManager>(app.startupSyncService, "authManager")
        require(!auth.isAuthenticated && auth.currentUserId == null) { "Cloud account connected; fixture refused." }
        val secret = Regex("(?i).*(token|api_key|oauth_state|credentials|password|session).*")
        File(context.filesDir, "datastore").listFiles()?.filter { it.name.endsWith(".preferences_pb") }?.forEach { file ->
            require(BackupRestoreBootstrap.readPreferences(file).asMap().none { (key, value) ->
                secret.matches(key.name) && value.toString().isNotBlank()
            }) { "Account-bearing preferences found; fixture refused." }
        }
        File(context.applicationInfo.dataDir, "shared_prefs").listFiles()?.filter { it.extension == "xml" }?.forEach { file ->
            file.inputStream().use { input ->
                val parser = Xml.newPullParser().apply { setInput(input, "UTF-8") }
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG) {
                        val name = parser.getAttributeValue(null, "name").orEmpty()
                        require(!name.startsWith("account.p") && !name.matches(Regex("profile\\.[1-6]")) && !secret.matches(name)) {
                            "Persisted account record found; fixture refused."
                        }
                    }
                    parser.next()
                }
            }
        }
    }

    private fun isQemu(): Boolean {
        fun property(name: String) = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("getprop $name")).bufferedReader().use { it.readText().take(64).trim() }
        return Build.HARDWARE in setOf("ranchu", "goldfish") &&
            (property("ro.kernel.qemu") == "1" || property("ro.boot.qemu") == "1")
    }

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { result.set(runCatching(block)) }
        return result.get().getOrThrow()
    }
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(owner: Any, name: String): T = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner) as T
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val SYNTHETIC_RETRY_ERROR = "Owned fixture synthetic terminal UI error; no transport failure"
        const val ENGLISH_CUE_AT_45_SECONDS = "EN cue 05 / 40 seconds: This is the English audio test."
        const val SYNTHETIC_CREDENTIAL = "owned-controller-fixture-not-a-real-key"
        const val CONTENT_ID = "owned:controller-fixture:v1"
        const val INPUT_URL = "https://owned-input.example.test/fixture.mp4"
        const val URL = "https://owned-playback.example.test/fixture.mp4"
        const val INPUT_URL_B = "https://owned-input.example.test/fixture-b.mp4"
        const val URL_B = "https://owned-playback.example.test/fixture-b.mp4"
        const val EPISODE_TWO_ID = "owned:controller-fixture:v1:episode-2"
        const val INPUT_URL_NEXT = "https://owned-input.example.test/fixture-next.mp4"
        const val URL_NEXT = "https://owned-playback.example.test/fixture-next.mp4"
        const val NEXT_SOURCE_SHA256 = "290f1d7c07ae1bf4fee89df76c1e7ce1b2042cba325313585fd049860cb90e1e"
        const val NEXT_MEDIA_SHA256 = "7402f5e64164fa5e8d02f818042e312d9670d305eb785fa9018fdebeaedbed70"
        const val ENGLISH_FIRST_CUE = "EN cue 01 / 0 seconds: This is the English audio test."
        const val READ_FAULT_MARKER = "owned-persistent-byte-read-fault-404"
        const val READ_FAULT_SEEK_MS = 110_000L
        const val ENGLISH_CUE_AT_110_SECONDS = "EN cue 12 / 110 seconds: This is the English audio test."
    }
}
