# Syclop source and local builds

Syclop is an independent fork of [NuvioTV](https://github.com/NuvioMedia/NuvioTV), started from branch `dev`, commit `b36db80835a79c09fef3675ea327e3f412841f23` on October 24, 2026. The upstream GPLv3 [LICENSE](LICENSE) and attributions remain present. The Kotlin namespace stays `com.nuvio.tv` for source/plugin compatibility; the independent Android application ID is `com.dintal.syclop` and the visible app name is Syclop.

## Standalone clone

```sh
git clone --branch syclop https://github.com/dintalframe/Syclop.git
cd Syclop
```

Use JDK 17, Python 3, Bash, `shasum`, and a normal Android SDK installation from Android Studio or the Android command-line tools. Set `JAVA_HOME` to the JDK and `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) to the SDK. Install platform `android-36` and build tools `36.0.0`, and accept the SDK licenses. The project uses NDK `29.0.14206865`; Gradle may download it into the SDK on the first build. The checked-in Gradle 8.13 wrapper downloads Gradle itself; a separate Gradle installation is unnecessary. The first build needs access to the configured dependency repositories.

```sh
export JAVA_HOME=/absolute/path/to/jdk-17
export ANDROID_HOME=/absolute/path/to/android-sdk
./build-syclop.sh
./build-syclop.sh --test
./build-syclop.sh --release
./build-syclop.sh --test --release
```

The sibling `.toolchain/env.sh` is optional. If it exists, the script sources it to reuse this development workspace's pinned toolchain. A standalone clone can use the normal JDK/SDK environment above without that file or the sibling `bootstrap.sh`. This environment-based path is implemented; a clean build on a separate machine has not yet been exercised. If SDK 36 or build tools are missing, the script can install them through `cmdline-tools/latest/bin/sdkmanager` or `sdkmanager` on `PATH`; otherwise install them in Android Studio first.

The script builds `fullDebug` by default or optimized, non-debuggable `fullRelease` with `--release`. It copies the arm64 APK, universal APK, SHA-256 record and public signing-certificate report to the sibling `../dist/development/` directory. It also creates `../dist/stable/`, but never promotes a build there automatically. Release artifacts use separate `syclop-*-release.apk` names, preserving debug artifacts. `--test` runs the selected local-mode, credential, debrid, tracking, Express, logging and backup safety suites. The script never installs on or connects to a device. Host compilation/tests do not establish device playback compatibility, account authentication, or measured performance.

The current Shield-installed optimized arm64 release is SHA-256 `0828d05e872ae806cd46a3e93af10455d435a260da557cfc9fdbcba40f45a228` (52,943,139 bytes). It passed 1,325 selected tests in 186 suites with zero failures/errors/skips, Android/Hilt compilation, lint and R8 packaging. Transfer MD5 `ccc5f7816cdf35c54321e70608132678`, the existing Syclop signing certificate and absence of `libnuvio_engine.so` were verified.

This release includes first-Home connected-account initialization, ownership-safe checkpoints, supplied dark startup/loading/error artwork, concise metadata errors and a warm-title-navigation correction. The selected test set includes initialization coordinator/read-gate/marker tests and Trakt, Simkl and MAL initialization reads. On the Shield, the import screen showed actual stages and a labelled partial Trakt snapshot before Home opened without Continue. Cold restart and device reboot retained Home, Continue Watching, Trakt/Simkl/MAL connections and Trakt source choices. Dark startup/error screens and a valid warm title lookup after an error passed. Source-search animation motion and consistent selected-file 3.4 GB / 18.6 Mbps fields were accepted on its preceding UI build. Unselected metadata remained unknown. Per-service final import counts, offline account completeness and all account-switch/failure flows were not individually device-verified.

Earlier category/public Simkl samples passed cold launch, sampled previews/card/detail-return navigation and attribution. Trakt/Simkl/MAL Connected and Trakt Library/Watch Progress/More Like This choices persisted through updates and app cold starts. Native Simkl authorization and manual sync completed. Frieren Season 2 episode 1 played smoothly with viewer-confirmed sound, Japanese audio and English subtitles; a newer same-file INFO check retained its exact identity and media facts. English dub, broader account lifecycle and general format/performance acceptance remain open. The separate installed ShieldHome dock/Netflix-button change has screenshot and viewer-reported physical acceptance, and its final reboot check passed.

Numbered sync-conflict source copies such as `HomeScreen 2.kt` are excluded from Kotlin and Java source sets; canonical filenames such as `HomeScreen2.kt` remain included. This avoids duplicate-class failures in synced workspaces. In this workspace, the original conflict copies were preserved outside the repository before rebuilding.

## Compatibility and release status

The current device-tested build is a **private beta candidate**, not a stable release across Android TV hardware. Its minimum is Android 7.0/API24. The universal APK includes arm64-v8a, armeabi-v7a, x86 and x86_64; architecture coverage alone does not establish fresh-install, firmware, low-memory or decoder compatibility. The same frozen arm64 build can be used for a controlled second-Shield test with the tester's own accounts. No owner account sessions, TV preferences or signing keys are packaged in that APK. The separate Shield launcher/debloat configuration is not part of this app test.

A compatibility audit found that `ACCESS_WIFI_STATE` implied a required Wi-Fi hardware feature; the source now explicitly makes Wi-Fi optional so Ethernet-only devices need not be excluded. This source fix is newer than the device-tested APK above. It does not require enabling debugging on the owner's Shield.

The inspected device-tested universal APK's ZIP alignment passes, but its arm64/x86_64 `libconscrypt_jni.so` and `libiamf.so` have 4 KB ELF load alignment. They need an upstream update or source rebuild and actual 16 KB runtime testing before claiming that compatibility. [Android's official guide](https://developer.android.com/guide/practices/page-sizes) distinguishes ELF compatibility from APK ZIP alignment. An official Conscrypt update is available; the bundled IAMF native source revision still needs establishing and rebuilding. No binary header patch or ZIP-only workaround has been applied.

Stable promotion also requires fresh-install/onboarding acceptance, a published supported-device/API matrix, representative playback and account lifecycle checks, and completion of the native source/notice audit. A compiler success or the word `release` in a Gradle variant does not promote a build to stable. Current Shield playback and integration limitations remain in the [feature audit](SYCLOP_FEATURE_AUDIT.md).

## Signing and public service registration

The first build creates a separate local signing key under the sibling `../.toolchain/syclop-private/` directory, outside the source repository. Its directory is mode 700 and key/password files are mode 600. Retain these private files to sign future compatible updates. They must not be published. Debug and release variants use this same local Syclop identity; the script explicitly disables CI's optional debug-signing override. A fresh clone that creates a different key cannot update somebody else's installed Syclop APK; it must use the original maintainer's key or a separate installation. No upstream signing key or cloud-service secret is needed.

Syclop's owner has registered its own public Trakt, MyAnimeList and Simkl applications. This workspace sets their public Client IDs and exact registered redirects in ignored `local.properties`:

```properties
TRAKT_CLIENT_ID=0nQc4G9CQfhwnM2g-8s1SsnLaTVO5CdN-qORYVpqKFg
TRAKT_REDIRECT_URI=https://github.com/dintalframe
MAL_CLIENT_ID=76daded66e7bb55596834840c1d73ebd
MAL_REDIRECT_URI=https://dintalframe.github.io/Syclop/mal/
SIMKL_CLIENT_ID=4e98516f315063630ed4ebf359c2faef8c13bf08a08f33f187826b5908accd5a
SIMKL_APP_NAME=syclop
```

These are public application identifiers, not viewer credentials. Maintainers can use the registered Syclop values for corresponding Syclop builds; a separately branded app should register its own identity. Viewers of a configured APK use the normal account connection flow and approve their own login; they do not create developer registrations. Advanced application setup is available for a maintainer override. No Trakt Client Secret was issued or is used. See [Trakt setup](TRAKT_APP_SETUP.md) and the [MyAnimeList return page](docs/mal/README.md).

Simkl uses AUTH V2 PIN/device-code sign-in without a client secret or redirect URL. Its public discovery also requires Syclop's application ID, but does not send a user's account tokens to the CDN. The owner registration is complete and the public catalog implementation is built and installed. Public Week/Today catalogs, a native anime detail with attribution and exact row return were checked on the Shield without a personal Simkl login. The native viewer authorization completed, Connected appeared and manual Sync now reported completion. Trakt/MAL connections and Trakt source selections were retained. Connected persisted through the newest cleanup update and app cold start. Token refresh/revoke and detailed sync correctness remain unverified. Keep `local.dev.properties` from overriding these values with another app's registration.

The fork starts in signed-out local mode, skips upstream account onboarding and keeps local profiles/add-ons/watch state in its own storage. Upstream account/sync transports are blocked before network execution, diagnostics have no upload DSN, and the upstream updater is disabled. The reserved `.invalid` URL only satisfies retained dependency constructors. Debrid services and user-selected add-ons retain their own network paths and account configuration. See [local backup scope and recovery](LOCAL_BACKUPS.md).

## Bundled native binaries and source limits

The source checkout tracks 15 native/bundled AARs in `app/libs/`: Media3 common/data-source/player/extractor/UI modules; AV1, FFmpeg, IAMF and MPEG-H decoders; `lib-nuvio-engine-android-0.1.6.aar`; `nextlib-mediainfo-local.aar`; `nuvio-libass-release.aar`; and `quickjs-kt-android-1.0.5-nuvio.aar`. Their baseline hashes, recorded from the pinned upstream checkout above, are in [app/libs/SHA256SUMS](app/libs/SHA256SUMS). Check them from the repository root with:

```sh
shasum -a 256 -c app/libs/SHA256SUMS
```

The FFmpeg downmix module includes source and its [NOTICE](ffmpeg-decoder-downmix/NOTICE.md); the bundled FFmpeg module also has upstream [build notes](app/libs/README.md). The hash manifest verifies binary identity against that checkout, not authorship, license completeness, or reproducibility from corresponding native source. Rebuilding every bundled binary from its corresponding source has not been established by this app build. Public Syclop APK distribution remains pending that verification; local compilation and a successful APK build do not resolve it.
