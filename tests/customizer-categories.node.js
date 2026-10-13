const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('fs'),vm=require('vm'),path=require('path');
const html=fs.readFileSync(path.join(__dirname,'../assets/customizer.html'),'utf8');
const script=html.match(/<script>([\s\S]*?)<\/script>/)[1];
const context={};vm.createContext(context);
vm.runInContext(script.slice(0,script.indexOf('// Current state'))+'\nthis.categories=CATEGORIES;',context);

test('UI categories match native grouping, with every skin slot appearing once',()=>{
 const categories=context.categories;
 assert.deepEqual(Object.keys(categories),['head','general','torso','legs','capes']);
 assert.deepEqual(Object.keys(categories.head.subcategories),['haircut','eyebrows','eyes','facialHair','headAccessory','faceAccessory','earAccessory']);
 assert.deepEqual(Object.keys(categories.general.subcategories),['underwear','bodyCharacteristic','face','mouth','ears']);
 assert.deepEqual(Object.keys(categories.torso.subcategories),['undertop','overtop','gloves']);
 const slots=Object.values(categories).flatMap(category=>Object.keys(category.subcategories));
 assert.equal(slots.length,19);assert.equal(new Set(slots).size,19);
});

test('every normal and selected category icon is a real packaged PNG',()=>{
 for(const category of Object.values(context.categories)) for(const node of [category,...Object.values(category.subcategories)]){
  const name=node.icon.match(/data-icon="([^"]+)"/)[1];
  for(const state of ['','Selected']){
   const png=fs.readFileSync(path.join(__dirname,'../assets/category-icons',name+state+'@2x.png'));
   assert.equal(png.subarray(0,8).toString('hex'),'89504e470d0a1a0a');
   assert(png.readUInt32BE(16)>0&&png.readUInt32BE(20)>0);
  }
 }
});
