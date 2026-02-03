/**
 * Metrics service - Redis-backed Prometheus format exporter + VictoriaMetrics query
 *
 * All metrics are stored in Redis for multi-worker aggregation.
 * Metrics are exported to Prometheus format, scraped by VictoriaMetrics.
 * Overtime charts query VictoriaMetrics for historical data.
 */
const http = require('http');
const storage = require('./storage');
const { redis, isConnected } = require('./redis');

// VictoriaMetrics config
const VM_HOST = process.env.VM_HOST || 'victoriametrics';
const VM_PORT = process.env.VM_PORT || 8428;

// Metrics buckets for histograms
const FPS_BUCKETS = [15, 30, 45, 60, 90, 120, 144, 240];
const LATENCY_BUCKETS = [10, 25, 50, 100, 150, 200, 300, 500, 1000];
const FRAME_TIME_BUCKETS = [5, 10, 16, 20, 33, 50, 100, 200]; // ms (60fps=16ms, 30fps=33ms)
const MEMORY_BUCKETS = [512, 1024, 2048, 4096, 8192, 16384]; // MB
const SESSION_DURATION_BUCKETS = [60, 300, 600, 1800, 3600, 7200, 14400, 28800]; // seconds (1m, 5m, 10m, 30m, 1h, 2h, 4h, 8h)
const CONNECT_TIME_BUCKETS = [50, 100, 200, 500, 1000, 2000, 5000]; // ms
const WORLD_LOAD_BUCKETS = [500, 1000, 2000, 5000, 10000, 20000]; // ms

// Redis key prefixes
const REDIS_KEYS = {
  COUNTERS: 'metrics:counters',
  GAUGES: 'metrics:gauges',
  HISTOGRAM: 'metrics:histogram:',
  AVGSTATE: 'metrics:avgstate:',
  LABELED: 'metrics:labeled:'
};

// Memory limits for labeled counters
const MAX_ENDPOINTS = 100;
const MAX_STATUS_CODES = 20;
const MAX_LABEL_VALUES = 50;

/**
 * Sanitize a string for use as a Prometheus label value
 * - Escapes backslashes, double quotes, and newlines
 * - Truncates to max length
 */
