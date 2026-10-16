'use strict';
const assert=require('node:assert/strict');
const {RenderSelection}=require('../../webapp/renderer/render-selection');
const {cleanShortcutBindings,normalizedKey}=require('../../webapp/renderer/keyboard-shortcuts');
const {memoryPolicy}=require('../../webapp/performance-policy');
const selection=new RenderSelection(),world={min:[-16,-8,-16],max:[32,32,32]};
assert.throws(()=>selection.command('//select 1 1 2 3'));selection.enabled=true;
selection.command('//select 1 4 -2 7');selection.command('//select 2 -3 8 -5');
assert.equal(selection.bounds(world),null);selection.renderEnabled=true;
assert.deepEqual(selection.bounds(world),{min:[-3,-2,-5],max:[5,9,8]});
for(const text of ['//select 3','//select 1 1 2','//select 1 NaN 2 3','//select 1 999999999999999 2 3','//select 1 1.5 2 3','process.exit()'])assert.throws(()=>selection.command(text));
selection.nudge([0,-1,0],1);assert.equal(selection.corners[1][1],7);
for(let axis=0;axis<3;axis++)for(const sign of [-1,1]){
  const direction=[0,0,0];direction[axis]=sign;selection.active=0;
  const before=selection.corners.map(p=>p.slice());selection.nudge(direction,1);
  assert.equal(selection.corners[0][axis],before[0][axis]+sign);assert.deepEqual(selection.corners[1],before[1]);
  selection.nudge(direction,-1);assert.deepEqual(selection.corners,before);
}
selection.active=1;let before=selection.corners[1].slice();selection.nudge([0,Math.sin(50*Math.PI/180),Math.cos(50*Math.PI/180)],1);assert.equal(selection.corners[1][2],before[2]+1);assert.equal(selection.corners[1][1],before[1]);
before=selection.corners[1].slice();selection.nudge([0,Math.sin(70*Math.PI/180),Math.cos(70*Math.PI/180)],1);assert.equal(selection.corners[1][1],before[1]+1);
assert.throws(()=>selection.nudge([NaN,0,0],1));
selection.mode='layer';selection.layer=-2;selection.nudge([0,0,1],-1);assert.deepEqual(selection.bounds(world),{min:[-16,-3,-16],max:[32,-2,32]});
selection.enabled=false;assert.ok(selection.bounds(world),'turning off the tool must retain the rendered selection');selection.renderEnabled=false;assert.equal(selection.bounds(world),null);selection.reset();assert.deepEqual(selection.corners,[null,null]);
const bindings=cleanShortcutBindings({fly:['KeyO'],forward:['invalid']});assert.deepEqual(bindings.fly,['KeyO']);assert.deepEqual(bindings.forward,['KeyW']);assert.equal(normalizedKey('ControlRight'),'Control');
const gb=1024**3;assert.ok(memoryPolicy(4*gb,2*gb).heapMB<memoryPolicy(32*gb,16*gb).heapMB);assert.ok(memoryPolicy(4*gb,2*gb).geometryBudgetMB<memoryPolicy(32*gb,16*gb).geometryBudgetMB);
console.log('PASS: gated commands, reversed and negative inclusive bounds, active-corner nudge, layer movement, invalid input rejection, keybinding fallback and adaptive memory');
