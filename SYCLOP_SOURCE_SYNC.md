# Source discovery, resolution and watch progress

These changes are development work. They do not promote an APK to stable, add an indexer account, or change the physical TV's debugging state.

## Progressive source searches

Express discovery emits admitted results as requests finish rather than waiting for every provider. The selected card retains its stable identity when later results sort above it. The search carries its own immutable diagnostics, including provider completion, mapping/coordinate failures, rate limits, timeouts, parser failures, title mismatches, hidden packs, invalid hashes and duplicates. An empty list can explain these reasons without exposing request URLs or account data. Retry currently starts a new search; retrying only failed providers remains future work.

Requests have bounded provider, origin and overall budgets. HTTP concurrency is limited globally and per origin, including redirect hops. HTTP 429 responses establish an origin cooldown using Retry-After; no retry loop bypasses it. Superseding a result snapshot cancels its availability enrichment. The existing memory-only discovery session can outlive a UI collector for navigation reuse; configuration/scope checks reject its obsolete output, and a replacement request invalidates the old session.

Native anime searches use up to three distinct English, Romaji and Japanese title families from the same verified mapping identity used for selected-file checks. Catalog-relative, TVDB season/episode and absolute release coordinates remain separate. A verified TVDB mapping can be used without an absolute episode number; Syclop does not guess an absolute offset. The experimental AniList JavaScript prototype is not activated by this change.

[Reported packs remain hidden](SYCLOP_EPISODE_SOURCES.md) before ranking and limits. Unknown release labels are candidates, not verified episode matches.

## Availability and selected-source resolution

Source discovery and authorized playback are separate. No local P2P engine or peer connection is enabled. A candidate must still resolve through the viewer's connected service and the existing playback-grant checks.

Premiumize and TorBox cache checks are bounded and account-scoped. Observations carry the profile, profile-selection revision, credential revision, canonical hash and expiry. Positive observations last 30 seconds, negative observations 10 seconds and unknown observations 5 seconds. Missing or malformed API responses mean unknown. AllDebrid and Real-Debrid do not receive unsupported bulk cache requests; unknown availability stays eligible for selected-source resolution. One service's negative observation cannot hide a source that another connected service might play. Source-search caches retain raw admitted candidates so temporary negatives cannot erase them for the longer discovery-cache lifetime.

The selected-source resolver has a 30-second total deadline and 12-second per-service deadline. It reports processing, not cached, expired authorization, rate limiting, timeout, wrong selection, network failure and unavailability separately. A wrong selected episode is terminal rather than a request to refresh the entire source search. Cancelling the last waiter cancels its shared operation; another active waiter keeps ownership. Account-scoped rate-limit cooldowns apply to repeated selections. Approved fallback remains limited to neutral hashes; a provider-bound URL is never rebound to another service.

Available inspected video/audio fields override conflicting release-name claims in ranking and filters. Unsupported or unobserved details remain unknown, including an exact surround layout without a matching channel mask. TrueHD alone does not prove Atmos, and DTS-HD alone does not prove DTS-HD MA. Size divided by runtime is a container-average bitrate estimate, not a measured video bitrate. Provider estimates remain labelled separately from selected-file facts. Inspecting every result's tracks is not enabled.

## Immediate local progress and cross-device changes

Saved internal-player progress updates Home locally after playback returns, without waiting for Trakt. Playback uses periodic checkpoints and pause, exit and completion saves; it does not write every position change. When Trakt supplies Continue Watching, that progress retains the profile and connected-account identity captured when playback began. It can survive a restart without letting a different account inherit the optimistic overlay. An old delayed episode completion retains its actual captured viewing time instead of receiving a newer timestamp after network work.

The Continue Watching row also avoids a render loop from the deprecated focus-restoration callback. Its modern fallback observes a stable derived visible index rather than reading changing layout information during composition. Remembered visible-card focus is preserved.

Home reconciles a stale paused episode with newer same-show progress or a completed episode's Next Up seed. A newer deliberate rewatch remains visible. Returning to Home triggers a throttled activity check and refreshes the row; it does not wait for the activity itself to resume.

A local overlay is retired only after a successful, owned remote read confirms the matching progress. Optimistic cloud entries and missing or failed responses cannot acknowledge it. A bounded snapshot can acknowledge only positive matching records it actually returned; omission never acknowledges a write. Retirement compares the exact stored record so a concurrent newer local save survives. After acknowledgement, a later remote removal is no longer resurrected by that local overlay.

Cross-device synchronization is near real time, not a cloud push guarantee. The existing Trakt activity poll checks approximately once a minute, fetches changed data and backs off on failures. Returning to the foreground also requests activity checks. Simkl uses its activity/delta refresh policy, and MyAnimeList refreshes its supported list data through its existing lifecycle/manual flows. This does not create cross-device synchronization of local app settings or provider credentials. An offline TV retains saved progress; it cannot acknowledge a cloud write without a confirming response.

The durable local overlay currently covers native internal playback with a known authenticated Trakt identity. External-player progress continues to use its separately documented [handoff and tracking boundaries](SYCLOP_PLAYBACK_CONTROLS.md#external-players).

## Browsing during refresh

Equivalent provider-list updates are filtered before category loading can be cancelled. A partially loaded category keeps its visible rows and in-flight catalog requests when an unrelated native catalog emits an update. Real profile, catalog-query or configuration changes still invalidate the request and reset its scoped presentation.

## Verification boundaries

The JVM regressions use synthetic account storage, mappings and service responses. The fixed `progressive-sources` and `continue-watching` emulator suites use production card/section presentation with synthetic data and real D-pad events. They create no account, media URL or playback. These checks do not prove live provider availability, actual cloud update latency or picture/sound on a Shield. See the [bench guide](tools/ANDROID_BENCH.md) for commands and retained results.
