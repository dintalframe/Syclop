import test from 'node:test';
import assert from 'node:assert/strict';
import resolveAnimeTitles, {
  createAnimeTitleResolver,
  createAnimeSearchModule,
} from './anilist-title-aliases.mjs';

const record = (changes = {}) => ({
  id: 154587,
  idMal: 52991,
  type: 'ANIME',
  format: 'TV',
  startDate: { year: 2023 },
  title: {
    romaji: 'Sousou no Frieren',
    english: 'Frieren: Beyond Journey’s End',
    native: '葬送のフリーレン',
  },
  synonyms: ['Frieren', 'Sōsō no Frieren'],
  ...changes,
});
const context = (changes = {}) => ({ query: {
  title: 'Frieren: Beyond Journey’s End', year: 2023,
  season: 1, episode: 2, type: 'anime', malId: 52991,
  ...changes,
} });
const json = (body, init = {}) => new Response(JSON.stringify(body), {
  ...init, headers: { 'Content-Type': 'application/json', ...init.headers },
});
function harness(records = record(), options = {}) {
  const requests = [];
  let now = 100_000;
  const resolve = createAnimeTitleResolver({
    clock: () => now,
    fetchImpl: async (url, init) => {
      requests.push({ url, init, payload: JSON.parse(init.body) });
      const data = requests.at(-1).payload.query.includes('Page')
        ? { Page: { media: Array.isArray(records) ? records : [records] } }
        : { Media: records };
      return json({ data });
    }, ...options,
  });
  return { resolve, requests, advance: (ms) => { now += ms; } };
}

test('fixed public POST uses variables and sends no account headers or credentials', async () => {
  const h = harness();
  const result = await h.resolve(context());
  assert.equal(result.match, 'catalog-id');
  assert.deepEqual(result.titles.slice(0, 3), [
    'Frieren: Beyond Journey’s End', 'Sousou no Frieren', '葬送のフリーレン',
  ]);
  const { url, init, payload } = h.requests[0];
  assert.equal(url, 'https://graphql.anilist.co');
  assert.equal(init.method, 'POST');
  assert.equal(init.credentials, 'omit');
  assert.equal(init.redirect, 'error');
  const headers = new Headers(init.headers);
  assert.match(headers.get('Content-Type'), /application\/json/);
  for (const name of ['Authorization', 'Cookie', 'Proxy-Authorization']) assert.equal(headers.get(name), null);
  assert.ok(Object.values(payload.variables).includes(52991));
  for (const field of ['romaji', 'english', 'native', 'synonyms', 'idMal', 'format', 'startDate'])
    assert.ok(payload.query.includes(field), field);
});

test('title search stays a candidate and safely encodes punctuation in JSON variables', async () => {
  const title = 'A "Quoted" Anime \\ Title';
  const h = harness(record({ title: { english: title, romaji: '別の題名', native: null }, synonyms: [] }));
  const result = await h.resolve(context({ title, malId: undefined }));
  assert.equal(result.match, 'title-year-candidate');
  assert.deepEqual(result.titles, [title, '別の題名']);
  assert.ok(h.requests[0].payload.query.includes('Page'));
  assert.ok(Object.values(h.requests[0].payload.variables).includes(title));
  assert.ok(!h.requests[0].payload.query.includes(title));
});

test('title candidates need an exact normalized name and the same year', async () => {
  for (const change of [
    { startDate: { year: 2024 } },
    { startDate: { year: null } },
    { title: { english: 'Frieren: Beyond Journey’s End Special' }, synonyms: [] },
    { type: 'MANGA' },
  ]) {
    const h = harness(record(change));
    const result = await h.resolve(context({ malId: undefined }));
    assert.equal(result.match, 'original');
    assert.deepEqual(result.titles, [context().query.title]);
  }
});

test('same-title same-year ambiguity never picks the first record', async () => {
  const h = harness([record(), record({ id: 200001, idMal: 60001 })]);
  assert.equal((await h.resolve(context({ malId: undefined }))).match, 'original');
});

test('catalog ID mismatch never falls back to a title guess', async () => {
  for (const changes of [{ idMal: 1 }, { id: 1 }]) {
    const h = harness(record(changes));
    const result = await h.resolve(context({ anilistId: 154587 }));
    assert.equal(result.match, 'original');
    assert.equal(h.requests.length, 1);
  }
});

