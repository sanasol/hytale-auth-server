const { createHash } = require('node:crypto');
const { sendJson } = require('../utils/response');

const LIVE_CONFIG_FLAGS = {
  enable_turn_credentials: { type: 'boolean', value: true },
  enable_serverside_noesis_ui: { type: 'boolean', value: true },
  enable_server_discovery_ugc_display: { type: 'boolean', value: true },
  enable_quiche_server_transport: { type: 'boolean', value: true },
  enable_proximity_chat: { type: 'boolean', value: true },
  enable_mod_browser: { type: 'boolean', value: true },
  enable_minigames: { type: 'boolean', value: true },
  enable_ice_framework: { type: 'boolean', value: true },
  enable_discord_integration: { type: 'boolean', value: true },
  enable_in_game_discord_link: { type: 'boolean', value: true },
  enable_new_server_discovery: { type: 'boolean', value: true },
  enable_news_tiles: { type: 'boolean', value: true },
  enable_social_layer: { type: 'boolean', value: true },
  enable_parties: { type: 'boolean', value: true },
};

const FLAGS_HASH = createHash('sha256').update(JSON.stringify(LIVE_CONFIG_FLAGS)).digest('hex');
const LEGACY_FLAGS_PATH = '/v1/release/any/any/feature-flags/209d2849185b9bef0d883bdcf57f52b1d981967e9a1ab15244b25dc96ca6708a.json';

const NEWS_TILES = {
  tiles: [
    {
      body: 'Link your Hytale and Discord accounts to see all of your friends and invite them to play!',
      created_at: '2026-05-25T20:18:35.376520016Z',
      cta_url: 'hytale://settings/social',
      id: '835c79aa-eaa6-4093-b03b-2c94a65ce75d',
      image_url: 'https://live-content.hytale.com/news/9bcc038d19ddfcb3ab73f04a71e6ac239e216a0c3215139da65ede38bea63cf6.png',
      title: 'Hytale x Discord',
    },
  ],
};

const LIVE_CONFIG_MANIFEST = {
  version: '2026-10-13T12:00:00Z',
  patchline: 'release',
  platform: {
    os: 'any',
    arch: 'any',
  },
  configs: {
    'feature-flags': {
      url: `/v1/release/any/any/feature-flags/${FLAGS_HASH}.json`,
      hash: FLAGS_HASH,
      updated: '2026-10-13T12:00:00Z',
    },
  },
};

function liveConfigVersion(url) {
  const clientVersion = url.searchParams.get('version') || '0.5.4';
  const configuredVersion = process.env.LIVE_CONFIG_VERSION;
  if (configuredVersion) return configuredVersion;
  return `${FLAGS_HASH}:v=${clientVersion}`;
}

function handleLiveConfigRoutes(req, res, url, urlPath) {
  if (urlPath.startsWith('/configs/')) {
    const host = req.headers.host || 'auth.sanasol.ws';
    const protocol = req.headers['x-forwarded-proto'] || 'https';
    const manifestUrl = process.env.LIVE_CONFIG_MANIFEST_URL || `${protocol}://${host}/liveconfig/manifest.json`;

    sendJson(res, 200, {
      flags: LIVE_CONFIG_FLAGS,
      manifest_url: manifestUrl,
      version: liveConfigVersion(url),
    });
    return true;
  }

  if (urlPath === '/liveconfig/manifest.json') {
    sendJson(res, 200, LIVE_CONFIG_MANIFEST);
    return true;
  }

  if (urlPath === LIVE_CONFIG_MANIFEST.configs['feature-flags'].url || urlPath === LEGACY_FLAGS_PATH) {
    sendJson(res, 200, LIVE_CONFIG_FLAGS);
    return true;
  }

  if (urlPath === '/news-tiles') {
    sendJson(res, 200, NEWS_TILES);
    return true;
  }

  return false;
}

module.exports = {
  handleLiveConfigRoutes,
  LIVE_CONFIG_FLAGS,
  NEWS_TILES,
  LIVE_CONFIG_MANIFEST,
};
