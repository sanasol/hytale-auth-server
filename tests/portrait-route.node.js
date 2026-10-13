const test=require('node:test'),assert=require('node:assert/strict'),fs=require('fs'),os=require('os'),path=require('path');
const temp=fs.mkdtempSync(path.join(os.tmpdir(),'portrait-route-'));process.env.DATA_DIR=temp;
let skin={bodyCharacteristic:'Default.01'},calls=0,fail=false;
const sf=require.resolve('../src/services/storage');require.cache[sf]={id:sf,filename:sf,loaded:true,exports:{getUserData:async()=>({skin})}};
const pf=require.resolve('../src/services/portraitRenderer');require.cache[pf]={id:pf,filename:pf,loaded:true,exports:{renderHead:async()=>{calls++;await new Promise(r=>setTimeout(r,5));if(fail)throw Error('fixture');return{buffer:Buffer.from('fixture-png'),timings:{total:5}};}}};
const avatar=require('../src/routes/avatar'),uuid='ce40d730-0025-4157-bcfb-f31c11a162d5';
function req(bg='black',method='GET',headers={}){return{method,url:`/avatar/${uuid}/head.png?bg=${bg}`,headers:{host:'localhost',...headers}};}
function res(){return{headers:{},setHeader(k,v){this.headers[k]=v},writeHead(s,h){this.status=s;Object.assign(this.headers,h)},end(b){this.body=b}};}
test('deduplicates concurrent misses, serves disk hits and conditional responses',async()=>{
 const a=res(),b=res();await Promise.all([avatar.serveServerHead(req(),a,uuid),avatar.serveServerHead(req(),b,uuid)]);assert.equal(calls,1);assert.equal(a.status,200);assert.equal(a.headers['Content-Type'],'image/png');assert.match(a.headers['Cache-Control'],/private/);assert.equal(a.headers['Cloudflare-CDN-Cache-Control'],'no-store');
 const c=res();await avatar.serveServerHead(req(),c,uuid);assert.equal(calls,1);assert.match(c.headers['Server-Timing'],/cache/);
 const d=res();await avatar.serveServerHead(req('black','GET',{'if-none-match':c.headers.ETag}),d,uuid);assert.equal(d.status,304);assert.equal(d.body,undefined);
 await avatar.serveServerHead(req('white'),res(),uuid);assert.equal(calls,2);skin={bodyCharacteristic:'Goblin.02'};await avatar.serveServerHead(req(),res(),uuid);assert.equal(calls,3);
});
test('renderer failure is retryable; missing skin and wrong methods reject early',async()=>{
 fail=true;skin={bodyCharacteristic:'Muscular.03'};let r=res();await avatar.serveServerHead(req(),r,uuid);assert.equal(r.status,503);assert.equal(r.headers['Retry-After'],'1');fail=false;r=res();await avatar.serveServerHead(req(),r,uuid);assert.equal(r.status,200);
 r=res();await avatar.serveServerHead(req('black','POST'),r,uuid);assert.equal(r.status,405);skin=null;r=res();await avatar.serveServerHead(req(),r,uuid);assert.equal(r.status,404);
});
process.on('exit',()=>fs.rmSync(temp,{recursive:true,force:true}));
test('default head route returns server PNG, with browser fallback if rendering fails',async()=>{
 skin={bodyCharacteristic:'Default.09'};fail=false;let r=res();await avatar.handleAvatarRoutes(req(),r,`/avatar/${uuid}/head`);assert.equal(r.headers['Content-Type'],'image/png');
 skin={bodyCharacteristic:'Default.10'};fail=true;r=res();await avatar.handleAvatarRoutes(req(),r,`/avatar/${uuid}/head`);assert.equal(r.headers['Content-Type'],'text/html');assert.match(r.body,/HytaleAvatarViewer/);fail=false;
});
