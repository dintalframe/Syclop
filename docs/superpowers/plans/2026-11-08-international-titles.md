# International Title Search Repair Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Find the same international title using its verified original spelling and boundedASCII fallback without changing media identity or episode coordinates.

**Architecture:** Extend existing TMDB find-result fields and add a narrow original-title lookup. Cache optional enrichment inside the existing Express metadata cache; reuse current provider query rendering/dedup/budgets. Add a narrow reported-year guard for newly enriched rows.

**Tech Stack:** Kotlin, existingRetrofit/Moshi/TMDBService, Express HTTP/parser, JUnit/MockWebServer.

**Spec:** docs/superpowers/specs/2026-11-08-international-titles-design.md

## Global Constraints
- ExistingIMDb/type/year/S/E and nativeMAL proofs stay authoritative; no guessed episode remapping.
- Canonical/original/ASCII variants at most3; strings<=256chars/noISOcontrols, retainnon-Latinletters.
- Original title comes only from successful unambiguous sameIMDb/type TMDB find result with positiveID and no conflicting known premiereyear; optional lookup bounded1500ms, canonical fallback on ordinaryfailure and caller cancellation propagates.
- Cache originaltitle with each Cinemeta record (existing100record ceiling); no fullenrichment calls/newdependencies/private-service actions.
- Existingconnected-service policy, templates/requestbudgets/URLdedup/nativeMAL paths unchanged; Shield, stable0.1.1 and originalWIP untouched.

## Review Focus
- EnglishCinemetaForbiddenLove must yield verifiedAşk-ıMemnu andAsk-iMemnu without a hardcoded title table.
- WrongIMDb/type/year metadata, empty/malformedoriginaltitles and cancellation cannot create aliases or stall indefinitely.
- Queryvariants preserveexact2008/S02E01/IDs; ASCII duplicates/IMDb-onlyURLs do not fan out.
- Clear1975remake filenames cannot be admitted as2008; legitimate2009season2/absentyear still works.
- NativeMAL proof aliases, non-Latinqueries, episode policy and provider budgets retain their existing behavior.

### Task 1: Identity-bound international title variants

**Files:** core/express/ExpressMedia.kt; core/tmdb/TmdbService.kt; data/remote/api/TmdbApi.kt; core/express/ExpressAddonAdapter.kt only for the narrow admission guard. Tests newExpressInternationalTitleTest and relevant existing ExpressMetadataResolverTest/ExpressAdapterTest/NativeMal*. No unrelated APIs/services.

**Interfaces:** Add `TmdbService.findOriginalTitle(imdbId:String,mediaType:String,expectedYear:Int?):String?` using existingfindByExternalId; optional DTOoriginal_title/original_name/release_date/first_air_date fields. Resolvercache may use a small private metadata record rather than mutableJSON. Keep publicExpressMetadataSource API/callers. Reuse queryTitles()/variables()/providerQueries() so progressive/direct paths both benefit.

- [ ] Write failing behavior tests using the realresolver and controlledTMDB/Cinemeta responses; a currentEnglish-only search mustfail the expectedoriginal/ASCIIquery assertions. Testbounds/dedup/cancellation/yearhints and exactcoordinates; avoid asserting onlymocks/constants.
- [ ] Rootruns RED with the sharedbuild frozen; record failures beforeproductionrepair.
- [ ] Implement the bounded lookup/cache/queryvariants and narrow international yearhintguard; no fullenrichment/aliases fromunverifiedtitle search.
- [ ] Rootruns GREEN on Express/NativeMAL and fullJVM suite after allUI changes settle. Include queryURL assertions and non-Englishmovie/series cases.
- [ ] Selfreview/commit ownedfiles and report exactcommands/results/remaining coverage limits. Publicprovider availability is not inferred from mockfixtures.

### Task 2: Make series aliases work without a TMDB key

**Files:** ExpressMedia.kt and focused ExpressInternationalTitleTest.kt; TmdbService.kt if avoiding an empty-key request; existing LicensesAttributionsScreen.kt and strings_syclop_licenses.xml for the standard data-provider credit.

**Interfaces:** Reuse ExpressHttpClient.get and identity-bound metadata cache. Fixed TVmaze IMDb lookup only; no provider settings or search interfaces.

- [ ] Add and run RED for unconfigured/unavailable TMDB and a trusted TVmaze IMDb response producing canonical/original/ASCII URLs and cached episode reuse; reject wrong IMDb/year/missing year, unsafe redirects, malformed/invalid metadata and ordinary failure while propagating cancellation.
- [ ] Add the bounded series-only fallback from the amended spec, with standard in-app provider attribution.
- [ ] Run Express/anime/episode-policy GREEN, then root reruns integrated unit/lint/build and independent review.
- [ ] Commit only after root has reviewed the final evidence.
