# International source title repair

User requested better international source finding, using the Turkish2008 Aşk-ı Memnu as the concrete case. Public Cinemeta response for verifiedIMDbtt1286677 contains onlyForbidden Love, year2008–2010 (diagnostic saved under service-development/menu-motion-2026-11-08). Unicode folding alone cannot repair that case.

Use the existing identity-based TMDB find endpoint to obtain only the original title for the sameIMDb/type, not title-search guesses or a full enrichment fan-out. Treat this as optional bounded enrichment, cached with the existing Cinemeta record; errors must preserve the existing canonical search and cancellation must propagate. Search canonical/original/ASCII-folded variants with a hard cap3, explicitTurkish dotlessı→i and Unicode normalization. Preserve non-Latin text; discard invalid/control/overlong variants. Keep nativeMAL proof aliases and exactIDs/year/season/episode unchanged, and deduplicate rendered provider URLs.

Do not blindly admit another remake: reject clear conflicting title-prefix year hints whenever a distinct search spelling is added, including canonical-only folding (series hints before premiere; movie differences beyond one year), without treating later episode-air years/remaster dates as a new series identity. Missing year hints remain usable. Known title/filename disagreement must not let the1975version masquerade as2008. No new media service, grants, executable plugin runtime, provider-template rewrite or episode-number guessing; original79long versus158short versions remain distinct.

Service coverage is separate from matching. Official KanalD publishes episodes, but this does not prove an installedSyclop connector or regional/subtitle availability. No private service/account playback test or source availability claim is authorized by these fixtures.

## Evidence-backed metadata fallback

The release build has no TMDB key configured (only configuration presence was inspected; no secret copied). A key-dependent lookup would not fix the concrete user case. Public TVmaze IMDb lookup returns exact IMDb tt1286677, positive ID18892, Turkish name Aşk-ı Memnu and premiere2008-09-04. Add this as a series-only fallback after unavailable TMDB original-title enrichment. This is a builder transport choice within the requested identity-bound title repair, not a connected playback service.

Use the existing isolated HTTP client, fixed HTTPS api.tvmaze.com origin, same-origin redirects, response cap512KiB, exact returned IMDb and matching known premiere year, positive record ID and normalized valid title. Keep optional enrichment total<=2500ms, cancellation propagation, canonical fallback, cache ceiling100, query cap3 and provider request budgets. Movies keep TMDB enrichment. Add the standard in-app TVmaze/CC BY-SA attribution using the existing data-provider credit pattern. No title search, account requirement, source coverage claim or episode remapping.
