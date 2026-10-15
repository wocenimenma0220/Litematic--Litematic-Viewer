'use strict';

const fs = require('node:fs/promises');
const { randomUUID } = require('node:crypto');
const { inflateSync } = require('node:zlib');
const RAW_PAGE_LIMIT=16*1024*1024,TRANSFER_SCRATCH_LIMIT=80*1024*1024;
const CRC_TABLE=Array.from({length:256},(_,i)=>{for(let b=0;b<8;b++)i=i&1?0xedb88320^(i>>>1):i>>>1;return i>>>0;});
function crc32(bytes){let value=0xffffffff;for(const byte of bytes)value=CRC_TABLE[(value^byte)&255]^(value>>>8);return (value^0xffffffff)>>>0;}
const INLINE_LIMIT = 32 * 1024 * 1024;
const CHUNK_LIMIT = 8 * 1024 * 1024;

/** One bounded, read-only capability for the current exported scene; never accepts a path from UI. */
class SceneTransfer {
  constructor() { this.current = null;this.scenes=new Map();this.decoding=false;this.peakScratchBytes=0; }
  async readPage(owner,id,page) {
    if(this.decoding)throw new Error('页面读取队列已满');
    const {offset,bytes,rawBytes,codec,checksum}=page||{};
    if(![offset,bytes,rawBytes,codec,checksum].every(Number.isSafeInteger)||bytes<1||rawBytes<1||rawBytes>RAW_PAGE_LIMIT
      ||!(codec===0&&bytes===rawBytes||codec===1&&bytes<rawBytes)||checksum<0||checksum>0xffffffff)throw new Error('无效的压缩页');
    this.decoding=true;
    try {
      const scratch=bytes+rawBytes*3+1024*1024;if(scratch>TRANSFER_SCRATCH_LIMIT)throw new Error('页面读取超过临时空间预算');
      this.peakScratchBytes=Math.max(this.peakScratchBytes,scratch);const wire=Buffer.allocUnsafeSlow(bytes);
      for(let at=0;at<bytes;at+=CHUNK_LIMIT){const n=Math.min(CHUNK_LIMIT,bytes-at);wire.set(new Uint8Array(await this.read(owner,id,offset+at,n)),at);}
      const started=performance.now(),raw=codec===1?inflateSync(wire,{maxOutputLength:rawBytes}):wire;
      if(raw.length!==rawBytes||crc32(raw)!==checksum)throw new Error('模型页校验失败，请重新渲染');
      if(this.scenes.get(id)?.owner!==owner)throw new Error('模型已更新，请重新加载预览。');
      return {data:raw.buffer.slice(raw.byteOffset,raw.byteOffset+raw.byteLength),storedBytes:bytes,decodedBytes:rawBytes,decodeMillis:performance.now()-started,scratchLimitBytes:TRANSFER_SCRATCH_LIMIT,peakScratchBytes:this.peakScratchBytes,cpuCacheBytes:0};
    } finally {this.decoding=false;}
  }
  async close() {
    const previous = [...this.scenes.values()],pending=this.closing;
    this.current = null;this.scenes.clear();
    this.closing=(async()=>{await pending;while(this.decoding)await new Promise(resolve=>setTimeout(resolve,2));for(const scene of previous)await scene.file.close();})();
    await this.closing;
  }
  async open(path, owner, options={}) {
    if(!options.retain)await this.close();else if(this.closing)await this.closing;
    if(this.scenes.size>=2)throw new Error('预览文件数量超限');
    const file = await fs.open(path, 'r');
    try {
      const actual = (await file.stat()).size,size=options.growing?options.committedBytes:actual;
      if(!Number.isSafeInteger(size)||size<0||size>actual)throw new Error('无效的已发布模型长度');
      const id = randomUUID();
      this.current = { id, file, size, owner, growing:options.growing===true,key:options.key };
      this.scenes.set(id,this.current);
      return { id, size, chunkBytes: CHUNK_LIMIT };
    } catch (error) { await file.close(); throw error; }
  }
  commit(owner,key,size){
    const scene=[...this.scenes.values()].find(scene=>scene.owner===owner&&scene.key===key&&scene.growing);
    if(scene){if(!Number.isSafeInteger(size)||size<scene.size)throw new Error('无效的区块发布长度');scene.size=size;}
  }
  async read(owner, id, offset, length) {
    const scene = this.scenes.get(id);
    if (!scene || scene.owner !== owner) throw new Error('模型已更新，请重新加载预览。');
    if (!Number.isSafeInteger(offset) || !Number.isSafeInteger(length) || offset < 0 || length < 1
      || length > CHUNK_LIMIT || offset + length > scene.size) throw new Error('无效的模型分块请求。');
    const bytes = Buffer.allocUnsafeSlow(length);
    let done = 0;
    while (done < length) {
      const result = await scene.file.read(bytes, done, length - done, offset + done);
      if (!result.bytesRead) throw new Error('模型文件不完整，请重新渲染。');
      done += result.bytesRead;
    }
    if (this.scenes.get(id) !== scene) throw new Error('模型已更新，请重新加载预览。');
    return bytes.buffer;
  }
}
module.exports = { SceneTransfer, INLINE_LIMIT, CHUNK_LIMIT,crc32,TRANSFER_SCRATCH_LIMIT };
