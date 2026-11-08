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
import com.nuvio.tv.BuildConfig
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
private const val LibMpvAndroidUrl = "https://github.com/mpv-player/mpv/tree/b0841e8c4d156b8403dc1876c4cbd9011eb583f3"
private const val ConscryptSourceUrl = "https://github.com/google/conscrypt/tree/2.8.0"
private const val SentryNativeSourceUrl = "https://github.com/getsentry/sentry-native/tree/0.15.2"
private const val MpegHSourceUrl = "https://github.com/Fraunhofer-IIS/mpeghdec/tree/4555490b82ff4cdb965c9501a96d1c248804fcb6"
private const val Av1SourceUrl = "https://chromium.googlesource.com/codecs/libgav1/+/c05bf9be660cf170d7c26bd06bb42b3322180e58"
private const val LibassSourceUrl = "https://github.com/NuvioMedia/nuvio-libass/tree/b824ae66738e279d09b1994d9977978e7ffcc90a"
private const val LibassNoticeDirectory = "licenses/libass-0.18.4"

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
        SettingsTextDialog(
            title = item.title,
            text = bundledNotices!!,
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
        title = stringResource(R.string.syclop_licenses_tvmaze_title),
        body = stringResource(R.string.syclop_licenses_tvmaze_body),
        url = "https://www.tvmaze.com"
    ),
    LicenseAttributionItem(
        title = stringResource(R.string.syclop_licenses_tvmaze_license_title),
        body = stringResource(R.string.syclop_licenses_tvmaze_license_body),
        url = "https://creativecommons.org/licenses/by-sa/4.0/"
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
        title = stringResource(R.string.syclop_licenses_nextlib_title),
        body = stringResource(R.string.syclop_licenses_nextlib_body),
        url = SyclopRepositoryUrl,
        noticeAssetPaths = listOf(
            "licenses/native-media/ATTRIBUTION.txt",
            "licenses/native-media/nextlib/ffmpeg/embedded/libavutil__x86__x86inc.asm.notice.txt",
            "licenses/native-media/nextlib/ffmpeg/embedded/libavutil__uuid.c.notice.txt",
            "licenses/native-media/nextlib/ffmpeg/embedded/libavutil__softfloat_tables.h.notice.txt",
            "licenses/native-media/nextlib/ffmpeg/embedded/libavutil__sha.c.notice.txt",
            "licenses/native-media/nextlib/ffmpeg/embedded/libavutil__fixed_dsp.h.notice.txt",
            "licenses/native-media/nextlib/ffmpeg/embedded/libavutil__fixed_dsp.c.notice.txt",
            "licenses/native-media/nextlib/ffmpeg/embedded/libavutil__avsscanf.c.notice.txt",
            "licenses/native-media/nextlib/ffmpeg/embedded/libavformat__oggparsevorbis.c.notice.txt",
            "licenses/native-media/nextlib/ffmpeg/embedded/libavformat__oggparsetheora.c.notice.txt",
            "licenses/native-media/nextlib/ffmpeg/embedded/libavformat__oggparsespeex.c.notice.txt",
            "licenses/native-media/nextlib/ffmpeg/embedded/libavformat__oggdec.h.notice.txt",
            "licenses/native-media/nextlib/ffmpeg/embedded/libavformat__oggdec.c.notice.txt",
            "licenses/native-media/nextlib/ffmpeg/embedded/libavformat__aadec.c.notice.txt",
            "licenses/native-media/nextlib/ffmpeg/embedded/libavcodec__x86__vc1dsp_init.c.notice.txt",
            "licenses/native-media/nextlib/ffmpeg/embedded/libavcodec__jrevdct.c.notice.txt",
            "licenses/native-media/nextlib/ffmpeg/embedded/libavcodec__arm__vp8dsp_armv6.S.notice.txt",
            "licenses/native-media/nextlib/ffmpeg/embedded/libavcodec__arm__jrevdct_arm.S.notice.txt",
            "licenses/native-media/nextlib/ffmpeg/embedded/libavcodec__aom_film_grain_template.c.notice.txt",
            "licenses/android-ndk-r29/NOTICE.toolchain",
            "licenses/native-media/nextlib/mbedtls/LICENSE",
            "licenses/ffmpeg-audio/COPYING.GPLv3",
            "licenses/ffmpeg-audio/COPYING.LGPLv3",
            "licenses/ffmpeg-audio/LICENSE.md",
            "licenses/native-media/nextlib/nextlib/COPYING.GPLv3"
        )
    ),
    LicenseAttributionItem(
        title = stringResource(R.string.syclop_licenses_ffmpeg_audio_title),
        body = stringResource(R.string.syclop_licenses_ffmpeg_audio_body),
        url = "https://github.com/FFmpeg/FFmpeg/tree/a35157abb96167fd7308edc92b55408d5bb084b0",
        noticeAssetPaths = listOf(
            "licenses/ffmpeg-audio/NOTICE.md",
            "licenses/iamf-1.1.0/Media3-APACHE-2.0.txt",
            "licenses/ffmpeg-audio/LICENSE.md",
            "licenses/ffmpeg-audio/COPYING.LGPLv2.1",
            "licenses/ffmpeg-audio/COPYING.LGPLv3",
            "licenses/ffmpeg-audio/COPYING.GPLv2",
            "licenses/ffmpeg-audio/COPYING.GPLv3"
        )
    ),
    LicenseAttributionItem(
        title = stringResource(R.string.licenses_attributions_exoplayer_title),
        body = stringResource(R.string.licenses_attributions_exoplayer_body),
        url = ApacheLicenseUrl
    ),
    LicenseAttributionItem(
        title = stringResource(R.string.licenses_attributions_libmpv_title),
        body = stringResource(R.string.syclop_licenses_mpv_body),
        url = LibMpvAndroidUrl,
        noticeAssetPaths = listOf(
            "licenses/native-media/ATTRIBUTION.txt",
            "licenses/native-media/mpv/component-embedded/libplacebo/3rdparty__json.h.notice.txt",
            "licenses/native-media/mpv/component-embedded/libplacebo/src__hash.h.notice.txt",
            "licenses/native-media/mpv/component-embedded/libplacebo/src__include__libplacebo__shaders__deinterlacing.h.notice.txt",
            "licenses/native-media/mpv/component-embedded/libxml2/dict.c.notice.txt",
            "licenses/native-media/mpv/component-embedded/libxml2/list.c.notice.txt",
            "licenses/native-media/mpv/component-embedded/libxml2/timsort.h.notice.txt",
            "licenses/native-media/mpv/component-embedded/mpv/include__mpv__client.h.notice.txt",
            "licenses/native-media/mpv/component-embedded/mpv/include__mpv__render.h.notice.txt",
            "licenses/native-media/mpv/component-embedded/mpv/osdep__android__strnlen.c.notice.txt",
            "licenses/native-media/mpv/dav1d/COPYING",
            "licenses/ffmpeg-audio/COPYING.GPLv2",
            "licenses/ffmpeg-audio/COPYING.GPLv3",
            "licenses/ffmpeg-audio/COPYING.LGPLv2.1",
            "licenses/ffmpeg-audio/COPYING.LGPLv3",
            "licenses/ffmpeg-audio/LICENSE.md",
            "licenses/native-media/mpv/ffmpeg-embedded/libavcodec__aacsbr_fixed.c.notice.txt",
            "licenses/native-media/mpv/ffmpeg-embedded/libavcodec__ac3dec_fixed.c.notice.txt",
            "licenses/native-media/nextlib/ffmpeg/embedded/libavcodec__aom_film_grain_template.c.notice.txt",
            "licenses/native-media/nextlib/ffmpeg/embedded/libavcodec__arm__jrevdct_arm.S.notice.txt",
            "licenses/native-media/mpv/ffmpeg-embedded/libavcodec__arm__vp8dsp_armv6.S.notice.txt",
            "licenses/native-media/mpv/ffmpeg-embedded/libavcodec__bsf__mjpeg2jpeg.c.notice.txt",
            "licenses/native-media/mpv/ffmpeg-embedded/libavcodec__faandct.c.notice.txt",
            "licenses/native-media/mpv/ffmpeg-embedded/libavcodec__ilbcdata.h.notice.txt",
            "licenses/native-media/mpv/ffmpeg-embedded/libavcodec__interplayacm.c.notice.txt",
            "licenses/native-media/mpv/ffmpeg-embedded/libavcodec__jfdctfst.c.notice.txt",
            "licenses/native-media/mpv/ffmpeg-embedded/libavcodec__jfdctint_template.c.notice.txt",
            "licenses/native-media/mpv/ffmpeg-embedded/libavcodec__jpeg2000htdec.c.notice.txt",
            "licenses/native-media/nextlib/ffmpeg/embedded/libavcodec__jrevdct.c.notice.txt",
            "licenses/native-media/mpv/ffmpeg-embedded/libavcodec__nellymoser.c.notice.txt",
            "licenses/native-media/mpv/ffmpeg-embedded/libavcodec__nellymoserdec.c.notice.txt",
            "licenses/native-media/mpv/ffmpeg-embedded/libavcodec__ra144dec.c.notice.txt",
            "licenses/native-media/mpv/ffmpeg-embedded/libavcodec__speexdata.h.notice.txt",
            "licenses/native-media/mpv/ffmpeg-embedded/libavcodec__texturedsp.c.notice.txt",
            "licenses/native-media/mpv/ffmpeg-embedded/libavcodec__vvc__itx_1d.c.notice.txt",
            "licenses/native-media/nextlib/ffmpeg/embedded/libavcodec__x86__vc1dsp_init.c.notice.txt",
            "licenses/native-media/mpv/ffmpeg-embedded/libavcodec__zerocodec.c.notice.txt",
            "licenses/native-media/mpv/ffmpeg-embedded/libavfilter__af_arnndn.c.notice.txt",
            "licenses/native-media/mpv/ffmpeg-embedded/libavfilter__af_deesser.c.notice.txt",
            "licenses/native-media/mpv/ffmpeg-embedded/libavfilter__af_hdcd.c.notice.txt",
            "licenses/native-media/mpv/ffmpeg-embedded/libavfilter__ebur128.c.notice.txt",
            "licenses/native-media/mpv/ffmpeg-embedded/libavfilter__edge_template.c.notice.txt",
            "licenses/native-media/mpv/ffmpeg-embedded/libavfilter__gblur.h.notice.txt",
            "licenses/native-media/mpv/ffmpeg-embedded/libavfilter__median.h.notice.txt",
            "licenses/native-media/mpv/ffmpeg-embedded/libavfilter__vf_avgblur.c.notice.txt",
            "licenses/native-media/mpv/ffmpeg-embedded/libavfilter__vf_bilateral.c.notice.txt",
            "licenses/native-media/mpv/ffmpeg-embedded/libavfilter__vf_bm3d.c.notice.txt",
            "licenses/native-media/mpv/ffmpeg-embedded/libavfilter__vf_deband.c.notice.txt",
            "licenses/native-media/mpv/ffmpeg-embedded/libavfilter__vf_hqx.c.notice.txt",
            "licenses/native-media/mpv/ffmpeg-embedded/libavfilter__vf_morpho.c.notice.txt",
            "licenses/native-media/nextlib/ffmpeg/embedded/libavformat__aadec.c.notice.txt",
            "licenses/native-media/mpv/ffmpeg-embedded/libavformat__imf.h.notice.txt",
            "licenses/native-media/nextlib/ffmpeg/embedded/libavformat__oggdec.c.notice.txt",
            "licenses/native-media/nextlib/ffmpeg/embedded/libavformat__oggdec.h.notice.txt",
            "licenses/native-media/nextlib/ffmpeg/embedded/libavformat__oggparsespeex.c.notice.txt",
            "licenses/native-media/nextlib/ffmpeg/embedded/libavformat__oggparsetheora.c.notice.txt",
            "licenses/native-media/nextlib/ffmpeg/embedded/libavformat__oggparsevorbis.c.notice.txt",
            "licenses/native-media/nextlib/ffmpeg/embedded/libavutil__avsscanf.c.notice.txt",
            "licenses/native-media/nextlib/ffmpeg/embedded/libavutil__fixed_dsp.c.notice.txt",
            "licenses/native-media/nextlib/ffmpeg/embedded/libavutil__fixed_dsp.h.notice.txt",
            "licenses/native-media/mpv/ffmpeg-embedded/libavutil__libm.h.notice.txt",
            "licenses/native-media/mpv/ffmpeg-embedded/libavutil__mathematics.c.notice.txt",
            "licenses/native-media/nextlib/ffmpeg/embedded/libavutil__sha.c.notice.txt",
            "licenses/native-media/nextlib/ffmpeg/embedded/libavutil__softfloat_tables.h.notice.txt",
            "licenses/native-media/nextlib/ffmpeg/embedded/libavutil__uuid.c.notice.txt",
            "licenses/native-media/mpv/ffmpeg-embedded/libavutil__x86__x86inc.asm.notice.txt",
            "licenses/native-media/mpv/glad-generated/CC0-1.0.txt",
            "licenses/native-media/mpv/glad-generated/arm64-v8a/egl.h.notice.txt",
            "licenses/native-media/mpv/glad-generated/arm64-v8a/gl.h.notice.txt",
            "licenses/libass-0.18.4/edde-distribution/expat/AUTHORS",
            "licenses/libass-0.18.4/edde-distribution/expat/COPYING",
            "licenses/libass-0.18.4/edde-distribution/expat/LICENSE.build",
            "licenses/libass-0.18.4/edde-distribution/fontconfig/AUTHORS",
            "licenses/libass-0.18.4/edde-distribution/fontconfig/COPYING",
            "licenses/libass-0.18.4/edde-distribution/freetype/FTL.TXT",
            "licenses/libass-0.18.4/edde-distribution/freetype/GPLv2.TXT",
            "licenses/libass-0.18.4/edde-distribution/freetype/LICENSE.TXT",
            "licenses/libass-0.18.4/edde-distribution/fribidi/AUTHORS",
            "licenses/libass-0.18.4/edde-distribution/fribidi/COPYING",
            "licenses/libass-0.18.4/edde-distribution/harfbuzz/AUTHORS",
            "licenses/libass-0.18.4/edde-distribution/harfbuzz/COPYING",
            "licenses/libass-0.18.4/edde-distribution/libass/COPYING",
            "licenses/libass-0.18.4/edde-distribution/libunibreak/AUTHORS",
            "licenses/libass-0.18.4/harfbuzz-upstream/COPYING",
            "licenses/libass-0.18.4/libunibreak-upstream/LICENCE",
            "licenses/libass-0.18.4/ATTRIBUTION.md",
            "licenses/native-media/mpv/libplacebo-7.387.0/LICENSE",
            "licenses/native-media/mpv/libplacebo-generated-inputs/fast_float/LICENSE-APACHE",
            "licenses/native-media/mpv/libplacebo-generated-inputs/fast_float/LICENSE-BOOST",
            "licenses/native-media/mpv/libplacebo-generated-inputs/fast_float/LICENSE-MIT",
            "licenses/native-media/mpv/libplacebo-generated-inputs/glad/LICENSE",
            "licenses/native-media/mpv/libxml2-2.15.4/Copyright",
            "licenses/native-media/mpv/lua-5.2.4/lua.h",
            "licenses/native-media/mpv/lua-5.2.4/readme.html",
            "licenses/native-media/nextlib/mbedtls/LICENSE",
            "licenses/native-media/mpv/mpv-0.42.0/Copyright",
            "licenses/native-media/mpv/mpv-0.42.0/LICENSE.GPL",
            "licenses/native-media/mpv/mpv-0.42.0/LICENSE.LGPL"
        )
    ),
    LicenseAttributionItem(
        title = stringResource(R.string.syclop_licenses_mpegh_title),
        body = stringResource(R.string.syclop_licenses_mpegh_body),
        url = MpegHSourceUrl,
        noticeAssetPaths = listOf(
            "licenses/iamf-1.1.0/Media3-APACHE-2.0.txt",
            "licenses/fraunhofer-mpegh-r2/LICENSE.txt"
        )
    ),
    LicenseAttributionItem(
        title = stringResource(R.string.syclop_licenses_av1_title),
        body = stringResource(R.string.syclop_licenses_av1_body),
        url = Av1SourceUrl,
        noticeAssetPaths = listOf(
            "licenses/av1/NOTICE.txt",
            "licenses/iamf-1.1.0/Media3-APACHE-2.0.txt",
            "licenses/cpu-features-0.10.1/LICENSE"
        )
    ),
    LicenseAttributionItem(
        title = stringResource(R.string.syclop_licenses_libass_title),
        body = stringResource(R.string.syclop_licenses_libass_body),
        url = LibassSourceUrl,
        noticeAssetPaths = listOf(
            "licenses/native-media/ATTRIBUTION.txt",
            "$LibassNoticeDirectory/ATTRIBUTION.md",
            "$LibassNoticeDirectory/edde-distribution/libass/COPYING",
            "$LibassNoticeDirectory/edde-distribution/freetype/LICENSE.TXT",
            "$LibassNoticeDirectory/edde-distribution/freetype/FTL.TXT",
            "$LibassNoticeDirectory/edde-distribution/freetype/GPLv2.TXT",
            "$LibassNoticeDirectory/edde-distribution/fribidi/AUTHORS",
            "$LibassNoticeDirectory/edde-distribution/fribidi/COPYING",
            "$LibassNoticeDirectory/edde-distribution/harfbuzz/AUTHORS",
            "$LibassNoticeDirectory/edde-distribution/harfbuzz/COPYING",
            "$LibassNoticeDirectory/harfbuzz-upstream/COPYING",
            "$LibassNoticeDirectory/libunibreak-upstream/LICENCE",
            "$LibassNoticeDirectory/edde-distribution/libunibreak/AUTHORS",
            "$LibassNoticeDirectory/edde-distribution/libunibreak/LICENSE.build",
            "$LibassNoticeDirectory/edde-distribution/fontconfig/AUTHORS",
            "$LibassNoticeDirectory/edde-distribution/fontconfig/COPYING",
            "$LibassNoticeDirectory/edde-distribution/expat/AUTHORS",
            "$LibassNoticeDirectory/edde-distribution/expat/COPYING",
            "$LibassNoticeDirectory/edde-distribution/expat/LICENSE.build"
        )
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
    ),
    LicenseAttributionItem(
        title = stringResource(R.string.syclop_licenses_sentry_native_title),
        body = stringResource(R.string.syclop_licenses_sentry_native_body),
        url = SentryNativeSourceUrl,
        noticeAssetPaths = listOf(
            "licenses/sentry-native-0.15.2/LICENSE",
            "licenses/sentry-native-0.15.2/libunwindstack-ndk/LICENSE",
            "licenses/sentry-native-0.15.2/mpack/LICENSE"
        )
    ),
    LicenseAttributionItem(
        title = stringResource(R.string.syclop_licenses_re2j_title),
        body = stringResource(R.string.syclop_licenses_re2j_body),
        url = "https://github.com/google/re2j/tree/re2j-1.8",
        noticeAssetPaths = listOf("licenses/re2j-1.8/LICENSE")
    )
)


/** Keeps bundled settings text readable with a TV remote. */
@Composable
internal fun SettingsTextDialog(title: String, text: String, onDismiss: () -> Unit) {
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
            Text(text, style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextPrimary)
        }
        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth().focusRequester(closeFocus)
                .focusProperties { up = textFocus }
        ) { Text(stringResource(R.string.action_close)) }
    }
}
