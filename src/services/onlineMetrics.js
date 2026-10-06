// Direct gateway observations only. No token-age estimates or Redis scans.
const RANGES = { '5m': [300, 15], '15m': [900, 30], '1h': [3600, 60], '6h': [21600, 300], '24h': [86400, 900], '7d': [604800, 3600], '30d': [2592000, 21600], '90d': [7776000, 43200], '365d': [31536000, 86400] };
const SERIES = { online_users: 'hytale_gateway_online_users', connections: 'hytale_gateway_connections' };
async function json(url) {
  const response = await fetch(url, { signal: AbortSignal.timeout(10000) });
  if (!response.ok) throw Error(`Metrics HTTP ${response.status}`);
  return response.json();
}
async function current() {
  try {
    const data = await json(`${process.env.GATEWAY_URL || 'http://socket-gateway:3000'}/health`);
    if (![data.onlineUsers, data.connections].every(n => Number.isInteger(n) && n >= 0)) throw Error('Invalid gateway counts');
    return { source: 'authenticated_websocket', available: true, onlineUsers: data.onlineUsers, connections: data.connections, deadPeerTimeoutSeconds: 50, timestamp: Date.now() };
  } catch {
    return { source: 'authenticated_websocket', available: false, onlineUsers: null, connections: null, timestamp: Date.now() };
  }
}
async function history(metric, range) {
  if (!SERIES[metric] || !RANGES[range]) throw Object.assign(Error('Unsupported online metric or range'), { status: 400 });
  const [seconds, step] = RANGES[range], series = SERIES[metric], end = Math.floor(Date.now()/1000), start = end - seconds;
  const vm = `http://${process.env.VM_HOST || 'victoriametrics'}:${process.env.VM_PORT || 8428}`;
  const query = async (expression, ranged = false) => {
    const params = new URLSearchParams(ranged ? { query: expression, start, end, step, nocache: 1 } : { query: expression, time: end, nocache: 1 });
    const data = await json(`${vm}/api/v1/${ranged ? 'query_range' : 'query'}?${params}`);
    if (data.status !== 'success') throw Error('Metrics query failed');
    return data.data.result;
  };
  const instant = async expression => {
    const result = await query(expression);
    const value = result[0]?.value?.[1];
    return value !== undefined && Number.isFinite(Number(value)) ? Number(value) : null;
  };
  // A missing scrape remains a gap, never a fabricated zero. UUIDs are not labels.
  const [mean, peak, average, maximum, samples, sum] = await Promise.all([
    query(`avg_over_time(${series}[${step}s])`, true),
    query(`max_over_time(${series}[${step}s])`, true),
    instant(`avg_over_time(${series}[${seconds}s])`),
    instant(`max_over_time(${series}[${seconds}s])`),
    instant(`count_over_time(${series}[${seconds}s])`),
    instant(`sum_over_time(${series}[${seconds}s])`)
  ]);
  const points = result => (result[0]?.values || []).map(([ts, value]) => ({ timestamp: ts*1000, value: Number.isFinite(Number(value)) ? Number(value) : null }));
  return { metric, range, startTime: start*1000, endTime: end*1000, stepSeconds: step, points: points(mean), peaks: points(peak), summary: {
    average, peak: maximum, samples: samples || 0,
    // Rectangular integration of 15-second observations; not extrapolated through outages.
    observedHours: sum === null ? null : sum*15/3600,
    coveragePercent: Math.min(100, (samples || 0)*15/seconds*100),
    hoursMethod: '15-second samples; includes menus; not gameplay time or unique users over the period'
  } };
}
module.exports = { current, history, RANGES, SERIES };
