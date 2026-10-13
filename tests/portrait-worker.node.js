const test=require('node:test'),assert=require('node:assert/strict'),path=require('path');
const {PortraitQueue}=require('../src/services/portraitRenderer');
const workerPath=path.join(__dirname,'worker-fixtures/portrait.cjs');
test('serial queue reuses worker and bounds pending work',async()=>{
 const q=new PortraitQueue({workerPath,maxPending:2});try{
 const a=q.render({value:'one',delay:20}),b=q.render({value:'two'});await assert.rejects(q.render({value:'three'}),/full/);
 const r=await Promise.all([a,b]);assert.deepEqual(r.map(x=>x.buffer.toString()),['one','two']);assert.deepEqual(r.map(x=>x.timings.concurrent),[1,1]);
 const worker=q.worker;await q.render({value:'four'});assert.equal(q.worker,worker);
 }finally{await q.close();}
});
test('worker errors and timeouts reject the job and recover queued requests',async()=>{
 for(const skin of [{exit:true},{hang:true}]){
 const q=new PortraitQueue({workerPath,timeoutMs:150});try{const a=q.render(skin),b=q.render({value:'recovered'});await assert.rejects(a,/exited|timed out/);assert.equal((await b).buffer.toString(),'recovered');}finally{await q.close();}
 }
});
test('render failures do not poison later work and close settles pending promises',async()=>{
 const q=new PortraitQueue({workerPath});try{await assert.rejects(q.render({fail:true}),/fixture failure/);assert.equal((await q.render({value:'ok'})).buffer.toString(),'ok');
 const a=q.render({hang:true}),b=q.render({value:'pending'});const settled=Promise.allSettled([a,b]);await q.close();assert.ok((await settled).every(x=>x.status==='rejected'));
 }finally{await q.close();}
});
