<div align="center">
  <img src="docs/assets/syclop-primary-head.png" alt="Syclop mascot" width="112" />
  <h1>Syclop</h1>
  <p><strong>Your media. In focus.</strong></p>
  <p>A native Android TV media app with a clean interface, local profiles and your own connected service accounts.</p>
  <p><a href="https://dintalframe.github.io/Syclop/">Website</a> · <a href="SYCLOP_BUILD.md">Build guide</a> · <a href="SYCLOP_FEATURE_AUDIT.md">Project status</a> · <a href="https://github.com/dintalframe/Syclop/issues">Report a bug</a></p>
</div>

## Status

Syclop is in **development / private beta**. There is no approved stable release or public APK download yet. The source and setup documentation are available here; stable promotion is pending broader device testing, native 16 KB compatibility and the bundled-native source/notice audit.

The app targets **Android 7.0/API24 and newer**. Universal builds include arm64-v8a, armeabi-v7a, x86 and x86_64. That covers processor architectures, not every TV firmware or media format. Current real-device acceptance is on NVIDIA Shield Pro. The latest selected host test run passed **1,604 tests in 213 suites**; device checks cover startup, navigation, connected-account retention, restart/reboot and selected playback samples. See the [verification boundaries and known issues](SYCLOP_FEATURE_AUDIT.md).

## Features

- **TV-first browsing:** Home, Movies, TV, Anime, Documentary and Kids share a dark layout with large artwork and D-pad navigation.
- **Native accounts:** connect Real-Debrid, AllDebrid, Trakt, MyAnimeList and Simkl from the app. Each profile uses its own accounts. Additional integration coverage is recorded in the feature audit.
- **Connected-service playback:** local P2P is disabled; playback requires an authorized native connected-service link. Provider discovery alone does not authorize a media URL.
- **Source information:** progressive search results with empty-search diagnostics, confidence-labelled bitrate estimates, quality and file size; inspected track facts override conflicting release labels. Unknown values stay explicit.
- **Progress updates:** internal playback updates Continue Watching locally; confirmed Trakt acknowledgements retire account-scoped local overlays, with throttled activity refresh on return to Home. See [sync behavior and limits](SYCLOP_SOURCE_SYNC.md).
- **Episode sources:** reported season packs and multi-episode batches are hidden from episode searches before ranking and pagination; selected-file checks reject conflicting episode hints.
- **Player choices:** ExoPlayer is the recommended internal player. Optional MPV and compatible installed external players are available, including Just Player and VLC selection.
- **Playback controls:** visible Start from beginning and Next Episode actions, plus a confirmed Restart Syclop action in Settings → About. See [usage and verification](SYCLOP_PLAYBACK_CONTROLS.md).
- **Account initialization:** first use imports bounded connected history/lists with visible progress, Retry and Continue with saved data.
- **Providers:** supported declarative Express/Magnify and Stremio JSON configurations, with daily checks scheduled by Android. Executable remote plugins are disabled.
- **Local customization:** profiles, appearance/layout/player preferences and scoped portable backup/restore. The supplied dark mascot animates during full-screen loading and errors.

Kids is a rated browsing category, **not a parental lock**. Source availability, supported codecs and individual integration workflows vary; the audit distinguishes implemented features from verified behavior. Syclop does not include media or subscription access. Third-party accounts and subscriptions remain separate.

## Build Syclop

Use JDK17, Python3 and the Android SDK described in the [build guide](SYCLOP_BUILD.md).

```sh
git clone --branch syclop https://github.com/dintalframe/Syclop.git
cd Syclop
./build-syclop.sh --test --release
```

The script builds signed local arm64 and universal APKs into **`../dist/development/`**, with checksums and a public signing-certificate report. It creates **`../dist/stable/`** but never promotes builds automatically. It does not connect to or modify a TV. Keep the generated private signing files outside Git so future updates retain the same installation identity. A new signing key cannot update an APK signed by someone else.

For configured account sign-in, set Syclop's public application registrations as described in [application setup](SYCLOP_APP_REGISTRATION.md). Viewers authorize their own accounts; they do not need developer registrations. Never commit viewer credentials, authorization codes, private provider URLs or signing material.

## First run

1. Install the Syclop media-app APK on a supported Android TV device and complete the layout/profile setup. The system launcher does not need to change.
2. Connect the services you use in Settings.
3. Add your supported provider configurations.
4. Let the connected-account initialization finish, or continue with saved data if a service is unavailable.
5. Choose media you are authorized to access and verify picture, sound, subtitles and resume on your device.

Syclop has no central cloud account or cross-device settings sync. Tracker services synchronize their own supported data; local backups have an [explicit scope](LOCAL_BACKUPS.md).

## Documentation

- [Builds, signing, compatibility and release status](SYCLOP_BUILD.md)
- [Local Android TV emulator bench](tools/ANDROID_BENCH.md)
- [Experimental anime title-alias lookup (JavaScript prototype)](tools/anime-title-aliases/README.md)
- [Episode source filtering and selected-file checks](SYCLOP_EPISODE_SOURCES.md)
- [Progressive discovery, resolver outcomes and watch-progress synchronization](SYCLOP_SOURCE_SYNC.md)
- [Feature coverage, device evidence and known limitations](SYCLOP_FEATURE_AUDIT.md)
- [Restart, start from beginning and next-episode controls](SYCLOP_PLAYBACK_CONTROLS.md)
- [Application registration for connected services](SYCLOP_APP_REGISTRATION.md)
- [Trakt setup](TRAKT_APP_SETUP.md) and [MyAnimeList return page](docs/mal/README.md)
- [Local backup and recovery](LOCAL_BACKUPS.md)
- [Artwork provenance](SYCLOP_ARTWORK.md)

## Contributing

Development takes place on the **`syclop`** branch. Read [CONTRIBUTING.md](CONTRIBUTING.md), report reproducible issues and keep pull requests focused. Include device/Android version, build identity, steps and relevant validation. Redact account details, tokens and playable URLs from reports.

## License and origins

Syclop's application code is licensed under [GNU GPLv3](LICENSE). The project began from [NuvioTV](https://github.com/NuvioMedia/NuvioTV), baseline commit `b36db80835a79c09fef3675ea327e3f412841f23`, and retains its license and upstream attribution. Syclop has its own Android app identity (`com.dintal.syclop`), service registrations, branding and development direction. The existing Kotlin namespace is retained for compatibility.

Bundled dependencies retain their own notices and source requirements; see the [native-library build notes](SYCLOP_BUILD.md#bundled-native-binaries-and-source-limits) and [decoder NOTICE](ffmpeg-decoder-downmix/NOTICE.md). Supplied mascot artwork has separate [provenance](SYCLOP_ARTWORK.md); the code license does not silently relicense those assets.
