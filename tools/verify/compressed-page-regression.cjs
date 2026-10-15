const fs=require('node:fs/promises'),path=require('node:path'),assert=require('node:assert/strict'),zlib=require('node:zlib');
const {SceneTransfer,crc32,TRANSFER_SCRATCH_LIMIT}=require('../../webapp/scene-transfer');
(async()=>{
 const out=path.join(__dirname,'cache-output/pages');await fs.mkdir(out,{recursive:true});const file=path.join(out,'page.bin');
 const raw=Buffer.alloc(8192*80);for(let i=0;i<raw.length;i++)raw[i]=i%71;const compressed=zlib.deflateSync(raw,{level:1});await fs.writeFile(file,compressed);
 const transfer=new SceneTransfer(),cap=await transfer.open(file,12),page={offset:0,bytes:compressed.length,rawBytes:raw.length,codec:1,checksum:crc32(raw)};
 const decoded=await transfer.readPage(12,cap.id,page);assert.deepEqual(Buffer.from(decoded.data),raw);assert.ok(decoded.peakScratchBytes<=TRANSFER_SCRATCH_LIMIT);assert.equal(decoded.cpuCacheBytes,0);
 for(const bad of [{...page,checksum:page.checksum^1},{...page,rawBytes:raw.length-1},{...page,rawBytes:16*1024*1024+1},{...page,codec:9},{...page,bytes:compressed.length+1}])await assert.rejects(transfer.readPage(12,cap.id,bad));
 await assert.rejects(transfer.readPage(11,cap.id,page));await assert.rejects(transfer.readPage(12,'stale',page));
 const changed=Buffer.from(compressed);changed[changed.length>>1]^=64;await fs.writeFile(file,changed);await assert.rejects(transfer.readPage(12,cap.id,page));await transfer.close();
 await fs.writeFile(file,raw);const next=await transfer.open(file,12),plain={offset:0,bytes:raw.length,rawBytes:raw.length,codec:0,checksum:crc32(raw)};assert.deepEqual(Buffer.from((await transfer.readPage(12,next.id,plain)).data),raw);await transfer.close();
 console.log('PASS: exact compressed/raw page roundtrip, CRC, truncated/oversized/wrong-codec/changed-page rejection, owner and stale capability protection, bounded decode and no CPU page cache');
})().catch(e=>{console.error(e);process.exitCode=1;});
