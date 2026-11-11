package com.nuvio.tv

import com.nuvio.tv.core.initialization.StartupInitializationCoordinator
import com.nuvio.tv.ui.components.StartupInitializationScreen
import com.nuvio.tv.ui.components.StartupInitializationContent
import com.nuvio.tv.ui.components.startupInitializationBlocksHome
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.nuvio.tv.ui.screens.ambient.AmbientIdleController
import com.nuvio.tv.ui.screens.ambient.AmbientModeHost
import com.nuvio.tv.ui.screens.ambient.LocalAmbientIdleController
import com.nuvio.tv.ui.components.LocalStartupLoadingState
import com.nuvio.tv.ui.components.StartupLoadingState
import com.nuvio.tv.ui.components.StartupDestination
import com.nuvio.tv.ui.components.shouldShowStartupSplash
import com.nuvio.tv.ui.components.startupDestinationForRoute
import com.nuvio.tv.core.runtime.PluginRuntimeHooks
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.ConfigurationCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.metrics.performance.JankStats
import androidx.metrics.performance.PerformanceMetricsState
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.DrawerValue
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.ModalNavigationDrawer
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import androidx.tv.material3.contentColorFor
import androidx.tv.material3.rememberDrawerState
import com.nuvio.tv.R
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.auth.DeviceSessionRegistration
import com.nuvio.tv.core.deeplink.AddonLinkInstallRequest
import com.nuvio.tv.core.deeplink.DeepLinkHandler
import com.nuvio.tv.core.deeplink.DeepLinkParser
import com.nuvio.tv.core.player.PlayerWindowBackdrop
import com.nuvio.tv.core.party.PartyRepository
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.ProfileSyncService
import com.nuvio.tv.core.sync.StartupSyncService
import com.nuvio.tv.core.tracking.TrackingProgressRefreshCoordinator
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.data.local.AppOnboardingDataStore
import com.nuvio.tv.data.local.AuthSessionNoticeDataStore
import com.nuvio.tv.data.local.ExperienceModeDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.StartupAuthNotice
import com.nuvio.tv.data.local.ThemeDataStore
import com.nuvio.tv.data.repository.MemberAccessRepository
import com.nuvio.tv.data.remote.supabase.AvatarRepository
import com.nuvio.tv.domain.model.AppFont
import com.nuvio.tv.domain.model.AppTheme
import com.nuvio.tv.domain.model.CustomThemeColors
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.domain.model.CardDepthStyle
import com.nuvio.tv.domain.model.CosmeticEntitlement
import com.nuvio.tv.domain.model.DiscoverLocation
import com.nuvio.tv.domain.model.ExperienceMode
import com.nuvio.tv.domain.model.MemberAccess
import com.nuvio.tv.domain.model.ProfileBackgroundSelection
import com.nuvio.tv.domain.model.resolveProfileBackgroundSelection
import com.nuvio.tv.domain.model.SettingsUiStyle
import com.nuvio.tv.domain.model.resolveAppTheme
import com.nuvio.tv.domain.model.resolveCustomThemeColors
import com.nuvio.tv.domain.deeplink.AppDeepLink
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.ui.components.NuvioScrollDefaults
import com.nuvio.tv.ui.components.BrandWordmark
import com.nuvio.tv.ui.components.LocalCardDepthStyle
import com.nuvio.tv.ui.components.LocalLandscapePosterMode
import com.nuvio.tv.ui.components.LocalAlwaysBackdropWithLogo
import com.nuvio.tv.ui.components.ProfileAvatarCircle
import com.nuvio.tv.ui.navigation.LocalNavViewModelStoreOwner
import com.nuvio.tv.ui.navigation.NuvioNavHost
import com.nuvio.tv.ui.navigation.Screen
import com.nuvio.tv.ui.navigation.rememberNavSessionViewModelStoreOwner
import com.nuvio.tv.ui.screens.party.PartyEffects
import com.nuvio.tv.ui.membership.LocalMemberAccess
import com.nuvio.tv.ui.screens.account.AuthQrSignInScreen
import com.nuvio.tv.ui.screens.addon.AddonDeepLinkConfirmationDialog
import com.nuvio.tv.ui.screens.addon.EssentialAddonSetupScreen
import com.nuvio.tv.ui.screens.profile.ProfileSelectionScreen
import com.nuvio.tv.ui.theme.NuvioComponents
import com.nuvio.tv.ui.theme.NuvioLayout
import com.nuvio.tv.ui.theme.NuvioMotion
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioRadii
import com.nuvio.tv.ui.theme.NuvioStrokes
import com.nuvio.tv.ui.theme.NuvioFocus
import com.nuvio.tv.ui.theme.sidebarFocusStroke
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.theme.ThemeColors
import com.nuvio.tv.ui.theme.accentBrush
import com.nuvio.tv.ui.theme.brandWordmarkResource
import com.nuvio.tv.ui.util.LocalFastHorizontalNavigationEnabled
import com.nuvio.tv.ui.util.LocalRecompositionHighlighterEnabled
import com.nuvio.tv.ui.util.rememberDrawerItemFocusRequesters
import com.nuvio.tv.ui.util.rememberRawSvgPainter
import com.nuvio.tv.updater.rememberInstalledUpdateNotes
import com.nuvio.tv.updater.InstalledUpdateNotesPolicy
import com.nuvio.tv.updater.UpdateViewModel
import com.nuvio.tv.updater.ui.UpdateBannerHost
import dagger.hilt.android.AndroidEntryPoint
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

val LocalSidebarExpanded = compositionLocalOf { false }
val LocalContentFocusRequester = compositionLocalOf { FocusRequester.Default }
/** Explicit root-navigation entry, independent of geometric content focus search. */
val LocalOpenSidebar = compositionLocalOf<(() -> Unit)?> { null }

data class SplashBackground(
    val profileColorHex: String? = null,
    val backgroundUrl: String? = null,
    val backgroundCacheKey: String? = null,
    val skipGradient: Boolean = false,
    val brandWordmarkRes: Int? = null
)
val LocalSplashBackground = compositionLocalOf { SplashBackground() }

private const val SIDEBAR_AUTO_COLLAPSE_DELAY_MS = 3_000L

private const val MAX_SUPPORTED_FONT_SCALE = 1.15f

data class DrawerItem(
    val route: String,
    val label: String,
    val iconRes: Int? = null,
    val icon: ImageVector? = null
)

private data class MainUiPrefs(
    val theme: AppTheme = AppTheme.WHITE,
    val customThemeColors: CustomThemeColors = CustomThemeColors.Default,
    val memberAccess: MemberAccess = MemberAccess.None,
    val font: AppFont = AppFont.INTER,
    val amoledMode: Boolean = false,
    val amoledSurfacesMode: Boolean = false,
    val hasChosenLayout: Boolean? = null,
    val experienceMode: ExperienceMode? = null,
    val experienceModeLoaded: Boolean = false,
    val addonSetupSkipped: Boolean = false,
    val sidebarCollapsed: Boolean = false,
    val modernSidebarEnabled: Boolean = false,
    val modernSidebarBlurPref: Boolean = false,
    val discoverLocation: DiscoverLocation? = null,
    val smoothBringIntoViewEnabled: Boolean = true,
    val fastHorizontalNavigationEnabled: Boolean = false,
    val composeHighlighterEnabled: Boolean = false,
    val settingsUiStyle: SettingsUiStyle = SettingsUiStyle.CLASSIC,
    val cardDepthStyle: CardDepthStyle = CardDepthStyle(),
    val landscapePosterMode: Boolean = false,
    val alwaysBackdropWithLogo: Boolean = false
)

@AndroidEntryPoint
open class MainActivity : ComponentActivity() {

    @Inject
    lateinit var themeDataStore: ThemeDataStore

    @Inject
    lateinit var layoutPreferenceDataStore: LayoutPreferenceDataStore

    @Inject
    lateinit var experienceModeDataStore: ExperienceModeDataStore

    @Inject
    lateinit var memberAccessRepository: MemberAccessRepository

    @Inject
    lateinit var addonRepository: AddonRepository

    @Inject
    lateinit var addonPreferences: com.nuvio.tv.data.local.AddonPreferences

    @Inject
    lateinit var firstWatchReadiness: com.nuvio.tv.core.setup.FirstWatchReadinessRepository

    @Inject
    lateinit var trackingProgressRefreshCoordinator: TrackingProgressRefreshCoordinator

    @Inject
    lateinit var startupInitializationCoordinator: StartupInitializationCoordinator

    @Inject
    lateinit var startupSyncService: StartupSyncService

    @Inject
    lateinit var androidTvChannelSyncService: com.nuvio.tv.core.sync.androidtv.AndroidTvChannelSyncService

    @Inject
    lateinit var profileSyncService: ProfileSyncService

    @Inject
    lateinit var profileManager: ProfileManager

    @Inject
    lateinit var profileLockStateDataStore: com.nuvio.tv.data.local.ProfileLockStateDataStore

    @Inject
    lateinit var authManager: AuthManager

    @Inject
    lateinit var deviceSessionRegistration: DeviceSessionRegistration

    @Inject
    lateinit var authSessionNoticeDataStore: AuthSessionNoticeDataStore

    @Inject
    lateinit var appOnboardingDataStore: AppOnboardingDataStore

    @Inject
    lateinit var avatarRepository: AvatarRepository

    @Inject
    lateinit var profileBackgroundRepository: com.nuvio.tv.data.remote.supabase.ProfileBackgroundRepository

    @Inject
    lateinit var trailerPlayerPool: com.nuvio.tv.core.player.TrailerPlayerPool

    @Inject
    lateinit var externalPlaybackTracker: com.nuvio.tv.core.player.ExternalPlaybackTracker

    @Inject
    lateinit var deepLinkHandler: DeepLinkHandler

    @Inject
    lateinit var tvCardLaunches: com.nuvio.tv.core.sync.androidtv.TvCardLaunchAuthenticator

    @Inject
    lateinit var partyRepository: PartyRepository

    private val pendingDeepLinkUrl = MutableStateFlow<String?>(null)
    private val pendingLaunchIntent = MutableStateFlow<Intent?>(null)

    private lateinit var jankStats: JankStats

    /** True until the first onResume after onCreate completes. */
    private var isFirstResumeAfterCreate = false
    /** Mirrors the committed visible gate; hidden overlays must not intercept its remote input. */
    private var startupInitializationGateVisible = false
    private var visibleAutoNextOverlayProfileId: Int? = null

    @OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
    override fun attachBaseContext(newBase: Context) {
        val tag = LocaleCache.localeTag.takeIf { it != LocaleCache.UNSET }

        if (!tag.isNullOrEmpty()) {
            val locale = Locale.forLanguageTag(tag)
            Locale.setDefault(locale)
            val config = Configuration(newBase.resources.configuration)
            config.setLocale(locale)
            super.attachBaseContext(newBase.createConfigurationContext(config))
        } else {
            // Cache not ready yet (very early cold start) — use system locale
            // The IO coroutine in Application.onCreate will finish before any activity
            // is usually created, but if not, we just use system locale until next launch
            super.attachBaseContext(newBase)
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        isFirstResumeAfterCreate = true
        window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))

        // Recover per-handoff result keys after Android restores this Activity's registry.
        externalPlaybackTracker.bindActivityResultRegistry(activityResultRegistry)

        PluginRuntimeHooks.onActivityCreate(this)

        window?.decorView?.post {
            val snapshot = com.nuvio.tv.core.player.DisplayCapabilities.detect(this)
            com.nuvio.tv.core.player.DisplayCapabilities.logSummary(snapshot)
        }

        captureDeepLinkIntent(intent)
        captureLaunchIntent(intent)

