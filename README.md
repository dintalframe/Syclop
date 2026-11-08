<div align="center">
  <img src="docs/assets/syclop-primary-head.png" alt="Syclop mascot" width="112" />
  <h1>Syclop</h1>
  <p>An independent Android TV media app for your own library and connected services.</p>
  <p><a href="https://dintalframe.github.io/Syclop/">Website</a> · <a href="https://github.com/dintalframe/Syclop/releases">Releases</a> · <a href="SYCLOP_BUILD.md">Build guide</a> · <a href="https://github.com/dintalframe/Syclop/issues">Issues</a></p>
</div>

Syclop is built for the TV remote. Browse and play media from sources you configure, use local profiles, and keep your place with Continue Watching. Bring a media source you are authorized to use and any accounts or subscriptions it requires.

## Availability

Syclop is in active development. Release assets carry their exact artifact identity and qualification status; check the [Releases page](https://github.com/dintalframe/Syclop/releases) for available builds and their stated scope. You can [build from source](SYCLOP_BUILD.md); [native source preparation and qualification limits](SYCLOP_NATIVE_SOURCES.md) are documented separately.

The declared minimum is Android 7.0 (API 24). The universal APK packages ARM64 (`arm64-v8a`), ARMv7 (`armeabi-v7a`), `x86`, and `x86_64`. Development runtime testing has focused on ARM64, including NVIDIA Shield TV and Android TV emulators. Device firmware, connected services, and media formats can affect playback. See the [project status and verification audit](SYCLOP_FEATURE_AUDIT.md).

## Set up your TV

1. Install a signed Syclop APK using Android’s installer, then open Syclop and complete the first-run setup.
2. Connect an authorized media source. On the public source branch, open **Settings → Content & Discovery** for add-ons and media servers, or **Settings → Integrations → Connected Services** for Debrid.
3. To sync supported library or watch-progress data, connect a tracker under **Settings → Tracking**. Tracker accounts are optional.

The Settings redesign under development groups these connection options under **Settings → Connected Services**.

Syclop uses local profiles. Connected services manage their own accounts and supported sync data. See the [local backup guide](LOCAL_BACKUPS.md) for what a portable backup includes.

## Build from source

Use JDK 17, Python 3, and the Android SDK described in the [build guide](SYCLOP_BUILD.md).

```sh
git clone --branch syclop https://github.com/dintalframe/Syclop.git
cd Syclop
./build-syclop.sh --test --release
```

The script produces signed local ARM64 and universal development APKs with checksums. Android updates require the same signing key as the installed app. See [application registration](SYCLOP_APP_REGISTRATION.md) if a source build needs service sign-in configured.

## Support and contributions

Use [GitHub Issues](https://github.com/dintalframe/Syclop/issues) for reproducible bugs and feature requests. Include the build version, device model, Android version, and steps to reproduce. Redact account details, tokens, private provider URLs, and playable links. See [Contributing](CONTRIBUTING.md) before opening a pull request.

## Credits and licenses

Syclop is an independent project derived from [NuvioTV](https://github.com/NuvioMedia/NuvioTV) at commit `b36db80835a79c09fef3675ea327e3f412841f23`. The application code is licensed under [GNU GPLv3](LICENSE), with upstream copyright and attribution retained.

Bundled dependencies retain their own licenses and notices. See the [native-library notes](SYCLOP_BUILD.md#bundled-native-binaries-and-source-limits), [decoder notice](ffmpeg-decoder-downmix/NOTICE.md), and [artwork provenance](SYCLOP_ARTWORK.md).
