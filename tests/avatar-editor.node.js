const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('fs'),vm=require('vm');
function harness(){
 const elements=new Map(),requests=[];
 const element=()=>({classList:{add(){},remove(){}},style:{},addEventListener(){},textContent:'',after(){}});
 const doc={getElementById:id=>{if(!elements.has(id))elements.set(id,element());return elements.get(id);}};
 const raw={bodyCharacteristic:'Goblin.33',underwear:'Boxer.Carmin',face:'Face_Neutral',ears:'Goblin_Ears',mouth:'Mouth_Default',eyes:'Plain_Eyes.Blue',faceAccessory:'Blindfold.Black.Thin'};
 let finish;
 class Viewer {init(){} async loadAvatar(){this.modelData={raw};} _clearCharacter(){} _determineHiddenParts(){} async _buildCharacter(){if(finish)await finish;} }
 const context={console,window:{location:{pathname:"/customizer/test-player",search:""}},URLSearchParams,document:doc,HytaleAvatarViewer:Viewer,localStorage:{getItem:()=> 'test-admin'},setTimeout,clearTimeout,alert:()=>{},fetch:async(url,o)=>{requests.push({url,...o});return {ok:true,json:async()=>({parts:{},raw:JSON.parse(o.body)}),text:async()=>''};}};
 vm.createContext(context);
 let js=fs.readFileSync('assets/customizer.html','utf8').match(/<script>([\s\S]*?)<\/script>/)[1].replace(/\n\s*init\(\);\s*$/,'');
 js+='\nglobalThis.api={initViewer,saveSkin,refreshPreview,getSkin:()=>currentSkin,getColors:()=>currentColors,set:(key,id,color)=>{currentSkin[key]=id;currentColors[key]=color;},clear:(key)=>{delete currentSkin[key];delete currentColors[key];delete currentVariants[key];},enableSave:()=>{canSave=true;},discard:()=>{loadStylesForCategory=()=>{};loadColorsForCategory=()=>{};loadVariantsForCategory=()=>{};scheduleRefresh=()=>{};setupControls();document.getElementById("btn-discard").onclick();}};';
 vm.runInContext(js,context);
 return {api:context.api,requests,hold:p=>{finish=p;}};
}
test('saved editor state keeps item, color, variant separate and removed accessories stay removed',async()=>{
 const h=harness();await h.api.initViewer();
 assert.equal(h.api.getSkin().faceAccessory,'Blindfold');assert.equal(h.api.getColors().faceAccessory,'Black');
 h.api.clear('faceAccessory');h.api.set('eyes','Shiny_Eyes','Red');h.api.enableSave();await h.api.saveSkin();
 const body=JSON.parse(h.requests[0].body);
 assert.equal(body.bodyCharacteristic,'Goblin.33');assert.equal(body.eyes,'Shiny_Eyes.Red');assert.equal(body.eyeColor,'Red');assert.equal(body.faceAccessory,null);
 assert.equal(h.requests[0].headers['X-Admin-Token'],'test-admin');
});
test('rapid selections serialize model builds and rebuild the latest selection once',async()=>{
 const h=harness();await h.api.initViewer();
 let done;h.hold(new Promise(r=>done=r));
 h.api.set('eyes','Plain_Eyes','Blue');const first=h.api.refreshPreview();
 await h.api.refreshPreview();h.api.set('eyes','Shiny_Eyes','Red');await h.api.refreshPreview();
 assert.equal(h.requests.length,1);
 h.hold(null);done();await first;
 await new Promise(r=>setImmediate(r));
 assert.equal(h.requests.length,2);assert.equal(JSON.parse(h.requests[1].body).eyes,'Shiny_Eyes.Red');
});

test('Discard restores parsed identifiers without appending color twice',async()=>{ const h=harness();await h.api.initViewer();h.api.set('eyes','Shiny_Eyes','Red');h.api.discard();h.api.enableSave();await h.api.saveSkin();const b=JSON.parse(h.requests[0].body);assert.equal(b.bodyCharacteristic,'Goblin.33');assert.equal(b.eyes,'Plain_Eyes.Blue');assert.equal(b.faceAccessory,'Blindfold.Black.Thin'); });