        setContent {
            var selectedProfileThisSession by key("selected-profile-id") {
                rememberSaveable { mutableStateOf<Int?>(null) }
            }
            var unlockedProfileThisActivity by remember { mutableStateOf<Int?>(null) }
            var startupSession by remember { mutableIntStateOf(0) }
            // Triggered immediately on profile click (before system confirms selection).
            var splashTriggered by remember { mutableStateOf(false) }
            // Overrides splash background with the currently focused profile
            // so the splash matches the profile selection background.
            var focusedSplashColor by remember { mutableStateOf<String?>(null) }
            var focusedSplashBgUrl by remember { mutableStateOf<String?>(null) }
            var focusedSplashCacheKey by remember { mutableStateOf<String?>(null) }
            var focusedSplashTheme by remember { mutableStateOf<AppTheme?>(null) }
            var onboardingCompletedThisSession by remember { mutableStateOf(false) }
            var onboardingProfileSyncInProgress by remember { mutableStateOf(false) }
            val hasSeenAuthQrFlow = remember(appOnboardingDataStore) {
                appOnboardingDataStore.hasSeenAuthQrOnFirstLaunch.map<Boolean, Boolean?> { it }
            }
            val hasSeenAuthQrOnFirstLaunch by hasSeenAuthQrFlow.collectAsState(initial = null)
            val authState by authManager.authState.collectAsState()
            val context = LocalContext.current
            val resources = LocalResources.current

            LaunchedEffect(authSessionNoticeDataStore, context) {
                authSessionNoticeDataStore.pendingNotice.collect { notice ->
                    if (notice == StartupAuthNotice.NUVIO) {
                        Toast.makeText(
                            context,
                            resources.getString(R.string.auth_notice_nuvio_logged_out),
                            Toast.LENGTH_LONG
                        ).show()
                        authSessionNoticeDataStore.consumeNotice(notice)
                    }
                }
            }

            PartyEffects(partyRepository)

            LaunchedEffect(hasSeenAuthQrOnFirstLaunch, authState) {
                if (hasSeenAuthQrOnFirstLaunch == false && authState is AuthState.FullAccount) {
                    appOnboardingDataStore.setHasSeenAuthQrOnFirstLaunch(true)
                    onboardingCompletedThisSession = true
                }
            }

            val activeProfileId by profileManager.activeProfileId.collectAsState()
            val initializationState by startupInitializationCoordinator.state.collectAsState()
            val startupSplashEnabled by profileManager.startupSplashEnabled.collectAsState()
            val startupLoadingState = remember(activeProfileId, startupSession) { StartupLoadingState() }
            val profiles by profileManager.profiles.collectAsState()
            val hasEverSelectedProfile by profileManager.hasEverSelectedProfile.collectAsState()
            val rememberLastProfileEnabled by profileManager.rememberLastProfileEnabled.collectAsState()
            val activeProfile = remember(activeProfileId, profiles) {
                profiles.firstOrNull { it.id == activeProfileId }
            }
            val localProfilePinStates by profileLockStateDataStore.pinEnabled.collectAsState(initial = null)
            val storedActiveProfileId by profileManager.storedActiveProfileId.collectAsState(initial = null)
            val storedProfiles by profileManager.storedProfiles.collectAsState(initial = null)
            var profileEntryReady by remember { mutableStateOf(!BuildConfig.LOCAL_ONLY_BUILD) }
            if (!profileEntryReady && localProfilePinStates != null &&
                storedActiveProfileId == activeProfileId && storedProfiles == profiles
            ) profileEntryReady = true
            var remoteProfilePinStates by remember { mutableStateOf<Map<Int, Boolean>>(emptyMap()) }

            LaunchedEffect(authState, profiles) {
                if (!BuildConfig.LOCAL_ONLY_BUILD && authState is AuthState.FullAccount) {
                    profileSyncService.pullProfileLockStates()
                        .onSuccess { remoteProfilePinStates = it }
                        .onFailure { remoteProfilePinStates = emptyMap() }
                } else {
                    remoteProfilePinStates = emptyMap()
                }
            }

            val profilePinStates = if (BuildConfig.LOCAL_ONLY_BUILD) localProfilePinStates.orEmpty() else remoteProfilePinStates
            val activeProfileHasPin = profilePinStates[activeProfileId] == true
            val hasSelectedProfileThisSession = selectedProfileThisSession == activeProfileId &&
                (!activeProfileHasPin || unlockedProfileThisActivity == activeProfileId)

            var profileSwitchedManually by remember { mutableStateOf(false) }
            val shouldAutoSelectProfile = profileEntryReady && rememberLastProfileEnabled &&
                hasEverSelectedProfile && !activeProfileHasPin &&
                !hasSelectedProfileThisSession && !profileSwitchedManually
            if (shouldAutoSelectProfile && !splashTriggered) {
                splashTriggered = true
                selectedProfileThisSession = activeProfileId
            }
            LaunchedEffect(shouldAutoSelectProfile) {
                if (shouldAutoSelectProfile) {
                    if (authManager.authState.value is AuthState.FullAccount) {
                        startupSyncService.requestSyncNow()
                    }
                    // Preload profile background catalog so the splash can show it
                    activeProfile?.profileBackgroundId?.let {
                        profileBackgroundRepository.loadSelectedAndPreload(it)
                    }
                }
            }

            var avatarCatalog by remember { mutableStateOf(emptyList<com.nuvio.tv.data.remote.supabase.AvatarCatalogItem>()) }
            val avatarMemberAccess by memberAccessRepository.access.collectAsState()
            val hasProfileAvatarAccess = avatarMemberAccess.entitlements
                .includes(CosmeticEntitlement.PROFILE_AVATARS)

            LaunchedEffect(hasProfileAvatarAccess) {
                avatarCatalog = runCatching {
                    avatarRepository.getAvatarCatalog(hasProfileAvatarAccess)
                }
                    .getOrDefault(emptyList())
            }

            val activeProfileAvatarImageUrl = remember(activeProfile, avatarCatalog) {
                activeProfile?.avatarUrl?.takeIf { it.isNotBlank() }
                    ?: activeProfile?.avatarId?.let { avatarRepository.getAvatarImageUrl(it, avatarCatalog) }
            }

            val mainUiPrefsFlow = remember(
                activeProfileId,
                startupSession,
                themeDataStore,
                layoutPreferenceDataStore,
                experienceModeDataStore,
                memberAccessRepository
            ) {
                val activeThemeFlow = combine(
                    themeDataStore.themeSelection,
                    memberAccessRepository.access
                ) { selection, memberAccess ->
                    selection.copy(
                        theme = resolveAppTheme(selection.theme, memberAccess.entitlements),
                        customColors = resolveCustomThemeColors(selection.customColors, memberAccess.tier)
                    ) to memberAccess
                }
                // Group flows into two batches to reduce intermediate flow allocations.
                // Each batch uses a single combine() instead of chaining .combine() calls,
                // which avoids N intermediate flow objects and redundant emissions on startup.
                val themeAndExperienceFlow = combine(
                    activeThemeFlow,
                    themeDataStore.selectedFont,
                    themeDataStore.amoledMode,
                    themeDataStore.amoledSurfacesMode,
                    experienceModeDataStore.mode,
                ) { themeAndAccess, font, amoledMode, amoledSurfacesMode, experienceMode ->
                    MainUiPrefs(
                        theme = themeAndAccess.first.theme ?: AppTheme.WHITE,
                        customThemeColors = themeAndAccess.first.customColors,
                        memberAccess = themeAndAccess.second,
                        font = font,
                        amoledMode = amoledMode,
                        amoledSurfacesMode = amoledSurfacesMode,
                        experienceMode = experienceMode,
                        experienceModeLoaded = true,
                    )
                }
                val layoutAndFeaturesFlow = combine(
                    layoutPreferenceDataStore.hasChosenLayout,
                    layoutPreferenceDataStore.sidebarCollapsedByDefault,
                    layoutPreferenceDataStore.modernSidebarEnabled,
                    layoutPreferenceDataStore.modernSidebarBlurEnabled,
                    layoutPreferenceDataStore.discoverLocation,
                ) { hasChosenLayout, sidebarCollapsed, modernSidebarEnabled, modernSidebarBlurPref, discoverLocation ->
                    MainUiPrefs(
                        hasChosenLayout = hasChosenLayout,
                        sidebarCollapsed = sidebarCollapsed,
                        modernSidebarEnabled = modernSidebarEnabled,
                        modernSidebarBlurPref = modernSidebarBlurPref,
                        discoverLocation = discoverLocation,
                    )
                }
                val extraFeaturesFlow = combine(
                    experienceModeDataStore.addonSetupSkipped,
                    layoutPreferenceDataStore.smoothBringIntoViewEnabled,
                    layoutPreferenceDataStore.fastHorizontalNavigationEnabled,
                    layoutPreferenceDataStore.composeHighlighterEnabled,
                    themeDataStore.settingsUiStyle,
                ) { addonSetupSkipped, smoothBringIntoView, fastHorizontalNav, composeHighlighter, settingsUiStyle ->
                    MainUiPrefs(
                        addonSetupSkipped = addonSetupSkipped,
                        smoothBringIntoViewEnabled = smoothBringIntoView,
                        fastHorizontalNavigationEnabled = fastHorizontalNav,
                        composeHighlighterEnabled = composeHighlighter,
                        settingsUiStyle = settingsUiStyle,
                    )
                }
                val landscapePrefsFlow = combine(
                    layoutPreferenceDataStore.modernLandscapePostersEnabled,
                    layoutPreferenceDataStore.alwaysShowLandscapeClearlogo
                ) { landscape, backdropWithLogo -> landscape to backdropWithLogo }

                combine(
                    themeAndExperienceFlow,
                    layoutAndFeaturesFlow,
                    extraFeaturesFlow,
                    layoutPreferenceDataStore.cardDepthStyle,
                    landscapePrefsFlow
                ) { themePrefs, layoutPrefs, extraPrefs, cardDepthStyle, (landscapePosterMode, alwaysBackdropWithLogo) ->
                    themePrefs.copy(
                        hasChosenLayout = layoutPrefs.hasChosenLayout,
                        sidebarCollapsed = layoutPrefs.sidebarCollapsed,
                        modernSidebarEnabled = layoutPrefs.modernSidebarEnabled,
                        modernSidebarBlurPref = layoutPrefs.modernSidebarBlurPref,
                        discoverLocation = layoutPrefs.discoverLocation,
                        addonSetupSkipped = extraPrefs.addonSetupSkipped,
                        smoothBringIntoViewEnabled = extraPrefs.smoothBringIntoViewEnabled,
                        fastHorizontalNavigationEnabled = extraPrefs.fastHorizontalNavigationEnabled,
                        composeHighlighterEnabled = extraPrefs.composeHighlighterEnabled,
                        settingsUiStyle = extraPrefs.settingsUiStyle,
                        cardDepthStyle = cardDepthStyle,
                        landscapePosterMode = landscapePosterMode,
                        alwaysBackdropWithLogo = alwaysBackdropWithLogo
                    )
                }
            }
            val mainUiPrefs by key(activeProfileId, startupSession) {
                mainUiPrefsFlow.collectAsState(initial = MainUiPrefs(hasChosenLayout = null))
            }
            val installedAddonConfiguration by key(activeProfileId, startupSession) {
                remember(addonPreferences) {
                    addonPreferences.installedAddonConfiguration
                }.collectAsState(initial = null)
            }
            val discoverLocation = mainUiPrefs.discoverLocation

            NuvioTheme(
                appTheme = mainUiPrefs.theme,
                customThemeColors = mainUiPrefs.customThemeColors,
                appFont = mainUiPrefs.font,
                amoledMode = mainUiPrefs.amoledMode,
                amoledSurfacesMode = mainUiPrefs.amoledSurfacesMode,
                settingsUiStyle = mainUiPrefs.settingsUiStyle
            ) {
                val defaultBringIntoViewSpec = LocalBringIntoViewSpec.current
                val bringIntoViewSpec = if (mainUiPrefs.smoothBringIntoViewEnabled) {
                    NuvioScrollDefaults.smoothScrollSpec
                } else {
                    defaultBringIntoViewSpec
                }
                val systemDensity = LocalDensity.current
                val clampedFontScaleDensity = remember(systemDensity) {
                    Density(
                        density = systemDensity.density,
                        fontScale = systemDensity.fontScale.coerceAtMost(MAX_SUPPORTED_FONT_SCALE)
                    )
                }
                val highlighterEnabled = BuildConfig.IS_DEBUG_BUILD && mainUiPrefs.composeHighlighterEnabled
                com.nuvio.tv.ui.util.RecompositionHighlighterFlag.enabled = highlighterEnabled
                val profileBgSelection = resolveProfileBackgroundSelection(
                    profile = activeProfile,
                    entitlements = mainUiPrefs.memberAccess.entitlements
                )
                val profileBgCatalog by profileBackgroundRepository.catalog.collectAsState()
                // Cache splash background in SharedPreferences so the splash
                // can show the correct background on cold start when
                // "remember last profile" skips the profile selection screen.
                val splashPrefs = remember {
                    context.getSharedPreferences("startup_splash", android.content.Context.MODE_PRIVATE)
                }
                val splashPreferencesReady = mainUiPrefs.hasChosenLayout != null && mainUiPrefs.experienceModeLoaded
                val splashBackground = remember(
                    profileBgSelection, profileBgCatalog, activeProfile, activeProfileId,
                    focusedSplashColor, focusedSplashBgUrl, focusedSplashCacheKey,
                    focusedSplashTheme, splashPreferencesReady, mainUiPrefs.theme
                ) {
                    val bgUrl = when (profileBgSelection) {
                        is ProfileBackgroundSelection.Custom -> profileBgSelection.url
                        is ProfileBackgroundSelection.Catalog -> {
                            profileBgCatalog.firstOrNull { it.id == profileBgSelection.id }
                                ?.imageFile?.toURI()?.toString()
                        }
                        else -> null
                    }
                    val colorHex = activeProfile?.avatarColorHex
                    // Only overwrite bg_url when we have a real value or when
                    // we know the profile has no background at all.
                    if (splashPreferencesReady && colorHex != null) {
                        splashPrefs.edit().putString("color_$activeProfileId", colorHex).apply()
                    }
                    if (splashPreferencesReady && bgUrl != null) {
                        splashPrefs.edit()
                            .putString("bg_url_$activeProfileId", bgUrl)
                            .apply()
                    } else if (splashPreferencesReady && profileBgSelection == null && activeProfile != null) {
                        splashPrefs.edit()
                            .remove("bg_url_$activeProfileId")
                            .apply()
                    }
                    val activeTheme = mainUiPrefs.theme
                    if (splashPreferencesReady && activeTheme != AppTheme.WHITE) {
                        splashPrefs.edit().putString("theme_$activeProfileId", activeTheme.name).apply()
                    } else if (splashPreferencesReady) {
                        splashPrefs.edit().remove("theme_$activeProfileId").apply()
                    }
                    // Fall back to cached values only on cold start (no focused override)
                    val hasFocusedOverride = focusedSplashColor != null
                    val fallbackColor = if (colorHex == null && !hasFocusedOverride) splashPrefs.getString("color_$activeProfileId", null) else null
                    val fallbackBg = if (
                        bgUrl == null && !hasFocusedOverride && (!splashPreferencesReady || profileBgSelection != null)
                    ) splashPrefs.getString("bg_url_$activeProfileId", null) else null
                    val resolvedBgUrl = if (hasFocusedOverride) focusedSplashBgUrl else (bgUrl ?: fallbackBg)
                    val skipGradient = !hasFocusedOverride && resolvedBgUrl == null &&
                        profileBgSelection is ProfileBackgroundSelection.Catalog
                    // Resolve branded logo: focused theme (profile screen) > active theme > cached theme > default
                    val resolvedTheme = focusedSplashTheme
                        ?: activeTheme.takeIf { splashPreferencesReady }
                        ?: splashPrefs.getString("theme_$activeProfileId", null)
                            ?.let { name -> AppTheme.entries.firstOrNull { it.name == name } }
                        ?: AppTheme.WHITE
                    SplashBackground(
                        profileColorHex = if (hasFocusedOverride) focusedSplashColor else (colorHex ?: fallbackColor),
                        backgroundUrl = resolvedBgUrl,
                        backgroundCacheKey = if (hasFocusedOverride) focusedSplashCacheKey else when (profileBgSelection) {
                            is ProfileBackgroundSelection.Catalog -> profileBgCatalog
                                .firstOrNull { it.id == profileBgSelection.id }
                                ?.let { "profile-background-${it.id}-v${it.assetVersion}" }
                            is ProfileBackgroundSelection.Custom -> "custom-profile-background-${profileBgSelection.url}"
                            null -> null
                        },
                        skipGradient = skipGradient,
                        brandWordmarkRes = resolvedTheme.brandWordmarkResource
                    )
                }
                CompositionLocalProvider(
                    LocalDensity provides clampedFontScaleDensity,
                    LocalBringIntoViewSpec provides bringIntoViewSpec,
                    LocalFastHorizontalNavigationEnabled provides mainUiPrefs.fastHorizontalNavigationEnabled,
                    LocalRecompositionHighlighterEnabled provides highlighterEnabled,
                    LocalCardDepthStyle provides mainUiPrefs.cardDepthStyle,
                    LocalLandscapePosterMode provides mainUiPrefs.landscapePosterMode,
                    LocalAlwaysBackdropWithLogo provides mainUiPrefs.alwaysBackdropWithLogo,
                    LocalMemberAccess provides mainUiPrefs.memberAccess,
                    LocalAmbientIdleController provides ambientIdleController,
                    com.nuvio.tv.core.player.LocalTrailerPlayerPool provides trailerPlayerPool,
                    LocalSplashBackground provides splashBackground,
                    LocalStartupLoadingState provides startupLoadingState
                ) {
                val transparentPlayerBackdrop = PlayerWindowBackdrop.isTransparentRequested
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RectangleShape,
                    colors = SurfaceDefaults.colors(
                        containerColor = if (transparentPlayerBackdrop) {
                            Color.Transparent
                        } else {
                            NuvioTheme.colors.Background
                        },
                        contentColor = contentColorFor(NuvioTheme.colors.Background)
                    )
                ) {
                    // Wrap everything in a Box. This prevents any black flash between
                    // profile selection and the home content
                    Box(modifier = Modifier.fillMaxSize()) {

                    var startupDestination = StartupDestination.Loading
                    var initializationHomeEntry = false
                    val surfaceContentReady = profileEntryReady && hasSeenAuthQrOnFirstLaunch != null &&
                        authState !is AuthState.Loading

                    if (!surfaceContentReady) {
                        // Still loading auth state; nothing to show yet.
                    } else if (
                        hasSeenAuthQrOnFirstLaunch == false &&
                        authState !is AuthState.FullAccount &&
                        !onboardingCompletedThisSession
                    ) {
                        startupDestination = StartupDestination.Setup
                        AuthQrSignInScreen(
                            onBackPress = { finish() },
                            onContinue = {
                                lifecycleScope.launch {
                                    val shouldRunRemoteOnboardingSync =
                                        authManager.authState.value is AuthState.FullAccount

                                    if (shouldRunRemoteOnboardingSync) {
                                        if (onboardingProfileSyncInProgress) return@launch
                                        onboardingProfileSyncInProgress = true
                                        val maxAttempts = 3
                                        var synced = false
                                        for (attempt in 0 until maxAttempts) {
                                            val result = profileSyncService.pullFromRemote()
                                            if (result.isSuccess) {
                                                synced = true
                                                break
                                            }
                                            if (attempt < maxAttempts - 1) {
                                                delay(1_000)
                                            }
                                        }
                                        if (!synced) {
                                            android.util.Log.w(
                                                "MainActivity",
                                                "Onboarding profile sync failed after retries; continuing"
                                            )
                                        }
                                    }
                                    appOnboardingDataStore.setHasSeenAuthQrOnFirstLaunch(true)
                                    onboardingCompletedThisSession = true
                                    onboardingProfileSyncInProgress = false
                                }
                                if (authManager.authState.value is AuthState.FullAccount) {
                                    startupSyncService.requestSyncNow()
                                }
                            }
                        )
                    } else {

                    val shouldShowProfileSelection =
                        !hasSelectedProfileThisSession && (profiles.size > 1 || activeProfileHasPin)

                    if (shouldShowProfileSelection) {
                        startupDestination = if (splashTriggered) StartupDestination.Loading else StartupDestination.ProfileSelection
                        ProfileSelectionScreen(
                            onProfileClicked = {
                                splashTriggered = true
                            },
                            onProfileSelectionFailed = {
                                splashTriggered = false
                            },
                            onProfileFocusChanged = { colorHex, bgUrl, cacheKey ->
                                if (!splashTriggered) {
                                    focusedSplashColor = colorHex
                                    focusedSplashBgUrl = bgUrl
                                    focusedSplashCacheKey = cacheKey
                                }
                            },
                            onProfileThemeFocused = { theme ->
                                if (!splashTriggered) {
                                    focusedSplashTheme = theme
                                }
                            },
                            onProfileSelected = { profileId, pinVerified ->
                                selectedProfileThisSession = profileId
                                unlockedProfileThisActivity = profileId.takeIf { pinVerified }
                                if (authManager.authState.value is AuthState.FullAccount) {
                                    startupSyncService.requestSyncNow()
                                }
                            }
                        )
                    } else {

                    var essentialSetupShown by remember(activeProfileId, startupSession) { mutableStateOf(false) }
                    val layoutChosen = mainUiPrefs.hasChosenLayout
                    val addonConfigurationReady = com.nuvio.tv.core.setup.firstWatchAddonConfigurationReady(activeProfile, installedAddonConfiguration)
                    val prefsLoading = layoutChosen == null || !mainUiPrefs.experienceModeLoaded || !addonConfigurationReady
                    if (!prefsLoading) {
                    val effectiveExperienceMode = mainUiPrefs.experienceMode
                        ?: if (layoutChosen) ExperienceMode.ADVANCED else null
                    val needsExperienceSelection = effectiveExperienceMode == null
                    val needsEssentialAddonSetup =
                        com.nuvio.tv.core.setup.shouldShowFirstWatchSetup(
                            isEssential = effectiveExperienceMode == ExperienceMode.ESSENTIAL,
                            hasAddons = installedAddonConfiguration?.hasAddons == true,
                            skipped = mainUiPrefs.addonSetupSkipped,
                            alreadyShown = essentialSetupShown,
                            configurationLoaded = addonConfigurationReady
                        )
                    LaunchedEffect(needsEssentialAddonSetup) {
                        if (needsEssentialAddonSetup) essentialSetupShown = true
                    }
                    val pendingDeepLink by pendingDeepLinkUrl.collectAsState()
                    val addonLinkProfileRevision = profileManager.activeProfileSelectionRevision
                    var addonLinkConfirmation by remember(activeProfileId, addonLinkProfileRevision, startupSession) {
                        mutableStateOf<AddonLinkInstallRequest?>(null)
                    }
                    var confirmedAddonLink by remember(activeProfileId, addonLinkProfileRevision, startupSession) {
                        mutableStateOf<AddonLinkInstallRequest?>(null)
                    }

                    LaunchedEffect(pendingDeepLink) {
                        val url = pendingDeepLink ?: return@LaunchedEffect
                        val link = DeepLinkParser.parse(url) as? AppDeepLink.AddonInstall ?: return@LaunchedEffect
                        if (!pendingDeepLinkUrl.compareAndSet(url, null) || addonLinkConfirmation != null || confirmedAddonLink != null) {
                            return@LaunchedEffect
                        }
                        addonLinkConfirmation = AddonLinkInstallRequest.create(link.manifestUrl, activeProfileId, addonLinkProfileRevision)
                        if (addonLinkConfirmation == null) {
                            Toast.makeText(context, R.string.provider_install_invalid_url, Toast.LENGTH_LONG).show()
                        }
                    }
                    LaunchedEffect(confirmedAddonLink) {
                        val request = confirmedAddonLink ?: return@LaunchedEffect
                        try {
                            Toast.makeText(context, resources.getString(R.string.addon_installing), Toast.LENGTH_SHORT).show()
                            deepLinkHandler.installAddon(request)?.let { result ->
                                Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                            }
                        } finally {
                            if (confirmedAddonLink === request) confirmedAddonLink = null
                        }
                    }
                    addonLinkConfirmation?.let { request ->
                        AddonDeepLinkConfirmationDialog(
                            host = request.host,
                            onCancel = { if (addonLinkConfirmation === request) addonLinkConfirmation = null },
                            onAdd = {
                                if (addonLinkConfirmation === request && confirmedAddonLink == null) {
                                    addonLinkConfirmation = null
                                    confirmedAddonLink = request
                                }
                            }
                        )
                    }

                    if (needsEssentialAddonSetup) {
                        startupDestination = StartupDestination.Setup
                        EssentialAddonSetupScreen(
                            onSkip = {
                                lifecycleScope.launch {
                                    experienceModeDataStore.setAddonSetupSkipped(true)
                                }
                            }
                        )
                    } else {
                    val sidebarCollapsed = mainUiPrefs.sidebarCollapsed
                    val modernSidebarEnabled = mainUiPrefs.modernSidebarEnabled
                    val modernSidebarBlurEnabled =
                        mainUiPrefs.modernSidebarBlurPref && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
                    val hideBuiltInHeadersForFloatingPill = modernSidebarEnabled && !sidebarCollapsed

                    val startDestination = when {
                        needsExperienceSelection -> Screen.ExperienceModeSelection.route
                        layoutChosen -> Screen.Home.route
                        else -> Screen.LayoutSelection.route
                    }
                    val navController = key("profile-navigation", activeProfileId) { rememberNavController() }
                    val navViewModelStoreOwner = rememberNavSessionViewModelStoreOwner(navController)
                    var optimisticRoute by remember { mutableStateOf<String?>(null) }
                    val navBackStackEntry by navController.currentBackStackEntryAsState()
                    val actualRoute = navBackStackEntry?.destination?.route
                    val currentRoute = optimisticRoute ?: actualRoute
                    startupDestination = startupDestinationForRoute(actualRoute ?: startDestination)
                    initializationHomeEntry = (actualRoute ?: startDestination) == Screen.Home.route

                    LaunchedEffect(actualRoute) {
                        optimisticRoute = null
                        ambientIdleController.onInteraction()
                    }

                    // Auto-play next episode for EXTERNAL players: the tracker resolves the
                    // next episode and we navigate into the same Screen.Stream auto-play route
                    // the internal onPlaybackEnded path uses. Collected from the root composable
                    // so it survives StreamScreen's self-pop and a process kill (metadata is
                    // recovered from disk and the event replayed).
                    LaunchedEffect(navController) {
                        externalPlaybackTracker.autoPlayNext.collect { next ->
                            if (!externalPlaybackTracker.claimAutoPlayNextNavigation(next, activeProfileId)) {
                                return@collect
                            }
                            Log.d(
                                "MainActivity",
                                "autoPlayNext received: S${next.nextSeason}E${next.nextEpisode} " +
                                    "videoId=${next.nextVideoId}; navigating to Stream"
                            )
                            navController.navigate(
                                Screen.Stream.createRoute(
                                    videoId = next.nextVideoId,
                                    contentType = next.contentType,
                                    title = next.contentName,
                                    poster = next.poster,
                                    backdrop = next.backdrop,
                                    logo = next.logo,
                                    season = next.nextSeason,
                                    episode = next.nextEpisode,
                                    year = next.year,
                                    contentId = next.contentId,
                                    contentName = next.contentName,
                                    returnToDetailOnBack = next.contentType.equals("series", ignoreCase = true),
                                    profileId = next.profileId
                                )
                            ) {
                                // Replace any lingering Stream screen (e.g. the previous
                                // episode's, restored from the backstack after a process restart).
                                popUpTo(Screen.Stream.route) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    }

                    val pendingLaunch by pendingLaunchIntent.collectAsState()
                    LaunchedEffect(navController, layoutChosen, pendingLaunch) {
                        val intent = pendingLaunch ?: return@LaunchedEffect
                        if (!layoutChosen) return@LaunchedEffect
                        if (!pendingLaunchIntent.compareAndSet(intent, null)) return@LaunchedEffect
                        val contentId = intent.getStringExtra("contentId") ?: return@LaunchedEffect
                        val contentType = intent.getStringExtra("contentType") ?: return@LaunchedEffect
                        if (tvCardLaunches.isCurrentCard(intent)) {
                            val videoId = intent.getStringExtra("videoId") ?: return@LaunchedEffect
                            val name = intent.getStringExtra("name") ?: return@LaunchedEffect
                            navController.navigate(
                                Screen.Stream.createRoute(
                                    videoId = videoId,
                                    contentType = contentType,
                                    title = name,
                                    poster = intent.getStringExtra("poster"),
                                    backdrop = intent.getStringExtra("backdrop"),
                                    logo = intent.getStringExtra("logo"),
                                    season = intent.getIntExtra("season", -1).takeIf { it >= 0 },
                                    episode = intent.getIntExtra("episode", -1).takeIf { it >= 0 },
                                    episodeName = intent.getStringExtra("episodeTitle"),
                                    contentId = contentId,
                                    contentName = name,
                                    returnToDetailOnBack = contentType.equals("series", ignoreCase = true),
                                    returnToHomeOnBack = true
                                )
                            )
                        } else {
                            navController.navigate(
                                Screen.Detail.createRoute(
                                    itemId = contentId,
                                    itemType = contentType
                                )
                            )
                        }
                    }

                    LaunchedEffect(navController, layoutChosen, pendingDeepLink) {
                        val url = pendingDeepLink ?: return@LaunchedEffect
                        if (!layoutChosen) return@LaunchedEffect
                        when (val deepLink = DeepLinkParser.parse(url)) {
                            is AppDeepLink.Meta -> {
                                if (!pendingDeepLinkUrl.compareAndSet(url, null)) return@LaunchedEffect
                                navController.navigate(
                                    Screen.Detail.createRoute(
                                        itemId = deepLink.id,
                                        itemType = deepLink.type
                                    )
                                ) {
                                    // A new title needs a new SavedStateHandle and detail ViewModel.
                                    launchSingleTop = false
                                }
                            }
                            is AppDeepLink.Party -> {
                                if (!pendingDeepLinkUrl.compareAndSet(url, null)) return@LaunchedEffect
                                navController.navigate(Screen.WatchParty.createRoute(deepLink.code)) {
                                    launchSingleTop = true
                                }
                            }
                            is AppDeepLink.AddonInstall -> Unit // Handled by the shared consent prompt above.
                            null -> pendingDeepLinkUrl.compareAndSet(url, null)
                        }
                    }

                    val view = LocalView.current
                    LaunchedEffect(currentRoute) {
                        val holder = PerformanceMetricsState.getHolderForHierarchy(view)
                        if (currentRoute != null) {
                            holder.state?.putState("Screen", currentRoute)
                        }
                    }

                    LaunchedEffect(discoverLocation, currentRoute) {
                        if (discoverLocation == null) return@LaunchedEffect
                        val onDiscoverRoute = currentRoute == Screen.Discover.route ||
                            currentRoute?.startsWith("${Screen.Discover.route}/") == true
                        if (discoverLocation == DiscoverLocation.OFF && onDiscoverRoute) {
                            navController.navigate(Screen.Home.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = false }
                                launchSingleTop = true
                            }
                        }
                    }

                    val rootRoutes = remember(discoverLocation) {
                        buildSet {
                            add(Screen.Home.route)
                            add(Screen.Search.route)
                            add(Screen.Library.route)
                            add(Screen.Settings.route)
                            if (discoverLocation == DiscoverLocation.IN_SIDEBAR) {
                                add(Screen.Discover.route)
                            }
                        }
                    }

                    val strNavHome = stringResource(R.string.nav_home)
                    val strNavDiscover = stringResource(R.string.nav_discover)
                    val strNavSearch = stringResource(R.string.nav_search)
                    val strNavLibrary = stringResource(R.string.nav_library)
                    val strNavSettings = stringResource(R.string.nav_settings)
                    val drawerItems = remember(
                        strNavHome,
                        strNavDiscover,
                        strNavSearch,
                        strNavLibrary,
                        strNavSettings,
                        discoverLocation
                    ) {
                        buildList {
                            add(
                                DrawerItem(
                                    route = Screen.Home.route,
                                    label = strNavHome,
                                    icon = Icons.Default.Home
                                )
                            )
                            if (discoverLocation == DiscoverLocation.IN_SIDEBAR) {
                                add(
                                    DrawerItem(
                                        route = Screen.Discover.route,
                                        label = strNavDiscover,
                                        icon = Icons.Default.Explore
                                    )
                                )
                            }
                            add(
                                DrawerItem(
                                    route = Screen.Search.route,
                                    label = strNavSearch,
                                    iconRes = R.raw.sidebar_search
                                )
                            )
                            add(
                                DrawerItem(
                                    route = Screen.Library.route,
                                    label = strNavLibrary,
                                    iconRes = R.raw.sidebar_library
                                )
                            )
                            add(
                                DrawerItem(
                                    route = Screen.Settings.route,
                                    label = strNavSettings,
                                    iconRes = R.raw.sidebar_settings
                                )
                            )
                        }
                    }
                    val selectedDrawerRoute = drawerItems.firstOrNull { item ->
                        currentRoute == item.route || currentRoute?.startsWith("${item.route}/") == true
                    }?.route
                    val selectedDrawerItem = drawerItems.firstOrNull { it.route == selectedDrawerRoute } ?: drawerItems.first()

                    val confirmExitEnabled by profileManager.confirmExitEnabled.collectAsState()
                    var backPressedOnce by remember { mutableStateOf(false) }
                    LaunchedEffect(backPressedOnce) {
                        if (backPressedOnce) {
                            delay(2000L)
                            backPressedOnce = false
                        }
                    }
                    val handleExitApp: () -> Unit = {
                        if (!confirmExitEnabled || backPressedOnce) {
                            finishAffinity()
                            finishAndRemoveTask()
                            if (confirmExitEnabled) {
                                // Kill the process to free RAM on low-memory devices.
                                android.os.Process.killProcess(android.os.Process.myPid())
                            }
                        } else {
                            backPressedOnce = true
                            Toast.makeText(this@MainActivity, getString(R.string.confirm_exit_toast), Toast.LENGTH_SHORT).show()
                        }
                    }

                    val installedNotesState by rememberInstalledUpdateNotes(appOnboardingDataStore)
                    var installedNotesDismissed by remember { mutableStateOf(false) }
                    val updateViewModel: UpdateViewModel = hiltViewModel(this@MainActivity)
                    val updateState by updateViewModel.uiState.collectAsState()
                    val initializationBlocksContent = startupInitializationBlocksHome(initializationHomeEntry,
                        activeProfileId, profileManager.activeProfileSelectionRevision, initializationState)
                    val automaticUpdateCheckReady = installedNotesState.isLoaded &&
                        !initializationBlocksContent &&
                        (startupDestination == StartupDestination.Home || startupDestination == StartupDestination.Content) &&
                        currentRoute?.startsWith("player/") != true
                    val showUpdatePrompt = installedNotesState.isLoaded && !initializationBlocksContent && startupLoadingState.complete &&
                        (currentRoute == Screen.Home.route || (updateState.manualPrompt && currentRoute == Screen.Settings.route))
                    val showInstalledNotes = InstalledUpdateNotesPolicy.canPresent(
                        homeReady = !installedNotesDismissed && !initializationBlocksContent &&
                            startupLoadingState.complete && startupDestination == StartupDestination.Home &&
                            currentRoute == Screen.Home.route,
                        hasPendingDeepLink = pendingDeepLink != null,
                        hasPendingLaunch = pendingLaunch != null,
                        hasAddonConfirmation = addonLinkConfirmation != null,
                        isInstallingAddon = confirmedAddonLink != null
                    )
                    StartupInitializationContent(blocked = initializationBlocksContent) {
                    UpdateBannerHost(
                        state = updateState.copy(showBanner = updateState.showBanner && showUpdatePrompt),
                        onDismissBanner = updateViewModel::dismissBanner,
                        onDownload = {
                            val updateRoute = navController.currentDestination?.route
                            updateViewModel.downloadUpdate {
                                this@MainActivity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                                    navController.currentDestination?.route == updateRoute
                            }
                        },
                        onDismissUnknownSources = updateViewModel::dismissUnknownSourcesDialog,
                        onOpenUnknownSources = updateViewModel::openUnknownSourcesSettings,
                        onFeedbackShown = updateViewModel::consumeFeedbackMessage,
                        automaticCheckReady = automaticUpdateCheckReady,
                        onAutomaticCheck = updateViewModel::checkForUpdatesAutomatically,
                        installedNotes = installedNotesState.notes.takeIf { showInstalledNotes },
                        onDismissInstalledNotes = {
                            installedNotesDismissed = true
                            lifecycleScope.launch {
                                try { appOnboardingDataStore.acknowledgeUpdateVersionCode(BuildConfig.VERSION_CODE) }
                                catch (_: java.io.IOException) { /* Keep this session usable if saving fails. */ }
                            }
                        }
                    ) {
                        val handleSwitchProfile = {
                            startupSession++
                            splashTriggered = false
                            profileSwitchedManually = true
                            focusedSplashColor = null
                            focusedSplashBgUrl = null
                            focusedSplashCacheKey = null
                            focusedSplashTheme = null
                            selectedProfileThisSession = null
                            unlockedProfileThisActivity = null
                        }
                        Box(modifier = Modifier.fillMaxSize()) {
                            CompositionLocalProvider(LocalNavViewModelStoreOwner provides navViewModelStoreOwner) {
                                if (modernSidebarEnabled) {
                                    ModernSidebarScaffold(
                                        longPressBackHeld = longPressBackHeld,
                                        navController = navController,
                                        startDestination = startDestination,
                                        currentRoute = currentRoute,
                                        rootRoutes = rootRoutes,
                                        drawerItems = drawerItems,
                                        selectedDrawerRoute = selectedDrawerRoute,
                                        selectedDrawerItem = selectedDrawerItem,
                                        sidebarCollapsed = sidebarCollapsed,
                                        modernSidebarBlurEnabled = modernSidebarBlurEnabled,
                                        hideBuiltInHeaders = hideBuiltInHeadersForFloatingPill,
                                        activeProfileName = activeProfile?.name ?: "",
                                        activeProfileColorHex = activeProfile?.avatarColorHex ?: "#1E88E5",
                                        activeProfileAvatarImageUrl = activeProfileAvatarImageUrl,
                                        showProfileSelector = profiles.size > 1,
                                        onSwitchProfile = handleSwitchProfile,
                                        onNavigate = { optimisticRoute = it },
                                        onExitApp = handleExitApp
                                    )
                                } else {
                                    LegacySidebarScaffold(
                                        longPressBackHeld = longPressBackHeld,
                                        navController = navController,
                                        startDestination = startDestination,
                                        currentRoute = currentRoute,
                                        rootRoutes = rootRoutes,
                                        drawerItems = drawerItems,
                                        selectedDrawerRoute = selectedDrawerRoute,
                                        sidebarCollapsed = sidebarCollapsed,
                                        hideBuiltInHeaders = false,
                                        activeProfileName = activeProfile?.name ?: "",
                                        activeProfileColorHex = activeProfile?.avatarColorHex ?: "#1E88E5",
                                        activeProfileAvatarImageUrl = activeProfileAvatarImageUrl,
                                        showProfileSelector = profiles.size > 1,
                                        onSwitchProfile = handleSwitchProfile,
                                        onNavigate = { optimisticRoute = it },
                                        onExitApp = handleExitApp
                                    )
                                }
                            }

                            val trackedAutoNextOverlay by externalPlaybackTracker.autoNextOverlay.collectAsState()
                            val autoNextOverlay = trackedAutoNextOverlay?.takeIf { it.profileId == activeProfileId }
                            SideEffect { visibleAutoNextOverlayProfileId = autoNextOverlay?.profileId }
                            DisposableEffect(Unit) {
                                onDispose { visibleAutoNextOverlayProfileId = null }
                            }
                            autoNextOverlay?.let { ov ->
                                com.nuvio.tv.ui.screens.player.LoadingOverlay(
                                    visible = true,
                                    backdropUrl = ov.backdrop,
                                    logoUrl = ov.logo,
                                    title = ov.title,
                                    message = ov.message ?: stringResource(R.string.external_auto_next_loading),
                                    progress = ov.progress,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }

                            val isTrailerPlaying by trailerPlayerPool.isTrailerPlaying.collectAsState()
                            LaunchedEffect(isTrailerPlaying) {
                                if (!isTrailerPlaying) ambientIdleController.onInteraction()
                            }
                            AmbientModeHost(
                                controller = ambientIdleController,
                                allowed = !initializationBlocksContent && currentRoute?.startsWith("player/") != true &&
                                    autoNextOverlay == null &&
                                    !isTrailerPlaying,
                                onShown = { trailerPlayerPool.stop() }
                            )
                        }
                    }
                    }
                } 
            } 
        } 
    }

                    // This overlay does not intercept callbacks, profile selection, setup or content intents.
                    // Navigation/deep-link effects above remain mounted while Home is covered.
                    val initializationSelection = profileManager.activeProfileSelectionRevision
                    LaunchedEffect(initializationHomeEntry, activeProfileId, initializationSelection) {
                        if (initializationHomeEntry) startupInitializationCoordinator.onHomeEntry(activeProfileId, initializationSelection)
                        else startupInitializationCoordinator.onLeaveHome()
                    }
                    val blockHomeForInitialization = startupInitializationBlocksHome(initializationHomeEntry,
                        activeProfileId, initializationSelection, initializationState)
                    SideEffect { startupInitializationGateVisible = blockHomeForInitialization }
                    DisposableEffect(Unit) {
                        onDispose { startupInitializationGateVisible = false }
                    }

                    LaunchedEffect(startupDestination, startupLoadingState) {
                        if (startupDestination == StartupDestination.Content) {
                            startupLoadingState.complete = true
                        }
                    }
                    val showSplash = shouldShowStartupSplash(
                        enabled = startupSplashEnabled,
                        complete = startupLoadingState.complete,
                        destination = startupDestination
                    )
                    LaunchedEffect(showSplash, startupDestination) {
                        if (!showSplash && startupDestination != StartupDestination.ProfileSelection) {
                            focusedSplashColor = null
                            focusedSplashBgUrl = null
                            focusedSplashCacheKey = null
                            focusedSplashTheme = null
                        }
                    }
                    if (showSplash) {
                        com.nuvio.tv.ui.components.StartupSplashScreen(
                            profileColorHex = splashBackground.profileColorHex,
                            profileBackgroundUrl = splashBackground.backgroundUrl,
                            statusText = if (surfaceContentReady) R.string.startup_loading_profile else R.string.startup_opening,
                            backgroundCacheKey = splashBackground.backgroundCacheKey,
                            skipGradient = splashBackground.skipGradient,
                            brandWordmarkRes = splashBackground.brandWordmarkRes
                        )
                    }
                    if (blockHomeForInitialization) {
                        val visibleInitialization = if (initializationState.profileId == activeProfileId &&
                            initializationState.selectionRevision == initializationSelection) initializationState
                            else com.nuvio.tv.core.initialization.StartupInitializationState(activeProfileId, initializationSelection)
                        StartupInitializationScreen(visibleInitialization,
                            onRetry = startupInitializationCoordinator::retry,
                            onContinue = startupInitializationCoordinator::continueWithSavedData)
                    }
                    }
                }
                }
            }
        }

        jankStats = JankStats.createAndTrack(window) { frameData ->
            if (frameData.isJank) {
                Log.w(
                    "JankStats",
                    "JANK: ${frameData.frameDurationUiNanos / 1_000_000}ms | states: ${frameData.states}"
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ambientIdleController.dismiss()
        if (::jankStats.isInitialized) jankStats.isTrackingEnabled = true
        memberAccessRepository.refreshIfStale()
        lifecycleScope.launch {
            deviceSessionRegistration.requestForegroundRegistration()
            startupSyncService.requestForegroundSync()
        }
        lifecycleScope.launch {
            val refreshIntent = if (isFirstResumeAfterCreate) {
                isFirstResumeAfterCreate = false
                TrackingRefreshIntent.INVALIDATED
            } else {
                TrackingRefreshIntent.AUTOMATIC
            }
            if (!startupInitializationCoordinator.shouldDeferForegroundRefresh) {
                trackingProgressRefreshCoordinator.refreshConnected(refreshIntent)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureDeepLinkIntent(intent)
        captureLaunchIntent(intent)
    }

    private fun captureDeepLinkIntent(intent: Intent?) {
        val url = intent?.dataString?.trim()?.takeIf(String::isNotBlank) ?: return
        pendingDeepLinkUrl.value = url
    }

    private fun captureLaunchIntent(intent: Intent?) {
        val launch = runCatching {
            if (intent?.action != Intent.ACTION_VIEW) return
            val contentId = intent.getStringExtra("contentId") ?: return
            val type = intent.getStringExtra("contentType") ?: return
            if (contentId.length !in 1..1024 || contentId.any(Char::isISOControl) || type !in setOf("movie", "series")) return
            Intent(intent)
        }.getOrNull() ?: return
        pendingLaunchIntent.value = launch
    }

    override fun onPause() {
        super.onPause()
        if (::jankStats.isInitialized) jankStats.isTrackingEnabled = false
    }

    // Intercept Back at the Activity level, before any Compose BackHandler, so the auto-next loader
    // can always be dismissed. Compose back-dispatch ordering kept putting the destination screen's
    // handler above the loader's, so Back never reached it.
    // Tracks whether a long-press Back sequence is in progress. When true, all Back
    // key events are consumed at the Activity level so that repeated DOWN events from a
    // held Back key don't cascade through Compose BackHandlers (e.g. opening the sidebar
    // and then immediately exiting the app).
    val longPressBackHeld = mutableStateOf(false)

    private val ambientIdleController = AmbientIdleController()

    override fun onUserInteraction() {
        super.onUserInteraction()
        ambientIdleController.onInteraction()
    }

    // Physical TV BACK repeat/up events are needed for loader priority and long-press gating.
    @android.annotation.SuppressLint("GestureBackNavigation", "RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // The same-window startup gate has the input priority its previous Dialog had.
        if (startupInitializationGateVisible) return super.dispatchKeyEvent(event)
        if (ambientIdleController.consumeKeyEvent(event)) return true
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (longPressBackHeld.value) {
                if (event.action == KeyEvent.ACTION_UP) longPressBackHeld.value = false
                return true
            }
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK &&
            visibleAutoNextOverlayProfileId == profileManager.activeProfileId.value &&
            externalPlaybackTracker.autoNextOverlay.value?.profileId == visibleAutoNextOverlayProfileId
        ) {
            if (event.action == KeyEvent.ACTION_UP) {
                Log.d("ExtAutoNext", "dispatchKeyEvent BACK -> dismissAutoNextOverlay (loader showing)")
                externalPlaybackTracker.dismissAutoNextOverlay()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStart() {
        // Returning from an external player: raise the auto-next loader before the player's
        // result is dispatched and before the window repaints, so the transition shows the
        // loader instantly with no episode-list flash. No-op unless a series episode is being
        // tracked; onActivityResult keeps it for a completion or dismisses it otherwise.
        externalPlaybackTracker.raiseAutoNextOverlayOnReturn()
        super.onStart()
        externalPlaybackTracker.onActivityResultRegistryStarted(activityResultRegistry)
        startupSyncService.startPeriodicSurfacePulls()
        androidTvChannelSyncService.onForegroundChanged(true)
    }

    override fun onStop() {
        externalPlaybackTracker.onActivityResultRegistryStopped(activityResultRegistry)
        externalPlaybackTracker.onExternalPlayerCoveredApp()
        super.onStop()
        startupSyncService.stopPeriodicSurfacePulls()
        // App going to background (e.g. user returning to the launcher): reconcile the
        // Continue Watching channel once so Projectivy repaints it with fresh progress.
        androidTvChannelSyncService.onForegroundChanged(false)
    }

    override fun onDestroy() {
        externalPlaybackTracker.unbindActivityResultRegistry(activityResultRegistry)
        super.onDestroy()
        PluginRuntimeHooks.onActivityDestroy()
    }
}

@Composable
private fun SidebarFocusRecoveryEffect(
    drawerItems: List<DrawerItem>,
    selectedDrawerRoute: String?,
    drawerItemFocusRequesters: Map<String, FocusRequester>,
    sidebarOwnsFocus: Boolean
) {
    LaunchedEffect(drawerItems, sidebarOwnsFocus, selectedDrawerRoute) {
        if (!sidebarOwnsFocus) {
            return@LaunchedEffect
        }
        if (selectedDrawerRoute != null && drawerItems.any { it.route == selectedDrawerRoute }) {
            return@LaunchedEffect
        }
        val fallbackRoute = drawerItems.firstOrNull()?.route ?: return@LaunchedEffect
        val requester = drawerItemFocusRequesters[fallbackRoute] ?: return@LaunchedEffect
        repeat(2) { withFrameNanos { } }
        runCatching { requester.requestFocus() }
    }
}

@Composable
internal fun rememberSidebarExpandProgress(expanded: Boolean) = animateFloatAsState(
    targetValue = if (expanded) 1f else 0f,
    animationSpec = tween(durationMillis = if (expanded) 240 else 200, easing = FastOutSlowInEasing),
    label = "sidebarExpandProgress"
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LegacySidebarScaffold(
    longPressBackHeld: MutableState<Boolean>,
    navController: NavHostController,
    startDestination: String,
    currentRoute: String?,
    rootRoutes: Set<String>,
    drawerItems: List<DrawerItem>,
    selectedDrawerRoute: String?,
    sidebarCollapsed: Boolean,
    hideBuiltInHeaders: Boolean,
    activeProfileName: String,
    activeProfileColorHex: String,
    activeProfileAvatarImageUrl: String?,
    showProfileSelector: Boolean,
    onSwitchProfile: () -> Unit,
    onNavigate: (String) -> Unit,
    onExitApp: () -> Unit
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val drawerItemFocusRequesters = rememberDrawerItemFocusRequesters(drawerItems)
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val showSidebar = currentRoute in rootRoutes

    LaunchedEffect(currentRoute) {
        longPressBackHeld.value = false
        drawerState.setValue(DrawerValue.Closed)
    }

    val sidebarTokens = NuvioComponents.tokens.sidebar
    val closedDrawerWidth = if (sidebarCollapsed) NuvioTheme.spacing.none else sidebarTokens.legacyCollapsedWidth
    val openDrawerWidth = sidebarTokens.legacyExpandedWidth
    val openDrawerItemWidth = sidebarTokens.itemWidth
    val sidebarExpandProgress by rememberSidebarExpandProgress(drawerState.currentValue == DrawerValue.Open)

    val focusManager = LocalFocusManager.current
    val isRtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    val contentFocusRequester = remember { FocusRequester() }
    var pendingContentFocusTransfer by remember { mutableStateOf(false) }
    var pendingSidebarFocusRequest by remember { mutableStateOf(false) }
    // Bumped on every key event the drawer sees so the auto-collapse timer
    // resets while the user navigates between drawer items.
    var legacyDrawerInteractionVersion by remember { mutableStateOf(0) }

    val openSidebar: () -> Unit = remember(showSidebar, drawerState) {
        {
            if (showSidebar) {
                pendingContentFocusTransfer = false
                pendingSidebarFocusRequest = true
                drawerState.setValue(DrawerValue.Open)
            }
        }
    }


    BackHandler(enabled = currentRoute in rootRoutes && drawerState.currentValue == DrawerValue.Closed) {
        pendingSidebarFocusRequest = true
        drawerState.setValue(DrawerValue.Open)
    }

    BackHandler(enabled = currentRoute in rootRoutes && drawerState.currentValue == DrawerValue.Open) {
        if (longPressBackHeld.value) return@BackHandler
        onExitApp()
    }

    LaunchedEffect(drawerState.currentValue, pendingContentFocusTransfer) {
        if (!pendingContentFocusTransfer || drawerState.currentValue != DrawerValue.Closed) {
            return@LaunchedEffect
        }
        repeat(2) { withFrameNanos { } }
        runCatching { contentFocusRequester.requestFocus() }
        pendingContentFocusTransfer = false
    }

    LaunchedEffect(drawerState.currentValue, selectedDrawerRoute, showSidebar, pendingSidebarFocusRequest) {
        if (!showSidebar || !pendingSidebarFocusRequest || drawerState.currentValue != DrawerValue.Open) {
            return@LaunchedEffect
        }
        val targetRoute = selectedDrawerRoute ?: drawerItems.firstOrNull()?.route ?: run {
            pendingSidebarFocusRequest = false
            return@LaunchedEffect
        }
        val requester = drawerItemFocusRequesters[targetRoute] ?: run {
            pendingSidebarFocusRequest = false
            return@LaunchedEffect
        }
        repeat(2) { withFrameNanos { } }
        runCatching { requester.requestFocus() }
        pendingSidebarFocusRequest = false
    }

    SidebarFocusRecoveryEffect(
        drawerItems = drawerItems,
        selectedDrawerRoute = selectedDrawerRoute,
        drawerItemFocusRequesters = drawerItemFocusRequesters,
        sidebarOwnsFocus = showSidebar && drawerState.currentValue == DrawerValue.Open
    )

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = { drawerValue ->
            if (showSidebar) {
                val drawerWidth = androidx.compose.ui.unit.lerp(closedDrawerWidth, openDrawerWidth, sidebarExpandProgress)
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(drawerWidth)
                        .clip(RectangleShape)
                        .background(NuvioTheme.colors.Background)
                        .padding(NuvioTheme.spacing.card.outer)
                        .selectableGroup()
                        .onPreviewKeyEvent { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyDown) {
                                legacyDrawerInteractionVersion++
                            }
                            val closeKey = if (isRtl) Key.DirectionLeft else Key.DirectionRight
                            if (keyEvent.key == closeKey && keyEvent.type == KeyEventType.KeyDown) {
                                drawerState.setValue(DrawerValue.Closed)
                                pendingContentFocusTransfer = false
                                true
                            } else {
                                false
                            }
                        }
                ) {
                    val isExpanded = drawerValue == DrawerValue.Open
                    val itemWidth = androidx.compose.ui.unit.lerp(
                        NuvioTheme.sizes.avatars.md, openDrawerItemWidth, sidebarExpandProgress
                    )

                    if (isExpanded || sidebarExpandProgress > 0f) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .wrapContentWidth(Alignment.Start, unbounded = true)
                                .requiredWidth(openDrawerWidth - NuvioTheme.spacing.card.outer * 2)
                                .graphicsLayer {
                                    alpha = sidebarExpandProgress
                                    translationX = (if (isRtl) 1f else -1f) * 16.dp.toPx() * (1f - sidebarExpandProgress)
                                }
                        ) {
                            Spacer(modifier = Modifier.height(30.dp))
                            if (showProfileSelector && activeProfileName.isNotEmpty()) {
                                var isProfileFocused by remember { mutableStateOf(false) }
                                val profileItemShape = NuvioTheme.shapes.navItem
                                val profileLeadingInset = NuvioTheme.spacing.lg + NuvioTheme.spacing.xxs
                                val profileAvatarSize = NuvioTheme.sizes.sidebar.leadingVisual
                                val profileLabelStart = 60.dp
                                val profileGapAfterAvatar =
                                    (profileLabelStart - profileLeadingInset - profileAvatarSize).coerceAtLeast(NuvioTheme.spacing.none)
                                val profileBgColor by animateColorAsState(
                                    targetValue = if (isProfileFocused) NuvioTheme.colors.FocusBackground else Color.Transparent,
                                    animationSpec = tween(140),
                                    label = "legacySidebarProfileBackground"
                                )
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .width(openDrawerItemWidth)
                                            .height(sidebarTokens.itemHeight)
                                            .background(color = profileBgColor, shape = profileItemShape)
                                            .then(if (isProfileFocused) Modifier.border(sidebarFocusStroke(), profileItemShape) else Modifier)
                                            .onFocusChanged { isProfileFocused = it.isFocused }
                                            .clickable(enabled = isExpanded) {
                                                onSwitchProfile()
                                                drawerState.setValue(DrawerValue.Closed)
                                            },
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Spacer(modifier = Modifier.width(profileLeadingInset))
                                        ProfileAvatarCircle(
                                            name = activeProfileName,
                                            colorHex = activeProfileColorHex,
                                            size = profileAvatarSize,
                                            avatarImageUrl = activeProfileAvatarImageUrl,
                                            imageCrossfade = false
                                        )
                                        Spacer(modifier = Modifier.width(profileGapAfterAvatar))
                                        Text(
                                            text = activeProfileName,
                                            color = if (isProfileFocused) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = TextAlign.Start,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            } else {
                                BrandWordmark(
                                    contentDescription = stringResource(R.string.app_name),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(42.dp)
                                )
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .offset(y = 28.dp)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        drawerItems.forEach { item ->
                            key(item.route) {
                                LegacySidebarButton(
                                    label = item.label,
                                    iconRes = item.iconRes,
                                    icon = item.icon,
                                    selected = selectedDrawerRoute == item.route,
                                    expanded = isExpanded,
                                    expandProgress = sidebarExpandProgress,
                                    onClick = {
                                        keyboardController?.hide()
                                        onNavigate(item.route)
                                        navigateToDrawerRoute(
                                            navController = navController,
                                            currentRoute = currentRoute,
                                            targetRoute = item.route
                                        )
                                        drawerState.setValue(DrawerValue.Closed)
                                        pendingContentFocusTransfer = currentRoute == item.route
                                    },
                                    modifier = Modifier.focusRequester(
                                        drawerItemFocusRequesters.getValue(item.route)
                                    )
                                        .width(itemWidth)
                                        .offset(x = NuvioTheme.spacing.md)
                                )
                        }
                    }
                }
            }
        }
        }
    ) {
        val contentStartPadding by animateDpAsState(
            targetValue = if (showSidebar && !sidebarCollapsed) {
                NuvioLayout.tokens.sidebarContentOffset
            } else {
                NuvioTheme.spacing.none
            },
            animationSpec = tween(NuvioMotion.tokens.durations.medium),
            label = "contentStartPadding"
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = contentStartPadding)
                .onPreviewKeyEvent { keyEvent ->
                    // Long-press Back on a root route directly opens the sidebar,
                    // bypassing the "scroll row to start" BackHandler in home content.
                    if (keyEvent.key == Key.Back) {
                        if (
                            keyEvent.type == KeyEventType.KeyDown &&
                            showSidebar &&
                            drawerState.currentValue == DrawerValue.Closed &&
                            currentRoute in rootRoutes &&
                            keyEvent.nativeKeyEvent.isLongPress
                        ) {
                            if (!longPressBackHeld.value) {
                                longPressBackHeld.value = true
                                pendingSidebarFocusRequest = true
                                drawerState.setValue(DrawerValue.Open)
                            }
                            return@onPreviewKeyEvent true
                        }
                        if (longPressBackHeld.value) {
                            if (keyEvent.type == KeyEventType.KeyUp) longPressBackHeld.value = false
                            return@onPreviewKeyEvent true
                        }
                    }
                    false
                }
                .onKeyEvent { keyEvent ->
                    val openKey = if (isRtl) Key.DirectionRight else Key.DirectionLeft
                    if (
                        showSidebar &&
                        drawerState.currentValue == DrawerValue.Closed &&
                        keyEvent.type == KeyEventType.KeyDown &&
                        keyEvent.key == openKey
                    ) {
                        if (focusManager.moveFocus(if (isRtl) FocusDirection.Right else FocusDirection.Left)) {
                            true
                        } else {
                            pendingSidebarFocusRequest = true
                            drawerState.setValue(DrawerValue.Open)
                            true
                        }
                    } else {
                        false
                    }
                }
        ) {
            CompositionLocalProvider(
                LocalSidebarExpanded provides (drawerState.currentValue == DrawerValue.Open),
                LocalContentFocusRequester provides contentFocusRequester,
                LocalOpenSidebar provides openSidebar
            ) {
                NuvioNavHost(
                    navController = navController,
                    startDestination = startDestination,
                    hideBuiltInHeaders = hideBuiltInHeaders
                )
            }
        }
    }
}

@Composable
internal fun LegacySidebarButton(
    label: String,
    iconRes: Int?,
    icon: ImageVector?,
    selected: Boolean,
    expanded: Boolean,
    expandProgress: Float = if (expanded) 1f else 0f,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val isRtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    val itemShape = NuvioTheme.shapes.navItem
    val backgroundColor by animateColorAsState(
        targetValue = when {
            isFocused -> NuvioTheme.colors.FocusBackground
            expanded && selected -> NuvioTheme.colors.Secondary
            else -> Color.Transparent
        },
        animationSpec = tween(140),
        label = "legacySidebarItemBackground"
    )
    val contentColor by animateColorAsState(
        targetValue = when {
            isFocused -> NuvioTheme.colors.TextPrimary
            expanded && selected -> NuvioTheme.colors.OnSecondary
            else -> NuvioTheme.colors.TextSecondary
        },
        animationSpec = tween(140),
        label = "legacySidebarItemContent"
    )
    val iconTint by animateColorAsState(
        targetValue = when {
            isFocused -> NuvioTheme.colors.TextPrimary
            expanded && selected -> NuvioTheme.colors.OnSecondary
            selected -> NuvioTheme.colors.Secondary
            !expanded -> NuvioTheme.colors.TextTertiary
            else -> NuvioTheme.colors.TextSecondary
        },
        animationSpec = tween(140),
        label = "legacySidebarItemIconTint"
    )
    val selectedCollapsedIconBrush = if (selected && !expanded) {
        NuvioTheme.palette.accentBrush()
    } else {
        null
    }
    val itemScale by animateFloatAsState(
        targetValue = if (isFocused && expanded) 1.02f else 1f,
        animationSpec = tween(durationMillis = 140, easing = NuvioMotion.tokens.easings.standard),
        label = "legacySidebarItemScale"
    )

    Card(
        onClick = onClick,
        modifier = modifier
            .height(NuvioComponents.tokens.sidebar.itemHeight)
            .graphicsLayer {
                scaleX = itemScale
                scaleY = itemScale
                transformOrigin = TransformOrigin.Center
            }
            .focusProperties { canFocus = expanded }
            .onFocusChanged { isFocused = it.hasFocus },
        colors = CardDefaults.colors(
            containerColor = backgroundColor,
            focusedContainerColor = backgroundColor,
        ),
        border = CardDefaults.border(
            border = androidx.tv.material3.Border.None,
            focusedBorder = androidx.tv.material3.Border(
                border = sidebarFocusStroke(),
                shape = itemShape
            )
        ),
        shape = CardDefaults.shape(shape = itemShape),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = NuvioFocus.tokens.pressedScale)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .semantics {
                    contentDescription = label
                    role = Role.Tab
                    this.selected = selected
                }
        ) {
        DrawerItemIcon(
            iconRes = iconRes,
            icon = icon,
            tint = iconTint,
            brush = selectedCollapsedIconBrush,
            modifier = Modifier
                .size(NuvioComponents.tokens.sidebar.iconSize)
                .align(Alignment.CenterStart)
                .offset(x = 13.dp)
        )
        if (expanded || expandProgress > 0f) {
            com.nuvio.tv.ui.components.AutoResizeText(
                text = label,
                color = contentColor,
                textAlign = TextAlign.Start,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .wrapContentWidth(Alignment.Start, unbounded = true)
                    .requiredWidth(NuvioComponents.tokens.sidebar.itemWidth - 68.dp)
                    .offset(x = 54.dp)
                    .graphicsLayer {
                        alpha = expandProgress
                        translationX = (if (isRtl) 1f else -1f) * 16.dp.toPx() * (1f - expandProgress)
                    }
            )
        }
    }
}
}

@Composable
private fun ModernSidebarScaffold(
    longPressBackHeld: MutableState<Boolean>,
    navController: NavHostController,
    startDestination: String,
    currentRoute: String?,
    rootRoutes: Set<String>,
    drawerItems: List<DrawerItem>,
    selectedDrawerRoute: String?,
    selectedDrawerItem: DrawerItem,
    sidebarCollapsed: Boolean,
    modernSidebarBlurEnabled: Boolean,
    hideBuiltInHeaders: Boolean,
    activeProfileName: String,
    activeProfileColorHex: String,
    activeProfileAvatarImageUrl: String?,
    showProfileSelector: Boolean,
    onSwitchProfile: () -> Unit,
    onNavigate: (String) -> Unit,
    onExitApp: () -> Unit
) {
    val showSidebar = currentRoute in rootRoutes
    val sidebarTokens = NuvioComponents.tokens.sidebar
    val openSidebarWidth = sidebarTokens.expandedWidth

    val focusManager = LocalFocusManager.current
    val isRtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    val contentFocusRequester = remember { FocusRequester() }
    val drawerItemFocusRequesters = rememberDrawerItemFocusRequesters(drawerItems)
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    var isSidebarExpanded by remember { mutableStateOf(false) }
    var sidebarCollapsePending by remember { mutableStateOf(false) }
    var pendingContentFocusTransfer by remember { mutableStateOf(false) }
    var pendingSidebarFocusRequest by remember { mutableStateOf(false) }
    val openSidebar: () -> Unit = remember(showSidebar) {
        {
            if (showSidebar) {
                pendingContentFocusTransfer = false
                sidebarCollapsePending = false
                isSidebarExpanded = true
                pendingSidebarFocusRequest = true
            }
        }
    }
    var focusedDrawerIndex by remember { mutableStateOf(-1) }
    var isFloatingPillIconOnly by remember { mutableStateOf(false) }
    var pillExpandRequestCount by remember { mutableIntStateOf(0) }
    val keepFloatingPillExpanded = false
    val keepSidebarFocusDuringCollapse =
        isSidebarExpanded || sidebarCollapsePending || pendingContentFocusTransfer
    val hasSidebarProfileItem = showProfileSelector && activeProfileName.isNotEmpty()
    val sidebarTopBoundaryIndex = if (hasSidebarProfileItem) drawerItems.size else 0

    LaunchedEffect(showSidebar) {
        if (!showSidebar) {
            isSidebarExpanded = false
            sidebarCollapsePending = false
            pendingContentFocusTransfer = false
            pendingSidebarFocusRequest = false
            isFloatingPillIconOnly = false
        }
    }

    // Collapse sidebar when navigating between root routes (e.g. Settings -> Home via Back)
    LaunchedEffect(currentRoute) {
        if (isSidebarExpanded && showSidebar) {
            sidebarCollapsePending = true
        }
    }

    LaunchedEffect(keepFloatingPillExpanded, showSidebar) {
        if (!showSidebar || keepFloatingPillExpanded) {
            isFloatingPillIconOnly = false
        }
    }

    // Expand pill label briefly after navigating to a different root route
    LaunchedEffect(selectedDrawerRoute) {
        if (showSidebar && !isSidebarExpanded) {
            isFloatingPillIconOnly = false
            pillExpandRequestCount++
        }
    }

    BackHandler(enabled = currentRoute in rootRoutes && !isSidebarExpanded && !sidebarCollapsePending) {
        isSidebarExpanded = true
        sidebarCollapsePending = false
        pendingSidebarFocusRequest = true
    }

    BackHandler(enabled = currentRoute in rootRoutes && isSidebarExpanded && !sidebarCollapsePending) {
        if (longPressBackHeld.value) return@BackHandler
        onExitApp()
    }

    LaunchedEffect(sidebarCollapsePending, isSidebarExpanded, showSidebar) {
        if (!showSidebar || !sidebarCollapsePending) {
            return@LaunchedEffect
        }
        if (!isSidebarExpanded) {
            sidebarCollapsePending = false
            return@LaunchedEffect
        }
        delay(95L)
        isSidebarExpanded = false
        sidebarCollapsePending = false
    }

    // Auto-collapse the expanded sidebar after a short period of inactivity.
    // The timer resets every time focus moves between drawer items, so the

    // Auto-collapse the floating pill back to icon-only when the user reveals
    // its label (DPAD UP from content) and then leaves it idle. The DPAD DOWN
    // path already collapses it instantly, this just covers the case where the
    // user releases UP and walks away.
    LaunchedEffect(isFloatingPillIconOnly, keepFloatingPillExpanded, showSidebar, isSidebarExpanded, pillExpandRequestCount) {
        if (!showSidebar || isFloatingPillIconOnly || keepFloatingPillExpanded || isSidebarExpanded) {
            return@LaunchedEffect
        }
        delay(SIDEBAR_AUTO_COLLAPSE_DELAY_MS)
        isFloatingPillIconOnly = true
    }

    val sidebarVisible = showSidebar && (isSidebarExpanded || !sidebarCollapsed)
    val sidebarHazeState = remember { HazeState() }
    // Keep the panel at its full size; only its GPU layer moves and fades.
    val sidebarExpandProgress by rememberSidebarExpandProgress(isSidebarExpanded && !sidebarCollapsePending)
    val shouldApplySidebarHaze = showSidebar && modernSidebarBlurEnabled
    val sidebarLabelAlpha = 1f

    // derivedStateOf prevents per-frame recomposition — only triggers when the boolean crosses the threshold
    val sidebarBlocksContentKeys by remember { derivedStateOf { sidebarExpandProgress > 0.2f } }
    val sidebarShowExpandedPanel by remember { derivedStateOf { sidebarExpandProgress > 0f } }
    val sidebarShowCollapsedPill by remember { derivedStateOf { sidebarExpandProgress < 1f } }

    val sidebarIconScale = 1f

    LaunchedEffect(isSidebarExpanded, sidebarCollapsePending, pendingContentFocusTransfer, showSidebar) {
        if (!showSidebar || !pendingContentFocusTransfer || isSidebarExpanded || sidebarCollapsePending) {
            return@LaunchedEffect
        }
        repeat(2) { withFrameNanos { } }
        runCatching { contentFocusRequester.requestFocus() }
        pendingContentFocusTransfer = false
    }

    LaunchedEffect(isSidebarExpanded, pendingSidebarFocusRequest, showSidebar, selectedDrawerRoute) {
        if (!showSidebar || !pendingSidebarFocusRequest || !isSidebarExpanded) {
            return@LaunchedEffect
        }
        val targetRoute = selectedDrawerRoute ?: drawerItems.firstOrNull()?.route ?: run {
            pendingSidebarFocusRequest = false
            return@LaunchedEffect
        }
        val requester = drawerItemFocusRequesters[targetRoute] ?: run {
            pendingSidebarFocusRequest = false
            return@LaunchedEffect
        }
        repeat(2) { withFrameNanos { } }
        runCatching { requester.requestFocus() }
        pendingSidebarFocusRequest = false
    }

    SidebarFocusRecoveryEffect(
        drawerItems = drawerItems,
        selectedDrawerRoute = selectedDrawerRoute,
        drawerItemFocusRequesters = drawerItemFocusRequesters,
        sidebarOwnsFocus = showSidebar && isSidebarExpanded
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { keyEvent ->
                // Consume all Back key events after long-press until released,
                // preventing the exit-app BackHandler from firing during the hold.
                if (longPressBackHeld.value && keyEvent.key == Key.Back) {
                    if (keyEvent.type == KeyEventType.KeyUp) longPressBackHeld.value = false
                    return@onPreviewKeyEvent true
                }
                false
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (shouldApplySidebarHaze) Modifier.hazeSource(state = sidebarHazeState)
                    else Modifier
                )
                .onPreviewKeyEvent { keyEvent ->
                    // Long-press Back on a root route directly opens the sidebar,
                    // bypassing the "scroll row to start" BackHandler in home content.
                    if (keyEvent.key == Key.Back) {
                        if (
                            keyEvent.type == KeyEventType.KeyDown &&
                            showSidebar &&
                            !isSidebarExpanded &&
                            !sidebarCollapsePending &&
                            currentRoute in rootRoutes &&
                            keyEvent.nativeKeyEvent.isLongPress
                        ) {
                            if (!longPressBackHeld.value) {
                                longPressBackHeld.value = true
                                isSidebarExpanded = true
                                sidebarCollapsePending = false
                                pendingSidebarFocusRequest = true
                            }
                            return@onPreviewKeyEvent true
                        }
                        if (longPressBackHeld.value) {
                            if (keyEvent.type == KeyEventType.KeyUp) longPressBackHeld.value = false
                            return@onPreviewKeyEvent true
                        }
                    }
                    if (
                        isSidebarExpanded &&
                        !sidebarCollapsePending &&
                        sidebarBlocksContentKeys &&
                        keyEvent.type == KeyEventType.KeyDown &&
                        isBlockedContentKey(keyEvent.key)
                    ) {
                        true
                    } else {
                        false
                    }
                }
                .onKeyEvent { keyEvent ->
                    if (showSidebar && !isSidebarExpanded && keyEvent.type == KeyEventType.KeyDown) {
                        if (!keepFloatingPillExpanded) {
                            when (keyEvent.key) {
                                Key.DirectionDown -> isFloatingPillIconOnly = true
                                Key.DirectionUp -> {
                                    isFloatingPillIconOnly = false
                                    pillExpandRequestCount++
                                }
                                else -> Unit
                            }
                        }
                        val openKey = if (isRtl) Key.DirectionRight else Key.DirectionLeft
                        if (keyEvent.key == openKey) {
                            if (focusManager.moveFocus(if (isRtl) FocusDirection.Right else FocusDirection.Left)) {
                                true
                            } else {
                                isSidebarExpanded = true
                                sidebarCollapsePending = false
                                pendingSidebarFocusRequest = true
                                true
                            }
                        } else {
                            false
                        }
                    } else {
                        false
                    }
                }
        ) {
            CompositionLocalProvider(
                LocalSidebarExpanded provides isSidebarExpanded,
                LocalContentFocusRequester provides contentFocusRequester,
                LocalOpenSidebar provides openSidebar
            ) {
                NuvioNavHost(
                    navController = navController,
                    startDestination = startDestination,
                    hideBuiltInHeaders = hideBuiltInHeaders
                )
            }
        }

        if (showSidebar && (sidebarVisible || sidebarShowExpandedPanel)) {
            val panelShape = RoundedCornerShape(sidebarTokens.panelRadius)
            val showExpandedPanel = isSidebarExpanded || sidebarShowExpandedPanel

            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .width(openSidebarWidth)
                    .padding(start = NuvioTheme.spacing.lg - NuvioTheme.spacing.xxs, top = NuvioTheme.spacing.lg, bottom = NuvioTheme.spacing.md, end = NuvioTheme.spacing.sm)
                    .selectableGroup()
                    .onPreviewKeyEvent { keyEvent ->
                        if (!isSidebarExpanded || keyEvent.type != KeyEventType.KeyDown) {
                            return@onPreviewKeyEvent false
                        }
                        when (keyEvent.key) {
                            Key.DirectionUp -> {
                                if (focusedDrawerIndex == sidebarTopBoundaryIndex) {
                                    true
                                } else {
                                    // Move focus within the sidebar; consume unconditionally
                                    // so focus never escapes into the content behind.
                                    focusManager.moveFocus(FocusDirection.Up)
                                    true
                                }
                            }

                            Key.DirectionDown -> {
                                if (focusedDrawerIndex == drawerItems.lastIndex) {
                                    // Already at the bottom drawer item — stay put.
                                    true
                                } else if (focusedDrawerIndex == drawerItems.size && hasSidebarProfileItem) {
                                    // Profile → first drawer item: skip moveFocus (the
                                    // Spacer gap causes it to land in content) and
                                    // request the first drawer item directly.
                                    drawerItems.firstOrNull()?.route?.let { route ->
                                        drawerItemFocusRequesters[route]?.requestFocus()
                                    }
                                    true
                                } else {
                                    focusManager.moveFocus(FocusDirection.Down)
                                    true
                                }
                            }

                            Key.DirectionRight, Key.DirectionLeft -> {
                                val collapseKey = if (isRtl) Key.DirectionLeft else Key.DirectionRight
                                if (keyEvent.key == collapseKey) {
                                    pendingContentFocusTransfer = false
                                    sidebarCollapsePending = true
                                    true
                                } else {
                                    false
                                }
                            }

                            else -> false
                        }
                    }
            ) {
                if (showExpandedPanel) {
                    ModernSidebarBlurPanel(
                        drawerItems = drawerItems,
                        selectedDrawerRoute = selectedDrawerRoute,
                        keepSidebarFocusDuringCollapse = keepSidebarFocusDuringCollapse,
                        sidebarLabelAlpha = sidebarLabelAlpha,
                        sidebarIconScale = sidebarIconScale,
                        sidebarExpandProgress = sidebarExpandProgress,
                        isSidebarExpanded = isSidebarExpanded,
                        sidebarCollapsePending = sidebarCollapsePending,
                        blurEnabled = modernSidebarBlurEnabled,
                        sidebarHazeState = sidebarHazeState,
                        panelShape = panelShape,
                        drawerItemFocusRequesters = drawerItemFocusRequesters,
                        onDrawerItemFocused = { focusedDrawerIndex = it },
                        onDrawerItemClick = { targetRoute ->
                            keyboardController?.hide()
                            onNavigate(targetRoute)
                            navigateToDrawerRoute(
                                navController = navController,
                                currentRoute = currentRoute,
                                targetRoute = targetRoute
                            )
                            pendingSidebarFocusRequest = false
                            isSidebarExpanded = false
                            sidebarCollapsePending = false
                            pendingContentFocusTransfer = currentRoute == targetRoute
                        },
                        activeProfileName = activeProfileName,
                        activeProfileColorHex = activeProfileColorHex,
                        activeProfileAvatarImageUrl = activeProfileAvatarImageUrl,
                        showProfileSelector = showProfileSelector,
                        onSwitchProfile = onSwitchProfile
                    )
                }
            }

            if (
                !sidebarCollapsed &&
                sidebarShowCollapsedPill &&
                selectedDrawerRoute != Screen.Search.route
            ) {
                CollapsedSidebarPill(
                    label = selectedDrawerItem.label,
                    iconRes = selectedDrawerItem.iconRes,
                    icon = selectedDrawerItem.icon,
                    iconOnly = isFloatingPillIconOnly && !keepFloatingPillExpanded,
                    blurEnabled = modernSidebarBlurEnabled,
                    hazeState = if (modernSidebarBlurEnabled) sidebarHazeState else null,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset {
                            IntOffset(
                                (NuvioTheme.spacing.lg - NuvioTheme.spacing.xxs).roundToPx(),
                                NuvioTheme.spacing.lg.roundToPx()
                            )
                        }
                        .graphicsLayer {
                            val progress = sidebarExpandProgress
                            alpha = 1f - progress
                            translationX = (if (isRtl) 1f else -1f) * 16.dp.toPx() * progress
                        },
                    onExpand = {
                        isSidebarExpanded = true
                        sidebarCollapsePending = false
                        pendingSidebarFocusRequest = true
                    }
                )
            }
        }
    }
}

@Composable
private fun CollapsedSidebarPill(
    label: String,
    iconRes: Int?,
    icon: ImageVector?,
    iconOnly: Boolean,
    blurEnabled: Boolean,
    hazeState: HazeState? = null,
    modifier: Modifier = Modifier,
    onExpand: () -> Unit
) {
    val pillShape = RoundedCornerShape(NuvioRadii.tokens.full)
    val colors = NuvioTheme.colors
    val bgElevated = colors.BackgroundElevated
    val bgCard = colors.BackgroundCard
    val borderBase = colors.Border
    val mediaColors = colors.media
    val pillBackgroundBrush = remember(blurEnabled) {
        val alpha = if (blurEnabled) 0.65f else 0.96f
        Brush.verticalGradient(listOf(
            Color(0xFF1C1C1E).copy(alpha = alpha),
            Color(0xFF1C1C1E).copy(alpha = alpha)
        ))
    }

    Row(
        modifier = modifier
            .focusProperties { canFocus = false }
            .clickable(onClick = onExpand)
            .semantics {
                role = Role.Button
                contentDescription = label
            }
            .padding(horizontal = NuvioTheme.spacing.hairline, vertical = NuvioTheme.spacing.xxs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .height(NuvioTheme.sizes.player.control)
                .clip(pillShape)
                .then(
                    if (blurEnabled && hazeState != null) {
                        Modifier.hazeEffect(state = hazeState) {
                            blurRadius = 24.dp
                            inputScale = HazeInputScale.Fixed(0.66f)
                        }
                    } else {
                        Modifier
                    }
                )
                .background(brush = pillBackgroundBrush, shape = pillShape)
        ) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .padding(start = 5.dp, end = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(NuvioTheme.sizes.sidebar.leadingVisual),
                    contentAlignment = Alignment.Center
                ) {
                    DrawerItemIcon(
                        iconRes = iconRes,
                        icon = icon,
                        tint = NuvioTheme.colors.text.onOverlay,
                        modifier = Modifier
                            .size(NuvioTheme.sizes.sidebar.leadingVisual - NuvioTheme.spacing.md)
                            .offset(y = (-0.5).dp)
                    )
                }

                AnimatedVisibility(
                    visible = !iconOnly,
                    enter = expandHorizontally(
                            animationSpec = tween(NuvioMotion.tokens.durations.fast, easing = FastOutSlowInEasing),
                            expandFrom = Alignment.Start,
                            clip = true
                        ),
                    exit = shrinkHorizontally(
                            animationSpec = tween(NuvioMotion.tokens.durations.fast, easing = FastOutSlowInEasing),
                            shrinkTowards = Alignment.Start,
                            clip = true
                        )
                ) {
                    Text(
                        text = label,
                        color = NuvioTheme.colors.text.onOverlay,
                        style = androidx.tv.material3.MaterialTheme.typography.titleLarge.copy(
                            lineHeight = 30.sp
                        ),
                        modifier = Modifier
                            .padding(start = 9.dp, end = NuvioTheme.spacing.md - 5.dp)
                            .offset(y = (-0.5).dp),
                        maxLines = 1
                    )
                }
            }
        }
    }
}

