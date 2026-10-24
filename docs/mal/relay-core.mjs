// Public OAuth return routing only. No access token or PKCE verifier belongs here.
export const STATE_PREFIX = 'syclop-mal-v1.';

export function privateIpv4(value) {
  if (!/^(?:\d{1,3}\.){3}\d{1,3}$/.test(value)) return false;
  const octets = value.split('.');
  if (octets.some(n => String(Number(n)) !== n || Number(n) > 255)) return false;
  const [a, b] = octets.map(Number);
  return a === 10 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168);
}

export function parseState(state) {
  if (typeof state !== 'string' || state.length > 2048 || !state.startsWith(STATE_PREFIX)) throw new Error('Invalid return state.');
  const encoded = state.slice(STATE_PREFIX.length);
  if (!/^[A-Za-z0-9_-]+$/.test(encoded)) throw new Error('Invalid return state.');
  let data;
  try {
    const binary = atob(encoded.replace(/-/g, '+').replace(/_/g, '/'));
    data = JSON.parse(new TextDecoder('utf-8', {fatal: true}).decode(Uint8Array.from(binary, c => c.charCodeAt(0))));
  } catch { throw new Error('Invalid return state.'); }
  if (!data || typeof data !== 'object' || Array.isArray(data) ||
      Object.keys(data).sort().join(',') !== 'nonce,return_uri' ||
      typeof data.nonce !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(data.nonce) ||
      typeof data.return_uri !== 'string') throw new Error('Invalid return state.');
  let destination;
  try { destination = new URL(data.return_uri); } catch { throw new Error('Invalid TV address.'); }
  if (destination.protocol !== 'http:' || !privateIpv4(destination.hostname) || destination.port !== '17893' ||
      destination.pathname !== '/mal/callback' || destination.username || destination.password ||
      destination.search || destination.hash || destination.href !== data.return_uri) throw new Error('Invalid TV address.');
  return destination;
}

export function authorizationReturn(search) {
  const query = new URLSearchParams(search);
  const names = [...query.keys()];
  if (new Set(names).size !== names.length || names.some(n => !['code', 'state', 'error', 'error_description'].includes(n))) {
    throw new Error('Unexpected authorization parameters.');
  }
  const state = query.get('state');
  const destination = parseState(state);
  if (query.has('error')) throw new Error('Authorization was cancelled or denied. Start again on the TV.');
  const code = query.get('code');
  if (!code || code.length > 4096 || /[\r\n\u0000]/.test(code)) throw new Error('Missing authorization code. Start again on the TV.');
  destination.searchParams.set('code', code);
  destination.searchParams.set('state', state);
  return {url: destination.href, host: destination.hostname};
}
