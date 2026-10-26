@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.ui.theme.NuvioTheme

import android.content.Intent
import android.net.Uri
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.RawRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.R
import com.nuvio.tv.core.cloud.PremiumizeCloudLibraryPosterUrl
import com.nuvio.tv.core.cloud.TorboxCloudLibraryPosterUrl
import com.nuvio.tv.core.cloud.cloudLibraryDisplayArtworkUrl
import com.nuvio.tv.ui.util.rememberRawSvgPainter
import com.nuvio.tv.ui.components.NuvioDialog
import kotlinx.coroutines.launch

private const val NuvioRepositoryUrl = "https://github.com/NuvioMedia/NuvioTV"
private const val SyclopRepositoryUrl = "https://github.com/dintalframe/Syclop"
private const val TmdbUrl = "https://www.themoviedb.org"
private const val TraktUrl = "https://trakt.tv"
private const val SimklUrl = "https://simkl.com"
private const val PremiumizeUrl = "https://www.premiumize.me"
private const val TorboxUrl = "https://torbox.app"
private const val MdbListUrl = "https://mdblist.com"
private const val IntroDbUrl = "https://introdb.app/"
private const val ImdbDatasetsUrl = "https://developer.imdb.com/non-commercial-datasets/"
private const val ApacheLicenseUrl = "https://www.apache.org/licenses/LICENSE-2.0"
private const val HazeLicenseUrl = "https://github.com/chrisbanes/haze/blob/1.7.2/LICENSE"
private const val LibMpvAndroidUrl = "https://github.com/jarnedemeulemeester/libmpv-android"
private const val ConscryptSourceUrl = "https://github.com/google/conscrypt/tree/2.8.0"

private sealed interface LicenseLogo {
    data class Drawable(@param:DrawableRes val resId: Int) : LicenseLogo
    data class Raw(@param:RawRes val resId: Int) : LicenseLogo
    data class Url(val url: String) : LicenseLogo
}

private data class LicenseAttributionItem(
    val title: String,
    val body: String,
    val url: String,
    val logo: LicenseLogo? = null,
    val noticeAssetPaths: List<String> = emptyList()
)

@Composable
fun LicensesAttributionsScreen(
    onBackPress: () -> Unit
) {
    BackHandler { onBackPress() }

    val firstFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { firstFocusRequester.requestFocus() }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = NuvioTheme.spacing.xxxl, vertical = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(36.dp)
    ) {
        LicensesAttributionsBrandPanel(
            modifier = Modifier
                .weight(0.42f)
                .fillMaxHeight()
        )

        LicensesAttributionsDetailsPanel(
            firstFocusRequester = firstFocusRequester,
            modifier = Modifier
                .weight(0.58f)
                .fillMaxHeight()
        )
    }
}

@Composable
private fun LicensesAttributionsBrandPanel(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.licenses_attributions_title),
            style = MaterialTheme.typography.headlineLarge,
            color = NuvioTheme.colors.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
        Text(
            text = stringResource(R.string.syclop_licenses_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = NuvioTheme.colors.TextSecondary
        )
    }
}

@Composable
private fun LicensesAttributionsDetailsPanel(
    firstFocusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .border(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
            .background(NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            .padding(20.dp)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
            ) {
                AttributionSection(
                    title = stringResource(R.string.licenses_attributions_section_app)
                ) {
                    AttributionDetailRow(
                        item = appLicenseItem(),
                        modifier = Modifier.focusRequester(firstFocusRequester)
                    )
                    AttributionDetailRow(item = upstreamLicenseItem())
                }

                AttributionSection(
                    title = stringResource(R.string.licenses_attributions_section_data)
                ) {
                    dataAttributionItems().forEach { item ->
                        AttributionDetailRow(item = item)
                    }
                }

                AttributionSection(
                    title = stringResource(R.string.licenses_attributions_section_playback)
                ) {
                    playbackLicenseItems().forEach { item ->
                        AttributionDetailRow(item = item)
                    }
                }

                AttributionSection(
                    title = stringResource(R.string.licenses_attributions_section_ui)
                ) {
                    AttributionDetailRow(
                        item = LicenseAttributionItem(
                            title = stringResource(R.string.licenses_attributions_haze_title),
                            body = stringResource(R.string.licenses_attributions_haze_body),
                            url = HazeLicenseUrl
                        )
                    )
                }
            }
            SettingsVerticalScrollIndicators(state = scrollState)
        }
    }
}

