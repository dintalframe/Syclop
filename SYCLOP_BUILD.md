# Syclop source and local builds

Syclop is an independent fork of [NuvioTV](https://github.com/NuvioMedia/NuvioTV), started from branch `dev`, commit `b36db80835a79c09fef3675ea327e3f412841f23` on October 24, 2026. The upstream GPLv3 [LICENSE](LICENSE) and attributions remain present. The Kotlin namespace stays `com.nuvio.tv` for source/plugin compatibility; the independent Android application ID is `com.dintal.syclop` and the visible app name is Syclop.

## Standalone clone

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

The script builds `fullDebug` by default or optimized, non-debuggable `fullRelease` with `--release`. It copies the arm64 APK, universal APK, SHA-256 record and public signing-certificate report to the sibling `../dist/` directory. Release artifacts use separate `syclop-*-release.apk` names, preserving debug artifacts. `--test` runs the selected local-mode, credential, debrid, tracking, Express, logging and backup safety suites. The script never installs on or connects to a device. Host compilation/tests do not establish device playback compatibility, account authentication, or measured performance.

## Signing and public service registration

The first build creates a separate local signing key under the sibling `../.toolchain/syclop-private/` directory, outside the source repository. Its directory is mode 700 and key/password files are mode 600. Retain these private files to sign future compatible updates. They must not be published. Debug and release variants use this same local Syclop identity; the script explicitly disables CI's optional debug-signing override. A fresh clone that creates a different key cannot update somebody else's installed Syclop APK; it must use the original maintainer's key or a separate installation. No upstream signing key or cloud-service secret is needed.

Syclop's owner has registered its own public Trakt and MyAnimeList applications. This workspace sets their public Client IDs and exact registered HTTPS redirects in ignored `local.properties`:

```properties
TRAKT_CLIENT_ID=0nQc4G9CQfhwnM2g-8s1SsnLaTVO5CdN-qORYVpqKFg
TRAKT_REDIRECT_URI=https://github.com/dintalframe
MAL_CLIENT_ID=76daded66e7bb55596834840c1d73ebd
MAL_REDIRECT_URI=https://dintalframe.github.io/Syclop/mal/
```

These are public application identifiers, not viewer credentials. Maintainers can use the registered Syclop values for corresponding Syclop builds; a separately branded app should register its own identity. Viewers of a configured APK use the normal account connection flow and approve their own login; they do not create developer registrations. Advanced application setup is available for a maintainer override. No Trakt Client Secret was issued or is used. See [Trakt setup](TRAKT_APP_SETUP.md) and the [MyAnimeList return page](docs/mal/README.md).

The fork starts in signed-out local mode, skips upstream account onboarding and keeps local profiles/add-ons/watch state in its own storage. Upstream account/sync transports are blocked before network execution, diagnostics have no upload DSN, and the upstream updater is disabled. The reserved `.invalid` URL only satisfies retained dependency constructors. Debrid services and user-selected add-ons retain their own network paths and account configuration. See [local backup scope and recovery](LOCAL_BACKUPS.md).

## Bundled native binaries and source limits

The source checkout tracks 15 native/bundled AARs in `app/libs/`: Media3 common/data-source/player/extractor/UI modules; AV1, FFmpeg, IAMF and MPEG-H decoders; `lib-nuvio-engine-android-0.1.6.aar`; `nextlib-mediainfo-local.aar`; `nuvio-libass-release.aar`; and `quickjs-kt-android-1.0.5-nuvio.aar`. Their baseline hashes, recorded from the pinned upstream checkout above, are in [app/libs/SHA256SUMS](app/libs/SHA256SUMS). Check them from the repository root with:

```sh
shasum -a 256 -c app/libs/SHA256SUMS
```

The FFmpeg downmix module includes source and its [NOTICE](ffmpeg-decoder-downmix/NOTICE.md); the bundled FFmpeg module also has upstream [build notes](app/libs/README.md). The hash manifest verifies binary identity against that checkout, not authorship, license completeness, or reproducibility from corresponding native source. Rebuilding every bundled binary from its corresponding source has not been established by this app build. Public Syclop APK distribution remains pending that verification; local compilation and a successful APK build do not resolve it.
