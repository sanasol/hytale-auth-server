// Requires an explicit disposable Redis and DATA_DIR.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { randomUUID } = require('crypto');
const { EventEmitter } = require('events');
const https = require('https');
if (!process.env.REDIS_URL || !process.env.DATA_DIR) throw Error('Disposable REDIS_URL and DATA_DIR required');
const s = require('../../src/services/social');
const interactions = require('../../src/services/serverInteractions');
const auth = require('../../src/services/auth');
const { handleServerDiscoveryRoutes: route } = require('../../src/routes/serverDiscovery');
const { connect } = require('../../src/services/redis');
const config = require('../../src/config');

test('persistent favorites: identity, migration, concurrent writes, flags, offline cards and removal', async () => {
  await connect();
  const a = randomUUID(), b = randomUUID(), ids = [randomUUID(), randomUUID()];
  const tokens = { [a]: auth.generateSessionToken(a), [b]: auth.generateSessionToken(b) };
  const cards = ids.map((uuid, i) => ({ uuid, name: `Server ${i}`, host: 'example.test', port: 5520, description: '', audience: 0, serverType: 0, regions: [0], likes: 0, favorites: 0, isLiked: false, isFavorited: false }));
  const original = https.get;
  let outage = false, santale = false;
  let requestedUrl;
  https.get = (url, options, callback) => {
    requestedUrl = new URL(url);
    const req = new EventEmitter();
    process.nextTick(() => {
      if (outage) return req.emit('error', new Error('source unavailable'));
      const response = new EventEmitter(); response.statusCode = 200; response.setEncoding = () => {};
      const payload = santale ? { data: [
        { id: 1, hostname: 'online.test', port: 5520, name: 'Online F2P', is_online: true, is_f2p: true },
        { id: 2, hostname: 'offline.test', port: 5520, name: 'Offline F2P', is_online: false, is_f2p: true },
        { id: 3, hostname: 'official.test', port: 5520, name: 'Official', is_online: true, is_f2p: false },
        { id: 4, hostname: 'unknown.test', port: 5520, name: 'Unknown', is_online: true, is_f2p: null },
      ], last_page: 1 } : cards;
      callback(response); response.emit('data', JSON.stringify(payload)); response.emit('end');
    });
    return req;
  };
  const call = async (id, method, path, token = tokens[id]) => {
    const res = { writeHead(status) { this.status = status; }, end(body) { this.body = body ? JSON.parse(body) : null; } };
    await route({ method, headers: { authorization: `Bearer ${token}` } }, res, new URL(path, 'https://discovery'), path.split('?')[0]);
    return res;
  };
  try {
    assert.equal((await call(a, 'GET', '/servers/listings')).status, 200);
    await s.redis.set(`${config.redisKeys.USER}${a}`, JSON.stringify({ favoriteServers: [ids[0]] }));
    assert.equal((await call(a, 'GET', '/me/interactions/favorite')).body[0].uuid, ids[0]);
    assert.equal((await call(a, 'POST', `/servers/${ids[0]}/interaction/like`, 'invalid')).status, 401);
    const results = await Promise.all([
      call(a, 'POST', `/servers/${ids[1]}/interaction/favorite`),
      call(a, 'POST', `/servers/${ids[0]}/interaction/like`),
    ]);
    assert.ok(results.every(x => x.status === 204));
    assert.equal((await call(b, 'GET', '/me/interactions/favorite')).body.length, 0);
    assert.equal((await call(a, 'GET', '/servers/listings')).body[0].isLiked, true);
    assert.equal((await call(a, 'GET', '/servers/listings')).body[1].isFavorited, true);
    // New TCP connection sees the committed record, not process memory.
    const db = s.redis.duplicate();
    try { assert.equal(JSON.parse(await db.get(s.key(a))).serverInteractions.favoriteServers.length, 2); } finally { db.disconnect(); }
    outage = true;
    assert.equal((await call(a, 'GET', '/me/interactions/favorite')).body.length, 2);
    assert.equal((await call(a, 'GET', '/me/interactions/favorite?offset=1')).body.length, 1);
    assert.equal((await call(a, 'DELETE', `/servers/${ids[0]}/interaction/favorite`)).status, 204);
    assert.equal((await call(a, 'DELETE', `/servers/${ids[0]}/interaction/favorite`)).status, 204);
    assert.equal((await call(a, 'GET', '/me/interactions/favorite')).body.length, 1);
    assert.equal((await call(a, 'GET', '/me/interactions/like')).body.length, 1);
    assert.equal((await call(a, 'DELETE', '/servers/not-uuid/interaction/favorite')).status, 400);
    outage = false; santale = true;
    for (const sort of ['players', 'featured']) {
      const result = await call(a, 'GET', `/servers/listings?sort=${sort}&online=false&f2p=false`);
      assert.equal(result.status, 200);
      assert.deepEqual(result.body.map(x => x.name), ['Online F2P']);
      assert.equal(requestedUrl.searchParams.get('online'), 'true');
      assert.equal(requestedUrl.searchParams.get('f2p'), 'true');
      await s.redis.hdel(interactions.CATALOG, result.body[0].uuid);
    }
  } finally {
    https.get = original;
    await s.redis.del(s.key(a), s.key(b), `${config.redisKeys.USER}${a}`);
    await s.redis.hdel(interactions.CATALOG, ...ids);
    s.redis.disconnect();
  }
});
