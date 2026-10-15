'use strict';

/** Random-access LMESH5 reader and a budgeted geometry working set. No full-scene vertex arrays. */
class LodStream {
  static async open(view,read,size,options={}) {
    const bytes=async(offset,length)=>{
      if(!Number.isSafeInteger(offset)||!Number.isSafeInteger(length)||offset<0||length<0||offset+length>size)throw Error('无效的分块范围');
      const result=new Uint8Array(length);
      for(let at=0;at<length;at+=8*1024*1024){const count=Math.min(8*1024*1024,length-at),part=await read(offset+at,count);if(part.byteLength!==count)throw Error('模型分块不完整');result.set(new Uint8Array(part),at);}
      return result;
    };
    const header=new DataView((await bytes(0,64)).buffer);
    const format=String.fromCharCode(...new Uint8Array(header.buffer,0,6)),compressed=format==='LMESH6';
    if(!['LMESH5','LMESH6'].includes(format)||header.getInt32(8)!==1||header.getInt32(28)!==4)throw Error('无法识别 LOD 模型格式');
    if(compressed&&typeof options.readPage!=='function')throw Error('缺少压缩页读取接口');
    const count=header.getInt32(12),offset=Number(header.getBigUint64(16)),length=header.getInt32(24),quads=Number(header.getBigUint64(32));
    if(count<0||count>200000||length<4||length>128*1024*1024||offset<64||offset+length!==size||!Number.isSafeInteger(quads))throw Error('LOD 索引无效');
    const min=Array.from({length:3},(_,i)=>header.getFloat32(40+i*4)),max=Array.from({length:3},(_,i)=>header.getFloat32(52+i*4));
    if(min.some((v,i)=>!Number.isFinite(v)||!Number.isFinite(max[i])||v>max[i]))throw Error('LOD 边界无效');
    const catalog=await bytes(offset,length),data=new DataView(catalog.buffer);let at=4,total=0;
    const need=n=>{if(at+n>length)throw Error('LOD 索引不完整');};
    if(data.getInt32(0)!==count||length<4+count*570)throw Error('LOD 区块数无效');
    const sections=[],positions=new Set(),descriptors=new Map();
    for(let id=0;id<count;id++) {
      need(554);const position=[data.getInt32(at),data.getInt32(at+4),data.getInt32(at+8)];at+=12;
      const key=position.join(',');if(positions.has(key))throw Error('LOD 区块重复');positions.add(key);
      const lo=Array.from({length:3},(_,i)=>data.getFloat32(at+i*4)),hi=Array.from({length:3},(_,i)=>data.getFloat32(at+12+i*4));at+=24;
      if(lo.some((v,i)=>!Number.isFinite(v)||!Number.isFinite(hi[i])||v>hi[i]))throw Error('LOD 区块边界无效');
      const exits=catalog.subarray(at,at+6),solid=catalog.subarray(at+6,at+518);at+=518;
      const levels=[];
      for(let level=0;level<4;level++) {
        need(4);const n=data.getInt32(at);at+=4;if(n<0||n>100000)throw Error('LOD 页数无效');need(n*(compressed?32:20));const pages=[];
        for(let p=0;p<n;p++) {
          const page={offset:Number(data.getBigUint64(at)),bytes:data.getInt32(at+8),quads:data.getInt32(at+12),layer:data.getInt32(at+16)};at+=20;
          page.rawBytes=compressed?data.getInt32(at):page.bytes;page.codec=compressed?data.getInt32(at+4):0;page.checksum=compressed?data.getUint32(at+8):0;if(compressed)at+=12;
          if(!Number.isSafeInteger(page.offset)||page.offset<64||page.bytes<1||page.offset+page.bytes>offset||![0,1,4,5,6].includes(page.layer)
            ||page.rawBytes<1||page.rawBytes>16*1024*1024||!(page.codec===0&&page.bytes===page.rawBytes||page.codec===1&&page.bytes<page.rawBytes)
            ||page.quads<0||page.quads>8192||(page.layer!==6&&page.rawBytes!==page.quads*80)||(page.layer===6&&(page.quads!==0||page.rawBytes<4||(page.rawBytes-4)%48)))throw Error('LOD 几何页无效');
          const previous=descriptors.get(page.offset);if(previous&&['bytes','quads','layer','rawBytes','codec','checksum'].some(k=>previous[k]!==page[k]))throw Error('LOD 页描述冲突');
          descriptors.set(page.offset,page);pages.push(previous||page);
        }
        levels.push(pages);
      }
      total+=levels[0].reduce((n,p)=>n+p.quads,0);
      const section={id,position,min:lo,max:hi,exits,solid,levels,center:lo.map((v,i)=>(v+hi[i])/2)};
      section.costs=LodStream.costs(levels);
      sections.push(section);
    }
    if(at!==length||total!==quads)throw Error('LOD 总面数不正确');
    return new LodStream(view,{sections,min,max,quads,indexBytes:length,compressed},bytes,options);
  }
  constructor(view,manifest,read,options) {
    this.view=view;this.gl=view.gl;this.manifest=manifest;this.read=read;
    this.readPage=options.readPage;this.decodedBytes=0;this.decodeMillis=0;this.transferPeakBytes=0;this.transferLimitBytes=80*1024*1024;
    this.budgetBytes=options.budgetBytes??256*1024*1024;
    this.checkBudget(this.budgetBytes);
    this.scratchBytes=16*1024*1024+2*8192*80;this.residentBytes=0;this.loadingBytes=0;this.peakBytes=0;this.bytesRead=0;
    this.residents=new Map();this.wanted=new Map();this.failed=new Set();this.disposed=false;this.lastPlan=-Infinity;this.loads=0;this.evictions=0;this.lodChanges=0;
    this.fallbacks=new Map();this.cache=new Map();this.cacheHits=0;this.preloadSequence=0;this.queue=[];this.queueAt=0;this.lodCounts=[0,0,0,0];
    this.positions=new Map(manifest.sections.map(section=>[section.position.join(','),section]));this.detailCount=0;
    this.hasOverview=options.overview===true||options.coarseOverview===true;
    for(const section of manifest.sections){section.fullReady=options.overview!==true;for(let level=0;level<4;level++)for(const page of section.levels[level])page.proxy=options.overview===true||options.coarseOverview===true&&level===3;}
    this.updateCapacity();
    this.overviewBytes=manifest.sections.reduce((n,s)=>n+s.costs[3],0);
    this.keepOverview=this.overviewBytes<=(this.capacity-this.swapReserve)*.6;
    this.tree=this.makeTree(manifest.sections.slice());
    this.sharedIndex=this.gl.createBuffer();this.gl.bindBuffer(this.gl.ELEMENT_ARRAY_BUFFER,this.sharedIndex);
    const indices=new Uint16Array(8192*6);for(let q=0;q<8192;q++)for(let k=0;k<6;k++)indices[q*6+k]=q*4+QUAD_TRIANGLES[k];
    this.gl.bufferData(this.gl.ELEMENT_ARRAY_BUFFER,indices,this.gl.STATIC_DRAW);
    this.onChange=options.onChange||(()=>{});this.onError=options.onError||(()=>{});
  }
  static costs(levels){return levels.map(pages=>pages.length?pages.reduce((sum,p)=>sum+(p.layer===6?Math.ceil(p.rawBytes*1.05):p.quads*(p.layer===4?224:80))+1024,4096):0);}
  beginProgressive(source,total){
    if(this.disposed||this.progressive)throw Error('渐进预览状态无效');if(!Number.isSafeInteger(total)||total<0||total>200000)throw Error('渐进区块数量无效');
    this.progressive=true;this.progressComplete=false;this.totalExpected=total;this.detailSource=source;
    this.view.sectionGraph=null;this.view.reachableSections=null;this.planKey=null;this.lastPlan=-Infinity;
  }
  appendProgressive(updates,committed){
    if(this.disposed||!this.progressive)return;if(!Array.isArray(updates)||updates.length>256||!Number.isSafeInteger(committed)||committed<64)throw Error('渐进区块发布无效');
    if(!updates.length)return;
    for(const update of updates){
      const {position,min,max}=update;
      if(![position,min,max].every(v=>Array.isArray(v)&&v.length===3)||position.some(v=>!Number.isSafeInteger(v)||Math.abs(v)>134217727)||min.some((v,i)=>!Number.isFinite(v)||!Number.isFinite(max[i])||v>max[i]))throw Error('渐进区块边界无效');
      if(!Array.isArray(update.levels)||update.levels.length!==4)throw Error('渐进档位无效');
      const levels=update.levels.map((pages,level)=>{
        if(!Array.isArray(pages)||pages.length>100000)throw Error('渐进页数无效');
        return pages.map(source=>{
          const page={...source,source:this.detailSource,proxy:level===3};
          if(!['offset','bytes','quads','layer','rawBytes','codec','checksum'].every(k=>Number.isSafeInteger(page[k]))||page.offset<64||page.bytes<1||page.offset+page.bytes>committed||![0,1,4,5,6].includes(page.layer)||page.quads<0||page.quads>8192||page.rawBytes<1||page.rawBytes>16*1024*1024||!(page.codec===0&&page.bytes===page.rawBytes||page.codec===1&&page.bytes<page.rawBytes)||page.checksum<0||page.checksum>0xffffffff||(page.layer===6?(page.quads!==0||page.rawBytes<4||(page.rawBytes-4)%48):page.rawBytes!==page.quads*80))throw Error('渐进模型页无效');
          return page;
        });
      });
      const decode=(encoded,length)=>{if(typeof encoded!=='string'||encoded.length>1024)throw Error('渐进可见性数据无效');const text=atob(encoded);if(text.length!==length)throw Error('渐进可见性长度无效');return Uint8Array.from(text,c=>c.charCodeAt(0));};
      const exits=decode(update.exits,6),solid=decode(update.solid,512),key=position.join(',');let section=this.positions.get(key);
      if(section?.fullReady)throw Error('渐进区块重复发布');
      if(!section){if(this.manifest.sections.length>=200000)throw Error('渐进区块数量超限');section={id:this.manifest.sections.length,position:position.slice(),min:min.slice(),max:max.slice(),levels};this.manifest.sections.push(section);this.positions.set(key,section);this.tree=this.insertTree(this.tree,section);}
      else {
        // Already uploaded proxies keep their descriptors and buffers until a detail upload succeeds.
        if(section.levels[3].length)levels[3]=section.levels[3];section.levels=levels;
        section.min=section.min.map((v,i)=>Math.min(v,min[i]));section.max=section.max.map((v,i)=>Math.max(v,max[i]));
      }
      section.fullReady=true;section.exits=exits;section.solid=solid;section.center=section.min.map((v,i)=>(v+section.max[i])/2);section.costs=LodStream.costs(section.levels);this.detailCount++;
      this.swapReserve=Math.min(this.capacity/4,Math.max(this.swapReserve,...section.costs.filter(cost=>cost<this.capacity/2)));
    }
    this.planKey=null;this.lastPlan=-Infinity;this.changed();
    if(this.lastCamera)this.plan(this.lastCamera,this.lastPlanes,true);this.onChange(this.stats());
  }
  finishProgressive(result){
    if(!this.progressive||this.detailCount!==this.totalExpected)throw Error('渐进模型尚未完整发布');
    this.progressComplete=true;this.manifest.quads=result.quads;this.view.quadCount=result.quads;
    this.view.sectionGraph=makeSectionGraph(this.manifest.sections,this.view.min,this.view.max,[{chunks:this.manifest.sections}]);
    this.planKey=null;this.lastPlan=-Infinity;this.changed();this.onChange(this.stats());
  }
  checkBudget(bytes) {
    if(!Number.isSafeInteger(bytes)||bytes<20*1024*1024||bytes>16384*1024*1024)throw Error('无效的几何缓存预算');
  }
  updateCapacity() {
    this.capacity=this.budgetBytes-this.scratchBytes-8192*12;
    const largest=this.manifest.sections.reduce((n,s)=>Math.max(n,...s.costs.filter(cost=>cost<this.capacity/2)),0);
    this.swapReserve=Math.min(this.capacity/4,Math.max(8192*80,largest));
  }
  async setBudgetBytes(bytes) {
    this.checkBudget(bytes);
    // Drain an obsolete upload before applying the smaller limit: it can still own GL buffers.
    const change=(this.budgetUpdate||Promise.resolve()).then(async()=>{
      if(this.disposed||this.budgetBytes===bytes)return;
      this.resizing=true;this.queue=[];this.queueAt=0;this.stopPreload();
      try {
        while(this.running&&!this.disposed)await new Promise(resolve=>setTimeout(resolve,5));
        if(this.disposed)return;
        this.budgetBytes=bytes;this.updateCapacity();
        this.trimCache(0);
        const victims=[...this.residents.values()].sort((a,b)=>(this.wanted.get(b.id)?.priority??Infinity)-(this.wanted.get(a.id)?.priority??Infinity)||b.bytes-a.bytes);
        for(const victim of victims)if(this.residentBytes>this.capacity&&this.fallbacks.has(victim.id))this.downgrade(victim.id,false);
        for(const [id,fallback] of this.fallbacks)if(this.residentBytes>this.capacity){this.drop(fallback);this.fallbacks.delete(id);}
        for(const victim of victims)if(this.residentBytes>this.capacity)this.remove(victim.id);
        // Peak statistics refer to the current configured budget, starting after the resize.
        this.peakBytes=this.residentBytes+this.scratchBytes+8192*12;
        this.failed.clear();this.planKey=null;this.lastPlan=-Infinity;
      } finally {this.resizing=false;}
      if(this.lastCamera)this.plan(this.lastCamera,this.lastPlanes,true);
      this.changed();this.onChange(this.stats());
    });
    this.budgetUpdate=change.catch(()=>{});return change;
  }
  makeTree(sections) {
    if(sections.length<=1)return {sections};
    const span=[0,1,2].map(axis=>{let lo=Infinity,hi=-Infinity;for(const s of sections){lo=Math.min(lo,s.position[axis]);hi=Math.max(hi,s.position[axis]);}return hi-lo;});
    const axis=span.indexOf(Math.max(...span));sections.sort((a,b)=>a.position[axis]-b.position[axis]||a.id-b.id);
    let middle=sections.length>>1;while(middle<sections.length&&sections[middle].position[axis]===sections[middle-1].position[axis])middle++;
    if(middle===sections.length){middle=sections.length>>1;while(middle>0&&sections[middle].position[axis]===sections[middle-1].position[axis])middle--;}
    if(!middle)return {sections};
    return {axis,plane:sections[middle].position[axis]*16,left:this.makeTree(sections.slice(0,middle)),right:this.makeTree(sections.slice(middle))};
  }
  insertTree(node,section){
    if(node.sections)return this.makeTree(node.sections.concat(section));
    const side=section.position[node.axis]*16<node.plane?'left':'right';node[side]=this.insertTree(node[side],section);return node;
  }
  visible(section,planes,cam) {
    if(!inRenderDistance(section,cam))return false;
    if(cam.culling===false)return true;
    if(!boxInFrustum(section,planes))return false;
    return !this.view.reachableSections||!section.sectionIds||section.sectionIds.some(id=>this.view.reachableSections[id]);
  }
  plan(cam,planes,force=false) {
    if(this.disposed||this.resizing)return;
    this.lastCamera=cam;this.lastPlanes=planes;
    const width=this.view.lodViewportWidth||this.view.canvas.width,height=this.view.lodViewportHeight||this.view.canvas.height;
    const distance=cam.orbit?null:(cam.viewDistance??6);
    const key=JSON.stringify([cam.orbit,cam.eye,cam.target,cam.up,cam.rotationDeg,cam.slantDeg,cam.zoom,cam.fovDeg,cam.culling,cam.occlusion,cam.lodLevel,cam.lodEnabled,distance,width,height]);
    const now=performance.now();if(!force&&(key===this.planKey||distance===this.lastViewDistance&&now-this.lastPlan<100))return;this.lastPlan=now;this.planKey=key;this.lastViewDistance=distance;
    const view=this.view,small=!this.progressive&&this.manifest.quads*80<8*1024*1024,eye=cam.eye||view.center;
    const candidates=[];
    const orbitPixels=cam.orbit?16*view.fitOrtho(cam.rotationDeg||0,cam.slantDeg||0,width,height,cam.zoom||1).scale:0;
    const range=this.manifest.sections.filter(section=>inRenderDistance(section,cam));
    this.overviewBytes=range.reduce((n,s)=>n+s.costs[3],0);this.rangeSections=range.length;
    this.keepOverview=(this.hasOverview||this.progressive||cam.lodEnabled!==false)&&this.overviewBytes<=(this.capacity-this.swapReserve)*.6;
    if(!this.keepOverview&&this.fallbacks.size){for(const fallback of this.fallbacks.values())this.drop(fallback);this.fallbacks.clear();this.changed();}
    for(const section of range)if(this.visible(section,planes,cam)) {
      const distance=Math.hypot(...section.center.map((v,i)=>v-eye[i]));
      let pixels;
      if(cam.orbit)pixels=orbitPixels;
      else pixels=16*height/(2*Math.tan((cam.fovDeg||70)*Math.PI/360)*Math.max(1,distance-14));
      let level=cam.lodEnabled===false?0:Number.isInteger(cam.lodLevel)?Math.max(0,Math.min(3,cam.lodLevel)):small?0:pixels>80?0:pixels>32?1:pixels>12?2:3;
      if(section.fullReady===false)level=3;
      const old=this.wanted.get(section.id);if(cam.lodEnabled!==false&&!small&&!Number.isInteger(cam.lodLevel)&&old&&Math.abs(level-old.level)===1){const threshold=[80,32,12][Math.min(level,old.level)];if(pixels>threshold*.8&&pixels<threshold*1.2)level=old.level;}
      candidates.push({section,desired:level,level:3,priority:distance,cost:section.costs[3]});
    }
    candidates.sort((a,b)=>a.priority-b.priority||a.section.id-b.section.id);
    const capacity=this.capacity-this.swapReserve,targets=new Map();let used=0;
    if(this.keepOverview) {
      for(const task of candidates){targets.set(task.section.id,task);used+=task.cost;}
      for(const section of range)if(!targets.has(section.id)){const task={section,desired:3,level:3,priority:Infinity,cost:section.costs[3]};targets.set(section.id,task);used+=task.cost;}
    }
    else for(const task of candidates){if(cam.lodEnabled===false&&task.section.fullReady!==false){task.level=task.desired=0;task.cost=task.section.costs[0];}if(used+task.cost<=capacity&&!this.failed.has(task.section.id+':'+task.level)){targets.set(task.section.id,task);used+=task.cost;}}
    for(const task of candidates)if((this.hasOverview||this.progressive||cam.lodEnabled!==false)&&targets.has(task.section.id))for(let level=task.desired;level<3;level++) {
      if(cam.lodEnabled===false&&level>0)break;
      const cost=task.section.costs[level],extra=this.keepOverview?cost:cost-task.cost;
      if(used+extra<=capacity&&!this.failed.has(task.section.id+':'+level)){used+=extra;task.cost=cost;task.level=level;break;}
    }
    this.budgetLimited=candidates.filter(t=>!targets.has(t.section.id)||cam.lodEnabled===false&&t.section.fullReady!==false&&targets.get(t.section.id).level!==0).length;this.wanted=targets;this.visibleSections=candidates.length;
    for(const [id,resident] of this.residents) {
      if(!targets.has(id))this.remove(id,true);
      else if(targets.get(id).level===3&&resident.level!==3&&this.fallbacks.has(id))this.downgrade(id);
    }
    const overview=this.keepOverview?[...targets.values()].filter(t=>!this.hasBase(t.section.id)).map(t=>({...t,level:3,cost:t.section.costs[3],base:true})):[];
    this.queue=overview.concat([...targets.values()].filter(t=>this.residents.get(t.section.id)?.level!==t.level));this.queueAt=0;
    this.pump();
  }
  changed(){this.view.revision++;}
  hasBase(id){return this.residents.get(id)?.level===3||this.fallbacks.has(id);}
  available(id,level){return this.residents.get(id)?.level===level||this.fallbacks.get(id)?.level===level||this.cache.has(id+':'+level);}
  prepared(id){return [0,1,2,3].every(level=>this.available(id,level));}
  remember(resident){const key=resident.id+':'+resident.level,old=this.cache.get(key);if(old&&old!==resident)this.drop(old);this.cache.delete(key);this.cache.set(key,resident);}
  trimCache(extra){for(const [key,resident] of this.cache){if(this.residentBytes+extra<=this.capacity)break;this.cache.delete(key);this.drop(resident);}}
  preload(scope,camera) {
    this.stopPreload();if(this.disposed)return;
    const eye=camera?.eye||this.view.center,cam={...camera,orbit:false,eye};
    const sections=this.manifest.sections.filter(s=>scope==='full'||inRenderDistance(s,cam)).sort((a,b)=>Math.hypot(...a.center.map((v,i)=>v-eye[i]))-Math.hypot(...b.center.map((v,i)=>v-eye[i])));
    this.preloadJob={id:++this.preloadSequence,scope,status:'running',sections,ids:new Set(sections.map(s=>s.id)),at:0,prepared:new Set(sections.filter(s=>this.prepared(s.id)).map(s=>s.id))};
    this.pump();this.onChange(this.stats());
  }
  stopPreload(){if(this.preloadJob?.status==='running'){this.preloadJob.status='paused';this.onChange(this.stats());}}
  nextPreload(){
    const job=this.preloadJob;if(!job||job.status!=='running')return null;
    while(job.at<job.sections.length*4){const section=job.sections[Math.floor(job.at/4)],level=job.at++%4;if(this.available(section.id,level)){if(this.prepared(section.id))job.prepared.add(section.id);continue;}
      const task={section,level,cost:section.costs[level],preloadId:job.id};
      // Background preparation never evicts a visible section or cycles through an oversized world.
      if(this.residentBytes+task.cost>this.capacity){job.at--;job.status='limited';return null;}return task;
    }
    job.status=job.prepared.size===job.sections.length?'complete':'limited';return null;
  }
  drop(resident){this.destroyUninstalled(resident);this.residentBytes-=resident.bytes;this.evictions++;if(this.preloadJob){this.preloadJob.prepared.delete(resident.id);if(this.preloadJob.status==='complete'&&this.preloadJob.prepared.size<this.preloadJob.sections.length)this.preloadJob.status='limited';}}
  remove(id,remember=false) {
    const resident=this.residents.get(id);if(!resident)return;
    if(remember)this.remember(resident);else this.drop(resident);this.lodCounts[resident.level]--;this.residents.delete(id);
    if(this.fallbacks.has(id)){const base=this.fallbacks.get(id);if(remember)this.remember(base);else this.drop(base);this.fallbacks.delete(id);}this.changed();
  }
  downgrade(id,remember=true){const resident=this.residents.get(id),base=this.fallbacks.get(id);if(remember)this.remember(resident);else this.drop(resident);this.fallbacks.delete(id);this.residents.set(id,base);this.lodCounts[resident.level]--;this.lodCounts[3]++;this.lodChanges++;this.changed();}
  valid(task){return !this.disposed&&!this.resizing&&(task.preloadId?this.preloadJob?.id===task.preloadId&&this.preloadJob.status==='running':task.base?this.keepOverview&&this.wanted.has(task.section.id):this.wanted.get(task.section.id)?.level===task.level);}
  ready(task){return task.preloadId?this.available(task.section.id,task.level):task.base?this.hasBase(task.section.id):this.residents.get(task.section.id)?.level===task.level;}
  install(task,resident,cached=false) {
    const id=task.section.id,old=this.residents.get(id);if(!cached)this.residentBytes+=resident.bytes;
    if(task.preloadId){this.remember(resident);}
    else if(task.base&&old&&old.level!==3){this.fallbacks.set(id,resident);}
    else {
      if(old){this.lodCounts[old.level]--;if(old.level===3&&this.keepOverview)this.fallbacks.set(id,old);else this.remember(old);if(old.level!==resident.level)this.lodChanges++;}
      this.residents.set(id,resident);this.lodCounts[resident.level]++;
    }
    if(this.preloadJob?.ids.has(id)&&this.prepared(id))this.preloadJob.prepared.add(id);
    if(cached)this.cacheHits++;else this.loads++;this.changed();
  }
  async pump() {
    if(this.running||this.disposed)return;this.running=true;
    let sliceStart=performance.now();
    const yieldToUi=async()=>{if(!this.disposed&&performance.now()-sliceStart>=8){this.onChange(this.stats());await new Promise(resolve=>setTimeout(resolve,0));sliceStart=performance.now();}};
    try {
      for(;;) {
        const task=this.queueAt<this.queue.length?this.queue[this.queueAt++]:this.nextPreload();if(!task||this.disposed)break;
        if(!this.valid(task)||this.ready(task)||this.failed.has(task.section.id+':'+task.level))continue;
        const key=task.section.id+':'+task.level,cached=this.cache.get(key);
        if(cached){this.cache.delete(key);this.install(task,cached,true);await yieldToUi();continue;}
        const limit=this.capacity;
        if(!task.preloadId)this.trimCache(task.cost);
        if(this.residentBytes+task.cost>limit) {
          const victims=[...this.residents.values()].filter(r=>r.id!==task.section.id&&(!this.keepOverview||r.level!==3)).sort((a,b)=>(this.wanted.get(b.id)?.priority||0)-(this.wanted.get(a.id)?.priority||0));
          for(const victim of victims){
            if(this.fallbacks.has(victim.id)){
              this.downgrade(victim.id,false);const wanted=this.wanted.get(victim.id);
              if(wanted)this.wanted.set(victim.id,{...wanted,level:3,cost:victim.section.costs[3]});
            }else this.remove(victim.id);
            if(this.residentBytes+task.cost<=limit)break;
          }
        }
        if(this.residentBytes+task.cost>limit){const old=this.residents.get(task.section.id);if(old)this.wanted.set(task.section.id,{...task,level:old.level,cost:old.bytes});this.budgetLimited++;continue;}
        this.loadingBytes=task.cost;this.peakBytes=Math.max(this.peakBytes,this.residentBytes+this.loadingBytes+this.scratchBytes+8192*12);
        let resident;
        try {
          resident=await this.load(task);
          if(!this.valid(task)){this.destroyUninstalled(resident);continue;}
          this.install(task,resident);
          if(this.residentBytes+this.scratchBytes+8192*12>this.budgetBytes)throw Error('LOD 几何缓存超出预算');
        }catch(error){
          if(resident&&this.residents.get(task.section.id)!==resident&&this.fallbacks.get(task.section.id)!==resident&&this.cache.get(task.section.id+':'+task.level)!==resident)this.destroyUninstalled(resident);
          if(this.valid(task)){this.failed.add(task.section.id+':'+task.level);if(task.preloadId)this.preloadJob.status='error';this.onError(error);this.lastPlan=-Infinity;this.planKey=null;}
        }finally{this.loadingBytes=0;}
        await yieldToUi();
      }
    }finally{this.running=false;if(!this.disposed)this.onChange(this.stats());}
  }
  destroyUninstalled(resident){if(resident)for(const page of resident.pages){if(page.vbo)this.gl.deleteBuffer(page.vbo);if(page.ibo)this.gl.deleteBuffer(page.ibo);}}
  async load(task) {
    const gl=this.gl,pages=[],resident={id:task.section.id,section:task.section,level:task.level,pages,bytes:task.cost,blendCount:0};
    try{
      for(const descriptor of task.section.levels[task.level]) {
        if(!this.valid(task))throw Error('LOD 请求已更新');
        let wire;
        if(this.manifest.compressed){const packet=await this.readPage(descriptor);wire=new Uint8Array(packet.data);if(wire.byteLength!==descriptor.rawBytes)throw Error('解压页长度错误');this.bytesRead+=packet.storedBytes;this.decodedBytes+=packet.decodedBytes;this.decodeMillis+=packet.decodeMillis;this.transferPeakBytes=packet.peakScratchBytes;this.transferLimitBytes=packet.scratchLimitBytes;}
        else {wire=await this.read(descriptor.offset,descriptor.bytes);this.bytesRead+=wire.byteLength;this.decodedBytes+=wire.byteLength;}
        if(!this.valid(task))throw Error('LOD 请求已更新');
        if(descriptor.layer===6) {
          const bsp=new DataView(wire.buffer,wire.byteOffset,wire.byteLength),count=bsp.getInt32(0);
          if(count<0||4+count*48!==wire.byteLength)throw Error('LOD 透明排序数据无效');
          resident.bsp=bsp;continue;
        }
        const positions=new DataView(wire.buffer,wire.byteOffset,wire.byteLength);
        for(let vertex=0;vertex<descriptor.quads*4;vertex++)for(let axis=0;axis<3;axis++)if(!Number.isFinite(positions.getFloat32(vertex*20+axis*4,true)))throw Error('LOD 顶点坐标无效');
        const page={...descriptor};pages.push(page);page.vbo=gl.createBuffer();
        gl.bindBuffer(gl.ARRAY_BUFFER,page.vbo);gl.bufferData(gl.ARRAY_BUFFER,wire,gl.STATIC_DRAW);
        if(page.layer===4) {
          page.data=wire;page.start=resident.blendCount;resident.blendCount+=page.quads;
          page.indices=new Uint16Array(page.quads*6);page.ibo=gl.createBuffer();
          gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER,page.ibo);gl.bufferData(gl.ELEMENT_ARRAY_BUFFER,page.indices.byteLength,gl.DYNAMIC_DRAW);
        }
        const error=gl.getError();if(error!==gl.NO_ERROR||gl.isContextLost())throw Error('分块上传失败（WebGL '+error+'）');
      }
      const count=resident.blendCount;resident.order=new Uint32Array(count);resident.centers=new Float32Array(count*3);
      resident.runPages=new Uint16Array(count);resident.runStarts=new Uint32Array(count);resident.runCounts=new Uint32Array(count);
      for(const page of pages)if(page.layer===4) {
        const data=new DataView(page.data.buffer,page.data.byteOffset,page.data.byteLength);
        for(let q=0;q<page.quads;q++)for(let axis=0;axis<3;axis++) {
          let sum=0;for(let corner=0;corner<4;corner++)sum+=data.getFloat32(q*80+corner*20+axis*4,true);
          resident.centers[(page.start+q)*3+axis]=sum*.25;
        }
      }
      if(resident.bsp) {
        const bsp=resident.bsp,n=bsp.getInt32(0),parents=new Uint8Array(n);resident.sides=new Uint8Array(n).fill(2);let faces=0,edges=0;
        for(let i=0;i<n;i++){
          const at=4+i*48,first=bsp.getInt32(at+32),length=bsp.getInt32(at+36),front=bsp.getInt32(at+40),back=bsp.getInt32(at+44);
          if(first<0||length<0||first+length>count||front>=n||back>=n||front< -1||back< -1||(front>=0&&front<=i)||(back>=0&&back<=i))throw Error('LOD 透明排序节点无效');
          for(const child of [front,back])if(child>=0){if(parents[child]++)throw Error('LOD 透明排序重复子节点');edges++;}
          for(let axis=0;axis<4;axis++)if(!Number.isFinite(bsp.getFloat64(at+axis*8)))throw Error('LOD 透明排序平面无效');faces+=length;
        }
        if(n&&edges!==n-1)throw Error('LOD 透明排序不连通');
        if(faces!==count)throw Error('LOD 透明面数不匹配');
      }
      return resident;
    }catch(error){this.destroyUninstalled(resident);throw error;}
  }
  bind(page) {
    if(this.view.uni.colorOnly!=null)this.gl.uniform1f(this.view.uni.colorOnly,page.proxy?1:0);
    const gl=this.gl,a=this.view.attr;gl.bindBuffer(gl.ARRAY_BUFFER,page.vbo);
    gl.enableVertexAttribArray(a.pos);gl.vertexAttribPointer(a.pos,3,gl.FLOAT,false,20,0);
    gl.enableVertexAttribArray(a.uv);gl.vertexAttribPointer(a.uv,2,gl.UNSIGNED_SHORT,true,20,12);
    gl.enableVertexAttribArray(a.color);gl.vertexAttribPointer(a.color,3,gl.UNSIGNED_BYTE,true,20,16);
  }
  orderBlend(resident,dir,eye) {
    const probe=eye||dir;if(resident.probe&&probe.every((v,i)=>v===resident.probe[i])&&resident.perspective===!!eye)return;
    resident.probe=probe.slice();resident.perspective=!!eye;const order=resident.order,bsp=resident.bsp;let at=0;
    if(bsp&&resident.sides){
      let changed=false,fallback=false;
      for(let i=0;i<resident.sides.length;i++) {
        const p=4+i*48,nx=bsp.getFloat64(p),ny=bsp.getFloat64(p+8),nz=bsp.getFloat64(p+16);
        const side=eye?nx*eye[0]+ny*eye[1]+nz*eye[2]-bsp.getFloat64(p+24):-(nx*dir[0]+ny*dir[1]+nz*dir[2]);
        const bit=side>=0?1:0;changed||=resident.sides[i]!==bit;resident.sides[i]=bit;fallback||=nx===0&&ny===0&&nz===0;
      }
      if(!changed&&!fallback)return;
    }
    const leaf=(first,count,sort)=>{
      if(sort){const faces=Array.from({length:count},(_,i)=>first+i),c=resident.centers;faces.sort((a,b)=>dir[0]*(c[b*3]-c[a*3])+dir[1]*(c[b*3+1]-c[a*3+1])+dir[2]*(c[b*3+2]-c[a*3+2])||a-b);for(const face of faces)order[at++]=face;}
      else for(let i=first;i<first+count;i++)order[at++]=i;
    };
    if(bsp&&bsp.getInt32(0)) {
      const stack=[0];let visits=0;
      while(stack.length) {
        const code=stack.pop(),index=code<0?~code:code,offset=4+index*48;
        if(++visits>bsp.getInt32(0)*3)throw Error('LOD 透明排序含循环');
        const first=bsp.getInt32(offset+32),count=bsp.getInt32(offset+36),nx=bsp.getFloat64(offset),ny=bsp.getFloat64(offset+8),nz=bsp.getFloat64(offset+16);
        if(code<0){leaf(first,count,nx===0&&ny===0&&nz===0);continue;}
        const side=eye?nx*eye[0]+ny*eye[1]+nz*eye[2]-bsp.getFloat64(offset+24):-(nx*dir[0]+ny*dir[1]+nz*dir[2]);
        const front=bsp.getInt32(offset+40),back=bsp.getInt32(offset+44),near=side>=0?front:back,far=side>=0?back:front;
        if(near>=0)stack.push(near);stack.push(~index);if(far>=0)stack.push(far);
      }
    }else leaf(0,resident.blendCount,true);
    if(at!==resident.blendCount)throw Error('LOD 透明排序丢失几何');
    const pages=resident.blendPages=resident.pages.filter(p=>p.layer===4);for(const page of pages)page.used=0;
    let runs=0,lastPage=-1;
    for(const face of order) {
      let lo=0,hi=pages.length-1;while(lo<hi){const mid=(lo+hi+1)>>1;if(pages[mid].start<=face)lo=mid;else hi=mid-1;}
      const page=pages[lo],local=face-page.start;
      if(lo!==lastPage){resident.runPages[runs]=lo;resident.runStarts[runs]=page.used;resident.runCounts[runs]=0;runs++;lastPage=lo;}
      for(let k=0;k<6;k++)page.indices[page.used*6+k]=local*4+QUAD_TRIANGLES[k];page.used++;resident.runCounts[runs-1]++;
    }
    resident.runLength=runs;
    for(const page of pages){this.gl.bindBuffer(this.gl.ELEMENT_ARRAY_BUFFER,page.ibo);this.gl.bufferSubData(this.gl.ELEMENT_ARRAY_BUFFER,0,page.indices);}
  }
  draw(cam,planes) {
    this.plan(cam,planes);const gl=this.gl,view=this.view,visible=new Set();
    for(const resident of this.residents.values())if(this.visible(resident.section,planes,cam))visible.add(resident.id);
    let quads=0,calls=0;
    for(const layer of [0,1,5]) {
      gl.disable(gl.BLEND);gl.depthMask(true);gl.colorMask(layer!==5,layer!==5,layer!==5,layer!==5);gl.uniform1f(view.uni.alphaCutoff,layer===1?.1:0);
      for(const id of visible)for(const page of this.residents.get(id).pages)if(page.layer===layer) {
        this.bind(page);gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER,this.sharedIndex);gl.drawElements(gl.TRIANGLES,page.quads*6,gl.UNSIGNED_SHORT,0);quads+=page.quads;calls++;
      }
    }
    gl.enable(gl.BLEND);gl.depthMask(false);gl.colorMask(true,true,true,true);gl.uniform1f(view.uni.alphaCutoff,0);
    const dir=view._viewDir(),eye=cam.orbit?null:cam.eye;
    const visit=node=>{
      if(node.sections){for(const section of node.sections)if(visible.has(section.id)) {
        const resident=this.residents.get(section.id);if(!resident.blendCount)continue;
        this.orderBlend(resident,dir,eye);
        for(let i=0;i<resident.runLength;i++){const page=resident.blendPages[resident.runPages[i]],count=resident.runCounts[i];this.bind(page);gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER,page.ibo);gl.drawElements(gl.TRIANGLES,count*6,gl.UNSIGNED_SHORT,resident.runStarts[i]*12);quads+=count;calls++;}
      }return;}
      const high=eye?eye[node.axis]>=node.plane:-dir[node.axis]>=0;
      visit(high?node.left:node.right);visit(high?node.right:node.left);
    };
    visit(this.tree);gl.depthMask(true);gl.colorMask(true,true,true,true);
    view.drawStats={totalQuads:this.manifest.quads,visibleQuads:quads,visibleChunks:visible.size,totalChunks:this.manifest.sections.length,drawCalls:calls,...this.stats()};
  }
  stats(){const job=this.preloadJob;return {residentBytes:this.residentBytes,loadingBytes:this.loadingBytes,budgetBytes:this.budgetBytes,peakBytes:this.peakBytes,residentSections:this.residents.size,requestedSections:this.wanted.size,overviewResident:this.keepOverview,loads:this.loads,cacheHits:this.cacheHits,cachedLevels:this.cache.size,evictions:this.evictions,lodChanges:this.lodChanges,bytesRead:this.bytesRead,decodedBytes:this.decodedBytes,decodeMillis:this.decodeMillis,transferPeakBytes:this.transferPeakBytes,transferLimitBytes:this.transferLimitBytes,cpuPageCacheBytes:0,indexBytes:this.manifest.indexBytes,budgetLimited:this.budgetLimited||0,lodCounts:this.lodCounts.slice(),running:this.running===true,progressive:this.progressive===true,progressComplete:this.progressComplete===true,publishedSections:this.detailCount,totalExpected:this.totalExpected,detailResidents:this.lodCounts[0]+this.lodCounts[1]+this.lodCounts[2],preload:job?{scope:job.scope,status:job.status,prepared:job.prepared.size,total:job.sections.length}:null};}
  async settled(timeout=60000) {
    const end=performance.now()+timeout;
    while(this.running||[...this.wanted.values()].some(t=>!this.failed.has(t.section.id+':'+t.level)&&this.residents.get(t.section.id)?.level!==t.level)) {
      if(this.disposed)return;if(performance.now()>end)throw Error('LOD 载入超时');await new Promise(r=>setTimeout(r,20));
    }
  }
  dispose(){this.disposed=true;this.wanted.clear();for(const id of this.residents.keys())this.remove(id);for(const resident of this.cache.values())this.drop(resident);this.cache.clear();this.gl.deleteBuffer(this.sharedIndex);}
}