function sanitizeLabel(value, maxLength = 64) {
  if (!value || typeof value !== 'string') return 'unknown';
  // Truncate first
  let sanitized = value.substring(0, maxLength);
  // Escape special characters for Prometheus format
  sanitized = sanitized
    .replace(/\\/g, '\\\\')    // Backslash -> \\
    .replace(/"/g, '\\"')       // Double quote -> \"
    .replace(/\n/g, '\\n')      // Newline -> \n
    .replace(/\r/g, '\\r');     // Carriage return -> \r
  return sanitized || 'unknown';
}

// Local write buffer to batch Redis writes (fire-and-forget)
let writeBuffer = {
  counters: {},
  gauges: {},
  histograms: {},
  avgstates: {},
  labeled: {}
};
let flushPending = false;

/**
 * Flush buffered writes to Redis (fire-and-forget)
 */
function scheduleFlush() {
  if (flushPending || !isConnected()) return;
  flushPending = true;

  // Use setImmediate to batch multiple operations within the same event loop tick
  setImmediate(async () => {
    flushPending = false;
    const buffer = writeBuffer;
    writeBuffer = { counters: {}, gauges: {}, histograms: {}, avgstates: {}, labeled: {} };

    try {
      const pipeline = redis.pipeline();

      // Counters
      for (const [key, value] of Object.entries(buffer.counters)) {
        pipeline.hincrbyfloat(REDIS_KEYS.COUNTERS, key, value);
      }

      // Gauges
      for (const [key, value] of Object.entries(buffer.gauges)) {
        pipeline.hset(REDIS_KEYS.GAUGES, key, value.toString());
      }

      // Histograms
      for (const [name, buckets] of Object.entries(buffer.histograms)) {
        for (const [bucket, count] of Object.entries(buckets)) {
          pipeline.hincrby(`${REDIS_KEYS.HISTOGRAM}${name}`, bucket, count);
        }
      }

      // Average states
      for (const [name, state] of Object.entries(buffer.avgstates)) {
        if (state.sum) pipeline.hincrbyfloat(`${REDIS_KEYS.AVGSTATE}${name}`, 'sum', state.sum);
        if (state.count) pipeline.hincrby(`${REDIS_KEYS.AVGSTATE}${name}`, 'count', state.count);
      }

      // Labeled counters
      for (const [prefix, labels] of Object.entries(buffer.labeled)) {
        for (const [label, count] of Object.entries(labels)) {
          pipeline.hincrby(`${REDIS_KEYS.LABELED}${prefix}`, label, count);
        }
      }

      await pipeline.exec();
    } catch (e) {
      // Silently ignore errors - metrics are non-critical
    }
  });
}

/**
 * Increment a counter (buffered, non-blocking)
 */
function incCounter(name, labels = {}) {
  if (name === 'requests_total') {
    writeBuffer.counters.requests_total = (writeBuffer.counters.requests_total || 0) + 1;
    if (labels.method) {
      const key = `requests_method_${labels.method}`;
      writeBuffer.counters[key] = (writeBuffer.counters[key] || 0) + 1;
    }
    if (labels.status) {
      const key = `requests_status_${labels.status}`;
      writeBuffer.counters[key] = (writeBuffer.counters[key] || 0) + 1;
    }
    if (labels.endpoint) {
      const ep = labels.endpoint.split('?')[0];
      if (!writeBuffer.labeled.endpoints) writeBuffer.labeled.endpoints = {};
      writeBuffer.labeled.endpoints[ep] = (writeBuffer.labeled.endpoints[ep] || 0) + 1;
    }
  } else if (name === 'auth_grants_total') {
    writeBuffer.counters.auth_grants_total = (writeBuffer.counters.auth_grants_total || 0) + 1;
  } else if (name === 'sessions_created_total') {
    writeBuffer.counters.sessions_created_total = (writeBuffer.counters.sessions_created_total || 0) + 1;
  } else if (name === 'telemetry_received_total') {
    writeBuffer.counters.telemetry_received_total = (writeBuffer.counters.telemetry_received_total || 0) + 1;
  } else if (name === 'language') {
    const lang = labels.language || 'unknown';
    if (!writeBuffer.labeled.languages) writeBuffer.labeled.languages = {};
    writeBuffer.labeled.languages[lang] = (writeBuffer.labeled.languages[lang] || 0) + 1;
  } else if (name === 'exit_reason') {
    const reason = labels.reason || 'unknown';
    if (!writeBuffer.labeled.exit_reasons) writeBuffer.labeled.exit_reasons = {};
    writeBuffer.labeled.exit_reasons[reason] = (writeBuffer.labeled.exit_reasons[reason] || 0) + 1;
  } else if (name === 'event') {
    const event = labels.event || 'unknown';
    if (!writeBuffer.labeled.events) writeBuffer.labeled.events = {};
    writeBuffer.labeled.events[event] = (writeBuffer.labeled.events[event] || 0) + 1;
  } else if (name === 'server_connect') {
    const key = labels.success === 'true' ? 'server_connect_success' : 'server_connect_failure';
    writeBuffer.counters[key] = (writeBuffer.counters[key] || 0) + 1;
  } else if (name === 'server_disconnect') {
    const reason = labels.reason || 'unknown';
    if (!writeBuffer.labeled.disconnects) writeBuffer.labeled.disconnects = {};
    writeBuffer.labeled.disconnects[reason] = (writeBuffer.labeled.disconnects[reason] || 0) + 1;
  } else if (name === 'world_joined') {
    const mode = labels.game_mode || 'unknown';
    if (!writeBuffer.labeled.world_joins) writeBuffer.labeled.world_joins = {};
    writeBuffer.labeled.world_joins[mode] = (writeBuffer.labeled.world_joins[mode] || 0) + 1;
  } else if (name === 'state_transition') {
    const key = `${labels.from || 'unknown'}->${labels.to || 'unknown'}`;
    if (!writeBuffer.labeled.state_transitions) writeBuffer.labeled.state_transitions = {};
    writeBuffer.labeled.state_transitions[key] = (writeBuffer.labeled.state_transitions[key] || 0) + 1;
  }
  scheduleFlush();
}

/**
 * Add value to a counter (for totals like playtime)
 */
function addToCounter(name, value) {
  if (name === 'total_playtime_seconds') {
    writeBuffer.counters.total_playtime_seconds = (writeBuffer.counters.total_playtime_seconds || 0) + value;
  }
  scheduleFlush();
}

/**
 * Set a gauge value (buffered, non-blocking)
 */
function setGauge(name, value) {
  writeBuffer.gauges[name] = value;
  scheduleFlush();
}

/**
 * Calculate percentile from histogram bucket counts (approximate)
 */
function calculatePercentile(buckets, distribution, percentile) {
  const total = distribution.reduce((a, b) => a + b, 0);
  if (total === 0) return 0;

  const targetCount = total * (percentile / 100);
  let cumulative = 0;

  for (let i = 0; i < buckets.length; i++) {
    cumulative += distribution[i];
    if (cumulative >= targetCount) {
      const prevCum = cumulative - distribution[i];
      const bucketStart = i === 0 ? 0 : buckets[i - 1];
      const bucketEnd = buckets[i];
      const fraction = distribution[i] > 0 ? (targetCount - prevCum) / distribution[i] : 0;
      return bucketStart + (bucketEnd - bucketStart) * fraction;
    }
  }

  return buckets[buckets.length - 1];
}

/**
 * Record a histogram observation and update running average (buffered, non-blocking)
 */
function observeHistogram(name, value) {
  const bucketMap = {
    fps: { buckets: FPS_BUCKETS, dist: 'fps', avgKey: 'fps' },
    session_avg_fps: { buckets: FPS_BUCKETS, dist: 'session_avg_fps', avgKey: null },
    latency: { buckets: LATENCY_BUCKETS, dist: 'latency', avgKey: 'latency' },
    frame_time: { buckets: FRAME_TIME_BUCKETS, dist: 'frame_time', avgKey: 'frame_time' },
    memory: { buckets: MEMORY_BUCKETS, dist: 'memory', avgKey: 'memory' },
    session_duration: { buckets: SESSION_DURATION_BUCKETS, dist: 'session_duration', avgKey: 'session_duration' },
    connect_time: { buckets: CONNECT_TIME_BUCKETS, dist: 'connect_time', avgKey: 'connect_time' },
    world_load_time: { buckets: WORLD_LOAD_BUCKETS, dist: 'world_load_time', avgKey: 'world_load_time' }
  };

  const config = bucketMap[name];
  if (!config) return;

  const { buckets, dist, avgKey } = config;

  // Find bucket and increment
  if (!writeBuffer.histograms[dist]) writeBuffer.histograms[dist] = {};

  let bucketIdx = buckets.length; // overflow bucket
  for (let i = 0; i < buckets.length; i++) {
    if (value <= buckets[i]) {
      bucketIdx = i;
      break;
    }
  }

  const bucketKey = bucketIdx.toString();
  writeBuffer.histograms[dist][bucketKey] = (writeBuffer.histograms[dist][bucketKey] || 0) + 1;

  // Update running average
  if (avgKey) {
    if (!writeBuffer.avgstates[avgKey]) writeBuffer.avgstates[avgKey] = { sum: 0, count: 0 };
    writeBuffer.avgstates[avgKey].sum += value;
    writeBuffer.avgstates[avgKey].count += 1;
  }

  scheduleFlush();
}

/**
 * Query VictoriaMetrics using PromQL
 */
async function queryVictoriaMetrics(query, start, end, step = '1m') {
  return new Promise((resolve, reject) => {
    const params = new URLSearchParams({
      query,
      start: Math.floor(start / 1000),
      end: Math.floor(end / 1000),
      step
    });

    const options = {
      hostname: VM_HOST,
      port: VM_PORT,
      path: `/api/v1/query_range?${params}`,
      method: 'GET',
      timeout: 5000
    };

    const req = http.request(options, (res) => {
      let data = '';
      res.on('data', chunk => data += chunk);
      res.on('end', () => {
        try {
          const json = JSON.parse(data);
          if (json.status === 'success' && json.data?.result?.[0]?.values) {
            resolve(json.data.result[0].values.map(([ts, val]) => ({
              timestamp: ts * 1000,
              value: parseFloat(val)
            })));
          } else {
            resolve([]);
          }
        } catch (e) {
          resolve([]);
        }
      });
    });

    req.on('error', () => resolve([]));
    req.on('timeout', () => { req.destroy(); resolve([]); });
    req.end();
  });
}

/**
 * Query VictoriaMetrics for instant value
 */
async function queryVictoriaMetricsInstant(query) {
  return new Promise((resolve, reject) => {
    const params = new URLSearchParams({ query });

    const options = {
      hostname: VM_HOST,
      port: VM_PORT,
      path: `/api/v1/query?${params}`,
      method: 'GET',
      timeout: 5000
    };

    const req = http.request(options, (res) => {
      let data = '';
      res.on('data', chunk => data += chunk);
      res.on('end', () => {
        try {
          const json = JSON.parse(data);
          if (json.status === 'success' && json.data?.result?.[0]?.value) {
            resolve(parseFloat(json.data.result[0].value[1]));
          } else {
            resolve(0);
          }
        } catch (e) {
          resolve(0);
        }
      });
    });

    req.on('error', () => resolve(0));
    req.on('timeout', () => { req.destroy(); resolve(0); });
    req.end();
  });
}

/**
 * Get metrics data from VictoriaMetrics
 */
async function getMetricsFromVM(metric, range = '1h') {
  const now = Date.now();
  let startTime, step;

  switch (range) {
    case '5m': startTime = now - 5 * 60 * 1000; step = '15s'; break;
    case '15m': startTime = now - 15 * 60 * 1000; step = '30s'; break;
    case '1h': startTime = now - 60 * 60 * 1000; step = '1m'; break;
    case '6h': startTime = now - 6 * 60 * 60 * 1000; step = '5m'; break;
    case '24h': startTime = now - 24 * 60 * 60 * 1000; step = '15m'; break;
    case '7d': startTime = now - 7 * 24 * 60 * 60 * 1000; step = '1h'; break;
    default: startTime = now - 60 * 60 * 1000; step = '1m';
  }

  const queries = {
    players: 'hytale_active_players',
    servers: 'hytale_active_servers',
    sessions: 'hytale_active_sessions',
    requests: 'rate(hytale_requests_total[1m])*60',
    telemetry: 'rate(hytale_telemetry_received_total[1m])*60',
    fps_avg: 'hytale_fps_avg',
    latency_avg: 'hytale_latency_avg',
    memory_avg: 'hytale_memory_avg',
    session_duration_avg: 'hytale_session_duration_avg',
    world_load_avg: 'hytale_world_load_avg',
    connect_time_avg: 'hytale_connect_time_avg',
    frame_time_avg: 'hytale_frame_time_avg',
    frame_time_p99: 'hytale_frame_time_p99',
    session_duration_p50: 'hytale_session_duration_p50',
    session_duration_p90: 'hytale_session_duration_p90',
    session_duration_p99: 'hytale_session_duration_p99',
    total_playtime_hours: 'hytale_total_playtime_hours'
  };

  const query = queries[metric] || metric;
  const points = await queryVictoriaMetrics(query, startTime, now, step);

  return { metric, range, startTime, endTime: now, points };
}

/**
 * Record current stats (for Prometheus scraping)
 */
async function recordCurrentStats() {
  try {
    const stats = await storage.getKeyCounts();
    setGauge('active_players', stats.activePlayers);
    setGauge('active_servers', stats.servers);
    setGauge('active_sessions', stats.sessions);
    setGauge('redis_connected', isConnected() ? 1 : 0);
  } catch (e) {
    // Non-critical
  }
}

/**
 * Read all metrics from Redis and compute derived values
 */
async function readMetricsFromRedis() {
  if (!isConnected()) {
    return { counters: {}, gauges: {}, histograms: {}, labeled: {} };
  }

  try {
    const pipeline = redis.pipeline();

    pipeline.hgetall(REDIS_KEYS.COUNTERS);
    pipeline.hgetall(REDIS_KEYS.GAUGES);

    // Histogram distributions
    const histogramNames = ['fps', 'latency', 'frame_time', 'memory', 'session_duration',
                           'session_avg_fps', 'connect_time', 'world_load_time'];
    for (const name of histogramNames) {
      pipeline.hgetall(`${REDIS_KEYS.HISTOGRAM}${name}`);
    }

    // Average states
    const avgNames = ['fps', 'latency', 'frame_time', 'memory', 'session_duration',
                     'connect_time', 'world_load_time'];
    for (const name of avgNames) {
      pipeline.hgetall(`${REDIS_KEYS.AVGSTATE}${name}`);
    }

    // Labeled counters
    const labeledNames = ['languages', 'exit_reasons', 'events', 'disconnects',
                         'world_joins', 'state_transitions', 'endpoints'];
    for (const name of labeledNames) {
      pipeline.hgetall(`${REDIS_KEYS.LABELED}${name}`);
    }

    const results = await pipeline.exec();

    let idx = 0;
    const counters = parseRedisHash(results[idx++][1]);
    const gauges = parseRedisHash(results[idx++][1]);

    const histograms = {};
    for (const name of histogramNames) {
      histograms[name] = parseRedisHash(results[idx++][1], true);
    }

    const avgStates = {};
    for (const name of avgNames) {
      avgStates[name] = parseRedisHash(results[idx++][1]);
    }

    const labeled = {};
    for (const name of labeledNames) {
      labeled[name] = parseRedisHash(results[idx++][1], true);
    }

    // Compute derived gauges
    computeDerivedMetrics(counters, gauges, avgStates, histograms);

    return { counters, gauges, histograms, labeled, avgStates };
  } catch (e) {
    console.error('Error reading metrics from Redis:', e.message);
    return { counters: {}, gauges: {}, histograms: {}, labeled: {} };
  }
}

/**
 * Parse Redis hash result to object
 */
function parseRedisHash(hash, asInt = false) {
  if (!hash) return {};
  const result = {};
  for (const [key, value] of Object.entries(hash)) {
    result[key] = asInt ? parseInt(value, 10) : parseFloat(value);
  }
  return result;
}

/**
 * Compute derived metrics (averages, percentiles)
 */
function computeDerivedMetrics(counters, gauges, avgStates, histograms) {
  // Compute averages from sum/count
  const avgMap = {
    fps: 'fps_avg',
    latency: 'latency_avg',
    memory: 'memory_avg',
    session_duration: 'session_duration_avg',
    world_load_time: 'world_load_avg',
    connect_time: 'connect_time_avg',
    frame_time: 'frame_time_avg'
  };

  for (const [key, gaugeName] of Object.entries(avgMap)) {
    const state = avgStates[key];
    if (state && state.count > 0) {
      gauges[gaugeName] = Math.round((state.sum / state.count) * 100) / 100;
    }
  }

  // Compute percentiles
  const bucketDefs = {
    frame_time: FRAME_TIME_BUCKETS,
    session_duration: SESSION_DURATION_BUCKETS
  };

  // Frame time p99
  if (histograms.frame_time) {
    const dist = bucketArrayFromHash(histograms.frame_time, FRAME_TIME_BUCKETS.length + 1);
    gauges.frame_time_p99 = Math.round(calculatePercentile(FRAME_TIME_BUCKETS, dist, 99) * 100) / 100;
  }

  // Session duration percentiles
  if (histograms.session_duration) {
    const dist = bucketArrayFromHash(histograms.session_duration, SESSION_DURATION_BUCKETS.length + 1);
    gauges.session_duration_p50 = Math.round(calculatePercentile(SESSION_DURATION_BUCKETS, dist, 50));
    gauges.session_duration_p90 = Math.round(calculatePercentile(SESSION_DURATION_BUCKETS, dist, 90));
    gauges.session_duration_p99 = Math.round(calculatePercentile(SESSION_DURATION_BUCKETS, dist, 99));
  }

  // Total playtime hours (from counter, not gauge)
  const totalPlaytime = counters.total_playtime_seconds || 0;
  gauges.total_playtime_hours = Math.round(totalPlaytime / 3600 * 10) / 10;
}

/**
 * Convert hash bucket counts to array
 */
function bucketArrayFromHash(hash, length) {
  const arr = new Array(length).fill(0);
  for (const [key, value] of Object.entries(hash)) {
    const idx = parseInt(key, 10);
    if (idx >= 0 && idx < length) {
      arr[idx] = value;
    }
  }
  return arr;
}

/**
 * Helper to export histogram in Prometheus format
 */
function exportHistogram(lines, name, help, buckets, distribution) {
  lines.push(`# HELP ${name} ${help}`);
  lines.push(`# TYPE ${name} histogram`);
  let cumSum = 0;
  for (let i = 0; i < buckets.length; i++) {
    cumSum += distribution[i] || 0;
    lines.push(`${name}{le="${buckets[i]}"} ${cumSum}`);
  }
  cumSum += distribution[buckets.length] || 0;
  lines.push(`${name}{le="+Inf"} ${cumSum}`);
}

/**
 * Generate Prometheus format metrics (reads from Redis)
 */
async function getPrometheusMetrics() {
  await recordCurrentStats();

  const { counters, gauges, histograms, labeled } = await readMetricsFromRedis();
  const lines = [];

  // Counters
  lines.push('# HELP hytale_requests_total Total HTTP requests');
  lines.push('# TYPE hytale_requests_total counter');
  lines.push(`hytale_requests_total ${counters.requests_total || 0}`);

  lines.push('# HELP hytale_requests_by_method HTTP requests by method');
  lines.push('# TYPE hytale_requests_by_method counter');
  for (const method of ['GET', 'POST', 'DELETE', 'OPTIONS', 'PUT']) {
    const count = counters[`requests_method_${method}`] || 0;
    if (count > 0) lines.push(`hytale_requests_by_method{method="${method}"} ${count}`);
  }

  lines.push('# HELP hytale_requests_by_status HTTP requests by status code');
  lines.push('# TYPE hytale_requests_by_status counter');
  for (const status of ['200', '201', '400', '401', '403', '404', '500']) {
    const count = counters[`requests_status_${status}`] || 0;
    if (count > 0) lines.push(`hytale_requests_by_status{status="${status}"} ${count}`);
  }

  lines.push('# HELP hytale_auth_grants_total Total auth grants issued');
  lines.push('# TYPE hytale_auth_grants_total counter');
  lines.push(`hytale_auth_grants_total ${counters.auth_grants_total || 0}`);

  lines.push('# HELP hytale_sessions_created_total Total sessions created');
  lines.push('# TYPE hytale_sessions_created_total counter');
  lines.push(`hytale_sessions_created_total ${counters.sessions_created_total || 0}`);

  lines.push('# HELP hytale_telemetry_received_total Total telemetry events received');
  lines.push('# TYPE hytale_telemetry_received_total counter');
  lines.push(`hytale_telemetry_received_total ${counters.telemetry_received_total || 0}`);

  lines.push('# HELP hytale_total_playtime_seconds Total playtime in seconds');
  lines.push('# TYPE hytale_total_playtime_seconds counter');
  lines.push(`hytale_total_playtime_seconds ${counters.total_playtime_seconds || 0}`);

  // Language distribution (sanitize to prevent injection)
  lines.push('# HELP hytale_language_total Players by language');
  lines.push('# TYPE hytale_language_total counter');
  const languages = labeled.languages || {};
  for (const [lang, count] of Object.entries(languages).slice(0, MAX_LABEL_VALUES)) {
    lines.push(`hytale_language_total{language="${sanitizeLabel(lang, 16)}"} ${count}`);
  }

  // Exit reason distribution (sanitize user-provided reasons)
  lines.push('# HELP hytale_exit_reason_total Session exits by reason');
  lines.push('# TYPE hytale_exit_reason_total counter');
  const exitReasons = labeled.exit_reasons || {};
  for (const [reason, count] of Object.entries(exitReasons).slice(0, MAX_LABEL_VALUES)) {
    lines.push(`hytale_exit_reason_total{reason="${sanitizeLabel(reason)}"} ${count}`);
  }

  // World joins by game mode (sanitize to prevent injection)
  lines.push('# HELP hytale_world_joined_total World joins by game mode');
  lines.push('# TYPE hytale_world_joined_total counter');
  const worldJoins = labeled.world_joins || {};
  for (const [mode, count] of Object.entries(worldJoins).slice(0, MAX_LABEL_VALUES)) {
    lines.push(`hytale_world_joined_total{game_mode="${sanitizeLabel(mode, 32)}"} ${count}`);
  }

  // Server connects
  lines.push('# HELP hytale_server_connect_total Server connection attempts');
  lines.push('# TYPE hytale_server_connect_total counter');
  lines.push(`hytale_server_connect_total{success="true"} ${counters.server_connect_success || 0}`);
  lines.push(`hytale_server_connect_total{success="false"} ${counters.server_connect_failure || 0}`);

  // Server disconnects by reason (sanitize user-provided reasons)
  lines.push('# HELP hytale_server_disconnect_total Server disconnects by reason');
  lines.push('# TYPE hytale_server_disconnect_total counter');
  const disconnects = labeled.disconnects || {};
  for (const [reason, count] of Object.entries(disconnects).slice(0, MAX_LABEL_VALUES)) {
    lines.push(`hytale_server_disconnect_total{reason="${sanitizeLabel(reason)}"} ${count}`);
  }

  // Gauges
  lines.push('# HELP hytale_active_players Current active players');
  lines.push('# TYPE hytale_active_players gauge');
  lines.push(`hytale_active_players ${gauges.active_players || 0}`);

  lines.push('# HELP hytale_active_servers Current active servers');
  lines.push('# TYPE hytale_active_servers gauge');
  lines.push(`hytale_active_servers ${gauges.active_servers || 0}`);

  lines.push('# HELP hytale_active_sessions Current active sessions');
  lines.push('# TYPE hytale_active_sessions gauge');
  lines.push(`hytale_active_sessions ${gauges.active_sessions || 0}`);

  lines.push('# HELP hytale_redis_connected Redis connection status');
  lines.push('# TYPE hytale_redis_connected gauge');
  lines.push(`hytale_redis_connected ${gauges.redis_connected || 0}`);

  // Running average gauges
  lines.push('# HELP hytale_fps_avg Current average FPS');
  lines.push('# TYPE hytale_fps_avg gauge');
  lines.push(`hytale_fps_avg ${gauges.fps_avg || 0}`);

  lines.push('# HELP hytale_latency_avg Current average latency (ms)');
  lines.push('# TYPE hytale_latency_avg gauge');
  lines.push(`hytale_latency_avg ${gauges.latency_avg || 0}`);

  lines.push('# HELP hytale_memory_avg Current average memory usage (MB)');
  lines.push('# TYPE hytale_memory_avg gauge');
  lines.push(`hytale_memory_avg ${gauges.memory_avg || 0}`);

  lines.push('# HELP hytale_session_duration_avg Current average session duration (seconds)');
  lines.push('# TYPE hytale_session_duration_avg gauge');
  lines.push(`hytale_session_duration_avg ${gauges.session_duration_avg || 0}`);

  lines.push('# HELP hytale_world_load_avg Current average world load time (ms)');
  lines.push('# TYPE hytale_world_load_avg gauge');
  lines.push(`hytale_world_load_avg ${gauges.world_load_avg || 0}`);

  lines.push('# HELP hytale_connect_time_avg Current average connect time (ms)');
  lines.push('# TYPE hytale_connect_time_avg gauge');
  lines.push(`hytale_connect_time_avg ${gauges.connect_time_avg || 0}`);

  lines.push('# HELP hytale_frame_time_avg Current average frame time (ms)');
  lines.push('# TYPE hytale_frame_time_avg gauge');
  lines.push(`hytale_frame_time_avg ${gauges.frame_time_avg || 0}`);

  lines.push('# HELP hytale_frame_time_p99 Frame time 99th percentile (ms)');
  lines.push('# TYPE hytale_frame_time_p99 gauge');
  lines.push(`hytale_frame_time_p99 ${gauges.frame_time_p99 || 0}`);

  lines.push('# HELP hytale_session_duration_p50 Session duration 50th percentile (seconds)');
  lines.push('# TYPE hytale_session_duration_p50 gauge');
  lines.push(`hytale_session_duration_p50 ${gauges.session_duration_p50 || 0}`);

  lines.push('# HELP hytale_session_duration_p90 Session duration 90th percentile (seconds)');
  lines.push('# TYPE hytale_session_duration_p90 gauge');
  lines.push(`hytale_session_duration_p90 ${gauges.session_duration_p90 || 0}`);

  lines.push('# HELP hytale_session_duration_p99 Session duration 99th percentile (seconds)');
  lines.push('# TYPE hytale_session_duration_p99 gauge');
  lines.push(`hytale_session_duration_p99 ${gauges.session_duration_p99 || 0}`);

  lines.push('# HELP hytale_total_playtime_hours Total playtime in hours');
  lines.push('# TYPE hytale_total_playtime_hours gauge');
  lines.push(`hytale_total_playtime_hours ${gauges.total_playtime_hours || 0}`);

  // Hardware stats (from all players who ever sent telemetry)
  const hwStats = await getHardwareStats(false);
  if (hwStats) {
    lines.push('# HELP hytale_hardware_players_total Total players with hardware telemetry');
    lines.push('# TYPE hytale_hardware_players_total gauge');
    lines.push(`hytale_hardware_players_total ${hwStats.total}`);

    lines.push('# HELP hytale_hardware_os Players by operating system');
    lines.push('# TYPE hytale_hardware_os gauge');
    for (const [os, count] of Object.entries(hwStats.os).slice(0, MAX_LABEL_VALUES)) {
      const safeOs = os.replace(/"/g, '\\"');
      lines.push(`hytale_hardware_os{os="${safeOs}"} ${count}`);
    }

    lines.push('# HELP hytale_hardware_gpu_vendor Players by GPU vendor');
    lines.push('# TYPE hytale_hardware_gpu_vendor gauge');
    for (const [vendor, count] of Object.entries(hwStats.gpu_vendor).slice(0, MAX_LABEL_VALUES)) {
      const safeVendor = vendor.replace(/"/g, '\\"');
      lines.push(`hytale_hardware_gpu_vendor{vendor="${safeVendor}"} ${count}`);
    }

    lines.push('# HELP hytale_hardware_resolution Players by screen resolution');
    lines.push('# TYPE hytale_hardware_resolution gauge');
    for (const [res, count] of Object.entries(hwStats.resolution).slice(0, MAX_LABEL_VALUES)) {
      lines.push(`hytale_hardware_resolution{resolution="${res}"} ${count}`);
    }

    lines.push('# HELP hytale_hardware_memory Players by system memory');
    lines.push('# TYPE hytale_hardware_memory gauge');
    for (const [mem, count] of Object.entries(hwStats.memory_gb).slice(0, MAX_LABEL_VALUES)) {
      lines.push(`hytale_hardware_memory{memory="${mem}"} ${count}`);
    }

    lines.push('# HELP hytale_hardware_cpu_cores Players by CPU core count');
    lines.push('# TYPE hytale_hardware_cpu_cores gauge');
    for (const [cores, count] of Object.entries(hwStats.cpu_cores).slice(0, MAX_LABEL_VALUES)) {
      lines.push(`hytale_hardware_cpu_cores{cores="${cores}"} ${count}`);
    }

    lines.push('# HELP hytale_hardware_refresh_rate Players by monitor refresh rate');
    lines.push('# TYPE hytale_hardware_refresh_rate gauge');
    for (const [rate, count] of Object.entries(hwStats.refresh_rate).slice(0, MAX_LABEL_VALUES)) {
      lines.push(`hytale_hardware_refresh_rate{rate="${rate}"} ${count}`);
    }

    lines.push('# HELP hytale_hardware_display_mode Players by display mode');
    lines.push('# TYPE hytale_hardware_display_mode gauge');
    for (const [mode, count] of Object.entries(hwStats.display_mode).slice(0, MAX_LABEL_VALUES)) {
      lines.push(`hytale_hardware_display_mode{mode="${mode}"} ${count}`);
    }
  }

  // Download stats
  const downloadStats = await storage.getDownloadStats();
  if (downloadStats && downloadStats.total > 0) {
    lines.push('# HELP hytale_downloads_total Total downloads by file');
    lines.push('# TYPE hytale_downloads_total counter');
    lines.push(`hytale_downloads_total ${downloadStats.total}`);

    for (const [filename, fileData] of Object.entries(downloadStats.files || {})) {
      const safeFilename = sanitizeLabel(filename, 64);
      lines.push(`hytale_downloads_by_file{file="${safeFilename}"} ${fileData.total || 0}`);
    }
  }

  // Histograms
  const histDefs = [
    { name: 'fps', metricName: 'hytale_player_fps_bucket', help: 'Player FPS distribution', buckets: FPS_BUCKETS },
    { name: 'latency', metricName: 'hytale_player_latency_bucket', help: 'Player latency distribution (ms)', buckets: LATENCY_BUCKETS },
    { name: 'frame_time', metricName: 'hytale_frame_time_bucket', help: 'Frame time distribution (ms)', buckets: FRAME_TIME_BUCKETS },
    { name: 'memory', metricName: 'hytale_memory_bucket', help: 'Memory usage distribution (MB)', buckets: MEMORY_BUCKETS },
    { name: 'session_duration', metricName: 'hytale_session_duration_bucket', help: 'Session duration distribution (seconds)', buckets: SESSION_DURATION_BUCKETS },
    { name: 'session_avg_fps', metricName: 'hytale_session_avg_fps_bucket', help: 'Session average FPS distribution', buckets: FPS_BUCKETS },
    { name: 'connect_time', metricName: 'hytale_connect_time_bucket', help: 'Server connect time distribution (ms)', buckets: CONNECT_TIME_BUCKETS },
    { name: 'world_load_time', metricName: 'hytale_world_load_bucket', help: 'World load time distribution (ms)', buckets: WORLD_LOAD_BUCKETS }
  ];

  for (const { name, metricName, help, buckets } of histDefs) {
    const dist = bucketArrayFromHash(histograms[name] || {}, buckets.length + 1);
    exportHistogram(lines, metricName, help, buckets, dist);
  }

  return lines.join('\n');
}

/**
 * Get current snapshot of all metrics (for debugging)
 */
async function getMetricsSnapshot() {
  const { counters, gauges, histograms, labeled } = await readMetricsFromRedis();

  return {
    counters: {
      requests_total: counters.requests_total || 0,
      requests_by_method: {
        GET: counters.requests_method_GET || 0,
        POST: counters.requests_method_POST || 0,
        DELETE: counters.requests_method_DELETE || 0,
        OPTIONS: counters.requests_method_OPTIONS || 0
      },
      auth_grants_total: counters.auth_grants_total || 0,
      sessions_created_total: counters.sessions_created_total || 0,
      telemetry_received_total: counters.telemetry_received_total || 0,
      total_playtime_seconds: counters.total_playtime_seconds || 0,
      total_playtime_hours: Math.round((counters.total_playtime_seconds || 0) / 3600 * 10) / 10
    },
    gauges: gauges,
    histograms: {
      fps: { buckets: FPS_BUCKETS, counts: bucketArrayFromHash(histograms.fps || {}, FPS_BUCKETS.length + 1) },
      latency: { buckets: LATENCY_BUCKETS, counts: bucketArrayFromHash(histograms.latency || {}, LATENCY_BUCKETS.length + 1) },
      frame_time: { buckets: FRAME_TIME_BUCKETS, counts: bucketArrayFromHash(histograms.frame_time || {}, FRAME_TIME_BUCKETS.length + 1) },
      memory: { buckets: MEMORY_BUCKETS, counts: bucketArrayFromHash(histograms.memory || {}, MEMORY_BUCKETS.length + 1) },
      session_duration: { buckets: SESSION_DURATION_BUCKETS, counts: bucketArrayFromHash(histograms.session_duration || {}, SESSION_DURATION_BUCKETS.length + 1) },
      session_avg_fps: { buckets: FPS_BUCKETS, counts: bucketArrayFromHash(histograms.session_avg_fps || {}, FPS_BUCKETS.length + 1) },
      connect_time: { buckets: CONNECT_TIME_BUCKETS, counts: bucketArrayFromHash(histograms.connect_time || {}, CONNECT_TIME_BUCKETS.length + 1) },
      world_load_time: { buckets: WORLD_LOAD_BUCKETS, counts: bucketArrayFromHash(histograms.world_load_time || {}, WORLD_LOAD_BUCKETS.length + 1) }
    },
    distributions: {
      languages: labeled.languages || {},
      exit_reasons: labeled.exit_reasons || {},
      events: labeled.events || {},
      server_connects: {
        success: counters.server_connect_success || 0,
        failure: counters.server_connect_failure || 0
      },
      server_disconnects: labeled.disconnects || {},
      world_joins: labeled.world_joins || {}
    },
    memory: {
      endpointsTracked: Object.keys(labeled.endpoints || {}).length,
      statusCodesTracked: 7,
      languagesTracked: Object.keys(labeled.languages || {}).length,
      exitReasonsTracked: Object.keys(labeled.exit_reasons || {}).length
    },
    timestamp: Date.now()
  };
}

/**
 * Get hardware stats from players
 * @param {boolean} activeOnly - If true, only get stats from currently active players. If false, get from all players who ever sent telemetry.
 */
async function getHardwareStats(activeOnly = true) {
  if (!isConnected()) return null;

  try {
    const stats = {
      os: {},
      gpu_vendor: {},
      gpu_model: {},
      resolution: {},
      refresh_rate: {},
      cpu_cores: {},
      memory_gb: {},
      display_mode: {},
      total: 0,
      totalWithHardware: 0
    };

    let uuids;
    if (activeOnly) {
      // Only currently active players
      const now = Date.now();
      uuids = await redis.zrangebyscore('active:players', now, '+inf', 'LIMIT', 0, 5000);
    } else {
      // All players who ever sent hardware telemetry
      uuids = await redis.smembers('players:with_hardware');
    }

    if (!uuids || !uuids.length) return stats;

    stats.totalWithHardware = uuids.length;

    // Process in batches of 500 to avoid memory issues
    const BATCH_SIZE = 500;
    for (let i = 0; i < uuids.length; i += BATCH_SIZE) {
      const batch = uuids.slice(i, i + BATCH_SIZE);
      const keys = batch.map(uuid => `user:${uuid}`);
      const userData = await redis.mget(keys);

      for (let j = 0; j < userData.length; j++) {
        if (!userData[j]) continue;

        let data;
        try { data = JSON.parse(userData[j]); } catch (e) { continue; }
        if (!data?.hardware) continue;

        const hw = data.hardware;
        stats.total++;

        if (hw.os) stats.os[hw.os] = (stats.os[hw.os] || 0) + 1;
        if (hw.gpu_vendor) stats.gpu_vendor[hw.gpu_vendor] = (stats.gpu_vendor[hw.gpu_vendor] || 0) + 1;

        if (hw.gpu_renderer) {
          let gpuName = hw.gpu_renderer.replace(/NVIDIA |AMD |Intel\(R\) |GeForce |Radeon /gi, '').substring(0, 40);
          stats.gpu_model[gpuName] = (stats.gpu_model[gpuName] || 0) + 1;
        }

        if (hw.resolution) stats.resolution[hw.resolution] = (stats.resolution[hw.resolution] || 0) + 1;
        if (hw.refresh_rate) stats.refresh_rate[`${hw.refresh_rate}Hz`] = (stats.refresh_rate[`${hw.refresh_rate}Hz`] || 0) + 1;
        if (hw.cpu_cores) stats.cpu_cores[`${hw.cpu_cores} cores`] = (stats.cpu_cores[`${hw.cpu_cores} cores`] || 0) + 1;
        if (hw.system_memory_mb) {
          const gb = Math.round(hw.system_memory_mb / 1024);
          stats.memory_gb[`${gb}GB`] = (stats.memory_gb[`${gb}GB`] || 0) + 1;
        }
        if (hw.display_mode) stats.display_mode[hw.display_mode] = (stats.display_mode[hw.display_mode] || 0) + 1;
      }
    }

    return stats;
  } catch (e) {
    console.error('getHardwareStats error:', e.message);
    return null;
  }
}

// Reset histogram distributions and averages periodically (every hour)
setInterval(async () => {
  if (!isConnected()) return;
  try {
    const pipeline = redis.pipeline();

    // Delete histogram keys
    const histogramNames = ['fps', 'latency', 'frame_time', 'memory', 'session_duration',
                           'session_avg_fps', 'connect_time', 'world_load_time'];
    for (const name of histogramNames) {
      pipeline.del(`${REDIS_KEYS.HISTOGRAM}${name}`);
    }

    // Delete average state keys
    const avgNames = ['fps', 'latency', 'frame_time', 'memory', 'session_duration',
                     'connect_time', 'world_load_time'];
    for (const name of avgNames) {
      pipeline.del(`${REDIS_KEYS.AVGSTATE}${name}`);
    }

    await pipeline.exec();
    console.log('Metrics: Reset hourly histogram and average data');
  } catch (e) {
    // Non-critical
  }
}, 3600000);

// Update gauges every minute
setInterval(recordCurrentStats, 60000);

module.exports = {
  incCounter,
  addToCounter,
  setGauge,
  observeHistogram,
  getPrometheusMetrics,
  getMetricsFromVM,
  getMetricsSnapshot,
  getHardwareStats,
  recordCurrentStats,
  queryVictoriaMetrics,
  queryVictoriaMetricsInstant
};
