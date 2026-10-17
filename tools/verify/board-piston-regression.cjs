const assert=require('node:assert/strict');
const fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const {BoardWorld,LIMIT}=require('../../webapp/renderer/board-world.js');
const context={window:{}};vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../../webapp/renderer/board-catalog.js'),'utf8'),context);
const catalog=context.window.BoardCatalog;
const offsets={side:{east:[1,0],west:[-1,0],up:[0,-1],down:[0,1],north:[0,0],south:[0,0]},top:{east:[1,0],west:[-1,0],north:[0,-1],south:[0,1],up:[0,0],down:[0,0]}};
const base=(id,facing,extended='true')=>({id,states:{facing,extended}});
for(const view of ['side','top'])for(const id of ['piston','sticky_piston'])for(const [facing,[dx,dy]]of Object.entries(offsets[view])){
  const world=new BoardWorld(catalog,view),cell=base(id,facing),count=dx||dy?2:1;
  assert(world.set(0,0,cell));assert.equal(world.cells.size,count);
  if(count===2){assert.equal(world.get(dx,dy).id,'piston_head');assert.equal(world.get(dx,dy).states.type,id==='sticky_piston'?'sticky':'normal');}
  const snapshot=world.snapshot();world.restore(snapshot);assert.deepEqual(world.snapshot(),snapshot,'save/load keeps both cells');
  const copy=new BoardWorld(catalog,view);copy.restore([['0,0',cell]]);assert.deepEqual(copy.snapshot(),snapshot,'old saves gain missing heads');
  // Grab either the head or the base; move the entire assembly in one operation.
  assert(world.move({x:dx,y:dy},{x:dx+3,y:dy+2}));assert.equal(world.get(3,2).id,id);assert.equal(world.cells.size,count);
  assert(world.set(3,2,base(id,facing,'false')));assert.equal(world.cells.size,1,'retract removes head');
  assert(world.set(3,2,cell));assert(world.set(3+dx,2+dy,null));assert.equal(world.cells.size,0,'erase either half removes assembly');
}
for(const view of ['side','top']){
  const world=new BoardWorld(catalog,view);world.set(0,0,base('piston','east'));
  assert(world.move({x:0,y:0},{x:1,y:0}),'move can overlap its own old head');
  world.set(4,0,{id:'stone'});let before=world.snapshot();
  assert.equal(world.move({x:1,y:0},{x:3,y:0}),false,'occupied head destination blocks entire move');assert.deepEqual(world.snapshot(),before);
  assert.equal(world.set(3,0,base('sticky_piston','east')),false,'head cannot overwrite another block');assert.deepEqual(world.snapshot(),before);
  assert.equal(world.set(LIMIT,0,base('piston','east')),false,'head must stay inside canvas');assert.deepEqual(world.snapshot(),before);
  assert(world.set(1,0,base('sticky_piston','west')));assert.equal(world.get(2,0),undefined,'rotation clears old head');assert.equal(world.get(0,0).states.type,'sticky');
  assert(world.set(0,0,{id:'stone'}));assert.equal(world.get(1,0),undefined,'replacing head clears matching base');
  world.restore([['0,0',base('piston','east')],['1,0',{id:'stone'}],['5,5',{id:'piston_head',states:{facing:'east',type:'sticky'}}]]);
  assert.equal(world.get(0,0).states.extended,'false','conflicting old save retracts without overwriting');assert.equal(world.get(1,0).id,'stone');assert.equal(world.get(5,5),undefined,'orphan heads are discarded');
  assert.equal(world.set(10,0,{id:'piston_head'}),false,'head is not an independent palette block');
  assert.equal(world.set(.5,0,base('piston','east')),false);
}
console.log('PASS: 2D normal/sticky pistons, both views and six facings, paired placement/move/rotation/retraction/removal, collision/bounds, save/load and old-save repair');
