# Syclop application registration and viewer accounts

Trakt application registration belongs to the Syclop maintainer. Viewers do **not** create developer applications, supply a Client Secret, or enter their Trakt password into Syclop. They choose Connect Trakt, then approve Syclop’s short device code on Trakt’s own website.

## One-time maintainer setup

1. The Syclop owner signs into the [Trakt application portal](https://developer.trakt.tv/apps/new) and registers Syclop. Trakt’s [current registration guide](https://developer.trakt.tv/docs/create-an-app) requires a verified GitHub account connected to its Developer panel. The owner completed this one-time Production Syclop registration with explicit approval on October 24, 2026 (application ID 246234); no Client Secret was issued.
2. Retain the public Client ID and the exact redirect URI accepted by that registration. The current Production registration form accepts an HTTPS redirect; the Syclop owner selected `https://github.com/dintalframe`. The application uses that default and accepts only an exact registered HTTPS URI. The portal rejected the legacy out-of-band and custom-scheme choices. A device flow does not need a browser callback on the TV.
3. For a distributed Syclop build, set `TRAKT_CLIENT_ID` and `TRAKT_REDIRECT_URI` in the maintainer’s untracked `local.properties` before building. A public Client ID is application identity, not a user credential. A local installation can instead use Tracking → Trakt → Advanced application setup to save the same Syclop registration once for the application.
4. Viewers then use the normal Connect Trakt device approval flow. Every Syclop profile has its own token state. Replacing the application registration requires reconnecting existing Trakt accounts.

No Client Secret is stored, embedded or transmitted. The [official current OpenAPI document](https://developer.trakt.tv/openapi.json) requires only `code` + `client_id` for device-token polling, marks `client_secret` deprecated and server-only, and assigns the OAuth endpoints to `https://auth.trakt.tv`. The [authentication guide](https://developer.trakt.tv/docs/authentication-oauth) explains device authorization and single-use refresh-token rotation. This fork uses that current public-client contract.

Access tokens, refresh tokens and private device codes are stored as authenticated ciphertext using this app installation’s Android Keystore key, scoped to the initiating profile and credential purpose. Existing plaintext preferences are migrated on access. A copied preference file does not carry the Keystore key. Token rotation is written together; token-bearing DTO diagnostics are redacted. Runtime account approval and real service playback still need device verification.

## Premiumize readiness

Premiumize’s [official API](https://www.premiumize.me/api) accepts either a viewer’s personal API key or an OAuth access token through `Authorization: Bearer`. Existing Syclop paths support key validation, encrypted storage, direct resolution, cache checks and cloud-library reads. Until Syclop has its own Premiumize application Client ID, its account row opens personal API-key entry, so a viewer does not need to register an application.

Premiumize device-code authorization is also present, but requires a Syclop-owned `PREMIUMIZE_CLIENT_ID` supplied by the maintainer at build time. No application registration or live Premiumize account approval has been performed here. Its inherited device polling treats `slow_down` as pending without increasing the delay; that path needs refinement and real testing before being described as production-ready. The personal-key path does not use device polling.

## Local Syclop account mode

Syclop has no central Nuvio/Syclop cloud account or cloud synchronization. Account Settings shows local profile management and links to the independent tracking/streaming account sections. Its local watch state remains on the TV; separately connected providers can synchronize through their own authenticated service. The disabled upstream account transport and updater remain unavailable.