private fun navigateToDrawerRoute(
    navController: NavHostController,
    currentRoute: String?,
    targetRoute: String
) {
    if (currentRoute == targetRoute) {
        if (targetRoute == Screen.Home.route) {
            // Scroll Home to top by clearing saved focus/scroll state on the ViewModel.
            val homeEntry = try {
                navController.getBackStackEntry(Screen.Home.route)
            } catch (_: IllegalArgumentException) {
                // "home" not yet on the back stack (e.g. nav graph not fully initialized).
                return
            }
            val homeViewModel = androidx.lifecycle.ViewModelProvider(homeEntry)[com.nuvio.tv.ui.screens.home.HomeViewModel::class.java]
            homeViewModel.requestScrollToTop()
        }
        return
    }
    try {
        navController.navigate(targetRoute) {
            popUpTo(navController.graph.startDestinationId) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    } catch (e: IllegalArgumentException) {
        Log.w("NuvioNavigation", "Route not found in nav graph: $targetRoute", e)
    }
}

private fun isBlockedContentKey(key: Key): Boolean {
    return key == Key.DirectionUp ||
        key == Key.DirectionDown ||
        key == Key.DirectionLeft ||
        key == Key.DirectionRight ||
        key == Key.DirectionCenter ||
        key == Key.Enter
}

@Composable
private fun DrawerItemIcon(
    iconRes: Int?,
    icon: ImageVector?,
    modifier: Modifier = Modifier,
    tint: Color = androidx.tv.material3.LocalContentColor.current,
    brush: Brush? = null
) {
    val iconModifier = if (brush == null) {
        modifier
    } else {
        modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
                onDrawWithContent {
                    drawContent()
                    drawRect(brush = brush, blendMode = BlendMode.SrcIn)
                }
            }
    }
    val iconTint = if (brush == null) tint else Color.White
    when {
        icon != null -> Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = iconModifier
        )

        iconRes != null -> Icon(
            painter = rememberRawSvgPainter(iconRes),
            contentDescription = null,
            tint = iconTint,
            modifier = iconModifier
        )
    }
}

object LocaleCache {
    const val UNSET = "__UNSET__"

    @Volatile
    var localeTag: String = UNSET
}
