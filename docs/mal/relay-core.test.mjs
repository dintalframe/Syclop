import test from 'node:test';
import assert from 'node:assert/strict';
import {authorizationReturn, parseState, privateIpv4, STATE_PREFIX} from './relay-core.mjs';
const encode = (route, nonce = 'A'.repeat(43), extra = {}) => STATE_PREFIX + Buffer.from(JSON.stringify({nonce, return_uri: route, ...extra})).toString('base64url');
const valid = 'http://192.168.1.104:17893/mal/callback';
test('returns the code and exact opaque state only to the private TV callback', () => {
  const state = encode(valid);
  const result = authorizationReturn(new URLSearchParams({code: 'code+/=', state}).toString());
  const url = new URL(result.url);
  assert.equal(result.host, '192.168.1.104');
  assert.equal(url.searchParams.get('code'), 'code+/=');
  assert.equal(url.searchParams.get('state'), state);
});
test('only canonical RFC1918 IPv4 addresses are accepted', () => {
  for (const ip of ['10.0.0.2', '172.16.0.1', '172.31.255.254', '192.168.0.2']) assert.ok(privateIpv4(ip));
  for (const ip of ['127.0.0.1', '169.254.1.2', '172.15.0.1', '172.32.0.1', '8.8.8.8', '192.168.001.2', '192.168.1.999', 'localhost', '[::1]']) assert.equal(privateIpv4(ip), false);
});
test('does not route to public hosts, different ports, paths, credentials or preloaded queries', () => {
  for (const route of ['http://8.8.8.8:17893/mal/callback', 'https://192.168.1.104:17893/mal/callback',
    'http://192.168.1.104:80/mal/callback', 'http://192.168.1.104:17893/admin',
    'http://name@192.168.1.104:17893/mal/callback', valid + '?code=old', valid + '#fragment',
    'http://0300.0250.1.104:17893/mal/callback']) assert.throws(() => parseState(encode(route)));
});
test('rejects malformed or oversized state and extra routing fields', () => {
  for (const state of ['', 'other.' + 'A'.repeat(50), STATE_PREFIX + '%%%', STATE_PREFIX + 'A'.repeat(2050),
    encode(valid, 'too-short'), encode(valid, 'A'.repeat(43), {redirect: 'https://example.org'})]) assert.throws(() => parseState(state));
});
test('rejects duplicate parameters, cancelled authorizations and missing or unsafe codes', () => {
  const state = encode(valid);
  for (const query of [`code=x&code=y&state=${state}`, `code=x&state=${state}&state=${state}`,
    `error=access_denied&state=${state}`, `state=${state}`, `code=%0Ax&state=${state}`,
    `code=x&state=${state}&next=https://example.org`]) assert.throws(() => authorizationReturn(query));
});
