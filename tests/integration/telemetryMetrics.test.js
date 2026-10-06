// Disposable Redis DB14 only: REDIS_URL=redis://127.0.0.1:16379/14 DATA_DIR=/tmp/hytale-social-keys node --test tests/integration/telemetryMetrics.test.js
const {test}=require('node:test');
const assert=require('node:assert/strict');
const {randomUUID}=require('node:crypto');
if (!process.env.REDIS_URL?.endsWith('/14') || !process.env.DATA_DIR) throw Error('Disposable Redis DB14 and DATA_DIR required');
const {redis,connect}=require('../../src/services/redis');
const usage=require('../../src/services/socialMetrics');
const telemetry=require('../../src/services/telemetryMetrics');
test('native outcomes deduplicate retries, isolate sessions, separate local worlds and reject invalid envelopes',async()=>{
 await connect(); await redis.flushdb();
 const id=randomUUID(),session=randomUUID();
 const event=(name,sequence,event_data)=>({type:'event',event_name:name,sequence,event_data,session_id:session,timestamp:new Date().toISOString()});
 try {
  const e=event('server_connect',1,{success:true,is_p2p:true});
  const results=await Promise.all([telemetry.record(id,e),telemetry.record(id,e)]);
  assert.equal(results.filter(Boolean).length,1);
  await telemetry.record(id,event('world_joined',2,{is_singleplayer:false}));
  await telemetry.record(id,event('world_joined',3,{is_singleplayer:true}));
  await telemetry.record(id,event('ice_result',4,{reason:'Nominated',used_relay:true}));
  await telemetry.record(id,event('ice_result',5,{reason:'Nominated',used_relay:false}));
  await telemetry.record(id,event('ice_result',6,{reason:'AllPairsFailed',used_relay:false}));
  await telemetry.record(id,event('ice_result',7,{reason:'FutureValue',used_relay:false}));
  assert.equal(await telemetry.record(id,{...e,timestamp:'invalid'}),false);
  assert.equal(await telemetry.record(id,{...e,sequence:1.5}),false);
  assert.equal(await telemetry.record(id,{...e,timestamp:new Date(Date.now()-8*86400000).toISOString()}),false);
  const counts=(await usage.read(1)).totals;
  assert.equal(counts.clientConnectSuccess,1);assert.equal(counts.clientP2PConnectSuccess,1);
  assert.equal(counts.clientWorldJoined,1);assert.equal(counts.iceRelay,1);assert.equal(counts.iceDirect,1);assert.equal(counts.iceFailed,1);assert.equal(counts.iceUnknown,1);
  // Auth is checked before processing even a syntactically valid native envelope.
  require.cache[require.resolve('../../src/services/metrics')]={exports:{}};
  const route=require('../../src/routes/telemetry');
  const res={writeHead(status){this.status=status;},end(){}};
  await route.handleTelemetry({},res,e,{authorization:'Bearer forged.token.signature'});
  assert.equal(res.status,401);
 }finally{await redis.flushdb();await redis.quit();}
});
