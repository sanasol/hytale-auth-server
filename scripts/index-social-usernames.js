// Run once after deploying the username writer: node scripts/index-social-usernames.js
const { redis, connect } = require('../src/services/redis');
const config = require('../src/config');
(async () => {
  await connect();
  let cursor = '0', indexed = 0;
  do {
    const [next, keys] = await redis.scan(cursor, 'MATCH', `${config.redisKeys.USERNAME}*`, 'COUNT', 1000);
    cursor = next;
    if (!keys.length) continue;
    const values = await redis.mget(...keys), tx = redis.pipeline();
    keys.forEach((key, i) => {
      const id = key.slice(config.redisKeys.USERNAME.length), name = values[i];
      if (name && /^[0-9a-f-]{36}$/i.test(id)) { tx.sadd(`social:v1:names:${name.toLowerCase()}`, id); indexed++; }
    });
    const result = await tx.exec(); if (result.some(([error]) => error)) throw Error('Index write failed');
  } while (cursor !== '0');
  await redis.set('social:v1:names-ready', '1');
  console.log(`Indexed ${indexed} usernames`);
})().catch(e => { console.error(e.message); process.exitCode = 1; }).finally(() => redis.disconnect());
