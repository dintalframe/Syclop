/** Standalone title-alias experiment. This is not an executable Magnify package or an app hook. */
const ENDPOINT = 'https://graphql.anilist.co';
const FIELDS = 'id idMal type format startDate { year } title { romaji english native } synonyms';
const ID_QUERY = `query AnimeTitleById($id: Int, $idMal: Int) { Media(id: $id, idMal: $idMal, type: ANIME) { ${FIELDS} } }`;
const TITLE_QUERY = `query AnimeTitlesByName($search: String!) { Page(page: 1, perPage: 5) { media(search: $search, type: ANIME) { ${FIELDS} } } }`;
const MAX_BYTES = 64 * 1024;
const INTERVAL = 2100;
const CONTROL = /[\p{Cc}\p{Cf}\p{Zl}\p{Zp}]/u;
const FORMATS = new Set(['TV', 'TV_SHORT', 'MOVIE', 'SPECIAL', 'OVA', 'ONA', 'MUSIC']);
class InvalidResponse extends Error {}
class ResponseTooLarge extends Error {}
class Deadline extends Error {}

function safeTitle(value) {
  return typeof value === 'string' && value.length <= 256 && value.trim().length > 0 && !CONTROL.test(value);
}
function normalized(value) {
  return value.normalize('NFKC').toLowerCase().replace(/[\p{P}\p{S}]+/gu, ' ').replace(/\s+/gu, ' ').trim();
}
function numericId(value) {
  if (typeof value === 'string' && /^[1-9]\d{0,9}$/.test(value)) value = Number(value);
  return Number.isInteger(value) && value > 0 && value <= 2147483647 ? value : null;
}
function throwIfAborted(signal) {
  if (signal?.aborted) throw signal.reason ?? new DOMException('Operation aborted', 'AbortError');
}
async function abortable(operation, signal) {
  if (signal?.aborted) {
    Promise.resolve(operation).catch(() => {});
    throwIfAborted(signal);
  }
  if (!signal) return operation;
  let onAbort;
  const cancelled = new Promise((_, reject) => {
    onAbort = () => reject(signal.reason ?? new DOMException('Operation aborted', 'AbortError'));
    signal.addEventListener('abort', onAbort, { once: true });
  });
  try { return await Promise.race([operation, cancelled]); }
  finally { signal.removeEventListener('abort', onAbort); }
}
function cancelBody(response) {
  try { Promise.resolve(response.body?.cancel()).catch(() => {}); } catch { /* Already closed. */ }
}
async function readJson(response, signal) {
  const declared = response.headers?.get('content-length');
  if (declared && /^\d+$/.test(declared) && Number(declared) > MAX_BYTES) {
    cancelBody(response);
    throw new ResponseTooLarge();
  }
  if (!response.body?.getReader) throw new InvalidResponse();
  const reader = response.body.getReader();
  const cancel = () => { try { Promise.resolve(reader.cancel()).catch(() => {}); } catch { /* Closed. */ } };
  signal.addEventListener('abort', cancel, { once: true });
  const chunks = [];
  let size = 0;
  try {
    throwIfAborted(signal);
    while (true) {
      const part = await reader.read();
      throwIfAborted(signal);
      if (part.done) break;
      if (!(part.value instanceof Uint8Array)) throw new InvalidResponse();
      size += part.value.byteLength;
      if (size > MAX_BYTES) throw new ResponseTooLarge();
      chunks.push(part.value);
    }
    const bytes = new Uint8Array(size);
    let offset = 0;
    for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
    try { return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes)); }
    catch { throw new InvalidResponse(); }
  } finally {
    signal.removeEventListener('abort', cancel);
    cancel();
    try { reader.releaseLock(); } catch { /* A cancelled reader may already be detached. */ }
  }
}
function aliases(media) {
  if (!media || typeof media !== 'object' || !Number.isInteger(media.id) || numericId(media.id) === null ||
      (media.idMal != null && (!Number.isInteger(media.idMal) || numericId(media.idMal) === null)) || media.type !== 'ANIME' ||
      !FORMATS.has(media.format) || !media.title || typeof media.title !== 'object' ||
      !Array.isArray(media.synonyms)) throw new InvalidResponse();
  return [media.title.romaji, media.title.native, media.title.english, ...media.synonyms.slice(0, 20)]
    .filter(safeTitle).filter(value => normalized(value).length > 0);
}
function titlesFor(original, alternatives = []) {
  const titles = [], seen = new Set();
  for (const value of [original, ...alternatives]) {
    if (!safeTitle(value)) continue;
    const identity = normalized(value);
    if (!identity || seen.has(identity)) continue;
    seen.add(identity);
    titles.push(value);
    if (titles.length === 6) break;
  }
  return titles;
}
function result(original, reason, match = 'original', alternatives = []) {
  return { titles: titlesFor(original, alternatives), match, reason };
}
function formatMatches(media, query) {
  return !(String(query.type).toLowerCase() === 'movie' && media.format !== 'MOVIE') &&
    !(Number(query.episode) > 0 && media.format === 'MOVIE');
}
function cooldownTime(response, now) {
  const deadlines = [];
  const retry = response.headers?.get('retry-after');
  if (retry) {
    const value = /^\d+(?:\.\d+)?$/.test(retry.trim()) ? now + Number(retry) * 1000 : Date.parse(retry);
    if (Number.isFinite(value) && value > now) deadlines.push(value);
  }
  const reset = response.headers?.get('x-ratelimit-reset');
  if (reset && /^\d+$/.test(reset)) {
    const value = Number(reset) * 1000;
    if (Number.isFinite(value) && value > now) deadlines.push(value);
  }
  return deadlines.length ? Math.max(now + INTERVAL, ...deadlines) : now + 60_000;
}

