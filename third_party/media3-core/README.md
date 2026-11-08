# Maintained Media3 core source

These six modules preserve the imported Nuvio player's existing managed behavior while making its custom code rebuildable. `src/` contains33 preferred maintained Java units:28 based on pinned AndroidX sources and five additions. They replace every modified/added definition in the imported core; unchanged classes retain exact official Google Media3 1.8.0 artifact identities and corresponding public source. This reconstruction is not the historical private source.

`upstream/media3-core-preferred-base-sources.tar.gz` contains the six modules' complete public main source, resources, manifests and build/license files at AndroidX commit `b7bbc6e2bc3e45ff3ed99884c114c50f03bba5c9`. All833 stock top-level source units corresponding to1818 stock classes are present. The preferred-base manifest pins every file. The full original public archive is identified separately; its large test-media corpus is omitted here.

`patches/maintained-player-core.patch` applies without fuzz or offsets to that archived source and reproduces all33 preferred units exactly. `patch-source-map.json` maps canonical AndroidX paths to the preferred module layout. AndroidX Apache headers are retained, together with the application's GPLv3 license and Nuvio attribution. Source/provenance records do not assert blanket legal clearance.

`build.py` compiles all33 units together with javac17.0.21 using `--release 8`, then combines them with pinned official stock classes. It never reads historical custom reference class bytes. Every changed/added definition must be compiled from source; complete1830-class inventory and all input/output identities are checked. Dependencies come from the app's pinned Gradle cache; all actual hashes are recorded in the output receipt. No network access or signing key is used by this builder.

Example, using explicit local tool/input paths:

```sh
python3 build.py \
  --jdk /path/to/jdk17.0.21 \
  --android /path/to/sdk/platforms/android-36/android.jar \
  --gradle-cache /path/to/gradle/caches \
  --stock-aars /path/to/official-stock-inputs \
  --allocator-native /path/to/hardened-allocator-output \
  --output /path/to/fresh-owned-output
```

The stock directory contains `google-MODULE.aar` for the six module names in `module-inputs.json`, downloaded from the exact official Maven URLs recorded there. The allocator directory contains `ABI/libmedia3_exoplayer_jni.so` for all four ABIs, built by the companion allocator-native source recipe. Its four exact hashes are required. Native libraries are not reconstructed from class bytes or replaced with the old unsafe allocator.

Add `--test --mockable-android /path/to/agp/mockable/android.jar` to execute29 decoder/subtitle preservation assertions against the coherent compiled classes. The host-only Pair helper compensates for the mock jar's discarded constructor arguments and never enters product output. Full transport/memory test harnesses and negative controls remain in the companion source evidence.

`metadata/` retains editable manifests, consumer rules, resources, AAR metadata and annotation XML. Annotation ZIP and AAR containers are serialized deterministically. The builder refuses an existing output directory. Two separate clean builds must reproduce all six AARs byte-for-byte before integration.

The maintained code preserves native/direct/heap allocation and ownership, ByteBuffer negotiation/fallback, routing/AES/counters, subtitle prefetch and existing decoder-selection behavior. The imported DV7 flag remains unused at the final renderer lookup; this source work does not claim to fix that behavior. The restricted localhost transport retains its existing raw TCP HTTP limitation. No new feature or public API is added.

Qualification compares all1830 class contracts and executable bodies against retained references. One factory method has a reviewed encoding difference when invoking final `Object.getClass`; otherwise normalized executable code matches. Class/member annotations, outer/inner declarations and native FastNative metadata match after restoring two NullableType field annotations. Twelve emitted classes omit unnamed compiler-generated MethodParameters entries under javac release8; no parameter count, descriptor, named parameter, executable code or member access changes. This difference is recorded rather than described as complete class-file byte equality. Code debug/stack-map encoding and final D8/R8/Android verification are separate gates.

The companion source handoff retains scoped routing/AES, memory/queue and decoder/subtitle tests and their red/green receipts. Full application regression/lint, native interfaces, exact APK packaging, all-ABI Android playback, upgrade retention and public update qualification determine release acceptance. Source rebuild success alone is not a stable-release claim.
