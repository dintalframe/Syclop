# Syclop for Android TV

Syclop is an independent GPLv3 fork of [NuvioTV](https://github.com/NuvioMedia/NuvioTV), with its own app identity, local profiles, dark TV layout and native service-account setup. It is under development: **full Syncler feature parity and real-device account/playback verification are not complete**. Syclop does not include Syncler's proprietary code or backend. Third-party service subscriptions remain separate.

Start with the [build instructions](SYCLOP_BUILD.md), [feature audit and verification gaps](SYCLOP_FEATURE_AUDIT.md), [local backup scope](LOCAL_BACKUPS.md), [Trakt application setup](TRAKT_APP_SETUP.md), and [MyAnimeList return page](docs/mal/README.md). Public Syclop APK distribution is pending native-library corresponding-source verification; this repository currently publishes source and the account return page.

The current Shield-installed optimized arm64 release is SHA-256 `0828d05e872ae806cd46a3e93af10455d435a260da557cfc9fdbcba40f45a228`. It passed 1,325 selected tests in 186 suites with zero failures/errors/skips, Android/Hilt compilation, lint and R8 packaging. Transfer MD5, the original signing certificate and absence of the local P2P engine were verified. The supplied transparent dark mascot is used for startup, full-screen loading and errors, with concise error text and Retry.

On first use of each connected tracking account, Home waits for bounded history/list imports and shows actual service stages and entry counts. Failed or timed-out imports offer Retry or Continue with saved data and are not marked complete. Successful account/profile-specific markers avoid repeating the blocking import on every launch; later launches warm account libraries in the background. This is an initialization checkpoint, not a complete offline backup. The Shield displayed a partial Trakt snapshot and active Simkl import, then entered Home without Continue. Cold app restart and device reboot retained Home/Continue Watching and Trakt, Simkl and MyAnimeList connections. Trakt stayed selected for Library, Watch Progress and More Like This. Final per-service entry counts, all account-change cases and offline completeness were not individually verified on the device.

The dark source-search animation was captured in different frames. Startup artwork, the concise failed-title screen and opening a new valid title from that error were inspected on the TV. The sampled Frieren file displayed an estimated whole-file average of 18.6 Mbps and 3.4 GB consistently in Sources and INFO, while an unselected file stayed unknown. Earlier category/public-Simkl and playback samples remain scoped below.

The native MAL path uses exact public episode mappings and a trusted title identity before a provider result or selected Debrid file can be accepted. On the prior `7f125de1…` Shield release, Frieren's separate MAL Season 2 entry (`mal:59978`, relative episode 1) returned matching absolute episode 29 / S02E01 releases. A selected file decoded in ExoPlayer: HEVC 1920×1080, E-AC-3 stereo, Japanese dialogue and embedded English subtitles. The viewer confirmed smooth picture and sound. English dub remains preferred but was not verified in this sample. This bounded mapping does not establish complete cross-catalog anime identity.

The new category screens reuse the Modern Home hero and landscape rows. Simkl public Anime/Movie/TV/Documentary discovery is available alongside MyAnimeList without a viewer account; Simkl account synchronization still requires a separate viewer approval. Kids uses explicit US G/TV-Y/TV-Y7/TV-Y7-FV/TV-G certificates and narrow genre evidence, excluding unknown or advisory ratings. This is catalog filtering, not a PIN-protected restricted profile. Trakt remains the selected Library/progress service. On `895af20f…`, the earlier Movie-tab backdrop/header defect was fixed: the correct preview appears before Down, and card Right/Up preserves the selected preview. Sampled TV/Anime/Documentary/Kids tabs used their own artwork; a TV-Y education detail returned to its exact Kids row. Safe Science/Nature/Documentary feeds can be empty and may not render; ratings are not relaxed to fill them. Simkl Week/Today rows, a native anime detail with View on Simkl, and return to the original row were checked. Simkl viewer authorization completed and Connected appeared alongside retained Trakt/MAL connections. Manual Sync now reported completion. Library, Watch Progress and More Like This remained set to Trakt; Simkl refresh, revoke and detailed sync correctness remain unverified. The newer cleanup displays plain-text Simkl overviews and clean Kids group/source labels. A controlled Anime detail/Back returned to its exact card; an earlier uncontrolled navigation sample was excluded rather than treated as a defect.

When sorting by bitrate, native selected-file averages come first, clearly labelled reported values next, then unknown. These averages include audio/overhead; they are not measured video-track bitrate or connection speed. The installed in-player Sources update reuses exact cached catalog runtime and native file facts for display, with no extra service resolution or unselected-file network probes; the in-player Sources check showed the exact playing file at an estimated 18.6 Mbps while an unselected file remained unknown. Movie reported-metadata parity still lacks a current ready-file sample. Reported-size/runtime averages are limited to conservative single-movie matches. Automatic defaults break ties by resolution and file size; explicit Original/custom choices remain available.

The source list filters for native Debrid eligibility and removes recognized seeder-count presentation fields. Eligible provider/hash metadata can still be submitted to the connected service for server-side resolution; its presence does not establish cache or playback availability. Local P2P remains disabled, and only a private native service grant can authorize a resolved file. Exo is the recommended default; compatible installed external players are supported. Just Player was detected on the Shield, while VLC support does not install VLC. The viewer retained System default for external playback.

Provider checks use a persisted network-constrained 24-hour Android job with a 90-minute flex window; Android may defer it. Job configuration and manual Cinemeta/OpenSubtitles/Magnify checks were verified on the Shield, preserving enabled state/order; Magnify Full reported 33 definitions. An elapsed daily automatic run and offline restart behavior remain unverified.

A separate 692 MB AVC MKV played smoothly through native AllDebrid and ExoPlayer, with viewer-confirmed picture and sound. A larger M2TS failed in Exo with `ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED`; explicit MPV playback showed software VC-1 decoding and stutter. The bundled FFmpeg lacks a VC-1/WMV3 MediaCodec wrapper, so an allowlist change cannot supply that missing bridge. These sample outcomes do not establish general format or performance acceptance.

For a selected neutral hash source, fallback tries the preferred connected service and then other connected services once each, without changing the preference. AllDebrid processing followed by a Real-Debrid attempt was observed. Provider-bound signed/hoster contracts remain bound. Broader live failure/cancellation/account-switch acceptance remains open.

The viewer approved Syclop as the first launcher dock item and Netflix-button target. The separate launcher is installed with the approved Syclop, YouTube, Disney+ and Apple TV order. The viewer confirmed the physical Netflix button, Home/Back and continuous held-volume behavior. Its final reboot check passed; Syncler remains installed as a backup. Public APK distribution remains pending native-library corresponding-source verification.

The original Nuvio project information and attribution follow. Those download links install upstream Nuvio, whose package and services differ from Syclop.

<div align="center">

  <img src="assets/brand/app_logo_wordmark.png" alt="Nuvio" width="300" />

  <p>
    A free, open-source media app for your phone, your desktop, and the TV you already own.
    <br />
    Bring your own sources. Nuvio turns them into a library with artwork, ratings, subtitles, and your place saved on every screen.
  </p>

  [Website](https://nuvio.tv) · [GitHub releases](https://github.com/NuvioMedia/NuvioTV/releases/latest) · [Support Nuvio](https://nuvio.tv/support)

</div>

## Get Nuvio TV

- [Android TV on Google Play](https://play.google.com/store/apps/details?id=com.nuvio.app)
- [Android TV APK](https://github.com/NuvioMedia/NuvioTV/releases/latest)

## Build from source

```bash
git clone https://github.com/NuvioMedia/NuvioTV.git
cd NuvioTV
./gradlew :app:assembleFullDebug
```

Nuvio TV is built with Kotlin, Jetpack Compose, TV Material 3, and Media3. Development requires Android Studio, a JDK, and the Android SDK.

## License

[GNU General Public License v3.0](./LICENSE)
