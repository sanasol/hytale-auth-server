const test = require('node:test');
const assert = require('node:assert/strict');
global.THREE = require('three');
const Avatar = require('../assets/avatar');
function viewer() { const v = Object.create(Avatar.prototype); v.character = new THREE.Group(); v.originalTransforms = new Map(); return v; }
test('animated shape offset rotates around the authored pivot while rest center is preserved', () => {
 const v=viewer(),n=new THREE.Group(); n.name='Head'; v.character.add(n);
 v._applyTransform(n,{name:'Head',position:{x:3,y:4,z:0},shape:{offset:{x:10,y:0,z:0}}});
 assert.ok(n.position.distanceTo(new THREE.Vector3(.13,.04,0))<1e-8);
 v._applyAnimation({duration:60,nodeAnimations:{Head:{orientation:[{time:0,delta:{x:0,y:0,z:Math.SQRT1_2,w:Math.SQRT1_2}}]}}},0);
 assert.ok(n.position.distanceTo(new THREE.Vector3(.03,.14,0))<1e-8);
 v._resetToOriginalPose(); assert.ok(n.position.distanceTo(new THREE.Vector3(.13,.04,0))<1e-8);
});
test('holdLast poses clamp rather than wrapping to the first frame',()=>{
 const v=viewer(),n=new THREE.Group();n.name='Head';v.character.add(n);
 const a={duration:60,holdLastKeyframe:true,nodeAnimations:{Head:{position:[{time:0,delta:{x:0,y:0,z:0}},{time:60,delta:{x:20,y:0,z:0}}]}}};
 v._applyAnimation(a,60);assert.equal(n.position.x,.2);v._applyAnimation(a,120);assert.equal(n.position.x,.2);
 a.holdLastKeyframe=false;v._applyAnimation(a,60);assert.equal(n.position.x,0);
});
test('animation reaches authored cosmetic node names and resets every matching node',()=>{
 const v=viewer();for(let i=0;i<2;i++){const n=new THREE.Group();v._applyTransform(n,{name:'Cape1',position:{y:i}});n.name='Cape1_cosmetic';v.character.add(n);}
 v._applyAnimation({duration:60,nodeAnimations:{Cape1:{position:[{time:0,delta:{x:5,y:0,z:0}}]}}},0);
 for(const n of v.character.children)assert.equal(n.position.x,.05);
 v._resetToOriginalPose();for(const n of v.character.children)assert.equal(n.position.x,0);
});
test('tops and shoes preserve base surfaces beneath partial garments',()=>{
 const v=viewer();v.hiddenBodyParts=new Set();v.polygonOffsetParts=new Set();v._determineHiddenParts({parts:{undertop:{},shoes:{}}});
 for(const name of ['Chest','Belly','L-Foot','R-Foot']){assert.equal(v.hiddenBodyParts.has(name),false);assert.equal(v.polygonOffsetParts.has(name),true);}
});
test('quaternion interpolation follows native shortest-path normalized lerp',()=>{
 const v=viewer(),q=v._interpolateQuaternion([{time:0,delta:{x:0,y:0,z:0,w:1}},{time:4,delta:{x:0,y:0,z:Math.sqrt(3)/2,w:.5}}],1,4,true);
 const expected=new THREE.Quaternion(0,0,Math.sqrt(3)/8,.875).normalize();assert.ok(q.angleTo(expected)<1e-7);
 const neg=v._interpolateQuaternion([{time:0,delta:{x:0,y:0,z:0,w:1}},{time:4,delta:{x:0,y:0,z:-Math.sqrt(3)/2,w:-.5}}],1,4,true);assert.ok(neg.angleTo(expected)<1e-7);
});
test('smooth tracks use the recovered weighted rational cubic rather than smoothstep',()=>{
 const v=viewer();assert.equal(v._easeAnimationTime(0),0);assert.equal(v._easeAnimationTime(1),1);assert.ok(Math.abs(v._easeAnimationTime(.5)-.5708333333333333)<1e-12);
 const pos=v._interpolatePosition([{time:0,interpolationType:'smooth',delta:{x:0,y:0,z:0}},{time:2,delta:{x:100,y:0,z:0}}],1,2,true);
 assert.ok(Math.abs(pos.x-.5708333333333333)<1e-12);
});
