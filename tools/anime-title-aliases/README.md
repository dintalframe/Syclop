# Anime title alias prototype

This standalone JavaScript experiment expands an anime title into bounded search variants. It is **not installed in Syclop**, is not an APK change, and is not a Magnify package. Node.js 22 or newer can run its dependency-free tests.

## Compatibility and language choice

Magnify's [Full manifest](https://magnify.pages.dev/manifest/full) currently declares `type: "express"`. Its package contains declarative provider definitions: `base_url`, media scopes, URL templates, keywords and parser mappings. An exported asynchronous JavaScript function is not a verified Magnify entry point.

Syclop interprets those definitions in Kotlin and deliberately rejects downloaded executable plugins. Its `ExpressMedia.queryTitles()` already uses alternate titles from native MyAnimeList metadata, with a three-query limit per anime provider. This prototype defines a separate interface for experimentation; adding its file to a provider URL will not run it.

**Production recommendation: Kotlin.** Enrich the native metadata once and share the validated aliases across providers, source admission and selected-file validation. Use JavaScript for an independent provider/server adapter when that host explicitly supports it. Adding a JavaScript engine to Syclop is unnecessary for a public metadata lookup.

## Interface

```js
import resolveAnimeTitles, {
  createAnimeSearchModule,
  createAnimeTitleResolver,
} from './anilist-title-aliases.mjs';

const context = {
  query: {
    title: 'Frieren: Beyond Journey’s End',
    year: 2023,
    type: 'anime',
    season: 1,
    episode: 2,
    malId: 52991, // Optional exact per-entry catalog identity, not a franchise ID.
    // anilistId: 154587,
  },
};

const aliases = await resolveAnimeTitles(context);
// { titles: [...], match: 'catalog-id', reason: ... }
```

`createAnimeTitleResolver({ fetchImpl, clock, timeoutMs })` creates an isolated resolver/cache for tests or a separate host. The default export reuses one resolver. `fetchImpl` must implement the standard Fetch/Response APIs, including streaming bodies and cancellation; omit it for the runtime's normal Fetch API. The default lookup deadline is three seconds.

The proposed `createAnimeSearchModule` adapter accepts an existing indexer callback. It does not implement an indexer's URL, parsing rules or authentication, and cannot promise compatibility with an unspecified provider runtime:

```js
const search = createAnimeSearchModule({
  // Supply your own tested, bounded indexer transport/parser.
  searchIndexer: async (variant, { signal }) => {
    return searchExistingIndexer(variant.query, { signal });
  },
  // Choose a stable key appropriate to that parser's actual result format.
  resultKey: row => `${row.infoHash.toLowerCase()}:${row.fileIdx ?? ''}`,
});

const controller = new AbortController();
const result = await search(context, { signal: controller.signal });
// { titles, match, reason, results, failedQueries }
```

The caller must implement `searchExistingIndexer` and choose a deduplication key that preserves distinct files. Queries run sequentially for at most six distinct names. Only `query.title` changes; year, season, episode and other context fields retain their values. Results have the callback's own format. Callback transports need their own deadlines, response limits and cancellation support.

Search modules without custom resolver options share the default lookup cache and quota. A host using custom transports should create **one** `createAnimeTitleResolver(...)` and pass it as `resolveTitles` to every search module. Creating independent factories per indexer would multiply requests. A lookup already in progress causes other uncached requests to use their original title immediately; this prototype does not queue or share a cancellable in-flight request.

## Matching and failure behavior

- An explicit `query.type: 'anime'` or `query.anime: true` is required. An ordinary movie/series request causes no AniList lookup.
- Prefer `anilistId` or `malId` belonging to the exact selected anime entry. The GraphQL response must match every supplied ID. Invalid or conflicting IDs retain the original title instead of triggering a fuzzy fallback.
- With no ID, use a small candidate page and require one exact normalized title/synonym plus the same start year. A numbered season above one needs an exact per-entry ID. A title-year candidate is **not verified catalog identity**, even if it is the only returned match.
- Keep the original name first, followed by Romaji, native Japanese, English and bounded synonyms, removing normalized duplicates and invalid names. Never remove arbitrary season numbers or invent release-absolute episode offsets.
- A failure, rate limit, ambiguous match, unsupported input or invalid response retains the original search name. Caller cancellation is propagated rather than converted to an empty successful result.
- Public metadata requests go only to `https://graphql.anilist.co`, with JSON GraphQL variables, no account headers/cookies, and no followed redirects. Only the selected numeric IDs or title are sent; the whole context is not serialized.
- Responses are limited to 64 KiB. The in-memory cache holds at most 64 entries, expiring positive metadata after ten minutes and negative results after thirty seconds. Uncached requests are spaced at least 2.1 seconds apart, with immediate original-title fallback rather than a long queue. Rate-limit responses and successful responses reporting an exhausted quota establish a cooldown; a missing usable retry/reset deadline falls back to sixty seconds. No automatic retries run.

This module performs metadata lookup and invokes the supplied search callback. It has no media transfer or playback implementation. Results still need Syclop's native episode/file validation and authorized Debrid resolution; an alias or indexer hit does not establish availability, quality, an English audio track or permission to play a URL.

## Run the host tests

```sh
node --test tools/anime-title-aliases/anilist-title-aliases.test.mjs
```

The tests use synthetic responses and callbacks. They do not contact AniList, indexers, connected accounts, an emulator or a TV. No Android build or device verification is implied by passing them.

## Before native integration

1. Keep one immutable exact-MAL alias snapshot shared by `ExpressMedia`, `NativeAnimeEpisodeProof` and selected-file verification. Merely appending aliases to search metadata would still leave alias-only files rejected by the existing proof. Do not weaken those checks or mint proof from a title-only candidate.
2. Preserve relative MAL episode, validated release-absolute episode and TVDB season/episode separately. Keep the identity fingerprint stable from search through playback authorization, even if metadata cache entries expire.
3. Test later seasons, split cours, remakes, movies, specials, conflicting IDs, unrelated search rows and actual-file matches before installing an Android change. Retain the existing original-title path during outages.
4. Review the intended deployment against AniList's API terms. Its current terms cover media data and restrict competing non-complementary list/tracker services; public, unauthenticated metadata is not an unrestricted-use grant. Whether Syclop's proposed use qualifies is unresolved here. This experiment does not enable AniList in the shipped app.

Official references: [request format](https://github.com/AniList/docs/blob/master/docs/guide/graphql/index.md), [Media query fields](https://github.com/AniList/docs/blob/master/docs/reference/query.md), [title ambiguity guidance](https://github.com/AniList/docs/blob/03281c0a4bbf0c7f2097e0c935cddaed1096aa65/docs/guide/graphql/queries/media.md), [rate limits](https://github.com/AniList/docs/blob/master/docs/guide/rate-limiting.md), and [terms](https://github.com/AniList/docs/blob/03281c0a4bbf0c7f2097e0c935cddaed1096aa65/docs/guide/terms-of-use.md). These can change; do not assume the normal 90-request rate is always available.
