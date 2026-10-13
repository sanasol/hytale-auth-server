const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('fs'),os=require('os'),path=require('path');
const {Readable}=require('stream');
const temp=fs.mkdtempSync(path.join(os.tmpdir(),'avatar-cache-test-'));
process.env.DATA_DIR=temp;
const storageFile=require.resolve('../src/services/storage');
let skin={bodyCharacteristic:'Default.01',eyes:'Medium_Eyes.Blue'};
require.cache[storageFile]={id:storageFile,filename:storageFile,loaded:true,exports:{getUserData:async()=>({skin}),verifyAdminToken:async t=>t==='valid-admin'}};
const authFile=require.resolve('../src/services/auth');
let claims=null;
require.cache[authFile]={id:authFile,filename:authFile,loaded:true,exports:{verifyToken:()=>claims}};
const avatar=require('../src/routes/avatar');
const uuid='ce40d730-0025-4157-bcfb-f31c11a162d5';
function response(){return {headers:{},setHeader(k,v){this.headers[k]=v;},writeHead(s,h){this.status=s;Object.assign(this.headers,h);},end(b){this.body=b;}};}
test('cache identity changes with skin, preserves background and ignores object key order',()=>{
 const a=avatar.headCacheKey(uuid,'black',skin);
 assert.equal(a,avatar.headCacheKey(uuid,'black',{eyes:skin.eyes,bodyCharacteristic:skin.bodyCharacteristic}));
 assert.notEqual(a,avatar.headCacheKey(uuid,'white',skin));
 assert.notEqual(a,avatar.headCacheKey(uuid,'black',{...skin,eyes:'Medium_Eyes.Red'}));
 assert.equal(avatar.normalizeHeadBackground('../bad'),'black');
});
test('only current 128px PNG is cached; stale render cannot overwrite changed skin',async()=>{
 const png=await require('sharp')({create:{width:128,height:128,channels:4,background:{r:50,g:100,b:150,alpha:1}}}).png().toBuffer();
 const key=avatar.headCacheKey(uuid,'white',skin);
 async function post(data,v){const req=Readable.from([data]);req.method='POST';req.headers={host:'localhost'};req.url=`/avatar/${uuid}/head-cache?bg=white&v=${v}`;const res=response();await avatar.handleAvatarRoutes(req,res,`/avatar/${uuid}/head-cache`);return res;}
 assert.equal((await post(Buffer.from('invalid'),key)).status,400);
 assert.equal((await post(png,key)).status,204);
 assert.ok(fs.existsSync(path.join(temp,'head-cache',key+'.png')));
 const req={method:'GET',headers:{host:'localhost'},url:`/avatar/${uuid}/head?bg=white`},res=response();
 await avatar.handleAvatarRoutes(req,res,`/avatar/${uuid}/head`);
 assert.equal(res.headers['Content-Type'],'image/png');
 assert.equal(res.headers['Cache-Control'],'no-cache');
 skin={...skin,eyes:'Medium_Eyes.Red'};
 assert.equal((await post(png,key)).status,409);
 const fresh=response();await avatar.handleAvatarRoutes(req,fresh,`/avatar/${uuid}/head`);
 assert.equal(fresh.headers['Content-Type'],'text/html');
 assert.match(fresh.body,/setSize\(128, 128, false\)/);
});
test('editor save requires admin session or an unexpired owner session',async()=>{
 const req={method:'PUT',headers:{}};
 let res=response();assert.equal(await avatar.authorizeSkinEditor(req,res,uuid),false);assert.equal(res.status,401);
 req.headers={'x-admin-token':'valid-admin'};assert.equal(await avatar.authorizeSkinEditor(req,response(),uuid),true);
 req.headers={authorization:'Bearer owner'};claims={uuid,scope:'hytale:server',exp:Date.now()/1000+60};assert.equal(await avatar.authorizeSkinEditor(req,response(),uuid),true);
 claims.exp=1;assert.equal(await avatar.authorizeSkinEditor(req,response(),uuid),false);
 claims={uuid:'other',scope:'hytale:server',exp:Date.now()/1000+60};assert.equal(await avatar.authorizeSkinEditor(req,response(),uuid),false);
});
process.on('exit',()=>fs.rmSync(temp,{recursive:true,force:true}));
