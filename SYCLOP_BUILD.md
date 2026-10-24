# Syclop source and local builds

Syclop is a personal fork of [NuvioTV](https://github.com/NuvioMedia/NuvioTV), started from branch `dev`, commit `b36db80835a79c09fef3675ea327e3f412841f23` on October 24, 2026. The upstream GPLv3 [LICENSE](LICENSE) and attributions remain present. The Kotlin namespace stays `com.nuvio.tv` for source/plugin compatibility; the independent Android application ID is `com.dintal.syclop` and the visible app name is Syclop.

Run `../bootstrap.sh` if the workspace toolchain is absent, then:

```sh
./build-syclop.sh
./build-syclop.sh --test
./build-syclop.sh --release
./build-syclop.sh --test --release
```

The script uses the checked-in Gradle 8.13 wrapper, JDK 17, Android SDK/build tools 36, and the pinned bundled native AARs. Gradle may install the configured NDK 29 into the workspace SDK. It builds `fullDebug` by default or optimized, non-debuggable `fullRelease` with `--release`, and copies an arm64 APK, universal APK, SHA-256 record, and public signing-certificate report to `../dist/`. Release artifacts use separate `syclop-*-release.apk` names, preserving debug artifacts. Both variants use the same local Syclop signing key so they can update this installation. It never installs on a device.

The first build creates a separate local signing key under `../.toolchain/syclop-private/`, outside the source repository. The directory is mode 700 and key/password files are mode 600. Retain these private files to sign future updates compatible with this installation. They are not included in build artifacts and must not be published. No upstream signing key or cloud service credential is required.

This fork starts in a signed-out local mode, skips upstream account onboarding, and keeps local profiles/add-ons/watch state in this app's own storage. Upstream account/sync transports are blocked before network execution, diagnostics have no upload DSN, and the upstream app updater is disabled. The inert reserved `.invalid` URL exists only to satisfy retained dependency constructors; it is not a server. Debrid services and user-selected add-ons retain their own network paths and account configuration.

Bundled native AARs originate from the pinned upstream checkout; their SHA-256 records are in `../.toolchain/nuvio-aar-sha256.txt`. The FFmpeg downmix module includes its source and [NOTICE](ffmpeg-decoder-downmix/NOTICE.md). Nuvio's engine and libass modules have separate upstream source repositories; rebuilding every bundled binary from corresponding sources has not been established by this app build.

App startup and playback on the Shield must be verified separately after a successful local build. Local compilation and unit tests do not establish playback compatibility, service authentication, or measured performance.

Syclop’s owner has now registered its own public Trakt application. The issued public Client ID and accepted HTTPS redirect are set in untracked `local.properties` for future builds. Viewer login still requires each viewer to approve the TV device code. See [Trakt application setup](TRAKT_APP_SETUP.md); no Client Secret was issued or is used.