/** Each factory owns a small cache and request budget; the default export shares one factory. */
export function createAnimeTitleResolver({ fetchImpl = globalThis.fetch, clock = Date.now, timeoutMs = 3000 } = {}) {
  if (typeof fetchImpl !== 'function' || typeof clock !== 'function') throw new TypeError('fetchImpl and clock must be functions');
  if (!Number.isInteger(timeoutMs) || timeoutMs < 10 || timeoutMs > 3000) throw new TypeError('timeoutMs must be an integer from 10 to 3000');
  const cache = new Map();
  let busy = false, lastRequest = -Infinity, cooldown = -Infinity;
  const now = () => {
    const value = clock();
    if (!Number.isFinite(value)) throw new TypeError('clock must return finite milliseconds');
    return value;
  };
  function remember(key, entry, time) {
    cache.delete(key);
    cache.set(key, { ...entry, expires: time + (entry.match === 'original' ? 30_000 : 600_000) });
    while (cache.size > 64) cache.delete(cache.keys().next().value);
  }

  return async function resolve(context, { signal } = {}) {
    throwIfAborted(signal);
    const query = context?.query;
    const original = safeTitle(query?.title) ? query.title : null;
    if (!query || typeof query !== 'object' || !original || !normalized(original)) return result(original, 'invalid-query');
    if (query.type !== 'anime' && query.anime !== true) return result(original, 'not-anime');
    const suppliedMal = query.malId !== undefined;
    const suppliedAniList = query.anilistId !== undefined;
    const malId = suppliedMal ? numericId(query.malId) : null;
    const anilistId = suppliedAniList ? numericId(query.anilistId) : null;
    if ((suppliedMal && malId === null) || (suppliedAniList && anilistId === null)) return result(original, 'invalid-catalog-id');
    const byId = suppliedMal || suppliedAniList;
    if (!byId && Number(query.season) > 1) return result(original, 'season-needs-catalog-id');
    const year = numericId(query.year);
    if (!byId && year === null) return result(original, 'missing-year');
    const movie = String(query.type).toLowerCase() === 'movie';
    const episode = Number(query.episode) > 0;
    const key = JSON.stringify(byId ? ['id', anilistId, malId, movie, episode] : ['title', normalized(original), year, movie, episode]);
    const time = now();
    const cached = cache.get(key);
    if (cached && cached.expires > time) {
      cache.delete(key); cache.set(key, cached);
      return result(original, cached.reason, cached.match, cached.alternatives);
    }
    if (cached) cache.delete(key);
    if (time < cooldown) return result(original, 'rate-limited');
    if (busy) return result(original, 'busy');
    if (time - lastRequest < INTERVAL) return result(original, 'throttled');
    busy = true;
    lastRequest = time;
    const controller = new AbortController();
    const onAbort = () => controller.abort(signal.reason ?? new DOMException('Operation aborted', 'AbortError'));
    signal?.addEventListener('abort', onAbort, { once: true });
    const timer = setTimeout(() => controller.abort(new Deadline()), timeoutMs);
    let entry;
    try {
      const wire = await abortable((async () => {
        const response = await fetchImpl(ENDPOINT, {
          method: 'POST', credentials: 'omit', redirect: 'error', cache: 'no-store', referrerPolicy: 'no-referrer',
          headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
          body: JSON.stringify({ query: byId ? ID_QUERY : TITLE_QUERY,
            variables: byId ? { id: anilistId, idMal: malId } : { search: original } }),
          signal: controller.signal
        });
        throwIfAborted(controller.signal);
        if (response.status === 429) { cancelBody(response); return { rateLimited: cooldownTime(response, now()) }; }
        // An invalid or stalled body still consumed the server's quota.
        if (response.headers?.get('x-ratelimit-remaining')?.trim() === '0') cooldown = cooldownTime(response, now());
        if (!response.ok) { cancelBody(response); return { failed: true }; }
        return { payload: await readJson(response, controller.signal) };
      })(), controller.signal);
      throwIfAborted(signal);
      if (wire.rateLimited !== undefined) {
        cooldown = wire.rateLimited;
        return result(original, 'rate-limited');
      }
      if (wire.failed) entry = { reason: 'request-failed', match: 'original', alternatives: [] };
      else {
        const payload = wire.payload;
        if (!payload || typeof payload !== 'object' || (payload.errors && (!Array.isArray(payload.errors) || payload.errors.length)) || !payload.data) throw new InvalidResponse();
        if (byId) {
          const media = payload.data.Media;
          if (media === null) entry = { reason: 'not-found', match: 'original', alternatives: [] };
          else {
            const alternatives = aliases(media);
            const correctIds = (!suppliedAniList || media.id === anilistId) && (!suppliedMal || media.idMal === malId);
            entry = !correctIds ? { reason: 'catalog-id-mismatch', match: 'original', alternatives: [] } :
              !formatMatches(media, query) ? { reason: 'format-mismatch', match: 'original', alternatives: [] } :
                { reason: 'catalog-id', match: 'catalog-id', alternatives };
          }
        } else {
          const media = payload.data.Page?.media;
          if (!Array.isArray(media) || media.length > 5) throw new InvalidResponse();
          const matches = media.map(item => ({ item, alternatives: aliases(item) })).filter(({ item, alternatives }) =>
            item.startDate?.year === year && alternatives.some(title => normalized(title) === normalized(original)));
          entry = matches.length > 1 ? { reason: 'ambiguous-title-year', match: 'original', alternatives: [] } :
            matches.length === 0 ? { reason: media.length ? 'title-year-mismatch' : 'not-found', match: 'original', alternatives: [] } :
              !formatMatches(matches[0].item, query) ? { reason: 'format-mismatch', match: 'original', alternatives: [] } :
                { reason: 'exact-title-year', match: 'title-year-candidate', alternatives: matches[0].alternatives };
        }
      }
    } catch (error) {
      throwIfAborted(signal);
      const reason = error instanceof Deadline ? 'timeout' : error instanceof ResponseTooLarge ? 'response-too-large' :
        error instanceof InvalidResponse ? 'invalid-response' : 'request-failed';
      entry = { reason, match: 'original', alternatives: [] };
    } finally {
      clearTimeout(timer);
      signal?.removeEventListener('abort', onAbort);
      busy = false;
    }
    remember(key, entry, now());
    return result(original, entry.reason, entry.match, entry.alternatives);
  };
}

