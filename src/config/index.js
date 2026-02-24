const path = require('path');
const os = require('os');

// Configuration via environment variables
const config = {
  // Server
  port: parseInt(process.env.PORT) || 3000,
  domain: process.env.DOMAIN || 'sanasol.ws',
  workers: parseInt(process.env.WORKERS) || Math.min(os.cpus().length, 4),

  // Paths
  dataDir: process.env.DATA_DIR || '/app/data',
  assetsPath: process.env.ASSETS_PATH || '/app/assets/Assets.zip',

  // Redis
  redisUrl: process.env.REDIS_URL || 'redis://localhost:6379',

  // Admin
  adminPassword: process.env.ADMIN_PASSWORD || 'changeme',
  adminTokenTtl: 86400, // 24 hours in seconds

  // Session
  sessionTtl: 36000, // 10 hours in seconds

  // JWT
  keyId: '2025-10-01-sanasol',

  // Cache
  headCacheTtl: 3600000, // 1 hour in milliseconds

  // Redis key prefixes
  redisKeys: {
    SESSION: 'session:',
    AUTH_GRANT: 'authgrant:',
    USER: 'user:',
    SERVER_PLAYERS: 'server:',
    PLAYER_SERVER: 'player:',
    USERNAME: 'username:',
    SERVER_NAME: 'servername:',
    ADMIN_TOKEN: 'admintoken:',
  },
};

// Derived paths
config.keyFile = path.join(config.dataDir, 'jwt_keys.json');
config.headCacheDir = path.join(config.dataDir, 'head-cache');
config.downloadsDir = process.env.DOWNLOADS_DIR || path.join(config.dataDir, 'downloads');
config.logSubmissionsDir = path.join(config.dataDir, 'log-submissions');

// Telegram notifications
config.telegramBotToken = process.env.TELEGRAM_BOT_TOKEN || '8690465007:AAGNi9X8oMMnDp_7Idj4VkPc0QYqaHlec0U';
config.telegramChannelId = process.env.TELEGRAM_CHANNEL_ID || '-1003774965623';

module.exports = config;
