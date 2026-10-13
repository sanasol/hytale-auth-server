const fs = require('node:fs');

const GAME_ID = 70216;
const PREFIX = 'curseforge:';
const CACHE_LIMIT = 256;
const CACHE_BYTES = 16 * 1024 * 1024;
const RESPONSE_LIMIT = 4 * 1024 * 1024;

function fail(status, message) { return Object.assign(new Error(message), { status }); }
function numeric(value, name, min = 0, max = 2147483647) {
  if (!/^\d+$/.test(String(value)) || Number(value) < min || Number(value) > max) throw fail(400, `Invalid ${name}`);
  return Number(value);
}
function providerId(value) {
  if (!String(value).startsWith(PREFIX)) throw fail(400, 'Unsupported provider ID');
  return numeric(String(value).slice(PREFIX.length), 'provider ID', 1);
}
function category(c) { return { id: PREFIX + c.id, provider: 'curseforge', name: c.name, slug: c.slug, iconUrl: c.iconUrl }; }
function author(a) { return { id: PREFIX + a.id, provider: 'curseforge', sources: [PREFIX + a.id], name: a.name, url: a.url, ...(a.avatarUrl ? { avatarUrl: a.avatarUrl } : {}) }; }
function mod(m) {
  return {
    id: PREFIX + m.id, provider: 'curseforge', sources: [PREFIX + m.id], name: m.name,
    slug: m.slug, summary: m.summary, logoUrl: m.logo?.url,
    authors: (m.authors || []).map(author), categories: (m.categories || []).map(category),
    screenshots: (m.screenshots || []).map(({ title, url, thumbnailUrl }) => ({ title, url, thumbnailUrl })),
    downloadCount: m.downloadCount, thumbsUpCount: m.thumbsUpCount || 0,
    dateCreated: m.dateCreated, dateModified: m.dateModified,
    distributionAllowed: m.allowModDistribution === true,
  };
}
function file(f) {
  return { id: PREFIX + f.id, provider: 'curseforge', displayName: f.displayName,
    fileName: f.fileName, fileDate: f.fileDate, fileLength: f.fileLength,
    gameVersions: f.gameVersions || [], releaseType: ({ 1: 'release', 2: 'beta', 3: 'alpha' })[f.releaseType],
    hashes: (f.hashes || []).filter(h => h.algo === 1 || h.algo === 2).map(h => ({ algo: h.algo === 1 ? 'sha1' : 'md5', value: h.value })),
  };
}
function page(result, convert) { return { data: (result.data || []).map(convert), pagination: result.pagination }; }
function searchParams(input, files = false) {
  const p = new URLSearchParams();
  const index = numeric(input.get('index') ?? 0, 'index', 0, 9999);
  const size = numeric(input.get('pageSize') ?? 50, 'pageSize', 1, 50);
  if (index + size > 10000) throw fail(400, 'Pagination exceeds 10000');
  p.set('index', index); p.set('pageSize', size);
  if (!files) p.set('gameId', GAME_ID);
  for (const [clientKey, upstreamKey] of [['class', 'classId'], ['category', 'categoryId']]) {
    const value = input.get(clientKey) ?? input.get(upstreamKey);
    if (!files && value != null) p.set(upstreamKey, providerId(value));
  }
  if (input.has('gameVersion')) {
    const version = input.get('gameVersion');
    if (!/^[\w. -]{1,64}$/.test(version)) throw fail(400, 'Invalid gameVersion');
    p.set('gameVersion', version);
  }
  if (!files) {
    const search = input.get('query') ?? input.get('searchFilter') ?? input.get('search');
    if (search != null) {
      if (search.length > 200) throw fail(400, 'Search is too long');
      p.set('searchFilter', search);
    }
    const sort = input.get('sortField') ?? '1';
    const namedSorts = { popular: 2, popularity: 2, lastupdated: 3, updated: 3, name: 4, downloads: 6, totaldownloads: 6 };
    p.set('sortField', namedSorts[sort.toLowerCase()] ?? numeric(sort, 'sortField', 1, 12));
    p.set('sortOrder', 'asc');
    if (input.has('sortOrder')) {
      if (!['asc', 'desc'].includes(input.get('sortOrder'))) throw fail(400, 'Invalid sortOrder');
      p.set('sortOrder', input.get('sortOrder'));
    }
  }
  const providers = input.get('providers') ?? input.get('provider');
  if (providers && providers !== 'curseforge') throw fail(400, 'Unsupported provider');
  return p;
}

