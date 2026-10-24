# Syclop MyAnimeList return page

This static page is prepared for `https://dintalframe.github.io/Syclop/mal/`. It is **not published yet**. The exact HTTPS URL must be live and registered with MyAnimeList before enabling the native relay.

The phone receives MAL's temporary authorization code and opaque state here. After a deliberate button press, it navigates to the private IPv4 TV callback at port 17893, path `/mal/callback`. Syclop validates the exact pending state/profile/expiry, then exchanges the code directly with MAL using the original registered HTTPS redirect and the PKCE verifier retained on the TV. The HTTPS host receives the authorization URL, but never the access/refresh token or verifier. There is no analytics, network fetch, storage or third-party script in this page. The one-time URL is removed from visible browser history after reading.

Version 1 supports RFC1918 private IPv4 home networks only. The state is `syclop-mal-v1.` plus unpadded base64url UTF-8 JSON with exactly two fields: `nonce` (32 random bytes encoded as 43 base64url characters) and `return_uri` (canonical `http://PRIVATE_IPV4:17893/mal/callback`). No username, query or fragment is permitted in that URI. The callback listener is active only for the five-minute native connection attempt. A browser unable to navigate to the LAN can copy the original registered callback URL into the active TV connection as a manual fallback.

Run the protocol/routing checks with:

```sh
node --test docs/mal/relay-core.test.mjs
```

Publishing requires the owner's approval to create/update the public Syclop source fork and enable GitHub Pages from the `docs/` directory. This source is part of the GPLv3 Syclop fork; retain the upstream license and attribution. No account credential is included in this page.
