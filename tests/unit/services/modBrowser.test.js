const { test } = require('node:test');
const assert = require('node:assert/strict');
const { createModBrowserService, searchParams, file } = require('../../../src/services/modBrowser');

function setup(responder) {
  const calls = [];
  const service = createModBrowserService({ apiKey: 'test-key', fetchImpl: async (url, options) => {
    calls.push({ url, options });
    return new Response(JSON.stringify(await responder(new URL(url), options)), { status: 200 });
  } });
  return { service, calls };
}
const project = { id: 42, gameId: 70216, name: 'Example', allowModDistribution: true, authors: [{ id: 9, name: 'Creator' }], categories: [{ id: 9137, name: 'Mods' }], logo: { url: 'https://media.forgecdn.net/a.png' } };

test('browse maps provider IDs and confines query/upstream credentials', async () => {
  const { service, calls } = setup(() => ({ data: [project], pagination: { index: 0, pageSize: 2, totalCount: 3, resultCount: 1 } }));
  const result = await service.handle('/api/mods', new URLSearchParams('pageSize=2&classId=curseforge:9137&search=hello&sortField=6&host=evil.test'));
  assert.equal(result.data[0].id, 'curseforge:42');
  assert.equal(result.data[0].authors[0].id, 'curseforge:9');
  assert.equal(result.data[0].distributionAllowed, true);
  const url = new URL(calls[0].url);
  assert.equal(url.host, 'api.curseforge.com');
  assert.equal(url.searchParams.get('classId'), '9137');
  assert.equal(url.searchParams.get('searchFilter'), 'hello');
  assert.equal(url.searchParams.get('gameId'), '70216');
  assert.equal(url.searchParams.has('host'), false);
  assert.equal(calls[0].options.headers['x-api-key'], 'test-key');
  assert.equal(calls[0].options.headers.Authorization, undefined);
});

test('invalid pagination, provider and sort are rejected', () => {
  for (const query of ['index=-1', 'pageSize=500', 'index=9999&pageSize=2', 'categoryId=other:1', 'sortField=0', 'sortOrder=random', 'providers=other']) {
    assert.throws(() => searchParams(new URLSearchParams(query)), { status: 400 });
  }
});

test('files preserve hashes, version, size and release type', () => {
  const result = file({ id: 12, fileName: 'a.zip', fileLength: 77, gameVersions: ['0.7'], releaseType: 2, hashes: [{ algo: 1, value: 'sha' }, { algo: 2, value: 'md' }] });
  assert.equal(result.id, 'curseforge:12');
  assert.equal(result.fileLength, 77);
  assert.deepEqual(result.hashes, [{ algo: 'sha1', value: 'sha' }, { algo: 'md5', value: 'md' }]);
  assert.equal(result.releaseType, 'beta');
});

test('publisher distribution block prevents download lookup', async () => {
  const { service, calls } = setup(() => ({ data: { ...project, allowModDistribution: false } }));
  await assert.rejects(service.handle('/api/mods/curseforge:42/files/curseforge:12/download-url', new URLSearchParams()), { status: 403 });
  assert.equal(calls.length, 1);
});

test('download uses only supplied provider URL and caches metadata', async () => {
  const { service, calls } = setup(url => ({ data: url.pathname.endsWith('download-url') ? 'https://edge.forgecdn.net/files/12/a.zip' : project }));
  const path = '/api/mods/curseforge:42/files/curseforge:12/download-url';
  assert.deepEqual(await service.handle(path, new URLSearchParams()), { downloadUrl: 'https://edge.forgecdn.net/files/12/a.zip' });
  await service.handle(path, new URLSearchParams());
  assert.equal(calls.length, 2);
});

test('cross-game projects and arbitrary download hosts are rejected', async () => {
  const otherGame = setup(() => ({ data: { ...project, gameId: 432 } }));
  await assert.rejects(otherGame.service.handle('/api/mods/curseforge:42', new URLSearchParams()), { status: 404 });
  const external = setup(url => ({ data: url.pathname.endsWith('download-url') ? 'https://evil.test/file' : project }));
  await assert.rejects(external.service.handle('/api/mods/curseforge:42/files/curseforge:12/download-url', new URLSearchParams()), { status: 502 });
});

test('creator maps paginated author filter, featured maps provider groups', async () => {
  const { service, calls } = setup(url => url.pathname.endsWith('featured') ? { data: { featured: [project], popular: [], recentlyUpdated: [] } } : { data: [project], pagination: { totalCount: 1 } });
  const creator = await service.handle('/api/creators/curseforge:9', new URLSearchParams());
  assert.equal(creator.creator.id, 'curseforge:9');
  assert.equal(new URL(calls[0].url).searchParams.get('authorId'), '9');
  const featured = await service.handle('/api/mods/featured', new URLSearchParams());
  assert.equal(featured.featured[0].id, 'curseforge:42');
  assert.equal(calls[1].options.method, 'POST');
});

test('transient provider errors are not cached or leaked', async () => {
  let calls = 0;
  const service = createModBrowserService({ apiKey: 'test-key', fetchImpl: async () => { calls++; throw new Error('private credential details'); } });
  for (let i = 0; i < 2; i++) await assert.rejects(service.handle('/api/categories', new URLSearchParams()), { status: 502, message: 'Mod provider request failed' });
  assert.equal(calls, 2);
});

test('observed official client query, class, category and named sorting map to CF', () => {
  const params = searchParams(new URLSearchParams('query=Perfect+Parries&class=curseforge:9137&category=curseforge:9212&sortField=totaldownloads&sortOrder=desc'));
  assert.equal(params.get('searchFilter'), 'Perfect Parries');
  assert.equal(params.get('classId'), '9137');
  assert.equal(params.get('categoryId'), '9212');
  assert.equal(params.get('sortField'), '6');
  assert.equal(params.get('sortOrder'), 'desc');
  for (const [name, id] of Object.entries({ popularity: 2, popular: 2, lastupdated: 3, updated: 3, name: 4, downloads: 6 })) {
    assert.equal(searchParams(new URLSearchParams('sortField=' + name)).get('sortField'), String(id));
  }
  const defaults = searchParams(new URLSearchParams());
  assert.equal(defaults.get('sortField'), '1');
  assert.equal(defaults.get('sortOrder'), 'asc');
});

test('class categories return child categories rather than top-level classes', async () => {
  const { service, calls } = setup(() => ({ data: [{ id: 9137, name: 'Mods', isClass: true }, { id: 9212, name: 'Gameplay', isClass: false }] }));
  const children = await service.handle('/api/categories', new URLSearchParams('class=curseforge:9137'));
  assert.deepEqual(children.map(c => c.id), ['curseforge:9212']);
  assert.equal(new URL(calls[0].url).searchParams.get('classId'), '9137');
});

test('root categories match the three official client classes and order', async () => {
  const { service } = setup(() => ({ data: [
    { id: 9999, name: 'Bootstrap', isClass: true },
    { id: 9185, name: 'Prefabs', isClass: true },
    { id: 9184, name: 'Worlds', isClass: true },
    { id: 9137, name: 'Mods', isClass: true },
    { id: 9998, name: 'Translations', isClass: true },
    { id: 9212, name: 'Gameplay', isClass: false },
  ] }));
  const roots = await service.handle('/api/categories', new URLSearchParams());
  assert.deepEqual(roots.map(c => c.id), ['curseforge:9137', 'curseforge:9184', 'curseforge:9185']);
});
