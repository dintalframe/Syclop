# Episode source searches

Episode searches hide reported season/series packs and multi-episode batches. For example, searching The Big Bang Theory S01E04 omits a `S01-S12 Complete` release while retaining an individual `S01E04` candidate. The filter runs before source ranking, result limits and pagination, including per-provider lists, All, autoplay and the player's episode source picker. Existing bitrate/quality/file-size sorting applies to the remaining candidates.

Ordinary Express searches use the episode query scope instead of also requesting the season scope. Native anime searches keep their existing anime scope, with reported packs removed before row and subpage budgets. A provider offering only packs may now yield no visible results; Syclop does not substitute another episode.

## What counts as a reported pack

The shared `EpisodeSourcePolicy` checks release titles, filenames, torrent names and structured provider season/episode lists. It recognizes multi-season ranges, complete-season/series labels, qualified anime batches and multi-episode labels/ranges. A standalone season marker without an episode clue is also treated as a pack. A filename or file-index hint for one leaf does not override an explicit pack declaration.

These are provider claims, not media inspection. Unknown/unlabelled releases remain candidates rather than being declared verified episodes. Requested `resolve.season` and `resolve.episode` values are never used as evidence that a release is a pack or a single episode. Movie searches bypass this episode-only policy.

## Selected-file guard

When resolving an ordinary episode, the shared Debrid selector requires one actual playable leaf whose basename declares the requested season and episode. An explicit filename for another episode cannot override the request. Conflicting matched filename hints, compound/range/fractional labels, ambiguous encodes and incomplete coordinates fail selection. Unreliable provider file indices cannot select another episode or cause a largest-file fallback.

The existing native MyAnimeList proof path keeps its separately validated relative, absolute and TVDB coordinates. This change does not derive anime offsets from filenames or titles. Filename checks establish declared coordinates; connected-service authorization and selected-file metadata checks remain separate steps.

## Verification

Host regressions cover the Big Bang Theory case, all-pack empty results, single-season markers, anime batches, native MAL coordinates, movie bypass and filtering before limits. Resolver tests exercise the actual AllDebrid and Real-Debrid code with synthetic service responses, checking that wrong-episode and archive files never reach unlock/download calls. Shared selector tests also cover Premiumize and TorBox.

The local Android bench has two account-free source-card fixtures:

```sh
./tools/conda-bench.sh build --test --ui-tests
./tools/conda-bench.sh --serial emulator-5580 episode-sources \
  --apk ../dist/development/syclop-arm64-debug.apk
```

The fixtures use production filtering and source cards, inject real D-pad events, assert the focused/clicked episode card, and save two fresh screenshots. They have no accounts, indexer requests, media URL or playback. A passing fixture does not establish live provider availability or Shield playback. Physical-device network debugging remains off; this change is tested on the isolated emulator bench before any later device installation.

The development check passed 1,441 selected JVM tests in 197 suites, 91 bench-tool tests and both API 34 source-card fixtures. Both new screenshots were inspected. Debug app/test packaging passed and both app APKs exclude the local P2P engine. This remains development evidence, without a stable-release promotion.
