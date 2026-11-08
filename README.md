<div align="center">
  <img src="docs/assets/syclop-primary-head.png" alt="Syclop mascot" width="112" />
  <h1>Syclop</h1>
  <p>Your media. Made for TV.</p>
  <p><a href="https://github.com/dintalframe/Syclop/releases/latest">Download latest release</a> · <a href="https://dintalframe.github.io/Syclop/">Website</a> · <a href="https://github.com/dintalframe/Syclop/releases">Release notes</a></p>
</div>

Syclop is an independent Android TV media app built for your remote. Browse your own library and connected services, use local profiles, and pick up where you left off with Continue Watching.

Bring a media source you are authorized to use, plus any accounts or subscriptions it requires. Syclop does not supply media.

## Set up your TV

Requires **Android 7.0+**. Check the [release notes](https://github.com/dintalframe/Syclop/releases/latest) for APK choices, checksums, and the build's verification limits.

1. Install the signed APK using Android's installer, open Syclop, and complete first-run setup.
2. Open **Settings → Connected Services → Add-ons and media servers** to connect your source. Debrid connections are under **Connected Services → Debrid services**.
3. Start watching. Optionally connect a tracker under **Connected Services → Connected accounts** for supported library and watch-progress sync.

Profiles stay local; each connected service manages its own account. See the [backup guide](LOCAL_BACKUPS.md) for portable backup scope.

## Support and development

[Report a bug](https://github.com/dintalframe/Syclop/issues) with your build version, device, Android version, and reproduction steps. Redact tokens, account details, private provider URLs, and playable links.

For source builds and contributions, see the [build guide](SYCLOP_BUILD.md), [native sources and qualification](SYCLOP_NATIVE_SOURCES.md), [service registration](SYCLOP_APP_REGISTRATION.md), [project audit](SYCLOP_FEATURE_AUDIT.md), and [contributing guide](CONTRIBUTING.md).

## Credits and licenses

Derived from [NuvioTV](https://github.com/NuvioMedia/NuvioTV), with upstream copyright and attribution retained. App code is licensed under [GNU GPLv3](LICENSE). Bundled components retain their own licenses: [native-library notes](SYCLOP_BUILD.md#bundled-native-binaries-and-source-limits), [decoder notice](ffmpeg-decoder-downmix/NOTICE.md), and [artwork provenance](SYCLOP_ARTWORK.md).
