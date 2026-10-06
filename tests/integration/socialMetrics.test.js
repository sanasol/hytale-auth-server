// Use an empty disposable Redis database, e.g. REDIS_URL=redis://127.0.0.1:16379/15.
const {test} = require('node:test');
const assert = require('node:assert/strict');
const {randomUUID} = require('crypto');
if (!process.env.REDIS_URL || !process.env.DATA_DIR) throw Error('Disposable REDIS_URL and DATA_DIR required');
const s = require('../../src/services/social');
const m = require('../../src/services/socialMetrics');
const {connect} = require('../../src/services/redis');
test('atomic metrics: WATCH retry, aborted state, bounded retention, snapshot baseline with concurrent deltas', async()=>{
  await connect();
  const id=randomUUID(), day=new Date().toISOString().slice(0,10), keys=[m.TOTALS_KEY,m.dailyKey(day),s.key(id)];
  let owned=false;
  try {
    assert.equal(await s.redis.exists(...keys),0,'Use an empty disposable metrics database');
    owned=true;
    const duplicate=s.redis.duplicate;
    let injected=false, calls=0;
    try {
      s.redis.duplicate=function(...args) {
        const db=duplicate.apply(this,args), multi=db.multi.bind(db);
        db.multi=()=>{
          const tx=multi(), exec=tx.exec.bind(tx);
          tx.exec=async()=>{
            if (!injected) { injected=true; await s.redis.set(s.key(id),'{}'); }
            return exec();
          };
          return tx;
        };
        return db;
      };
      await s.change([id],(r,emit,metric)=>{
        calls++; r[id].testMetric=true;
        metric('friendsAccepted'); metric('friendEdgesDelta'); metric('partiesCreated'); metric('partiesDelta');
      });
    } finally { s.redis.duplicate=duplicate; }
    assert.equal(calls,2);
    const snapshot=await m.read(1);
    assert.equal(snapshot.totals.friendsAccepted,1);
    assert.equal(snapshot.daily[0].friendsAccepted,1);
    assert.equal(snapshot.baselineKnown,false);
    assert.equal(snapshot.current.friendEdges,null);
    await assert.rejects(s.change([id],(r,emit,metric)=>{ metric('friendsAccepted'); s.fail(409,'abort'); }),{status:409});
    assert.equal((await m.read(1)).totals.friendsAccepted,1);
    const ttl=await s.redis.ttl(m.dailyKey(day));
    assert.ok(ttl>364*86400 && ttl<=365*86400);
    await s.change([id],(r,emit,metric)=>{ metric('friendEdgesDelta',-1); metric('partiesDelta',-1); });
    assert.equal(await m.initBaseline({baselineId:'checkpoint-test',friendEdges:10,parties:5,friendEdgesDelta:1,partiesDelta:1}),true);
    const result=await m.read(1);
    assert.deepEqual(result.current,{friendEdges:9,parties:4});
    assert.equal(await m.initBaseline({baselineId:'another-id',friendEdges:999,parties:999,friendEdgesDelta:0,partiesDelta:0}),false);
    assert.deepEqual((await m.read(1)).current,result.current);
    await s.change([id],(r,emit,metric)=>metric('friendEdgesDelta'));
    assert.equal((await m.read(1)).current.friendEdges,10);
  } finally {
    if (owned) await s.redis.del(...keys);
    s.redis.disconnect();
  }
});
