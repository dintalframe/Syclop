# Native sources and qualification

Syclop's native-source companion is prepared as `syclop-native-sources-0.1.1.tar.gz`. It identifies fixed native inputs independently of the final application Git commit. Published release assets must identify their exact app source, APK hashes, signer and qualification scope separately. Preparation of this archive is not a stable-release or publication claim.

`SYCLOP_SOURCE_MANIFEST.json` describes historical 0.1.1/native-input provenance. Its version, code and hashes remain historical when the application advances. The release workflow generates `release-identity.json` from both final signed APKs, including their verified root Git revisions, current app versions, hashes, signer and ABI sets. Its `nativeSourceCompanion` preserves the historical archive filename, hash, size and release URL. Identity verification establishes no alignment, unchanged-native-library or runtime qualification claim.

## Qualification and release status

Signed-candidate/runtime and public-updater acceptance belong to the exact release qualification record. Consult the release assets for artifact-specific status. A successful source build, repeated native hashes or the universal ABI list does not establish support for every Android TV device.

Owned qualification includes two clean builds of the four-ABI MPV chain, repeated libass static/wrapper builds, allocator boundary tests, audio downmix comparisons, normal ASS bitmap/atlas A/B, and selected ARM64/API36/16 KiB decoder, local-container and MPV/NextLib co-load fixtures. MPV HTTPS HLS reaches decoded end with certificate verification enabled and an owned trusted CA; the unchanged production CA bundle rejects the unrelated fixture certificate. This does not replace representative hardware GPU, MediaCodec, audio-output/passthrough, broader media, API 24/other-ABI, fresh-install or account acceptance.

The [build guide](SYCLOP_BUILD.md) describes ordinary app builds and signing. The [feature audit](SYCLOP_FEATURE_AUDIT.md) preserves earlier artifact-specific observations. Neither document promotes an APK automatically.

## Selected replacement sources