const sharedResolver = createAnimeTitleResolver();
export default async function resolveAnimeTitles(context, options) {
  return sharedResolver(context, options);
}

/** Indexer transport owns its deadline; this adapter supplies no provider schema or credentials. */
export function createAnimeSearchModule({ searchIndexer, resultKey, resolveTitles, ...resolverOptions } = {}) {
  if (typeof searchIndexer !== 'function' || typeof resultKey !== 'function') throw new TypeError('searchIndexer and resultKey must be functions');
  if (resolveTitles !== undefined && typeof resolveTitles !== 'function') throw new TypeError('resolveTitles must be a function');
  const isolated = ['fetchImpl', 'clock', 'timeoutMs'].some(name => resolverOptions[name] !== undefined);
  const resolve = resolveTitles ?? (isolated ? createAnimeTitleResolver(resolverOptions) : resolveAnimeTitles);
  return async function search(context, { signal } = {}) {
    throwIfAborted(signal);
    const resolved = await abortable(Promise.resolve().then(() => resolve(context, { signal })), signal);
    throwIfAborted(signal);
    if (!resolved || !Array.isArray(resolved.titles) || resolved.titles.length > 6 || !resolved.titles.every(safeTitle) ||
        !['catalog-id', 'title-year-candidate', 'original'].includes(resolved.match) || typeof resolved.reason !== 'string') {
      throw new TypeError('resolveTitles must return the bounded title resolver result');
    }
    const results = [], failedQueries = [], seen = new Set();
    for (const title of resolved.titles) {
      throwIfAborted(signal);
      if (results.length >= 300) break;
      try {
        const cloned = structuredClone(context);
        cloned.query.title = title;
        const found = await abortable(Promise.resolve().then(() => searchIndexer(cloned, { signal })), signal);
        throwIfAborted(signal);
        if (!Array.isArray(found)) throw new TypeError('Indexer must return an array');
        for (const item of found.slice(0, 100)) {
          if (results.length >= 300) break;
          let identity;
          try {
            identity = resultKey(item);
            if (!((typeof identity === 'string' && identity.length > 0 && identity.length <= 1024) ||
                (typeof identity === 'number' && Number.isFinite(identity)))) throw new TypeError('Invalid result key');
          } catch { if (!failedQueries.includes(title)) failedQueries.push(title); continue; }
          if (!seen.has(identity)) { seen.add(identity); results.push(item); }
        }
      } catch {
        throwIfAborted(signal);
        if (!failedQueries.includes(title)) failedQueries.push(title);
      }
    }
    return { ...resolved, results, failedQueries };
  };
}