@Composable
private fun AttributionSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = NuvioTheme.colors.TextSecondary,
            fontWeight = FontWeight.Bold
        )
        content()
    }
}

@Composable
private fun AttributionDetailRow(
    item: LicenseAttributionItem,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val readFailure = stringResource(R.string.conscrypt_notices_unavailable)
    var bundledNotices by remember(item.noticeAssetPaths) { mutableStateOf<String?>(null) }

    if (bundledNotices != null) {
        BundledLicenseDialog(
            title = item.title,
            notices = bundledNotices!!,
            onDismiss = { bundledNotices = null }
        )
    }

    Card(
        onClick = {
            if (item.noticeAssetPaths.isEmpty()) {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.url)))
            } else {
                runCatching {
                    item.noticeAssetPaths.joinToString("\n\n") { path ->
                        context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
                    }
                }.onSuccess { bundledNotices = it }
                    .onFailure { Toast.makeText(context, readFailure, Toast.LENGTH_LONG).show() }
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp),
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.Background,
            focusedContainerColor = NuvioTheme.colors.Background
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = RoundedCornerShape(SettingsSecondaryCardRadius)
            )
        ),
        shape = CardDefaults.shape(RoundedCornerShape(SettingsSecondaryCardRadius)),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.Top
        ) {
            item.logo?.let { logo ->
                AttributionLogo(logo = logo)
                Spacer(modifier = Modifier.width(14.dp))
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = NuvioTheme.colors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.body,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary
                )
                Text(
                    text = item.url,
                    style = MaterialTheme.typography.labelSmall,
                    color = NuvioTheme.colors.Primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                tint = NuvioTheme.colors.TextTertiary,
                modifier = Modifier
                    .padding(top = NuvioTheme.spacing.xxs)
                    .size(18.dp)
            )
        }
    }
}

@Composable
private fun AttributionLogo(
    logo: LicenseLogo
) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(NuvioTheme.radii.md))
            .background(Color(0xFF24272E)),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = when (logo) {
                is LicenseLogo.Drawable -> painterResource(id = logo.resId)
                is LicenseLogo.Raw -> rememberRawSvgPainter(rawIconRes = logo.resId, targetSize = NuvioTheme.spacing.xxxl)
                is LicenseLogo.Url -> rememberUrlSvgPainter(url = logo.url)
            },
            contentDescription = null,
            modifier = Modifier.size(38.dp),
            contentScale = ContentScale.Fit
        )
    }
}

@Composable
private fun rememberUrlSvgPainter(url: String): Painter {
    val context = LocalContext.current
    val density = LocalDensity.current
    val sizePx = with(density) { NuvioTheme.spacing.xxxl.roundToPx() }
    val request = remember(url, context, sizePx) {
        ImageRequest.Builder(context)
            .data(url)
            .size(sizePx)
            .crossfade(false)
            .build()
    }
    return rememberAsyncImagePainter(model = request)
}

@Composable
private fun appLicenseItem() = LicenseAttributionItem(
    title = stringResource(R.string.syclop_licenses_app_title),
    body = stringResource(R.string.syclop_licenses_app_body),
    url = SyclopRepositoryUrl
)

@Composable
private fun upstreamLicenseItem() = LicenseAttributionItem(
    title = stringResource(R.string.licenses_attributions_nuvio_title),
    body = stringResource(R.string.licenses_attributions_nuvio_body),
    url = NuvioRepositoryUrl
)

