const { test } = require('node:test');
const assert = require('node:assert/strict');
const { createHash } = require('node:crypto');
const { handleLiveConfigRoutes, LIVE_CONFIG_FLAGS, LIVE_CONFIG_MANIFEST } = require('../../../src/routes/liveConfig');

function get(path) {
  let body;
  const handled = handleLiveConfigRoutes({ headers: { host: 'auth.example' } }, {
    writeHead(status) { assert.equal(status, 200); },
    end(value) { body = JSON.parse(value); },
  }, new URL(path, 'https://auth.example'), path.split('?')[0]);
  assert.equal(handled, true);
  return body;
}

test('config, manifest and content URL agree on flags and their version', () => {
  const config = get('/configs/release?version=0.7.0');
  const manifest = get('/liveconfig/manifest.json');
  const entry = manifest.configs['feature-flags'];
  const flags = get(entry.url);
  assert.deepEqual(flags, config.flags);
  assert.equal(entry.hash, createHash('sha256').update(JSON.stringify(flags)).digest('hex'));
  assert.equal(config.version, `${entry.hash}:v=0.7.0`);
  assert.equal(config.manifest_url, 'https://auth.example/liveconfig/manifest.json');
  assert.equal(Object.keys(flags).length, 14);
  for (const flag of Object.values(flags)) assert.deepEqual(flag, { type: 'boolean', value: true });
  assert.equal(flags.enable_mod_browser.value, true);
  assert.equal(flags.enable_minigames.value, true);
});

test('clients with the previous manifest can still fetch flags', () => {
  assert.deepEqual(get('/v1/release/any/any/feature-flags/209d2849185b9bef0d883bdcf57f52b1d981967e9a1ab15244b25dc96ca6708a.json'), LIVE_CONFIG_FLAGS);
});
