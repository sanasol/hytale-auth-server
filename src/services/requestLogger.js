const fs = require('fs');
const path = require('path');
const config = require('../config');

// Lazy-load metrics to avoid circular dependency
let metrics = null;
function getMetrics() {
  if (!metrics) {
    try { metrics = require('./metrics'); } catch (e) {}
  }
  return metrics;
}

// Log file path
const LOG_DIR = process.env.LOG_DIR || path.join(config.dataDir, 'logs');
const LOG_FILE = path.join(LOG_DIR, 'requests.log');

// Ensure log directory exists
try {
  if (!fs.existsSync(LOG_DIR)) {
    fs.mkdirSync(LOG_DIR, { recursive: true });
  }
} catch (err) {
  console.error('Failed to create log directory:', err.message);
}

// Log rotation settings
const MAX_LOG_SIZE = parseInt(process.env.MAX_LOG_SIZE || '50') * 1024 * 1024; // 50MB default
const MAX_LOG_FILES = parseInt(process.env.MAX_LOG_FILES || '5');

/**
 * Rotate log file if it exceeds max size
 */
function rotateLogIfNeeded() {
  try {
    if (!fs.existsSync(LOG_FILE)) return;

    const stats = fs.statSync(LOG_FILE);
    if (stats.size < MAX_LOG_SIZE) return;

    // Rotate existing files
    for (let i = MAX_LOG_FILES - 1; i >= 1; i--) {
      const oldFile = `${LOG_FILE}.${i}`;
      const newFile = `${LOG_FILE}.${i + 1}`;
      if (fs.existsSync(oldFile)) {
        if (i === MAX_LOG_FILES - 1) {
          fs.unlinkSync(oldFile); // Delete oldest
        } else {
          fs.renameSync(oldFile, newFile);
        }
      }
    }

    // Rotate current file
    fs.renameSync(LOG_FILE, `${LOG_FILE}.1`);
    console.log('Log file rotated');
  } catch (err) {
    console.error('Log rotation failed:', err.message);
  }
}

/**
 * Get client IP from request, handling proxies
 */
function getClientIp(req) {
  // X-Forwarded-For can contain multiple IPs: client, proxy1, proxy2, ...
  const forwardedFor = req.headers['x-forwarded-for'];
  if (forwardedFor) {
    // Take the first IP (original client)
    return forwardedFor.split(',')[0].trim();
  }

  // X-Real-IP (common alternative)
  const realIp = req.headers['x-real-ip'];
  if (realIp) {
    return realIp;
  }

  // Fallback to socket address
  return req.socket?.remoteAddress || req.connection?.remoteAddress || 'unknown';
}

/**
 * Sanitize body for logging (remove sensitive data)
 */
function sanitizeBody(body) {
  if (!body || typeof body !== 'object') return body;

  const sanitized = { ...body };

  // Remove sensitive fields
  const sensitiveFields = [
    'password', 'token', 'secret', 'apiKey', 'api_key',
    'sessionToken', 'identityToken', 'accessToken', 'refreshToken',
    'authorization', 'auth'
  ];

  for (const field of sensitiveFields) {
    if (sanitized[field]) {
      sanitized[field] = '[REDACTED]';
    }
  }

  return sanitized;
}

/**
 * Sanitize headers for logging
 */
function sanitizeHeaders(headers) {
  const sanitized = { ...headers };

  // Redact authorization header but keep type
  if (sanitized.authorization) {
    const parts = sanitized.authorization.split(' ');
    sanitized.authorization = parts[0] + ' [REDACTED]';
  }

  // Remove cookie header
  if (sanitized.cookie) {
    sanitized.cookie = '[REDACTED]';
  }

  return sanitized;
}

/**
 * Log a request to file
 */
function logRequest(req, res, body = null, responseTime = 0) {
  try {
    rotateLogIfNeeded();

    const timestamp = new Date().toISOString();
    const clientIp = getClientIp(req);
    const userAgent = req.headers['user-agent'] || 'unknown';
    const method = req.method;
    const url = req.url;
    const host = req.headers.host || 'unknown';
    const statusCode = res.statusCode || 0;
    const contentLength = res.getHeader?.('content-length') || 0;

    const logEntry = {
      timestamp,
      ip: clientIp,
      method,
      url,
      host,
      userAgent,
      statusCode,
      responseTime: `${responseTime}ms`,
      contentLength,
      headers: sanitizeHeaders(req.headers),
    };

    // Include body for POST/PUT/PATCH
    if (body && ['POST', 'PUT', 'PATCH'].includes(method)) {
      logEntry.body = sanitizeBody(body);
    }

    // Query parameters
    try {
      const urlObj = new URL(url, `http://${host}`);
      if (urlObj.searchParams.toString()) {
        logEntry.query = Object.fromEntries(urlObj.searchParams);
      }
    } catch (e) {
      // Invalid URL, skip query parsing
    }

    const logLine = JSON.stringify(logEntry) + '\n';

    fs.appendFile(LOG_FILE, logLine, (err) => {
      if (err) {
        console.error('Failed to write log:', err.message);
      }
    });

    // Track metrics (non-blocking)
    try {
      const m = getMetrics();
      if (m) {
        m.incCounter('requests_total', {
          method,
          status: statusCode,
          endpoint: url.split('?')[0]
        });
      }
    } catch (e) {}
  } catch (err) {
    console.error('Request logging failed:', err.message);
  }
}

/**
 * Create a middleware wrapper that logs requests
 */
function createLoggingMiddleware() {
  return function loggingMiddleware(req, res, body, next) {
    const startTime = Date.now();

    // Intercept response finish to log with status code
    const originalEnd = res.end;
    res.end = function(...args) {
      const responseTime = Date.now() - startTime;
      logRequest(req, res, body, responseTime);
      return originalEnd.apply(this, args);
    };

    if (next) next();
  };
}

/**
 * Simple log function to call after handling request
 */
function log(req, res, body, startTime) {
  const responseTime = Date.now() - startTime;
  logRequest(req, res, body, responseTime);
}

module.exports = {
  logRequest,
  log,
  createLoggingMiddleware,
  getClientIp,
  LOG_FILE,
  LOG_DIR,
};