test('exact sequel ID retains season and relative episode without deriving offsets', async () => {
  const input = context({ malId: 59978, year: 2026, season: 2, episode: 1 });
  const before = structuredClone(input);
  const h = harness(record({ id: 209000, idMal: 59978, startDate: { year: 2026 } }));
  assert.equal((await h.resolve(input)).match, 'catalog-id');
  assert.deepEqual(input, before);
});

test('unsafe or unsupported input avoids the network', async () => {
  for (const changes of [
    { type: 'series' }, { type: 'movie' }, { title: '' }, { title: 'x'.repeat(257) },
    { title: 'Bad\nTitle' }, { malId: -1 }, { malId: 1.5 }, { anilistId: 'wrong' },
    { malId: undefined, year: undefined }, { malId: undefined, season: 2 },
  ]) {
    const h = harness();
    assert.equal((await h.resolve(context(changes))).match, 'original', JSON.stringify(changes));
    assert.equal(h.requests.length, 0, JSON.stringify(changes));
  }
  assert.equal((await resolveAnimeTitles(context({ type: 'series' }))).match, 'original');
});

test('movie format cannot stand in for an anime episode or vice versa', async () => {
  const episode = harness(record({ format: 'MOVIE' }));
  assert.equal((await episode.resolve(context())).match, 'original');
  const movie = harness(record());
  assert.equal((await movie.resolve(context({ type: 'movie', anime: true, episode: null, season: null }))).match, 'original');
});

test('aliases are bounded, normalized, de-duplicated and exclude controls', async () => {
  const h = harness(record({ synonyms: [
    'SOUSOU NO FRIEREN', 'Sousou　no　Frieren', 'Bad\u0000Title', 'x'.repeat(257),
    'First Other Alias', 'Second Other Alias', 'Third Other Alias', ...Array(100).fill('Duplicate'),
  ] }));
  const result = await h.resolve(context());
  assert.ok(result.titles.length <= 6);
  assert.equal(result.titles.filter(t => /sousou/i.test(t)).length, 1);
  assert.ok(result.titles.every(t => t.length <= 256 && !/[\u0000-\u001f]/u.test(t)));
  assert.equal(result.titles[0], context().query.title);
});

test('positive cache is copied, reused between episodes, and expires', async () => {
  const h = harness();
  const first = await h.resolve(context());
  first.titles.push('Injected alias');
  const second = await h.resolve(context({ episode: 3 }));
  assert.ok(!second.titles.includes('Injected alias'));
  assert.equal(h.requests.length, 1);
  h.advance(10 * 60 * 1000 + 1);
  await h.resolve(context());
  assert.equal(h.requests.length, 2);
});

test('cached metadata does not replace the current caller title or leak query context', async () => {
  const h = harness();
  const first = context();
  first.account = { token: 'MUST_STAY_LOCAL' };
  first.query.privateProvider = 'https://private.example/?key=MUST_STAY_LOCAL';
  await h.resolve(first);
  assert.ok(!h.requests[0].init.body.includes('MUST_STAY_LOCAL'));
  const result = await h.resolve(context({ title: 'Sousou no Frieren' }));
  assert.equal(result.titles[0], 'Sousou no Frieren');
});

test('cache revalidates the caller ID constraints rather than reusing a partial key', async () => {
  const h = harness();
  await h.resolve(context());
  h.advance(2101);
  assert.equal((await h.resolve(context({ anilistId: 1 }))).match, 'original');
});

test('uncached requests are paced with original-title fallback instead of a long queue', async () => {
  const h = harness();
  await h.resolve(context());
  const busy = await h.resolve(context({ malId: 2 }));
  assert.equal(busy.match, 'original');
  assert.equal(h.requests.length, 1);
});

