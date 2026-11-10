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
./build-syclop.sh --test --ui-tests
```

The sibling `.toolchain/env.sh` is optional. If it exists, the script sources it to reuse this development workspace's pinned toolchain. A standalone clone can use the normal JDK/SDK environment above without that file or the sibling `bootstrap.sh`. This environment-based path is implemented; a clean build on a separate machine has not yet been exercised. If SDK 36 or build tools are missing, the script can install them through `cmdline-tools/latest/bin/sdkmanager` or `sdkmanager` on `PATH`; otherwise install them in Android Studio first.

The script builds `fullDebug` by default or optimized, non-debuggable `fullRelease` with `--release`. It copies the arm64 APK, universal APK, SHA-256 record and public signing-certificate report to the sibling `../dist/development/` directory. It also creates `../dist/stable/`, but never promotes a build there automatically. Release artifacts use separate `syclop-*-release.apk` names, preserving debug artifacts. `--test` runs the selected local-mode, credential, debrid, tracking, Express, logging and backup safety suites. The script never installs on or connects to a device. Host compilation/tests do not establish device playback compatibility, account authentication, or measured performance.

For the isolated emulator fixtures, `--ui-tests` builds a debuggable app plus `syclop-ui-tests.apk` and includes all three APKs in the development checksum file. It cannot be combined with `--release`. Run the fixed controls suite with `tools/conda-bench.sh --serial emulator-5580 playback-controls`; see the [bench guide](tools/ANDROID_BENCH.md#playback-control-fixtures) for artifact checks, screenshots and test boundaries. Fixed `progressive-sources` and `continue-watching` commands also cover source focus/diagnostics and episode-progress cards; [source and sync behavior](SYCLOP_SOURCE_SYNC.md) records the corresponding limits.

The earlier Shield-tested optimized ARM64 release is SHA-256 `0828d05e872ae806cd46a3e93af10455d435a260da557cfc9fdbcba40f45a228` (52,943,139 bytes). It passed 1,325 selected tests in 186 suites with zero failures, errors or skips, Android/Hilt compilation, lint and R8 packaging. Transfer MD5 `ccc5f7816cdf35c54321e70608132678`, the existing Syclop signing certificate and absence of `libnuvio_engine.so` were verified.

This release includes first-Home connected-account initialization, ownership-safe checkpoints, supplied dark startup/loading/error artwork, concise metadata errors and a warm-title-navigation correction. The selected test set includes initialization coordinator/read-gate/marker tests and Trakt, Simkl and MAL initialization reads. On the Shield, the import screen showed actual stages and a labelled partial Trakt snapshot before Home opened without Continue. Cold restart and device reboot retained Home, Continue Watching, Trakt/Simkl/MAL connections and Trakt source choices. Dark startup/error screens and a valid warm title lookup after an error passed. Source-search animation motion and consistent selected-file 3.4 GB / 18.6 Mbps fields were accepted on its preceding UI build. Unselected metadata remained unknown. Per-service final import counts, offline account completeness and all account-switch/failure flows were not individually device-verified.

Earlier category/public Simkl samples passed cold launch, sampled previews/card/detail-return navigation and attribution. Trakt/Simkl/MAL Connected and Trakt Library/Watch Progress/More Like This choices persisted through updates and app cold starts. Native Simkl authorization and manual sync completed. Frieren Season 2 episode 1 played smoothly with viewer-confirmed sound, Japanese audio and English subtitles; a newer same-file INFO check retained its exact identity and media facts. English dub, broader account lifecycle and general format/performance acceptance remain open. The separate installed ShieldHome dock/Netflix-button change has screenshot and viewer-reported physical acceptance, and its final reboot check passed.

Numbered sync-conflict source copies such as `HomeScreen 2.kt` are excluded from Kotlin and Java source sets; canonical filenames such as `HomeScreen2.kt` remain included. This avoids duplicate-class failures in synced workspaces. In this workspace, the original conflict copies were preserved outside the repository before rebuilding.

## Compatibility and release status

Release assets record the exact signed artifact, app source and qualification status. The exact release qualification record identifies which candidate/runtime and public-updater checks passed. Building a `release` Gradle variant or reproducing native libraries does not establish stable or public-release readiness. See [current native inputs and qualification limits](SYCLOP_NATIVE_SOURCES.md#qualification-and-release-status) and the [historical feature audit](SYCLOP_FEATURE_AUDIT.md).

### Release notes shown on the TV

The full distribution shows the available release's notes before the user explicitly chooses **Update now** and confirms Android's installation prompt. After an upgrade, **What's new** shows the installed release's bundled notes once when Home is ready. Back or Close acknowledges that version across profiles and backup restore. A clean installation has no upgrade notice. The installed notice works offline and also covers an APK installed outside the app; the automatic-update banner setting does not hide it. Play Store builds retain the installed notice but do not download or install APK updates themselves.

The release workflow generates and reviews `release-notes.md` once, then uses the same file for the APK assets and GitHub release body. `release-notes.version` contains the exact `versionName` from that release's source metadata. Gradle copies both inputs into generated assets without editing tracked application files. Before publication, `scripts/verify_bundled_release_notes.py` checks both APKs against the reviewed notes and each APK's actual packaged version. Blank or pending notes fail verification.

Keep the final `release-notes.md` and `release-notes.version` with the matching source handoff; both are also downloadable release assets. For a local reproduction, place those exact files at the source root before running the normal release build. Do not regenerate them from a later commit range or edit them after candidate qualification. Verify a built APK with `python3 scripts/verify_bundled_release_notes.py --aapt /path/to/android-sdk/build-tools/VERSION/aapt --version VERSION_NAME --notes release-notes.md /path/to/syclop-release.apk`, replacing the paths and version with the candidate's actual values.

Development builds without these optional inputs have no installed-release popup. Mismatched input versions fail the build; missing or mismatched assets at runtime are ignored so they cannot describe another release.

The declared minimum is Android 7.0/API 24. Universal packaging includes `arm64-v8a`, `armeabi-v7a`, `x86` and `x86_64`; packaging an ABI does not verify that device's firmware, memory limits, codecs or account flows. Physical testing described above belongs to the identified historical Shield APKs. Their acceptance does not automatically transfer to the newly rebuilt native chain. The source makes Wi-Fi optional for Ethernet-only devices. Collection export requires Android10/API29 or later; older versions retain the existing failure status instead of accessing an unavailable platform API.

The current native preparation includes Conscrypt 2.8.0 and reproduced IAMF, MPEG-H, AV1, allocator, FFmpeg downmix, NextLib, MPV and libass inputs. ARM64 emulator fixtures cover selected normal media paths and 16 KiB pages. Other-ABI/API24 execution, representative hardware GPU/audio/passthrough, broader codecs, fresh-install/onboarding and connected-account lifecycle acceptance remain candidate-specific gates. [Native components, exact sources and source archive](SYCLOP_NATIVE_SOURCES.md)

Earlier artifacts contained some 4 KiB-aligned 64-bit native libraries. The current replacement groups have recorded 16 KiB alignment for rebuilt 64-bit/native inputs; 32-bit libass preserves its documented 4 KiB layout. The final APK still needs its own ELF/ZIP alignment, stripping, native-byte and notice-inventory verification. [Android page-size guidance](https://developer.android.com/guide/practices/page-sizes)

Conscrypt 2.8.0 is the official Maven input, with [upstream source](https://github.com/google/conscrypt/tree/7cff5d9ab2e4ed3f89957e34270b61d8a621c4b6) and its pinned BoringSSL source at [427ec40cc8edc545253289232e885708c409e5ea](https://github.com/google/boringssl/tree/427ec40cc8edc545253289232e885708c409e5ea). Its owned ARM64 loopback TLS 1.2/1.3 tests exchanged data and rejected an unrelated CA; they do not establish every production provider route. Sentry Native 0.15.2 and its [unwind dependency](https://github.com/getsentry/libunwindstack-ndk/tree/284202fb1e42dbeba6598e26ced2e1ec404eecd1) remain official imported components with exact bundled notices. Compiler/runtime provenance records and notices are included in the native-source handoff.

## Signing and public service registration

The first build creates a separate local signing key under the sibling `../.toolchain/syclop-private/` directory, outside the source repository. Its directory is mode 700 and key/password files are mode 600. Retain these private files to sign future compatible updates. They must not be published. Debug and release variants use this same local Syclop identity; the script explicitly disables CI's optional debug-signing override. A fresh clone that creates a different key cannot update somebody else's installed Syclop APK; it must use the original maintainer's key or a separate installation. No upstream signing key or cloud-service secret is needed.

`SYCLOP_SIGNING_HOME` may select an existing private signing directory outside Git using an absolute path when reusing the same identity across local checkouts. It must contain both `syclop-local.jks` and `signing.password`, or neither for initial creation. The bench verifies the Android test APK's certificate separately; a test APK does not replace the app's signing identity.

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

The fork starts in signed-out local mode, skips upstream account onboarding and keeps local profiles/add-ons/watch state in its own storage. Upstream account/sync transports are blocked before network execution and diagnostics have no upload DSN. Syclop's full distribution checks its own trusted release channel and requires explicit installation consent; Play Store self-installation is disabled. The reserved `.invalid` URL only satisfies retained dependency constructors. Debrid services and user-selected add-ons retain their own network paths and account configuration. See [local backup scope and recovery](LOCAL_BACKUPS.md).

## Bundled native binaries and source limits

The 0.1.1 preparation uses source-pinned replacements for the application-owned allocator JNI, audio-only FFmpeg downmix, NextLib, MPV and libass chains, together with the existing reproduced AV1/IAMF/MPEG-H inputs. QuickJS was removed from the disabled executable-plugin path and is not an intended packaged native input. Final signed-APK inventory must independently confirm its absence on every ABI.

The [native-source guide](SYCLOP_NATIVE_SOURCES.md) records exact versions/commits, licenses, source/archive contents, rebuild recipes, accepted compatibility decisions and remaining qualification gates. The prepared `syclop-native-sources-0.1.1.tar.gz` companion contains the selected upstream sources once, exact application JNI/wrapper source, patches, generated configurations, build records and notices. Its component/file manifests and verifier identify the prepared archive; the final source Git commit and published artifact identities must be supplied during release reconciliation. Preparation does not mean that an archive or APK is publicly published.

The libass and allocator replacements are completed build inputs, not the earlier experimental fixtures. MPV and libass reproduce for all four ABIs and have owned ARM64 normal-use regression receipts. The final app still needs candidate/device qualification, source-and-notice inventory reconciliation and public-updater acceptance.

The six maintained Media3 core AARs are built together from 33 preferred source units and the pinned public AndroidX source base in `third_party/media3-core/`. The included build, patch and comparison records cover all 1,830 classes; the README records the reviewed code-encoding and compiler-metadata differences. This maintained reconstruction replaces the imported custom binaries; it does not claim to recover their historical private build records. Preserve Apache-2.0 licenses, attribution and applicable notices. The GPL/LGPL native chains have their own corresponding-source and redistribution requirements, addressed by their selected sources and handoff.