| Native component | Selected source and modifications |
| --- | --- |
| Media3 managed core | [AndroidX 1.8.0 `b7bbc6e2…`](https://github.com/androidx/media/tree/b7bbc6e2bc3e45ff3ed99884c114c50f03bba5c9), archived six-module main source and33 preferred maintained Java units. The coherent recipe compiles every modified/added definition from source and retains the complete1830-class inventory. Original historical private source is not claimed. |
| Media3 allocator JNI | Syclop-owned `media3_exoplayer_jni.c`, with checked direct-buffer/array ranges, overflow and pending-exception handling. Raw-address/free APIs still require valid caller-owned allocations. The companion includes the complete implementation, seven-method contract, build recipe and small boundary test. |
| Audio-only FFmpeg downmix | [FFmpeg release/8.1 `a35157ab…`](https://github.com/FFmpeg/FFmpeg/tree/a35157abb96167fd7308edc92b55408d5bb084b0), exact Nuvio-derived downmix JNI/Java wrapper and four-ABI decoder configuration. Container/network code is disabled. Normal audio structure matches; seek/reset history and lossy AC3 encoding differences are explicitly retained in its build records. |
| NextLib | [Public wrapper `302adbfa…`](https://github.com/anilbeesetti/nextlib/tree/302adbfa91adfc12f04a3eeb74341ba7c629bbf3), the shared FFmpeg maintenance source plus three exact upstream encryption-info/MOV fixes, [Mbed TLS 3.6.7 `068ff080…`](https://github.com/Mbed-TLS/mbedtls/tree/068ff080b369adfac81509f9b57b2afabaf82dc5), plus FFmpeg8 compatibility and Java-long JNI-width stabilization patches. The release companion identifies the exact subsequent input/frame-loader ownership repair. Production use is restricted to downloaded local containers; the native TLS baseline alone is not trusted for remote authorization/probing. |
| MPV | [MPV 0.42.0 `b0841e8c…`](https://github.com/mpv-player/mpv/tree/b0841e8c4d156b8403dc1876c4cbd9011eb583f3), unchanged [Android wrapper `96c3507c…`](https://github.com/abdallahmehiz/mpv-android/tree/96c3507c5fc8eedbf5fff458101cad794392eca7), patched shared FFmpeg source, Mbed TLS 3.6.7, [libxml2 2.15.4](https://download.gnome.org/sources/libxml2/2.15/libxml2-2.15.4.tar.xz), [libplacebo 7.387.0 `275f7dea…`](https://github.com/haasn/libplacebo/tree/275f7dea27924cdd34ab2e6c20e448b6bc4fab65), dav1d, Lua 5.2.4, the patched font/shaping chain below, and the Android-only fork selection from [downstream fix `07908d29…`](https://github.com/ZHINFINITY/Mpv-infinity/commit/07908d2912448df629a829159956c99ac2e61a96). The existing fork/exec implementation is retained; TLS and descriptor checks remain enabled. |
| libass/font shaping | [Edde 0.18.4 `9c936772…`](https://github.com/edde746/libass/tree/9c936772ffdaabdd9dc271ef8fc800e5b025a71b), [Nuvio wrapper `b824ae66…`](https://github.com/NuvioMedia/nuvio-libass/tree/b824ae66738e279d09b1994d9977978e7ffcc90a), FreeType 2.14.3 with upstream fixes [`5a280ecd…`](https://github.com/freetype/freetype/commit/5a280ecde6f324de0d226261036e736e0cb49a71) and [`f3ca71c9…`](https://github.com/freetype/freetype/commit/f3ca71c9900fe860849b3163a6e2c1e765b291d9), HarfBuzz 13.0.1, FriBidi 1.0.16, libunibreak 6.1, Fontconfig 2.18.3 and Expat 2.8.4. Locked source archives and released WrapDB overlays are included. |
| AV1 | [Media3 1.8.0 `b7bbc6e2…`](https://github.com/androidx/media/tree/b7bbc6e2bc3e45ff3ed99884c114c50f03bba5c9/libraries/decoder_av1), [libgav1 v0.20.0 `c05bf9be…`](https://chromium.googlesource.com/codecs/libgav1/+/c05bf9be660cf170d7c26bd06bb42b3322180e58), [cpu_features 0.10.1 `d3b2440f…`](https://github.com/google/cpu_features/tree/d3b2440fcfc25fe8e6d0d4a85f06d68e98312f5b), plus retained Surface ownership and YUV layout guards. |
| IAMF | Media3 1.8.0 and [libiamf 1.1.0 `f06e919e…`](https://github.com/AOMediaCodec/libiamf/tree/f06e919e2ad5502a2adc4bdd4e146f2e7e7ffb63), selected complete build sources, PCM-only configuration and exact BSD Clear/patent texts. |
| MPEG-H | Media3 1.8.0 and [Fraunhofer r2.0.0 `4555490b…`](https://github.com/Fraunhofer-IIS/mpeghdec/tree/4555490b82ff4cdb965c9501a96d1c248804fcb6), exact JNI/Java source, build inputs and license. |

The Android Mbed TLS adaptation delegates platform entropy to Bionic's platform CSPRNG, while retaining the generic Linux default. Its explicit trust boundary is the supported Android operating system; no successful first-boot seed or blanket advisory clearance is inferred from a TLS test. Exact source, policy rationale and per-ABI repeat records accompany the release. Native exception/error-path and final runtime acceptance remain separate gates.

QuickJS was removed from the disabled executable-plugin path. It is not a selected native source input for this preparation. Every final signed APK must still independently pass the all-ABI absence/inventory check.

## Archive contents and verification

The companion contains a `component-manifest.json`, `FILES.json`, `verify-archive.py`, `prepare-sources.py`, unique provenance-pinned upstream archives, complete selected app-owned JNI/wrapper source, the maintained managed-core source/recipe, Android entropy and input-ownership overlays, exact patches, per-ABI build recipes/configurations and notice texts. The pristine FFmpeg maintenance archive appears once: audio downmix uses its audio-only configuration; NextLib and MPV apply the separately retained container backport. The final AV1 JNI source and both applied overlays are retained. Compiler/runtime source manifests and exact NDK notices identify the build toolchain without bundling its executable tool binaries.

Verify before unpacking:

```sh
python3 verify-archive.py --self-check syclop-native-sources-0.1.1.tar.gz
```

Run the verifier supplied alongside the archive, or copy the same script from the archive after checking its release checksum. It checks outer archive path safety, complete member/hash coverage and upstream archive identities. Extract into a new directory, then prepare the exact source variants without downloads:

```sh
python3 syclop-native-sources-0.1.1/prepare-sources.py /absolute/path/to/new-native-source-work
```

The selected native build recipes record the verified NDK r29/API 21–24 and Darwin CMake/Ninja/compiler environments. Source preparation is separate from a full native build. Some retained recipes use their recorded fixed logical paths and original Java/AAR packaging inputs; the companion README explains reconstruction and the matching app checkout. A fresh full rebuild on an unrelated host is not newly claimed here.

No maintainer, viewer or disposable workspace signing/TLS credentials, user data, APKs or runtime logs are copied into the source handoff. Exact upstream archives can contain their own already-public example keys or test vectors; those remain provenance-pinned upstream source inputs, not Syclop owner credentials.

## Licenses and source-maintenance boundaries

The configured MPV FFmpeg build is GPLv3-or-later. The separate downmix and NextLib wrappers retain their own GPL attribution; their FFmpeg configurations and dependencies are recorded separately. Preserve all applicable GPL/LGPL, ISC, Apache, MIT/BSD, FreeType, patent and other exact texts. Top-level COPYING alone is insufficient: the archive includes embedded decoder/filter/demuxer notices, libxml2 per-file exceptions and generated GL/EGL CC0/Apache notices. Preserve Nuvio's attribution to peerless2012 and Plezy and the accompanying IJG statement: **this software is based in part on the work of the Independent JPEG Group**.

Official unchanged Conscrypt 2.8.0, Sentry Native 0.15.2, AndroidX graphics-path 1.0.1 and datastore-core-android 1.1.1 inputs are identified by immutable Maven coordinates/artifact hashes and source/notice records. They are distinguished from locally rebuilt native groups. Graphics/datastore native source commits have not been independently reconstructed from their publication records; no guessed commit is supplied.

The six managed Media3 core modules now have a coherent source recipe in [`third_party/media3-core`](third_party/media3-core/README.md). All33 preferred maintained units compile together; unchanged classes retain pinned official Media3 1.8.0 identities and their archived public source. The maintained reconstruction preserves imported behavior and does not claim to recover historical private source. Complete1830-class inventory, code, metadata and packaging comparisons are recorded separately from final app/runtime acceptance.

Raw native identity is not claimed across an upstream upgrade. MPV's existing JNI/Java API and prior public client functions remain; its client API minor is 2.6. The removed legacy FFmpeg `hls` URLProtocol is rejected by the actual app playback policy/grants, while supported verified HTTPS HLS has normal-use regression coverage. libxml2's upstream legacy export changes are listed with every rebuilt consumer's XML import closure. Advisory queries and configuration/path triage are scoped evidence, not a promise that every source version is vulnerability-free.
