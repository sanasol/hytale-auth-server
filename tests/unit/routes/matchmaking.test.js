const { test } = require('node:test');
const assert = require('node:assert/strict');
const values = new Map();
const uuid = '12345678-1234-1234-1234-123456789012';
const authPath = require.resolve('../../../src/services/auth');
const redisPath = require.resolve('../../../src/services/redis');
require.cache[authPath] = { exports: { verifyToken: token => token === 'valid' ? { uuid, scope: 'hytale:server', exp: Date.now() / 1000 + 60 } : null } };
require.cache[redisPath] = { exports: { redis: {
  async set(key, value, mode) { assert.equal(mode, 'NX'); if (!values.has(key)) values.set(key, value); },
  async get(key) { return values.get(key) || null; },
} } };
const { handleMatchmakingRoutes } = require('../../../src/routes/matchmaking');
async function request(path, method = 'GET', token = 'valid') {
  const url = new URL(path, 'https://example.com');
  let status, body;
  const handled = await handleMatchmakingRoutes({method, headers: {authorization: `Bearer ${token}`}}, {
    writeHead(code) { status = code; }, end(data) { body = JSON.parse(data); },
  }, url, url.pathname);
  return {handled, status, body};
}
test('unrelated routes pass through and unauthenticated matchmaking is rejected', async () => {
  assert.equal((await request('/unrelated')).handled, false);
  assert.equal((await request('/game-types?clientVersion=3908406161', 'GET', 'invalid')).status, 401);
});
test('catalog validates protocol version and never advertises unavailable hosts as playable', async () => {
  assert.equal((await request('/game-types?clientVersion=0.7.0')).status, 400);
  assert.equal((await request('/game-types?clientVersion=4294967296')).status, 400);
  const result = await request('/game-types?clientVersion=3908406161');
  assert.equal(result.status, 200);
  assert.ok(result.body.length > 0);
  assert.ok(result.body.every(game => game.available === false));
});
test('rules acceptance persists per account and repeated acceptance preserves first timestamp', async () => {
  assert.deepEqual((await request('/rules/acceptance')).body, {acceptedAt: null});
  const accepted = (await request('/rules/acceptance', 'PUT')).body;
  assert.ok(Number.isFinite(Date.parse(accepted.acceptedAt)));
  assert.deepEqual((await request('/rules/acceptance')).body, accepted);
  assert.deepEqual((await request('/rules/acceptance', 'PUT')).body, accepted);
  assert.equal(values.size, 1);
});
test('no fictional ticket or successful join is returned without a game host', async () => {
  assert.equal((await request('/tickets/current')).status, 404);
  assert.equal((await request('/tickets', 'POST')).status, 503);
});
test('catalog images are hosted locally and static route only serves catalog entries', async () => {
  const games = (await request('/game-types?clientVersion=3908406161')).body;
  const imageUrl = new URL(games[0].iconUrl);
  assert.equal(imageUrl.hostname, 'auth.sanasol.ws');
  let status, bytes;
  await handleMatchmakingRoutes({method: 'GET', headers: {}}, {
    writeHead(code, headers) { status = code; assert.equal(headers['Content-Type'], 'image/png'); },
    end(body) { bytes = body; },
  }, imageUrl, imageUrl.pathname);
  assert.equal(status, 200);
  assert.equal(bytes.subarray(0, 8).toString('hex'), '89504e470d0a1a0a');
  assert.equal((await request('/minigames/unknown.png')).status, 404);
});
