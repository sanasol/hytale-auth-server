// Apply counts from an isolated checkpoint, never scan the live database.
// REDIS_URL=... node scripts/init-social-metrics-baseline.js snapshot-counts.json
const fs = require('fs');
const { connect, redis } = require('../src/services/redis');
const { initBaseline } = require('../src/services/socialMetrics');
(async () => {
  if (!process.argv[2]) throw Error('Snapshot JSON file required');
  const snapshot = JSON.parse(fs.readFileSync(process.argv[2],'utf8'));
  await connect();
  console.log(await initBaseline(snapshot) ? 'Social baseline initialized' : 'Baseline already initialized; no change');
})().catch(error=>{ console.error(error.message); process.exitCode=1; }).finally(()=>redis.disconnect());