test('GraphQL errors, HTTP failures, bad JSON and excessive responses preserve the original', async () => {
  for (const response of [
    () => json({ errors: [{ message: 'Private message not propagated' }], data: { Media: record() } }),
    () => json({}, { status: 503 }),
    () => new Response('{', { headers: { 'Content-Type': 'application/json' } }),
    () => new Response('x'.repeat(65537), { headers: { 'Content-Type': 'application/json' } }),
    () => json({ data: { Media: null } }),
  ]) {
    const h = harness(undefined, { fetchImpl: async () => response() });
    const result = await h.resolve(context());
    assert.equal(result.match, 'original');
    assert.deepEqual(result.titles, [context().query.title]);
    assert.ok(!JSON.stringify(result).includes('Private message'));
  }
});

test('429 Retry-After applies across different title lookups without retrying', async () => {
  let calls = 0;
  const h = harness(undefined, { fetchImpl: async () => {
    calls++;
    return calls === 1 ? json({}, { status: 429, headers: { 'Retry-After': '60' } })
      : json({ data: { Media: record({ idMal: 2 }) } });
  } });
  await h.resolve(context());
  h.advance(3000);
  assert.equal((await h.resolve(context({ malId: 2 }))).match, 'original');
  assert.equal(calls, 1);
  h.advance(60_001);
  assert.equal((await h.resolve(context({ malId: 2 }))).match, 'catalog-id');
  assert.equal(calls, 2);
});

test('headerless 429 and an exhausted successful response both defer new lookups', async () => {
  for (const exhausted of [false, true]) {
    let calls = 0;
    const h = harness(undefined, { fetchImpl: async () => {
      calls++;
      if (calls === 1) return exhausted
        ? json({ data: { Media: record() } }, { headers: {
          'X-RateLimit-Remaining': '0', 'X-RateLimit-Reset': '160',
        } }) : json({}, { status: 429 });
      return json({ data: { Media: record({ idMal: 2 }) } });
    } });
    const first = await h.resolve(context());
    assert.equal(first.match, exhausted ? 'catalog-id' : 'original');
    h.advance(3000);
    assert.equal((await h.resolve(context({ malId: 2 }))).match, 'original');
    assert.equal(calls, 1);
    h.advance(60_001);
    assert.equal((await h.resolve(context({ malId: 2 }))).match, 'catalog-id');
    assert.equal(calls, 2);
  }
});

test('an unreadable response cannot discard an exhausted-quota header', async () => {
  let calls = 0;
  const h = harness(undefined, { fetchImpl: async () => {
    calls++;
    return new Response('{', { headers: {
      'Content-Type': 'application/json', 'X-RateLimit-Remaining': '0', 'X-RateLimit-Reset': '160',
    } });
  } });
  assert.equal((await h.resolve(context())).match, 'original');
  h.advance(3000);
  assert.equal((await h.resolve(context({ malId: 2 }))).match, 'original');
  assert.equal(calls, 1);
});

test('negative lookup cache prevents repeated failures and later allows recovery', async () => {
  let calls = 0;
  const h = harness(undefined, { fetchImpl: async () => {
    calls++;
    return calls === 1 ? json({}, { status: 503 }) : json({ data: { Media: record() } });
  } });
  await h.resolve(context());
  h.advance(2101);
  await h.resolve(context({ episode: 3 }));
  assert.equal(calls, 1);
  h.advance(30_001);
  assert.equal((await h.resolve(context())).match, 'catalog-id');
  assert.equal(calls, 2);
});

test('lookup deadline covers reading a stalled response body', async () => {
  let cancelled = false;
  const h = harness(undefined, { timeoutMs: 25, fetchImpl: async () => new Response(new ReadableStream({
    start(controller) { controller.enqueue(new TextEncoder().encode('{')); },
    cancel() { cancelled = true; },
  }), { headers: { 'Content-Type': 'application/json' } }) });
  const started = performance.now();
  assert.equal((await h.resolve(context())).match, 'original');
  assert.ok(performance.now() - started < 2000);
  assert.equal(cancelled, true);
});

test('caller cancellation aborts HTTP and is never reported as an empty successful search', async () => {
  const abort = new AbortController();
  let wireSignal;
  let started;
  const ready = new Promise(resolve => { started = resolve; });
  const h = harness(undefined, { fetchImpl: async (_url, init) => {
    wireSignal = init.signal;
    started();
    return new Promise(() => {});
  } });
  const pending = h.resolve(context(), { signal: abort.signal });
  await ready;
  abort.abort();
  await assert.rejects(pending, e => e.name === 'AbortError');
  assert.equal(wireSignal.aborted, true);
});

