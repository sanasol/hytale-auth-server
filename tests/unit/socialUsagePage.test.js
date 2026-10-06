const assert = require('node:assert/strict');
const vm = require('node:vm');
const pages = require('../../src/routes/adminPages');
const render = name => { let html; pages[name]({}, {writeHead(){},end(value){html=value;}}); return html; };
const html=render('handleMetricsPage');
assert(!render('handleServersPage').includes('function loadSocialUsage'));
const scripts=[...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(m=>m[1]);
scripts.forEach(s=>new vm.Script(s));
const script=scripts.find(s=>s.includes('async function loadSocialUsage'));
const code=script.slice(script.indexOf('    const socialGroups ='),script.indexOf('    async function loadActivityChart'));
const nodes=new Map();
function node(id) { if(!nodes.has(id))nodes.set(id,{value:'30',textContent:'',children:[],getContext(){return {};},replaceChildren(){this.children=[];},append(x){this.children.push(x);}});return nodes.get(id); }
const charts=[];
let payload={since:'2026-10-06T12:00:00Z',baselineKnown:true,current:{friendEdges:8,parties:2},totals:{friendsAccepted:20},daily:[{date:'2026-10-05'},{date:'2026-10-06',friendsAccepted:3}]};
let fail=false;
const context=vm.createContext({document:{getElementById:node,querySelectorAll:()=>[],createElement:()=>({children:[],append(x){this.children.push(x);}})},Chart:function(ctx,config){Object.assign(this,config);this.update=()=>{};this.resize=()=>{};charts.push(this);},chartConfig:{scales:{x:{},y:{}}},authFetch:async()=>({ok:!fail,json:async()=>payload})});
vm.runInContext(code,context);
(async()=>{
 await vm.runInContext('loadSocialUsage()',context);
 assert.equal(node('socialFriends').textContent,'8');
 assert.deepEqual(Array.from(charts[0].data.datasets[0].data),[null,3]);
 assert.equal(charts.length,14);
 assert(charts.every(chart=>chart.options.scales.x.offset === true));
 const fields=vm.runInContext('socialGroups.flatMap(g=>g.charts.flatMap(c=>c.fields.map(f=>f[0])))',context);
 // Every event field from the backend is presented once, with no extra invented metric.
 const source=require('node:fs').readFileSync(require.resolve('../../src/services/socialMetrics'),'utf8');
 const expected=vm.runInNewContext(source.match(/const FIELDS = (\[[^;]+\]);/)[1]);
 assert.deepEqual([...fields].sort(),[...expected].sort());
 vm.runInContext("showSocialCategory('connections')",context);
 assert.equal(node('social-panel-friends').hidden,true);
 assert.equal(node('social-panel-connections').hidden,false);
 fail=true;await vm.runInContext('loadSocialUsage()',context);
 assert.equal(node('socialFriends').textContent,'—');assert.equal(charts[0].data.labels.length,0);
 assert.match(node('socialStatus').textContent,/unavailable/);
 // A slow response for an older range must not overwrite the latest selection.
 let resolveOld; context.authFetch=()=>new Promise(resolve=>{resolveOld=resolve;});
 const old=vm.runInContext('loadSocialUsage()',context);
 context.authFetch=async()=>({ok:true,json:async()=>({...payload,current:{friendEdges:99,parties:2}})});
 await vm.runInContext('loadSocialUsage()',context);
 resolveOld({ok:true,json:async()=>payload});await old;
 assert.equal(node('socialFriends').textContent,'99');
 console.log('Social metrics: all counters, category switching, unknown history, failures and stale responses passed');
})().catch(e=>{console.error(e);process.exitCode=1;});
