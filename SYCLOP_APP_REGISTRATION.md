# Syclop public application registrations

Syclop's maintainer registers the application once. Ordinary viewers connect their own Trakt, MyAnimeList and Simkl accounts; they do not create developer applications or supply developer credentials. Each Syclop profile keeps its own service tokens.

The values below are **public application identifiers and redirect URLs**, not user passwords, access tokens, refresh tokens or signing keys. A public Client ID identifies Syclop and does not grant access to a viewer's account without that viewer's authorization.

## Registered Syclop applications

| Service | Public application Client ID | Exact registered redirect |
|---|---|---|
| Trakt | `0nQc4G9CQfhwnM2g-8s1SsnLaTVO5CdN-qORYVpqKFg` | `https://github.com/dintalframe` |
| MyAnimeList | `76daded66e7bb55596834840c1d73ebd` | `https://dintalframe.github.io/Syclop/mal/` |
| Simkl | `4e98516f315063630ed4ebf359c2faef8c13bf08a08f33f187826b5908accd5a` | Not used by AUTH V2 PIN authorization |

Trakt's owner completed its Production registration on 2026-10-24 (portal application 246234); the numeric portal application ID is distinct from the public Client ID above. No Client Secret was issued or is used. See [Trakt setup and current public-client contract](TRAKT_APP_SETUP.md).

The MyAnimeList application is registered and the [Syclop HTTPS return page](https://dintalframe.github.io/Syclop/mal/) is deployed. Its exact trailing slash is part of the registered redirect. A viewer completed native MAL authorization on the Shield, and Connected persisted through app updates, cold starts and a device reboot. This does not establish every list mutation, token refresh, revoke or account-switch path.

Simkl's owner registration uses AUTH V2 PIN authorization without a client secret or redirect URL. Its public discovery feeds use Syclop's public application identity without a viewer login. Personal Simkl authorization and manual Sync now completed on the Shield; public discovery and personal synchronization remain separate features.

## Maintainer build fields

Merge these public fields into the build checkout's untracked `local.properties`. Preserve any other existing local settings; this block contains only application registration values.

```properties
TRAKT_CLIENT_ID=0nQc4G9CQfhwnM2g-8s1SsnLaTVO5CdN-qORYVpqKFg
TRAKT_REDIRECT_URI=https://github.com/dintalframe
MAL_CLIENT_ID=76daded66e7bb55596834840c1d73ebd
MAL_REDIRECT_URI=https://dintalframe.github.io/Syclop/mal/
SIMKL_CLIENT_ID=4e98516f315063630ed4ebf359c2faef8c13bf08a08f33f187826b5908accd5a
SIMKL_APP_NAME=syclop
```

Gradle copies these public values into `BuildConfig`. Client IDs default to blank when omitted, so the affected integration remains unconfigured. The Trakt redirect and Simkl app name have the application defaults shown above. Advanced device configuration can override the application registration; normal viewers should not need that screen in a configured build. Changing registration may require reconnecting accounts. An already installed APK does not acquire a new build configuration until it is updated. The checked-in [example properties](local.example.properties) contain these public fields; preserve existing SDK paths and other local settings when merging them.

Keep account passwords, temporary device/authorization codes, PKCE verifiers, access/refresh tokens, any private client secret, and signing material out of source, public documentation and issue reports. These flows use public/native clients; Syclop does not embed a client secret. Account tokens are encrypted locally and isolated by profile. See [backup scope](LOCAL_BACKUPS.md) for the distinction between exported public configuration and excluded private account state.

## Verification scope

The Shield-tested arm64 private beta `0828d05e` passed 1,325 selected tests in 186 suites with zero failures/errors/skips. Real viewer approvals and Connected status were checked for Real-Debrid, AllDebrid, Trakt, MyAnimeList and Simkl. Tracking connections and the selected Trakt Library, Watch Progress and More Like This sources survived app cold starts and a device reboot. First-Home initialization showed actual import stages and a labelled partial Trakt snapshot before opening Home. These checks do not verify complete offline history, every remote mutation, real token rotation/revoke, all account-switch flows or other Android TV hardware. See the [build record](SYCLOP_BUILD.md) and [feature audit](SYCLOP_FEATURE_AUDIT.md) for the current limits.

## Normal viewer connection

For Trakt, open **Settings → Tracking → Trakt → Connect**, scan the QR code or visit the displayed Trakt verification page, and approve Syclop's device code while signed into your own Trakt account. The TV polls Trakt directly and saves the resulting tokens to the initiating profile. The configured HTTPS redirect is application registration information; this device flow does not require a browser callback on the TV.

For MyAnimeList, open **Settings → Tracking → MyAnimeList → Connect** and scan the QR code on a phone that can reach the TV's home network. Approve Syclop on MyAnimeList, then use the return page's deliberate button to send the temporary code and exact state to the TV. Syclop exchanges it directly with MyAnimeList using the registered HTTPS redirect and the PKCE verifier retained on the TV. The callback host does not receive account tokens or that verifier.

The current MAL return protocol supports RFC1918 private IPv4 home networks. Its TV listener runs at port 17893, path `/mal/callback`, only during the five-minute connection attempt. State, callback destination, expiry and initiating profile must match. If browser-to-LAN navigation fails, copy the complete original HTTPS callback URL into the active TV connection dialog as the manual fallback. No viewer should paste that temporary URL into chat or a public issue. [Return-page protocol details](docs/mal/README.md) describe the routing and limits.

For Simkl, open **Settings → Tracking → Simkl → Connect** and approve the displayed PIN on Simkl's own site while signed into your account. The resulting account belongs to the initiating Syclop profile. Adding Simkl does not replace the selected Trakt Library or Watch Progress source.

After connecting MAL, its native Home/search catalogs and five Library status lists are available; select MyAnimeList as the Library source in Tracking settings if desired. Completed-episode sync requires exact MAL IDs. MAL stores episode counts/status, so Syclop retains local timestamp resume positions. Sampled native anime browsing and playback passed on the Shield; this does not verify the whole catalog or every account action. First-use imports can report partial snapshots or failures; Retry and Continue with saved data remain explicit choices.

## Build environment scope

The build script supports an optional sibling `.toolchain/env.sh` for this TV workspace or a standalone checkout configured with JDK 17 and the Android SDK paths described in [SYCLOP_BUILD.md](SYCLOP_BUILD.md). It builds and copies artifacts without installing on the TV. A clean, separate-machine bootstrap has not yet been exercised. These six public properties do not supply SDK paths, signing material or other machine-specific setup. `LOCAL_ONLY_BUILD` is fixed in the app build configuration; no Supabase or upstream cloud-account configuration is required.
