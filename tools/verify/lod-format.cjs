const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),assert=require('node:assert/strict'),zlib=require('node:zlib');
const root=path.resolve(__dirname,'../..'),out=path.join(__dirname,'lod-output'),wire=fs.readFileSync(path.join(out,'dense.lmesh'));
const gpu={};vm.createContext(gpu);vm.runInContext(fs.readFileSync(path.join(root,'webapp/renderer/gpu-view.js'),'utf8'),gpu);
const context={performance,setTimeout,QUAD_TRIANGLES:[0,1,2,0,2,3],boxInFrustum:()=>true,inRenderDistance:gpu.inRenderDistance};vm.createContext(context);
vm.runInContext(fs.readFileSync(path.join(root,'webapp/renderer/lod-stream.js'),'utf8')+'\nglobalThis.Stream=LodStream;',context);
const createView=()=>{const allocated=new Set();return {allocated,revision:0,center:[160,8,8],canvas:{width:640,height:384},fitOrtho:()=>({scale:1}),gl:{createBuffer(){const buffer={};allocated.add(buffer);return buffer;},deleteBuffer(buffer){allocated.delete(buffer);},bindBuffer(){},bufferData(){},getError(){return 0;},isContextLost(){return false;},NO_ERROR:0,ELEMENT_ARRAY_BUFFER:1,ARRAY_BUFFER:2,STATIC_DRAW:3,DYNAMIC_DRAW:4}};};
async function open(file,view=createView(),reads=[]) {return context.Stream.open(view,async(offset,length)=>{reads.push({offset,length});return file.buffer.slice(file.byteOffset+offset,file.byteOffset+offset+length);},file.length,{budgetBytes:32*1024*1024,readPage:async page=>{const encoded=file.subarray(page.offset,page.offset+page.bytes),raw=(page.codec&1)?zlib.inflateSync(encoded):encoded;return {data:raw.buffer.slice(raw.byteOffset,raw.byteOffset+raw.byteLength),storedBytes:encoded.length,decodedBytes:raw.length,decodeMillis:0,peakScratchBytes:encoded.length+raw.length,scratchLimitBytes:80*1024*1024};}});}
(async()=>{
 const reads=[],view=createView(),stream=await open(wire,view,reads);assert.equal(stream.manifest.sections.length,20);assert.equal(reads.reduce((n,r)=>n+r.length,0),64+wire.readInt32BE(24));stream.dispose();assert.equal(view.allocated.size,0);
 const catalog=Number(wire.readBigUInt64BE(16)),first=catalog+4,firstPage=first+554+4;let second=first+554;
 for(let level=0;level<4;level++){const count=wire.readInt32BE(second);second+=4+count*32;}
 const mutations=[
  file=>file.writeInt32BE(9,8),file=>file.writeBigUInt64BE(0n,16),file=>file.writeInt32BE(129*1024*1024,24),
  file=>file.writeBigUInt64BE(file.readBigUInt64BE(32)+1n,32),file=>file.writeBigUInt64BE(BigInt(catalog-1),firstPage),
  file=>file.writeInt32BE(8193,firstPage+12),file=>file.writeInt32BE(9,firstPage+16),file=>file.copy(file,second,first,first+12),
 ];
 for(const mutate of mutations){const bad=Buffer.from(wire);mutate(bad);await assert.rejects(open(bad));}
 await assert.rejects(open(wire.subarray(0,63)));
 const compact=Buffer.alloc(40);compact.writeInt16LE(2,0);compact.writeInt16LE(3,2);compact.writeInt16LE(4,4);compact[6]=1;compact[7]=0|(1<<2)|(2<<4)|(3<<6);
 const expanded=context.Stream.expandCompact(compact,1,[5,-2,7]),decoded=new DataView(expanded.buffer,expanded.byteOffset,expanded.byteLength);
 assert.deepEqual([decoded.getFloat32(0,true),decoded.getFloat32(4,true),decoded.getFloat32(8,true)],[82,-29,116]);const badCompact=Buffer.from(compact);badCompact[6]=3;assert.throws(()=>context.Stream.expandCompact(badCompact,1,[0,0,0]));
 const staleView=createView(),stale=await open(wire,staleView),staleSection=stale.manifest.sections[0],staleTask={section:staleSection,level:0,cost:staleSection.costs[0]};
 let finish;stale.readPage=()=>new Promise(resolve=>{finish=resolve;});stale.wanted.set(staleSection.id,staleTask);const pending=stale.load(staleTask);stale.dispose();finish({data:new ArrayBuffer(staleSection.levels[0][0].rawBytes),storedBytes:0,decodedBytes:0,decodeMillis:0,peakScratchBytes:0,scratchLimitBytes:80*1024*1024});await assert.rejects(pending);assert.equal(staleView.allocated.size,0);
 const stableView=createView(),stable=await open(wire,stableView),camera={orbit:true,rotationDeg:45,slantDeg:30,zoom:1,lodLevel:1};
 stable.plan(camera,null,true);await stable.settled();assert.equal(stable.residents.size,20);assert.equal(stable.fallbacks.size,20);assert.ok(stableView.revision>0);
 const before=stable.stats(),oldResidents=new Map(stable.residents);let release;const readNormally=stable.readPage;
 stable.readPage=async page=>{if(!release)await new Promise(resolve=>{release=resolve;});return readNormally(page);};
 stable.plan({...camera,lodLevel:0},null,true);assert.ok(release,'detail replacement did not start');
 for(const [id,resident] of oldResidents){assert.equal(stable.residents.get(id),resident);for(const page of resident.pages)assert.ok(stableView.allocated.has(page.vbo),'old geometry removed before replacement was ready');}
 stable.readPage=readNormally;release();await stable.settled();assert.equal(stable.residents.size,20);assert.ok(stable.peakBytes<=stable.budgetBytes);
 const completed=stable.stats();for(let i=0;i<12;i++)stable.plan({...camera,lodLevel:0,renderScale:i%2?.25:1},null,true);
 await stable.settled();assert.equal(stable.loads,completed.loads,'unchanged clicks restarted geometry loads');
 await stable.setBudgetBytes(2048*1024*1024);await stable.settled();
 assert.equal(stable.budgetBytes,2048*1024*1024,'custom budgets above 1 GiB were rejected');
 assert.ok([...stable.residents.values()].every(r=>r.level===0),'enlarging the budget failed to load requested detail');
 const expandedBytes=stable.residentBytes;
 await stable.setBudgetBytes(32*1024*1024);await stable.settled();
 assert.ok(stable.residentBytes<expandedBytes,'lowering the budget failed to evict detail');
 assert.ok(stable.peakBytes<=stable.budgetBytes,'resizing exceeded the new budget');
 await stable.setBudgetBytes(16384*1024*1024);await stable.settled();
 let releaseResize;const oldRead=stable.readPage;
 stable.readPage=async page=>{if(!releaseResize)await new Promise(resolve=>{releaseResize=resolve;});return oldRead(page);};
 stable.plan({...camera,lodLevel:1},null,true);assert.ok(releaseResize,'no upload to cancel during budget resize');
 const resizing=stable.setBudgetBytes(32*1024*1024);await new Promise(resolve=>setTimeout(resolve,10));
 assert.ok(stable.resizing,'budget applied before the in-flight upload drained');
 stable.readPage=oldRead;releaseResize();await resizing;await stable.settled();
 assert.ok(stable.peakBytes<=stable.budgetBytes,'cancelled upload exceeded resized budget');
 assert.equal(stable.failed.size,0,'resizing marked a cancelled page as a broken section');
 await stable.setBudgetBytes(2048*1024*1024);
 for(const level of [0,1,2,3]){stable.plan({...camera,lodLevel:level},null,true);await stable.settled();}
 const cachedLoads=stable.loads;
 for(const level of [0,1,2,3,0,1,3,0]){stable.plan({...camera,lodLevel:level},null,true);await stable.settled();}
 assert.equal(stable.loads,cachedLoads,'zoom round trips reread/reuploaded cached geometry');assert.ok(stable.cacheHits>0);
 for(const invalid of [0,19*1024*1024,16384*1024*1024+1,NaN])await assert.rejects(stable.setBudgetBytes(invalid));
 stable.dispose();assert.equal(stableView.allocated.size,0,'overview/detail buffers leaked');
 const backgroundView=createView(),background=await open(wire,backgroundView);await background.setBudgetBytes(2048*1024*1024);background.plan({...camera,lodLevel:3},null,true);await background.settled();
 background.preload('distance',{eye:[160,8,8],viewDistance:1});await background.settled();assert.equal(background.preloadJob.prepared.size,3);assert.equal(background.preloadJob.sections.length,3);assert.equal(background.residents.size,20,'preload replaced the visible preview');
 background.preload('full',{eye:[160,8,8]});await background.settled();assert.equal(background.preloadJob.status,'complete');assert.equal(background.preloadJob.prepared.size,20);
 const preparedLoads=background.loads;background.plan({...camera,lodLevel:0},null,true);await background.settled();assert.equal(background.loads,preparedLoads,'prepared detail was read again');
 await background.setBudgetBytes(32*1024*1024);background.plan({...camera,lodLevel:3},null,true);await background.settled();background.preload('full',{eye:[160,8,8]});await background.settled();assert.equal(background.preloadJob.status,'limited','full preload ignored its cache limit');assert.ok(background.peakBytes<=background.budgetBytes);
 background.dispose();assert.equal(backgroundView.allocated.size,0,'background buffers leaked');
 const cancelView=createView(),cancel=await open(wire,cancelView);cancel.plan({...camera,lodLevel:3},null,true);await cancel.settled();const normal=cancel.readPage,baselineBuffers=cancelView.allocated.size;let finishPreload;
 cancel.readPage=page=>new Promise(resolve=>{finishPreload=async()=>resolve(await normal(page));});cancel.preload('full',{eye:[160,8,8]});assert.ok(finishPreload,'background job did not begin');cancel.stopPreload();await finishPreload();await cancel.settled();assert.equal(cancel.preloadJob.status,'paused');assert.equal(cancelView.allocated.size,baselineBuffers,'paused upload leaked GPU buffers');cancel.dispose();assert.equal(cancelView.allocated.size,0);
 const rangeView=createView(),ranged=await open(wire,rangeView),fly={eye:[160,8,8],target:[160,8,20],culling:false,lodLevel:1};
 ranged.plan(fly,null,true);await ranged.settled();assert.equal(ranged.residents.size,13,'default six-chunk radius was not applied');
 ranged.plan({...fly,viewDistance:1},null);await ranged.settled();assert.equal(ranged.residents.size,3,'view distance change was throttled or retained distant blocks');
 for(const resident of ranged.residents.values())assert.ok(resident.section.position[0]>=9&&resident.section.position[0]<=11);
 ranged.plan({...fly,viewDistance:128},null);await ranged.settled();assert.equal(ranged.residents.size,20,'128 chunks did not load the expanded range');
 ranged.plan({...fly,viewDistance:1,eye:[-.01,100000,-.01]},null,true);await ranged.settled();assert.equal(ranged.residents.size,1,'negative chunk coordinates or vertical range were incorrect');
 ranged.plan({...camera,viewDistance:1},null,true);await ranged.settled();assert.equal(ranged.residents.size,20,'orbit overview was cropped by fly distance');
 ranged.dispose();assert.equal(rangeView.allocated.size,0);
 assert.equal(gpu.inRenderDistance({position:[-7,999,-7]},{eye:[-.01,-999,-.01]}),true);
 assert.equal(gpu.inRenderDistance({position:[-8,0,-7]},{eye:[-.01,0,-.01]}),false);
 const result={pass:true,indexOnlyStartupBytes:reads.reduce((n,r)=>n+r.length,0),wireBytes:wire.length,malformedInputs:mutations.length+2,compactGpuExpansion:true,complexFallback:true,cancelledLoadReleasesBuffers:true,stableReplacements:true,clicksDoNotReload:true,customBudgetUpTo16GiB:true,liveBudgetEviction:true,resizeCancelsUpload:true,defaultSixChunks:true,rangeExpansionAndRelease:true,negativeCoordinates:true,orbitUnaffected:true};
 fs.writeFileSync(path.join(out,'format-result.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result));
})().catch(error=>{console.error(error);process.exitCode=1;});
