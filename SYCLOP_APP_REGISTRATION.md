# Syclop public application registrations

Syclop's maintainer registers the application once. Ordinary viewers connect their own Trakt and MyAnimeList accounts; they do not create developer applications or supply developer credentials. Each Syclop profile keeps its own service tokens.

The values below are **public application identifiers and redirect URLs**, not user passwords, access tokens, refresh tokens or signing keys. A public Client ID identifies Syclop and does not grant access to a viewer's account without that viewer's authorization.

## Current production configuration

| Service | Public application Client ID | Exact registered redirect |
|---|---|---|
| Trakt | `0nQc4G9CQfhwnM2g-8s1SsnLaTVO5CdN-qORYVpqKFg` | `https://github.com/dintalframe` |
| MyAnimeList | `76daded66e7bb55596834840c1d73ebd` | `https://dintalframe.github.io/Syclop/mal/` |

Trakt's owner completed its Production registration on 2026-10-24 (portal application 246234); the numeric portal application ID is distinct from the public Client ID above. No Client Secret was issued or is used. See [Trakt setup and current public-client contract](TRAKT_APP_SETUP.md).

The owner reports successful MyAnimeList application registration and supplied its public Client ID. The [Syclop HTTPS return page](https://dintalframe.github.io/Syclop/mal/) is deployed and was verified live. Its exact trailing slash is part of the configured redirect. Registration/configuration has been supplied for the next release; a native OAuth/device round trip, list read and account write have **not** yet been verified. Publishing the page and receiving a Client ID do not establish those runtime behaviors.

## Maintainer build fields

Merge these public fields into the build checkout's untracked `local.properties`. Preserve any other existing local settings; this block contains only application registration values.

```properties
TRAKT_CLIENT_ID=0nQc4G9CQfhwnM2g-8s1SsnLaTVO5CdN-qORYVpqKFg
TRAKT_REDIRECT_URI=https://github.com/dintalframe
MAL_CLIENT_ID=76daded66e7bb55596834840c1d73ebd
MAL_REDIRECT_URI=https://dintalframe.github.io/Syclop/mal/
```

Gradle copies these public values into `BuildConfig`. MyAnimeList defaults are blank when omitted, so builds without that registration remain unconfigured. The Trakt redirect has the same application default shown above, but its Client ID must still be supplied. Advanced device configuration can override the application registration; normal viewers should not need that screen in a configured release. Changing registration may require reconnecting accounts. An already installed older APK does not acquire a new build configuration until it is updated.

Keep account passwords, temporary device/authorization codes, PKCE verifiers, access/refresh tokens, any private client secret, and signing material out of source, public documentation and issue reports. These flows use public/native clients; Syclop does not embed a client secret. Account tokens are encrypted locally and isolated by profile. See [backup scope](LOCAL_BACKUPS.md) for the distinction between exported public configuration and excluded private account state.

## Normal viewer connection

For Trakt, open **Settings → Tracking → Trakt → Connect**, scan the QR code or visit the displayed Trakt verification page, and approve Syclop's device code while signed into your own Trakt account. The TV polls Trakt directly and saves the resulting tokens to the initiating profile. The configured HTTPS redirect is application registration information; this device flow does not require a browser callback on the TV.

For MyAnimeList, open **Settings → Tracking → MyAnimeList → Connect** and scan the QR code on a phone that can reach the TV's home network. Approve Syclop on MyAnimeList, then use the return page's deliberate button to send the temporary code and exact state to the TV. Syclop exchanges it directly with MyAnimeList using the registered HTTPS redirect and the PKCE verifier retained on the TV. The callback host does not receive account tokens or that verifier.

The current MAL return protocol supports RFC1918 private IPv4 home networks. Its TV listener runs at port 17893, path `/mal/callback`, only during the five-minute connection attempt. State, callback destination, expiry and initiating profile must match. If browser-to-LAN navigation fails, copy the complete original HTTPS callback URL into the active TV connection dialog as the manual fallback. No viewer should paste that temporary URL into chat or a public issue. [Return-page protocol details](docs/mal/README.md) describe the routing and limits.

After connecting MAL, its native Home/search catalogs and five Library status lists can be used; select MyAnimeList as the Library source in Tracking settings. Completed-episode sync requires exact MAL IDs. MAL stores episode counts/status, so Syclop retains local timestamp resume positions. Account actions, metadata and playback still need real-device verification; [the feature audit](SYCLOP_FEATURE_AUDIT.md) records remaining gaps.

## Build environment scope

The currently exercised build workflow uses this shared TV workspace: `media-client/build-syclop.sh` depends on the sibling `.toolchain/env.sh` and workspace bootstrap described in [SYCLOP_BUILD.md](SYCLOP_BUILD.md). It builds and copies artifacts without installing on the TV.

Standalone-checkout toolchain portability is still being finalized/validated. Do not assume that copying these four public properties makes a fresh clone's build script self-contained. This registration document does not supply SDK paths, signing configuration or other machine-specific setup, and it does not claim a tested standalone bootstrap.
