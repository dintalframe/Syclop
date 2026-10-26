# Android TV emulator bench

The bench builds development APKs and tests a local Android TV emulator. It requires Python 3, an existing Android SDK with command-line tools/latest, and the Java/build prerequisites used by `build-syclop.sh`. It never connects to a LAN address or targets the Shield. Real-device, decoder, HDR, HDMI/audio and remote-control acceptance remain separate checks.

Run commands from the `media-client` checkout. Global options must appear before the subcommand.

```sh
export ANDROID_HOME="/absolute/path/to/android-sdk"
export SYCLOP_BENCH_HOME="$HOME/Library/Caches/Syclop/android-bench"
python3 tools/android-bench.py --serial emulator-5580 status
```

`--sdk` can override `ANDROID_HOME`/`ANDROID_SDK_ROOT`; `--home` overrides `SYCLOP_BENCH_HOME`. The macOS default is `~/Library/Caches/Syclop/android-bench`; Linux uses `$XDG_CACHE_HOME/syclop/android-bench` or `~/.cache/syclop/android-bench`. Keep mutable AVDs and reports outside synced folders. The source checkout, Desktop and known Apple cloud roots are rejected, as are escaping AVD/report symlinks. Existing root permissions are preserved.

On macOS, also keep the source checkout and build JDK/SDK in ordinary local directories outside Desktop/iCloud and other cloud-managed folders. Source or toolchain inputs can be evicted during compilation even when AVD data and build caches are stored elsewhere. Use a verified local checkout and intact toolchain copies; the bench does not migrate them automatically.

The SDK can remain in a separate configured directory. `--emulator /absolute/path/to/emulator` or `SYCLOP_EMULATOR` optionally selects a separately installed emulator bundle; its selected system-image directory still comes from the SDK. Such an override is not updated by SDK `setup` automatically. The bench does not bootstrap command-line tools or accept SDK licenses silently.

## Miniconda environment