test('an already cancelled caller cannot receive cached aliases or start a new request', async () => {
  const h = harness();
  await h.resolve(context());
  const abort = new AbortController();
  abort.abort();
  await assert.rejects(h.resolve(context(), { signal: abort.signal }), e => e.name === 'AbortError');
  assert.equal(h.requests.length, 1);
});

test('search callback sees all variants with unchanged coordinates and merged unique results', async () => {
  const seen = [];
  const input = context();
  const before = structuredClone(input);
  const search = createAnimeSearchModule({
    fetchImpl: async () => json({ data: { Media: record() } }),
    searchIndexer: async variant => {
      seen.push(structuredClone(variant));
      return [{ key: 'shared' }, { key: variant.query.title }];
    }, resultKey: row => row.key,
  });
  const result = await search(input);
  assert.deepEqual(input, before);
  assert.deepEqual(seen.map(c => c.query.title), result.titles);
  assert.equal(result.results.filter(row => row.key === 'shared').length, 1);
  for (const variant of seen) assert.deepEqual({ ...variant.query, title: before.query.title }, before.query);
});

test('one failed indexer query does not hide successful aliases or expose its error text', async () => {
  let calls = 0;
  const search = createAnimeSearchModule({
    fetchImpl: async () => json({ data: { Media: record() } }),
    searchIndexer: async () => {
      if (++calls === 1) throw new Error('https://private.example/?key=SECRET');
      return [{ key: 'result' }];
    }, resultKey: row => row.key,
  });
  const result = await search(context());
  assert.deepEqual(result.results, [{ key: 'result' }]);
  assert.ok(!JSON.stringify(result).includes('SECRET'));
});

test('indexer cancellation stops later variants and propagates to the caller', async () => {
  const abort = new AbortController();
  let calls = 0;
  const search = createAnimeSearchModule({
    fetchImpl: async () => json({ data: { Media: record() } }),
    searchIndexer: async (_context, { signal }) => {
      assert.equal(signal, abort.signal);
      calls++;
      abort.abort();
      throw abort.signal.reason;
    }, resultKey: row => row.key,
  });
  await assert.rejects(search(context(), { signal: abort.signal }), e => e.name === 'AbortError');
  assert.equal(calls, 1);
});

test('pre-cancelled search never invokes its resolver or indexer', async () => {
  let calls = 0;
  const abort = new AbortController();
  abort.abort();
  const search = createAnimeSearchModule({
    resolveTitles: async () => { calls++; throw new Error('Must not start'); },
    searchIndexer: async () => { calls++; return []; },
    resultKey: row => row.key,
  });
  await assert.rejects(search(context(), { signal: abort.signal }), e => e.name === 'AbortError');
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(calls, 0);
});

test('multiple provider adapters can share one lookup budget and metadata cache', async () => {
  const h = harness();
  const create = () => createAnimeSearchModule({
    resolveTitles: h.resolve, searchIndexer: async () => [], resultKey: row => row.key,
  });
  await create()(context());
  await create()(context({ episode: 3 }));
  assert.equal(h.requests.length, 1);
  assert.equal((await create()(context({ malId: 2 }))).match, 'original');
  assert.equal(h.requests.length, 1);
});

test('indexer mutation cannot alter caller metadata and results obey the global bound', async () => {
  const input = context();
  input.extra = { nested: ['original'] };
  const before = structuredClone(input);
  let calls = 0;
  const search = createAnimeSearchModule({
    fetchImpl: async () => json({ data: { Media: record() } }),
    searchIndexer: async variant => {
      variant.query.episode = 999;
      variant.extra.nested.push('mutation');
      const prefix = ++calls;
      return Array.from({ length: 1000 }, (_unused, index) => ({ key: `${prefix}-${index}` }));
    }, resultKey: row => row.key,
  });
  const result = await search(input);
  assert.deepEqual(input, before);
  assert.equal(result.results.length, 300);
  assert.equal(calls, 3);
});

test('search requires an explicit result format adapter', () => {
  assert.throws(() => createAnimeSearchModule({ searchIndexer: async () => [] }), TypeError);
  assert.throws(() => createAnimeSearchModule({ resultKey: row => row.key }), TypeError);
});
