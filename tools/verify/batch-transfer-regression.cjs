'use strict';
const fs=require('node:fs/promises'),path=require('node:path'),assert=require('node:assert/strict'),vm=require('node:vm'),{deflateSync}=require('node:zlib');
const {SceneTransfer,crc32,BATCH_PAGE_LIMIT,BATCH_RAW_LIMIT}=require('../../webapp/scene-transfer');
(async()=>{
 const out=path.join(__dirname,'batch-transfer-output');await fs.mkdir(out,{recursive:true});
 const bytes=[Buffer.alloc(64)],pages=[],raws=[];let offset=64;
 for(let i=0;i<384;i++){
  const raw=Buffer.alloc(80);raw.writeFloatLE(i,0);const wire=deflateSync(raw);raws.push(raw);bytes.push(wire);
  pages.push({offset,bytes:wire.length,rawBytes:80,codec:1,checksum:crc32(raw),quads:1,layer:0});offset+=wire.length;
 }
 const file=path.join(out,'pages');await fs.writeFile(file,Buffer.concat(bytes));const transfer=new SceneTransfer(),scene=await transfer.open(file,17);
 const batch=await transfer.readPages(17,scene.id,pages.slice(0,128));
 for(let i=0;i<batch.length;i++)assert.deepEqual(Buffer.from(batch[i].data),raws[i]);
 await assert.rejects(transfer.readPages(18,scene.id,pages.slice(0,2)));
 await assert.rejects(transfer.readPages(17,scene.id,pages.slice(0,BATCH_PAGE_LIMIT+1)));
 await assert.rejects(transfer.readPages(17,scene.id,[]));
 await assert.rejects(transfer.readPages(17,scene.id,[{...pages[0],rawBytes:BATCH_RAW_LIMIT+1,codec:1}]));
 await assert.rejects(transfer.readPages(17,scene.id,[pages[0],{...pages[1],checksum:0}]));
 const allocated=new Set(),view={revision:0,center:[0,0,0],canvas:{width:640,height:384},fitOrtho:()=>({scale:1}),gl:{
  createBuffer(){const value={};allocated.add(value);return value;},deleteBuffer(value){allocated.delete(value);},bindBuffer(){},bufferData(){},getError(){return 0;},isContextLost(){return false;},NO_ERROR:0,ELEMENT_ARRAY_BUFFER:1,ARRAY_BUFFER:2,STATIC_DRAW:3,DYNAMIC_DRAW:4,
 }};
 const context={performance,setTimeout,QUAD_TRIANGLES:[0,1,2,0,2,3],boxInFrustum:()=>true,inRenderDistance:()=>true};vm.createContext(context);
 vm.runInContext(await fs.readFile(path.join(__dirname,'../../webapp/renderer/lod-stream.js'),'utf8')+'\nglobalThis.Stream=LodStream;',context);
 const sections=pages.map((page,id)=>({id,position:[id,0,0],min:[id*16,0,0],max:[id*16+16,16,16],center:[id*16+8,8,8],levels:[[page],[page],[page],[page]]}));
 const errors=[],stream=new context.Stream(view,{sections,min:[0,0,0],max:[6144,16,16],quads:384,compressed:true,indexBytes:0},()=>{},
  {budgetBytes:32*1048576,onError:e=>errors.push(e),readPage:p=>transfer.readPage(17,scene.id,p),readPages:ps=>transfer.readPages(17,scene.id,ps)});
 stream.plan({orbit:true,lodLevel:0},[],true);await stream.settled();
 assert.equal(stream.residents.size,384);assert.equal(stream.lodCounts[0],384);assert.equal(errors.length,0);
 assert.ok(stream.batchRequests<16,`Too many page round trips: ${stream.batchRequests}`);assert.equal(stream.pageRequests,0);assert.ok(stream.batchPeakBytes<=BATCH_RAW_LIMIT);
 assert.ok(stream.peakBytes<=stream.budgetBytes);assert.equal(stream.batchBytes,0);stream.dispose();assert.equal(allocated.size,0);
 // A CRC error in a prefetched section must not poison the valid section requesting the batch.
 const correct=pages[3].checksum;pages[3].checksum^=1;const isolatedErrors=[];
 const isolated=new context.Stream(view,{sections,min:[0,0,0],max:[6144,16,16],quads:384,compressed:true,indexBytes:0},()=>{},
  {budgetBytes:32*1048576,onError:e=>isolatedErrors.push(e),readPage:p=>transfer.readPage(17,scene.id,p),readPages:ps=>transfer.readPages(17,scene.id,ps)});
 isolated.plan({orbit:true,lodLevel:0},[],true);await isolated.settled();assert.equal(isolated.residents.size,383);assert.ok(isolatedErrors.length>0);
 assert.ok([...isolated.failed].every(key=>key.startsWith('3:')));isolated.dispose();pages[3].checksum=correct;assert.equal(allocated.size,0);
 // Closing a capability during an in-flight batch invalidates all packets before publication.
 const original=transfer.read.bind(transfer);let release;const gate=new Promise(r=>release=r);
 transfer.read=async(...args)=>{await gate;return original(...args);};const pending=transfer.readPages(17,scene.id,pages.slice(0,2));
 await assert.rejects(transfer.readPage(17,scene.id,pages[0]),/队列/);const closing=transfer.close();release();await assert.rejects(pending);await closing;
 console.log('PASS: bounded batches, exact bytes/CRC, ownership, stale close, queue admission; 384 real LOD0 uploads with '+stream.batchRequests+' batch requests and no retained CPU pages');
})().catch(e=>{console.error(e);process.exitCode=1;});
