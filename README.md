# Syclop for Android TV

Syclop is an independent GPLv3 fork of [NuvioTV](https://github.com/NuvioMedia/NuvioTV), with its own app identity, local profiles, dark TV layout and native service-account setup. It is under development: **full Syncler feature parity and real-device account/playback verification are not complete**. Syclop does not include Syncler's proprietary code or backend. Third-party service subscriptions remain separate.

Start with the [build instructions](SYCLOP_BUILD.md), [feature audit and verification gaps](SYCLOP_FEATURE_AUDIT.md), [local backup scope](LOCAL_BACKUPS.md), [Trakt application setup](TRAKT_APP_SETUP.md), and [MyAnimeList return page](docs/mal/README.md). Public Syclop APK distribution is pending native-library corresponding-source verification; this repository currently publishes source and the account return page.

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
