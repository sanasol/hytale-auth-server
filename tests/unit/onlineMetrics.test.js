const { test } = require('node:test');
const assert = require('node:assert/strict');
const online = require('../../src/services/onlineMetrics');

test('direct counts, bounded year queries, missing observations and source failure', async t => {
  let calls = [];
  const mock = t.mock.method(global, 'fetch', async url => {
    calls.push(String(url));
    const u = new URL(url);
    if (u.pathname === '/health') return { ok: true, json: async () => ({ onlineUsers: 2, connections: 3 }) };
    const q = u.searchParams.get('query');
    const value = q.startsWith('avg_') ? 2 : q.startsWith('max_') ? 3 : q.startsWith('count_') ? 4 : 8;
    const data = u.pathname.endsWith('query_range') ? [{ values: [[1000, '2'], [1030, 'NaN']] }] : [{ value: [1000, String(value)] }];
    return { ok: true, json: async () => ({ status: 'success', data: { result: data } }) };
  });
  assert.equal((await online.current()).onlineUsers, 2);
  const result = await online.history('online_users', '365d');
  assert.equal(result.stepSeconds, 86400);
  assert.equal(result.summary.peak, 3);
  assert.equal(result.summary.observedHours, 8*15/3600);
  assert.equal(result.points[1].value, null);
  assert.ok(calls.some(x => x.includes('31536000s')));
  const requests = calls.length;
  await assert.rejects(online.history('arbitrary_query', '365d'), /Unsupported/);
  await assert.rejects(online.history('online_users', '999y'), /Unsupported/);
  assert.equal(calls.length, requests);
  mock.mock.mockImplementation(async () => ({ ok: true, json: async () => ({ status: 'success', data: { result: [] } }) }));
  const empty = await online.history('connections', '1h');
  assert.equal(empty.summary.average, null); assert.equal(empty.summary.observedHours, null); assert.equal(empty.summary.coveragePercent, 0);
  mock.mock.mockImplementation(async () => ({ ok: false, status: 503 }));
  assert.equal((await online.current()).onlineUsers, null);
  await assert.rejects(online.history('online_users', '1h'), /503/);
});
