const crypto = require('crypto');
const https = require('https');

const storage = require('../services/storage');
const { sendJson, sendNoContent } = require('../utils/response');

const DEFAULT_SOURCE_URL = 'https://santale.top/api/all-servers';

function deterministicUuid(input) {
  const hash = crypto.createHash('sha1').update(input).digest();
  hash[6] = (hash[6] & 0x0f) | 0x50;
  hash[8] = (hash[8] & 0x3f) | 0x80;
  const hex = hash.subarray(0, 16).toString('hex');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function fetchJson(sourceUrl) {
  return new Promise((resolve, reject) => {
    const req = https.get(sourceUrl, {
      headers: {
        Accept: 'application/json',
        'User-Agent': 'SanasolAuthServer/1.0',
      },
      timeout: 5000,
    }, (upstream) => {
      let data = '';
      upstream.setEncoding('utf8');
      upstream.on('data', (chunk) => {
        data += chunk;
      });
      upstream.on('end', () => {
        if (upstream.statusCode < 200 || upstream.statusCode >= 300) {
          reject(new Error(`serverlist HTTP ${upstream.statusCode}`));
          return;
        }
        try {
          resolve(JSON.parse(data));
        } catch (e) {
          reject(e);
        }
      });
    });

    req.on('timeout', () => {
      req.destroy(new Error('serverlist request timed out'));
    });
    req.on('error', reject);
  });
}

function isOfficialListingShape(server) {
  return server
    && typeof server.uuid === 'string'
    && typeof server.name === 'string'
    && typeof server.host === 'string'
    && typeof server.port === 'number'
    && typeof server.description === 'string'
    && typeof server.audience === 'number'
    && typeof server.serverType === 'number'
    && Array.isArray(server.regions)
    && typeof server.likes === 'number'
    && typeof server.favorites === 'number'
    && typeof server.isLiked === 'boolean'
    && typeof server.isFavorited === 'boolean';
}

function asArray(value) {
  if (Array.isArray(value)) return value;
  if (value === undefined || value === null) return [];
  return [value];
}

function mapSantaleServerType(server) {
  const modes = [...asArray(server.game_modes), ...asArray(server.tags)]
    .map((value) => String(value).toLowerCase());

  if (modes.some((value) => value.includes('minigame'))) return 4;
  if (modes.some((value) => value.includes('pvp'))) return 3;
  if (modes.some((value) => value.includes('roleplay') || value.includes('rpg'))) return 1;
  return 0;
}

function mapSantaleRegions(server) {
  const country = String(server.country_code || '').toUpperCase();
  const regionMap = {
    US: [0],
    CA: [0],
    MX: [0],
    BR: [2],
    AR: [2],
    CL: [2],
    GB: [3],
    IE: [3],
    PL: [3],
    DE: [3],
    FR: [3],
    ES: [3],
    IT: [3],
    NL: [3],
    TR: [5],
    RU: [5],
    CN: [8],
    JP: [8],
    KR: [8],
    SG: [8],
    AU: [9],
    NZ: [9],
  };
  return regionMap[country] || [0, 1, 3];
}

function isSantaleListingShape(server) {
  return server
    && typeof server.hostname === 'string'
    && typeof server.port === 'number'
    && typeof server.name === 'string';
}

function transformSantaleServer(server, interactions) {
  const stableId = `${server.source || 'santale'}:${server.id || ''}:${server.hostname}:${server.port}`;
  const uuid = deterministicUuid(stableId);
  const liked = interactions.likedServers.includes(uuid);
  const favorited = interactions.favoriteServers.includes(uuid);
  const votes = Number(server.votes_count || 0);

  return {
    audience: server.is_f2p === false ? 1 : 0,
    // Upstream omits creation dates; .NET requires a date, use the unknown-date sentinel.
    createdAt: Number.isFinite(Date.parse(server.created_at)) ? new Date(server.created_at).toISOString() : '1970-01-01T00:00:00.000Z',
    description: server.description || server.short_description || '',
    favorites: votes + (favorited ? 1 : 0),
    host: server.hostname,
    isFavorited: favorited,
    isLiked: liked,
    likes: votes + (liked ? 1 : 0),
    name: server.name,
    // The source has no Hytale owner identity; Guid.Empty represents unknown.
    ownerProfileId: '00000000-0000-0000-0000-000000000000',
    port: server.port,
    regions: mapSantaleRegions(server),
    serverType: mapSantaleServerType(server),
    uuid,
  };
}

function filterListings(listings, url) {
  const requestedAudiences = url.searchParams.getAll('audience').map(Number);
  const requestedTypes = url.searchParams.getAll('serverType').map(Number);
  const requestedRegions = url.searchParams.getAll('regions').map(Number);

  return listings.filter((server) => {
    if (requestedAudiences.length > 0 && !requestedAudiences.includes(server.audience)) {
      return false;
    }
    if (requestedTypes.length > 0 && !requestedTypes.includes(server.serverType)) {
      return false;
    }
    if (requestedRegions.length > 0 && !server.regions.some((region) => requestedRegions.includes(region))) {
      return false;
    }
    return true;
  });
}

function sortListings(listings, sort) {
  const sorted = [...listings];
  if (sort === 'featured') {
    sorted.sort((a, b) => (b.favorites + b.likes) - (a.favorites + a.likes));
  } else if (sort === 'random') {
    sorted.sort((a, b) => a.uuid.localeCompare(b.uuid));
  } else {
    sorted.sort((a, b) => b.likes - a.likes);
  }
  return sorted;
}

async function getListings(url, uuid) {
  const configuredSource = process.env.SERVER_DISCOVERY_SOURCE_URL;
  const sourceIsConfigured = Boolean(configuredSource);
  const sourceUrl = new URL(configuredSource || DEFAULT_SOURCE_URL);

  if (!sourceIsConfigured) {
    sourceUrl.searchParams.set('per_page', process.env.SERVER_DISCOVERY_PER_PAGE || '100');
    sourceUrl.searchParams.set('page', '1');
    const sort = url.searchParams.get('sort') || 'players';
    sourceUrl.searchParams.set('sort', sort === 'featured' ? 'votes' : 'players');
  } else {
    for (const [key, value] of url.searchParams.entries()) {
      sourceUrl.searchParams.append(key, value);
    }
  }

  const upstream = await fetchJson(sourceUrl.toString());
  if (Array.isArray(upstream) && upstream.every(isOfficialListingShape)) {
    return { listings: upstream };
  }

  const sourceItems = upstream.data || [];
  if (!sourceIsConfigured && Array.isArray(sourceItems) && sourceItems.every(isSantaleListingShape)) {
    const interactions = await storage.getServerDiscoveryInteractions(uuid);
    const listings = sourceItems.map((server) => transformSantaleServer(server, interactions));
    const filtered = filterListings(listings, url);
    const offset = Math.max(Number(url.searchParams.get('offset') || 0), 0);
    return { listings: sortListings(filtered, url.searchParams.get('sort') || 'players').slice(offset) };
  }

  if (sourceIsConfigured && Array.isArray(upstream) && !upstream.every(isOfficialListingShape)) {
    return {
      unsupported: true,
      error: 'SERVER_DISCOVERY_SOURCE_URL response is not the observed official listings shape',
    };
  }

  return {
    unsupported: true,
    error: 'server discovery source response shape is not supported',
  };
}

async function handleServerDiscoveryRoutes(req, res, url, urlPath, uuid) {
  if (urlPath === '/servers/listings' && req.method === 'GET') {
    try {
      const result = await getListings(url);
      if (result.unsupported) {
        sendJson(res, 501, {
          error: result.error,
          observed_shape: 'top-level JSON array of {audience,createdAt,description,favorites,host,isFavorited,isLiked,likes,name,ownerProfileId,port,regions,serverType,uuid}',
        });
      } else {
        sendJson(res, 200, result.listings);
      }
    } catch (e) {
      console.error('server discovery listings failed:', e.message);
      sendJson(res, 502, { error: 'server discovery source unavailable' });
    }
    return true;
  }

  const interactionMatch = urlPath.match(/^\/servers\/([^/]+)\/interaction\/(like|favorite)$/);
  if (interactionMatch && req.method === 'POST') {
    await storage.addServerDiscoveryInteraction(uuid, interactionMatch[1], interactionMatch[2]);
    sendNoContent(res);
    return true;
  }

  return false;
}

module.exports = {
  handleServerDiscoveryRoutes,
  isOfficialListingShape,
  transformSantaleServer,
};