@Composable
private fun dataAttributionItems() = listOf(
    LicenseAttributionItem(
        title = stringResource(R.string.licenses_attributions_tmdb_title),
        body = stringResource(R.string.licenses_attributions_tmdb_body),
        url = TmdbUrl,
        logo = LicenseLogo.Drawable(R.drawable.rating_tmdb)
    ),
    LicenseAttributionItem(
        title = stringResource(R.string.licenses_attributions_trakt_title),
        body = stringResource(R.string.licenses_attributions_trakt_body),
        url = TraktUrl,
        logo = LicenseLogo.Raw(R.raw.trakt_tv_favicon)
    ),
    LicenseAttributionItem(
        title = stringResource(R.string.licenses_attributions_simkl_title),
        body = stringResource(R.string.licenses_attributions_simkl_body),
        url = SimklUrl,
        logo = LicenseLogo.Raw(R.raw.simkl_tv_glyph)
    ),
    LicenseAttributionItem(
        title = stringResource(R.string.licenses_attributions_premiumize_title),
        body = stringResource(R.string.licenses_attributions_premiumize_body),
        url = PremiumizeUrl,
        logo = LicenseLogo.Url(PremiumizeCloudLibraryPosterUrl)
    ),
    LicenseAttributionItem(
        title = stringResource(R.string.licenses_attributions_torbox_title),
        body = stringResource(R.string.licenses_attributions_torbox_body),
        url = TorboxUrl,
        logo = cloudLibraryDisplayArtworkUrl(TorboxCloudLibraryPosterUrl)?.let(LicenseLogo::Url)
    ),
    LicenseAttributionItem(
        title = stringResource(R.string.licenses_attributions_mdblist_title),
        body = stringResource(R.string.licenses_attributions_mdblist_body),
        url = MdbListUrl,
        logo = LicenseLogo.Raw(R.raw.mdblist_logo)
    ),
    LicenseAttributionItem(
        title = stringResource(R.string.licenses_attributions_introdb_title),
        body = stringResource(R.string.licenses_attributions_introdb_body),
        url = IntroDbUrl,
        logo = LicenseLogo.Drawable(R.drawable.introdb_favicon)
    ),
    LicenseAttributionItem(
        title = stringResource(R.string.licenses_attributions_imdb_title),
        body = stringResource(R.string.licenses_attributions_imdb_body),
        url = ImdbDatasetsUrl
    )
)

@Composable
private fun playbackLicenseItems() = listOf(
    LicenseAttributionItem(
        title = stringResource(R.string.licenses_attributions_exoplayer_title),
        body = stringResource(R.string.licenses_attributions_exoplayer_body),
        url = ApacheLicenseUrl
    ),
    LicenseAttributionItem(
        title = stringResource(R.string.licenses_attributions_libmpv_title),
        body = stringResource(R.string.licenses_attributions_libmpv_body),
        url = LibMpvAndroidUrl
    ),
    LicenseAttributionItem(
        title = stringResource(R.string.conscrypt_notices_title),
        body = stringResource(R.string.conscrypt_notices_description),
        url = ConscryptSourceUrl,
        noticeAssetPaths = listOf(
            "licenses/conscrypt-2.8.0/LICENSE",
            "licenses/conscrypt-2.8.0/NOTICE",
            "licenses/conscrypt-2.8.0/licenses/LICENSE.netty.txt",
            "licenses/conscrypt-2.8.0/licenses/LICENSE.harmony.txt",
            "licenses/conscrypt-2.8.0/boringssl/LICENSE"
        )
    )
)


/** Bundled notice text remains readable without sending the viewer to a browser. */
@Composable
private fun BundledLicenseDialog(title: String, notices: String, onDismiss: () -> Unit) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val textFocus = remember { FocusRequester() }
    val closeFocus = remember { FocusRequester() }
    val scrollStep = with(LocalDensity.current) { 100.dp.toPx() }
    LaunchedEffect(Unit) {
        repeat(2) { withFrameNanos { } }
        textFocus.requestFocus()
    }
    NuvioDialog(
        title = title,
        subtitle = stringResource(R.string.conscrypt_notices_navigation),
        onDismiss = onDismiss
    ) {
        Box(
            Modifier.fillMaxWidth().height(220.dp)
                .focusRequester(textFocus)
                .onPreviewKeyEvent { event ->
                    val key = event.nativeKeyEvent
                    if (key.action != KeyEvent.ACTION_DOWN) false else when (key.keyCode) {
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (scroll.value < scroll.maxValue) {
                                scope.launch { scroll.scrollTo((scroll.value + scrollStep.toInt()).coerceAtMost(scroll.maxValue)) }
                            } else closeFocus.requestFocus()
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            scope.launch { scroll.scrollTo((scroll.value - scrollStep.toInt()).coerceAtLeast(0)) }
                            true
                        }
                        else -> false
                    }
                }
                .focusable()
                .verticalScroll(scroll)
        ) {
            Text(notices, style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextPrimary)
        }
        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth().focusRequester(closeFocus)
                .focusProperties { up = textFocus }
        ) { Text(stringResource(R.string.action_close)) }
    }
}