function createModBrowserService({ fetchImpl = globalThis.fetch, apiKey, now = Date.now } = {}) {
  const cache = new Map();
  const pending = new Map();
  let cacheBytes = 0;
  function key() {
    if (apiKey) return apiKey;
    if (process.env.CURSEFORGE_API_KEY) return process.env.CURSEFORGE_API_KEY;
    if (process.env.CURSEFORGE_API_KEY_FILE) {
      try { return fs.readFileSync(process.env.CURSEFORGE_API_KEY_FILE, 'utf8').trim(); } catch { /* Report configuration failure without exposing paths or secrets. */ }
    }
    throw fail(503, 'Mod provider is not configured');
  }
  async function request(path, body) {
    const cacheKey = path + (body ? JSON.stringify(body) : '');
    const saved = cache.get(cacheKey);
    if (saved && saved.expires > now()) return saved.data;
    if (pending.has(cacheKey)) return pending.get(cacheKey);
    if (pending.size >= 16) throw fail(503, 'Mod provider is busy');
    const operation = (async () => {
      let response;
      try {
        response = await fetchImpl('https://api.curseforge.com/v1' + path, {
          method: body ? 'POST' : 'GET', redirect: 'error', signal: AbortSignal.timeout(8000),
          headers: { 'x-api-key': key(), Accept: 'application/json', ...(body ? { 'Content-Type': 'application/json' } : {}) },
          ...(body ? { body: JSON.stringify(body) } : {}),
        });
      } catch (error) { throw error.status ? error : fail(502, 'Mod provider request failed'); }
      if (!response.ok) throw fail(response.status === 404 ? 404 : response.status === 403 ? 403 : 502, 'Mod provider request failed');
      const chunks = []; let length = 0;
      for await (const chunk of response.body) {
        length += chunk.length;
        if (length > RESPONSE_LIMIT) throw fail(502, 'Mod provider response too large');
        chunks.push(chunk);
      }
      let data;
      try { data = JSON.parse(Buffer.concat(chunks).toString()); } catch { throw fail(502, 'Invalid mod provider response'); }
      const previous = cache.get(cacheKey);
      if (previous) { cache.delete(cacheKey); cacheBytes -= previous.size; }
      while (cache.size >= CACHE_LIMIT || cacheBytes + length > CACHE_BYTES) {
        const oldest = cache.keys().next().value;
        cacheBytes -= cache.get(oldest).size; cache.delete(oldest);
      }
      cache.set(cacheKey, { data, size: length, expires: now() + 60000 }); cacheBytes += length;
      return data;
    })();
    pending.set(cacheKey, operation);
    try { return await operation; } finally { pending.delete(cacheKey); }
  }
  async function getMod(id) {
    const { data } = await request(`/mods/${id}`);
    if (!data || data.gameId !== GAME_ID) throw fail(404, 'Mod not found');
    return data;
  }
  return {
    async handle(path, input) {
      if (path === '/api/categories') {
        const classId = input.has('class') ? providerId(input.get('class')) : null;
        const { data } = await request(`/categories?gameId=${GAME_ID}${classId ? '&classId=' + classId : ''}`);
        if (classId) return data.filter(c => !c.isClass).map(category);
        // These are the three content classes supported by the current game UI.
        return [9137, 9184, 9185].map(id => data.find(c => c.id === id)).filter(Boolean).map(category);
      }
      if (path === '/api/mods') return page(await request('/mods/search?' + searchParams(input)), mod);
      if (path === '/api/mods/featured') {
        const { data } = await request('/mods/featured', { gameId: GAME_ID, excludedModIds: [] });
        return { featured: (data.featured || []).map(mod), popular: (data.popular || []).map(mod), recentlyUpdated: (data.recentlyUpdated || []).map(mod) };
      }
      let match = path.match(/^\/api\/creators\/([^/]+)$/);
      if (match) {
        const id = providerId(match[1]); const query = searchParams(input); query.set('authorId', id);
        const result = await request('/mods/search?' + query);
        const creator = result.data.flatMap(m => m.authors || []).find(a => a.id === id);
        if (!creator) throw fail(404, 'Creator not found');
        return { creator: author(creator), mods: page(result, mod) };
      }
      match = path.match(/^\/api\/mods\/([^/]+)(?:\/files(?:\/([^/]+)\/download-url)?)?$/);
      if (!match) throw fail(404, 'Mod browser route not found');
      const id = providerId(match[1]);
      const project = await getMod(id);
      if (!path.includes('/files')) return mod(project);
      if (!match[2]) return page(await request(`/mods/${id}/files?` + searchParams(input, true)), file);
      const fileId = providerId(match[2]);
      // Respect publisher distribution settings and never synthesize a CDN URL.
      if (project.allowModDistribution !== true) throw fail(403, 'Publisher disabled third-party distribution');
      const { data } = await request(`/mods/${id}/files/${fileId}/download-url`);
      let target;
      try { target = new URL(data); } catch { throw fail(403, 'Download is unavailable'); }
      if (target.protocol !== 'https:' || !target.hostname.endsWith('.forgecdn.net') || target.username || target.password) throw fail(502, 'Invalid download URL');
      return { downloadUrl: target.href };
    },
  };
}
module.exports = { createModBrowserService, searchParams, mod, file, providerId };