Use an [official Miniconda installer](https://repo.anaconda.com/miniconda/) for your host. The portable recipe is [`conda/environment.yml`](conda/environment.yml). It selects Python 3.12 from conda-forge, excludes default channels and disables user-site packages. The bench needs no pip packages. Java, the Android SDK, the native emulator and AVD data stay in their existing locations.

On this Mac, Miniconda is installed separately at `~/Library/Developer/Syclop/miniconda3`, with the environment under `envs/syclop-bench`. Existing Anaconda and global shell initialization are unchanged. To recreate the environment from the checkout:

```sh
export SYCLOP_MINICONDA_HOME="$HOME/Library/Developer/Syclop/miniconda3"
"$SYCLOP_MINICONDA_HOME/bin/conda" env create \
  --prefix "$SYCLOP_MINICONDA_HOME/envs/syclop-bench" \
  --file tools/conda/environment.yml --no-default-packages
```

Store machine-specific SDK/JDK settings locally in this environment, using your own installed paths:

```sh
"$SYCLOP_MINICONDA_HOME/bin/conda" env config vars set \
  --prefix "$SYCLOP_MINICONDA_HOME/envs/syclop-bench" \
  JAVA_HOME="/absolute/path/to/jdk-17" \
  ANDROID_HOME="/absolute/path/to/android-sdk" \
  ANDROID_SDK_ROOT="/absolute/path/to/android-sdk"
```

This Mac also has `SYCLOP_EMULATOR` set to its separate emulator executable and `SYCLOP_BENCH_HOME` set to its local bench cache. These local values are not embedded in the portable recipe. Do not set global ADB routing variables; the bench manages its own isolated server.

The wrapper runs the selected environment without changing your current shell or requiring `conda init`:

```sh
./tools/conda-bench.sh --serial emulator-5580 status
./tools/conda-bench.sh --serial emulator-5580 start
./tools/conda-bench.sh --serial emulator-5580 smoke
```

It forwards the same options and subcommands as `android-bench.py`. For another installation, set `SYCLOP_MINICONDA_HOME`; `SYCLOP_CONDA_PREFIX` can select a different existing environment prefix. On Linux the default Miniconda root is `~/miniconda3`.

For an interactive development shell:

```sh
source "$SYCLOP_MINICONDA_HOME/etc/profile.d/conda.sh"
conda activate "$SYCLOP_MINICONDA_HOME/envs/syclop-bench"
python -m unittest discover -s tools -p test_android_bench.py -v
python tools/android-bench.py --serial emulator-5580 status
conda deactivate
```

## Isolated emulator connection

Every device action requires an explicit `emulator-NNNN` serial with an even console port in Android Emulator's supported range. The primary uses console port 5580, transport port 5581 and serial `emulator-5580`. TCP addresses, physical serials and implicit device selection are rejected. An online target must also report a QEMU emulator property before installation, capture or navigation.

A separate ADB server listens on loopback port **5038**. Default physical-device server port 5037 is refused. Server startup uses `adb -L tcp:localhost:5038 start-server` with inherited ADB routing cleared; subsequent device calls use explicit `-H 127.0.0.1 -P 5038 -s emulator-5580`. The emulator receives the same server/socket environment and mDNS auto-connect is disabled. No command scans, enumerates, connects to, reboots or closes the real Shield's connection. `stop` leaves this isolated ADB server running and preserves AVD data.

If using an emulator started elsewhere, start it against this isolated server and select its exact serial. A device registered only with server 5037 will not be found automatically.

## Setup and create, once

The primary preset is API 34 Android TV ARM64:

```sh
python3 tools/android-bench.py setup
python3 tools/android-bench.py create
```

`setup` downloads only `platform-tools`, `emulator` and the explicitly selected image `system-images;android-34;android-tv;arm64-v8a`; it never starts a device. `create` makes `syclop-tv34-arm64` under the isolated AVD home and refuses to overwrite an existing directory or index. It uses the `tv_720p` device profile, 1280×720, density 213, 2048 MB RAM, two cores, a 1536 MB data partition, no SD card and cold boot. At least 3 GiB must remain free before creation. Image installation itself requires additional space.

`--device` accepts the exact TV identifier offered by the selected SDK's `avdmanager list device`. `--image` and `--avd` support an explicitly chosen host-compatible image and a `syclop-…` name. ARM hosts reject x86 image setup; other hosts must select a supported image rather than assume ARM acceleration exists.

A second **prepared, opt-in** preset is `--profile tv36-16k-arm64`, selecting `system-images;android-36;google-tv-ps16k;arm64-v8a` and `syclop-tv36-16k-arm64`. It is not downloaded by primary setup. Keep one initial AVD on machines with limited disk space; a 16 KiB image does not establish native-library compatibility by itself.

## Start and prepare the fixture

```sh
python3 tools/android-bench.py --serial emulator-5580 start
```

Start disables snapshots, the boot animation, emulator metrics and crash reporting. CPU virtualization is retained; guest graphics default to `--gpu software` for reliable UI checks. Use `--gpu auto` or `--gpu host` only when the local graphics backend is known to work. Software graphics cannot establish real-device rendering or playback performance. Audio remains enabled for manual playback checks. `start --headless` hides the emulator window. `--boot-timeout 240` sets the boot deadline; valid limits are 30–600 seconds. A timeout does not delete data or silently stop a still-running emulator. The bench also works with an AVD created manually in the same isolated AVD home. Android user state, emulator state and startup logs are scoped to this bench cache. Each launch prints its private startup-log path; logs may contain local paths and emulator public keys, so keep them private.

Build and install are separate commands:

```sh
python3 tools/android-bench.py build --test
python3 tools/android-bench.py --serial emulator-5580 install
```

`build` invokes `build-syclop.sh --release`; `--test` adds its selected unit checks. `build --ui-tests` instead builds a debuggable development app and Android test APK for the fixed fixtures below. Neither build starts or installs to any device. Build output remains in the terminal and is not copied into bench reports. The default install artifact is the universal `../dist/development/syclop-release.apk`. To test the ARM64 development artifact explicitly:

```sh
python3 tools/android-bench.py --serial emulator-5580 install --apk ../dist/development/syclop-arm64-release.apk
```

Installation requires an existing APK inside `dist/development`, the Syclop package identity and a compatible minimum SDK. There is no fallback to legacy or stable artifacts. It uses `install -r` and never clears app data. The SDK's configured build-tools/36.0.0 `aapt2` verifies the manifest.

On a fresh install, complete **Choose Syclop Experience**, any profile selection and other one-time setup manually in the emulator. For the six-tab Home fixture, choose the Advanced experience. The bench does not click through setup, approve login/permissions, enter credentials or choose service preferences. Account-free UI checks can use an account-free fixture. If import/setup blocks Home, complete the intended setup manually and rerun smoke.

## Playback-control fixtures

```sh
./tools/conda-bench.sh build --test --ui-tests
./tools/conda-bench.sh --serial emulator-5580 playback-controls
```

The build exports `syclop-debug.apk`, `syclop-arm64-debug.apk` and `syclop-ui-tests.apk` to `../dist/development`, with checksums. To reuse an existing private signing identity across local checkouts, set `SYCLOP_SIGNING_HOME` to its private directory containing `syclop-local.jks` and `signing.password`. Keep that directory outside Git. Without an override the existing `../.toolchain/syclop-private` location is used. A new signing identity cannot update an already installed app signed with another key. `--ui-tests` cannot be combined with `--release`.

`playback-controls` verifies both signatures and scoped manifest declarations before installation. Both packages must be debuggable, have the exact app/test identities and test runner/target, and match the emulator's SDK and native ABI. The app and test certificates may differ, as supported by Android's debuggable instrumentation. `--apk` and `--test-apk` accept only files resolving beneath `dist/development`; the ARM64 app can be selected to reduce transfer size. Installation uses `-r -t` and preserves app data. The target must be an explicitly selected, QEMU-verified emulator on the isolated ADB server.

Only the three fixed playback-controls test classes run: eleven synthetic, account-free cases covering visible Resume/Start actions, internal-player action availability and restart confirmation. There are no arbitrary class, runner, shell or instrumentation-argument options. `--test-timeout` accepts 60–600 seconds (default 300). The command deletes only the six fixed screenshots generated by those fixtures before running, so earlier captures cannot supply a passing result. It requires the complete test-event protocol and six fresh PNGs with valid structure and checksums. Raw instrumentation output is not persisted in the sanitized JSON report.

Reports and screenshots remain private in the bench cache. An automated pass records that **visual inspection is still required**; inspect all six images before accepting appearance or focus. These component tests exercise real controls with synthetic state. They do not authorize an account, search providers, establish live Debrid playback or verify an external player's behavior. The source and fixture build must correspond; this command does not export artifacts or promote stable builds.

## Category-navigation fixtures

```sh
./tools/conda-bench.sh build --test --ui-tests
./tools/conda-bench.sh --serial emulator-5580 category-navigation
```

This separate fixed suite uses the same signed development app/test APK preflight, emulator-only routing and strict instrumentation protocol as `playback-controls`. It accepts the same artifact overrides and bounded `--test-timeout`, with no arbitrary class, runner or argument options. Four synthetic cases exercise the production category presentation, content entry, Home scaffold and Modern rows: delayed Anime catalogs, delayed Kids groups, category changes and intentional catalog-order resets. D-pad input and assertions verify the focused card and one-Up return to the correct header.

The fixture supplies in-memory metadata with no artwork URLs, provider search, account setup or playback. It removes only its four fixed earlier images before running, requires all four test events and fresh valid PNGs, and leaves app data intact. Inspect all four images after a pass; visual acceptance is recorded separately. These deterministic cases complement the live-data Home smoke. They cannot establish network timing, catalog accuracy or freedom from the intermittent Android 14 accessibility crash.

## Capture and smoke

```sh
python3 tools/android-bench.py --serial emulator-5580 status
python3 tools/android-bench.py --serial emulator-5580 smoke
python3 tools/android-bench.py --serial emulator-5580 stop
```

`status` captures the current emulator display and reports API, ABI and actual page size. `smoke` cold-launches Syclop without clearing its data, waits up to 90 seconds for the six English header labels, then:

1. Verifies the geometric Home/Movies/TV/Anime/Documentary/Kids header and acquires its actual keyboard focus.
2. Normalizes to Home and moves right exactly one tab at a time.
3. Captures each tab preview, verifies Down leaves that header, captures the entry and verifies Up returns to the same tab. While content loads, Down is retried only while the original header remains focused, for at most 20 seconds per category.
4. Checks the right boundary at Kids and verifies each Left transition back to Home. Home intentionally restores its saved tile focus, so one Up normalizes that state back to its header before the final screenshot.

Expected header focus is polled for up to ten seconds after a transition; waiting never sends extra keys. This avoids treating an in-flight accessibility update as a lost key. Only directional D-pad keys are injected. There is no Center/Enter, title selection, playback start, login approval, keyboard text, account/history write or settings mutation. A foreground-app check prevents navigation after Syclop loses focus. During startup, losing focus after Syclop was already observed also fails explicitly, even if a background process survives. Missing labels, ambiguous focus, failed Down/Up entry or unexpected focus order fail the report. Localized labels and unavailable accessibility semantics require a human check; the script does not report them as passed. `smoke --ui-timeout 90` accepts 15–180 seconds for initial UI readiness.

The smoke verifies navigation, not correct catalog data, artwork, classification, account import, media quality or playback. Inspect the screenshots; a green JSON report alone does not establish visual correctness. Empty categories still need a focusable content/empty-state entry to pass Down/Up checks. A late catalog inserted above the focused item can make the one-Up return fail. Preserve that failure as evidence of loading-time focus behavior; a later warm-cache pass does not erase it.

Failures after emulator verification also attempt a `failure.png` capture. Reports and PNGs are local under `<bench-home>/reports/<UTC-command>/`. JSON contains fixed step labels, emulator facts, APK basename/hash and pass/failure status. Raw logcat, accessibility XML, command output, account names, service URLs, device codes and environment values are not retained in JSON. Separate emulator startup logs stay local under `<bench-home>/logs/`. **Screenshots can show personal content or an account/QR screen already on display. Keep reports private and inspect/redact images before sharing.** No report is uploaded automatically.

## Stop, reset and pure tests

`stop` requests shutdown only for the explicitly selected, QEMU-verified emulator and preserves its data. No app force-clear, AVD wipe or deletion occurs automatically.

To delete an AVD originally created by this bench, stop it first, then explicitly request:

```sh
python3 tools/android-bench.py --serial emulator-5580 reset --confirm-reset
```

Reset requires matching name/home/image/directory ownership metadata, a matching AVD index, an offline selected serial and no active or stale AVD lock. A manually created AVD has no bench ownership marker and cannot be reset by this script. There is no automatic adoption, forced lock removal or arbitrary AVD deletion.

Pure safety/parser tests run without the SDK, network, build or emulator:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tools -p test_android_bench.py -v
```

## Local setup troubleshooting

On macOS, if Qt says the Cocoa platform plugin is missing even though its bundled file exists, inspect the SDK plugin directory with `ls -lO`. In this setup, the macOS `hidden` flag caused Qt to skip the files; removing only that flag from the affected SDK plugin tree restored discovery. If the sync service reapplies it, keep an intact emulator bundle outside the synced tree and select its executable with `SYCLOP_EMULATOR`; verify the copied files and retain the SDK source. Do not mix bundled Qt libraries with unrelated installations. A separate host-graphics crash was avoided with the default software renderer. Headless mode remains available for automated screenshots.

## Release coverage still needed

This account-free API 34 bench is a repeatable UI fixture. Before stable promotion, retain real-device playback/audio/remote checks, test the API 24 support floor on suitable hardware or a compatible host, and run the opt-in API 36 image to verify its actual 16 KiB page size and native-library loading. Account refresh/revocation, upgrades, network loss and external-player handoff need their own acceptance scenarios. A successful bench run does not establish these results.

## Initial local acceptance — 25 October 2026

The Apple Silicon bench booted API 34 Android TV ARM64 with hardware CPU virtualization, software guest graphics, 1280×720 output and a verified 4096-byte page size. The signed development APK installed fresh, Advanced/Modern setup completed, and account-free catalogs loaded. A navigation run captured all six categories and verified their Down/Up entry checks. The 32 pure bench-tool tests pass.

**The full UI smoke suite had not passed at this initial stage.** Preserve the failed reports: a late anime catalog prepended above the focused row interrupted the one-Up return, and another cold-launch inspection encountered a `NullPointerException` in Android `ViewRootImpl.getAccessibilityFocusedRect`. These need investigation before treating API 34 UI automation as a release gate. Home restoring its saved tile focus is expected behavior and is handled separately by the harness. No account data was imported into the bench.

## Stability follow-up — 25 October 2026

The public `build --test --ui-tests` path completed: 1,386 selected JVM tests in 194 suites passed without failures/errors/skips, the signed app/test APKs packaged successfully, and both exported app APKs exclude the native local P2P engine. All 82 pure bench-tool tests passed. The fixed `playback-controls` command ran all eleven fixtures on API 34; its six fresh screenshots were inspected for labels and focus states.

The new category presentation preserves already displayed rows as other responses arrive, including grouped Kids rows. Five regressions cover late arrivals, removals, duplicate keys and configuration resets. Separate ADB-only manual samples reached Anime and Kids, kept first-card focus while loading settled and returned to the correct tab with one Up press. Those samples do not simulate every response order or replace the failed automated smoke.

The full Home smoke still failed before its category checks. A matching local crash record showed a null `View.mContext` access inside `ViewRootImpl.getAccessibilityFocusedRect`, and the failure screenshot showed the Android TV launcher. The process could survive or restart in the background, so process presence alone did not prove healthy startup. Foreground loss now receives a specific failure instead of being misclassified as incomplete setup. No retry was relabelled as a passing smoke run. Live Debrid/external-player playback, wider device coverage and the Android 14 accessibility failure remain open.

## Shared-session and category follow-up — 25 October 2026

The final development app/test APKs built through `build --test --ui-tests`. All 1,398 selected JVM checks in 194 suites and all 87 pure bench-tool tests passed without failures/errors/skips. Fifteen production-controller tests cover replay saves within and across controllers, failed episode visits, foreground re-entry, ordinary pause/resume and unrelated episode/profile finals. Repository/engine boundaries remain mocked; no live account or playback grant is used.

On the final API 34 APK, all eleven `playback-controls` fixtures and all four `category-navigation` fixtures passed. All ten fresh screenshots were inspected. The fixtures verify grouping of synthetic TV-Y/TV-G Kids cards and use the same presentation/entry helpers as production. Diagnostic logs and earlier failed runs remain local.

A full Home smoke passed on the preceding `37953ca` APK, with six Down/Up entries, both boundary directions and fourteen inspected captures. A separate run on the final shared-session APK failed before Home: Android 14 again threw inside `ViewRootImpl.getAccessibilityFocusedRect`, and foreground loss correctly failed the report. This intermittent framework-stack accessibility issue remains open; the successful sample does not erase it, and no crash suppression or automatic relaunch was added.

Static inspection of both rebuilt app APKs confirmed the local P2P engine is absent, official Conscrypt 2.8.0 JNI bytes are packaged with 16 KiB alignment in all four universal ABIs, and all five exact Conscrypt/BoringSSL notice assets are included. IAMF's 64-bit core remains at 4 KiB alignment. Dedicated TLS/16 KiB runtime checks, corresponding native sources/notices, external-player result ordering and representative hardware acceptance remain separate gates. The frozen physical Shield APK, release/stable artifacts, owner accounts and Shield debugging state were not changed.
