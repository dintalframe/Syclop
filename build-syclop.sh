#!/usr/bin/env bash
# Build only. This script never connects to, installs on, or changes a TV.
set -euo pipefail
project_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
workspace_dir="$(CDPATH= cd -- "$project_dir/.." && pwd)"
run_tests=0
variant=Debug
variant_dir=debug
for option in "$@"; do
  case "$option" in
    --test) run_tests=1 ;;
    --release) variant=Release; variant_dir=release ;;
    --help|-h)
      printf 'Usage: ./build-syclop.sh [--test] [--release]\nBuilds Syclop fullDebug by default; --release builds the optimized, non-debuggable fullRelease.\n--test runs the selected isolation, credential, debrid, Trakt, MAL, Express, backup and log-redaction checks. No TV connection.\n'
      exit 0 ;;
    *) printf 'Unknown option: %s\n' "$option" >&2; exit 2 ;;
  esac
done
if [ -f "$workspace_dir/.toolchain/env.sh" ]; then
  source "$workspace_dir/.toolchain/env.sh"
else
  # A normal standalone clone can use an Android Studio/command-line SDK installation.
  if [ -z "${JAVA_HOME:-}" ] || [ -z "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]; then
    printf 'Set JAVA_HOME to a JDK 17 installation and ANDROID_HOME (or ANDROID_SDK_ROOT) to your Android SDK. See SYCLOP_BUILD.md.\n' >&2
    exit 1
  fi
  export ANDROID_HOME="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"
  export ANDROID_SDK_ROOT="$ANDROID_HOME"
fi
if [ ! -x "$JAVA_HOME/bin/java" ] || [ ! -x "$JAVA_HOME/bin/keytool" ]; then
  printf 'JAVA_HOME must identify a complete JDK with java and keytool.\n' >&2
  exit 1
fi
if ! command -v python3 >/dev/null 2>&1; then
  printf 'Python 3 is required to create the private local signing password.\n' >&2
  exit 1
fi
if [ ! -f "$ANDROID_HOME/platforms/android-36/android.jar" ] || [ ! -x "$ANDROID_HOME/build-tools/36.0.0/aapt2" ]; then
  sdkmanager="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
  if [ ! -x "$sdkmanager" ]; then sdkmanager="$(command -v sdkmanager || true)"; fi
  if [ -z "$sdkmanager" ] || [ ! -x "$sdkmanager" ]; then
    printf 'Install Android SDK platform 36 and build-tools 36.0.0 using Android Studio SDK Manager, or put sdkmanager on PATH.\n' >&2
    exit 1
  fi
  "$sdkmanager" --sdk_root="$ANDROID_HOME" 'platforms;android-36' 'build-tools;36.0.0'
fi

signing_dir="$workspace_dir/.toolchain/syclop-private"
mkdir -p "$signing_dir"
chmod 700 "$signing_dir"
key_file="$signing_dir/syclop-local.jks"
password_file="$signing_dir/signing.password"
if { [ -f "$key_file" ] && [ ! -f "$password_file" ]; } || { [ ! -f "$key_file" ] && [ -f "$password_file" ]; }; then
  printf 'Incomplete local signing identity in %s; restore both original key and password files before building.\n' "$signing_dir" >&2
  exit 1
fi
if [ ! -f "$password_file" ]; then
  python3 - "$password_file" <<'PY'
import os
from pathlib import Path
import secrets
import sys
destination = Path(sys.argv[1])
descriptor = os.open(str(destination), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(descriptor, 'w') as stream:
    stream.write(secrets.token_hex(24) + '\n')
PY
fi
chmod 600 "$password_file"
if [ ! -f "$key_file" ]; then
  umask 077
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$key_file" -storetype JKS \
    -storepass:file "$password_file" -keypass:file "$password_file" \
    -alias syclop-local -dname 'CN=Syclop Local Development,O=Personal,C=US' \
    -keyalg RSA -keysize 3072 -validity 10000 > "$signing_dir/key-generation.log" 2>&1
fi
chmod 600 "$key_file"
export CI_USE_DEBUG_SIGNING=false
export NUVIO_RELEASE_STORE_FILE="$key_file"
export NUVIO_RELEASE_KEY_ALIAS=syclop-local
export NUVIO_RELEASE_STORE_PASSWORD="$(cat "$password_file")"
export NUVIO_RELEASE_KEY_PASSWORD="$NUVIO_RELEASE_STORE_PASSWORD"

cd "$project_dir"
if [ "$run_tests" -eq 1 ]; then
  ./gradlew --no-daemon --console=plain --max-workers=2 \
    -Dorg.gradle.jvmargs='-Xmx4096m -XX:MaxMetaspaceSize=1024m -Dfile.encoding=UTF-8' \
    :app:testFull${variant}UnitTest --tests 'com.nuvio.tv.core.network.SyclopLocalModeTest' \
    --tests 'com.nuvio.tv.core.auth.SyclopGuestStartupTest' \
    --tests 'com.nuvio.tv.core.logging.LogDiagnosticsTest' \
    --tests 'com.nuvio.tv.core.debrid.*' \
    --tests 'com.nuvio.tv.core.anime.*' \
    --tests 'com.nuvio.tv.data.remote.dto.AllDebridMagnetCollectionAdapterTest' \
    --tests 'com.nuvio.tv.core.mediafacts.*' \
    --tests 'com.nuvio.tv.core.providers.*' \
    --tests 'com.nuvio.tv.updater.SelfUpdateDisabledPolicyTest' \
    --tests 'com.nuvio.tv.core.cloud.RealDebridCloudLibraryProviderApiTest' \
    --tests 'com.nuvio.tv.data.local.DebridCredentialStorageTest' \
    --tests 'com.nuvio.tv.data.local.DebridFallbackPreferenceTest' \
    --tests 'com.nuvio.tv.data.local.TrailerSettingsProfileDefaultsTest' \
    --tests 'com.nuvio.tv.data.local.AddonInstallCommitGuardTest' \
    --tests 'com.nuvio.tv.data.local.AddonProfileCatalogSnapshotTest' \
    --tests 'com.nuvio.tv.data.local.ContinueWatchingCacheCodecTest' \
    --tests 'com.nuvio.tv.data.local.ContinueWatchingEnrichmentCacheTest' \
    --tests 'com.nuvio.tv.core.sync.WatchStateMutationPersistenceTest' \
    --tests 'com.nuvio.tv.core.sync.WatchStateMutationStoreTest' \
    --tests 'com.nuvio.tv.data.repository.AddonManifestPlaceholderTest' \
    --tests 'com.nuvio.tv.core.trakt.*' \
    --tests 'com.nuvio.tv.data.local.TraktAuthDataStoreTest' \
    --tests 'com.nuvio.tv.data.local.TraktCredentialStorageTest' \
    --tests 'com.nuvio.tv.data.repository.TraktAuthServiceTest' \
    --tests 'com.nuvio.tv.data.mal.*' \
    --tests 'com.nuvio.tv.data.simkl.*' \
    --tests 'com.nuvio.tv.data.catalog.*' \
    --tests 'com.nuvio.tv.core.streams.*' \
    --tests 'com.nuvio.tv.core.torrent.*' \
    --tests 'com.nuvio.tv.core.plugin.RemoteExecutablePluginPolicyTest' \
    --tests 'com.nuvio.tv.ui.screens.ambient.AmbientCriticalAuthLeaseTest' \
    --tests 'com.nuvio.tv.ui.screens.settings.MalSettingsModalLifecycleTest' \
    --tests 'com.nuvio.tv.ui.screens.settings.Simkl*' \
    --tests 'com.nuvio.tv.ui.screens.settings.TrackingSettingsViewModelTest' \
    --tests 'com.nuvio.tv.core.tracking.TrackingLibrarySourceControllerTest' \
    --tests 'com.nuvio.tv.core.tracking.TrackingSourceMemoryTest' \
    --tests 'com.nuvio.tv.core.express.*' \
    --tests 'com.nuvio.tv.core.backup.*' \
    --tests 'com.nuvio.tv.core.player.ExternalPlayer*' \
    --tests 'com.nuvio.tv.core.player.ExternalAutoNext*' \
    --tests 'com.nuvio.tv.core.player.ExternalHandoffTrackerScopeTest' \
    --tests 'com.nuvio.tv.core.player.MpvInfoSnapshotTest' \
    --tests 'com.nuvio.tv.ui.screens.player.MpvStreamInfoDataTest' \
    --tests 'com.nuvio.tv.ui.screens.player.PlayerSourcePanelPresentationTest' \
    --tests 'com.nuvio.tv.ui.screens.player.PlayerSourcePanelMetadataTest' \
    --tests 'com.nuvio.tv.ui.screens.player.PlayerInitialBufferingTimeoutPolicyTest' \
    --tests 'com.nuvio.tv.ui.screens.settings.SettingsStructureTest' \
    --tests 'com.nuvio.tv.ui.screens.home.*' \
    --tests 'com.nuvio.tv.core.initialization.*' \
    --tests 'com.nuvio.tv.data.repository.TraktProgressInitializationTest' \
    --tests 'com.nuvio.tv.data.repository.TraktProgressServiceRefreshTest' \
    --tests 'com.nuvio.tv.data.repository.TraktProgressServiceFailureTest' \
    --tests 'com.nuvio.tv.core.deeplink.DeepLinkParser*' \
    --tests 'com.nuvio.tv.data.local.MDBListRatingCredentialStorage*' \
    --tests 'com.nuvio.tv.ui.screens.settings.MDBListSettingsViewModel*' \
    --tests 'com.nuvio.tv.data.repository.MDBListRepository*' \
    --tests 'com.nuvio.tv.data.repository.StreamRepositoryPluginIsolationTest'
fi
./gradlew --no-daemon --console=plain --max-workers=2 \
  -Dorg.gradle.jvmargs='-Xmx4096m -XX:MaxMetaspaceSize=1024m -Dfile.encoding=UTF-8' \
  :app:assembleFull${variant}
mkdir -p "$workspace_dir/dist"
outputs="$project_dir/app/build/outputs/apk/full/$variant_dir"
python3 - "$outputs" "$variant_dir" <<'PY'
from pathlib import Path
import sys
import zipfile
outputs = Path(sys.argv[1])
variant = sys.argv[2]
for abi in ('arm64-v8a', 'universal'):
    apk = outputs / f'app-full-{abi}-{variant}.apk'
    with zipfile.ZipFile(apk) as archive:
        if any(Path(name).name == 'libnuvio_engine.so' for name in archive.namelist()):
            raise SystemExit(f'Refusing APK containing the disabled local P2P engine: {apk.name}')
print('Verified both APKs exclude the native local P2P engine.')
PY
cp "$outputs/app-full-arm64-v8a-${variant_dir}.apk" "$workspace_dir/dist/syclop-arm64-${variant_dir}.apk"
cp "$outputs/app-full-universal-${variant_dir}.apk" "$workspace_dir/dist/syclop-${variant_dir}.apk"
(
  cd "$workspace_dir/dist"
  checksum_file=syclop.sha256
  if [ "$variant_dir" = release ]; then checksum_file=syclop-release.sha256; fi
  shasum -a 256 "syclop-arm64-${variant_dir}.apk" "syclop-${variant_dir}.apk" > "$checksum_file"
)
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --print-certs "$workspace_dir/dist/syclop-arm64-${variant_dir}.apk" \
  > "$workspace_dir/dist/syclop-${variant_dir}-signing-certificate.txt"
printf 'Built %s and %s. No TV installation performed.\n' "$workspace_dir/dist/syclop-arm64-${variant_dir}.apk" "$workspace_dir/dist/syclop-${variant_dir}.apk"
