const fs=require('node:fs/promises'),path=require('node:path'),vm=require('node:vm'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'../..'),out=path.join(__dirname,'lod-output/view-distance-settings');
const context={module:{exports:{}},require:name=>name==='electron'?{nativeImage:{}}:require(name),Buffer};vm.createContext(context);
(async()=>{
 vm.runInContext(await fs.readFile(path.join(root,'webapp/ui-settings.js'),'utf8'),context);const {UiSettings}=context.module.exports;
 await fs.mkdir(path.join(out,'settings'),{recursive:true});const file=path.join(out,'settings/ui.json');
 for(const value of [null,0,129,2.5,'6']){await fs.writeFile(file,JSON.stringify({viewDistance:value}));assert.equal((await new UiSettings(out).settings()).viewDistance,6);}
 for(const value of [1,6,128]){const settings=new UiSettings(out);await settings.save({viewDistance:value});assert.equal((await new UiSettings(out).settings()).viewDistance,value);}
 for(const value of [null,0,31,16385,256.5,'256']){await fs.writeFile(file,JSON.stringify({geometryBudgetMB:value}));assert.equal((await new UiSettings(out).settings()).geometryBudgetMB,256);}
 for(const value of [32,256,2048,16384]){const settings=new UiSettings(out);await settings.save({geometryBudgetMB:value});assert.equal((await new UiSettings(out).settings()).geometryBudgetMB,value);}
 for(const mode of ['distance','full','streaming']){const settings=new UiSettings(out);await settings.save({preloadMode:mode});assert.equal((await new UiSettings(out).settings()).preloadMode,mode);}
 const gpu={};vm.createContext(gpu);vm.runInContext(await fs.readFile(path.join(root,'webapp/renderer/gpu-view.js'),'utf8')+'\nglobalThis.View=GpuView;',gpu);
 const layer={quads:3,chunks:[0,1,2].map(x=>({position:[x,0,0],min:[x*16,0,0],max:[x*16+16,16,16],start:x,faces:new Uint32Array([x])}))};
 const view={reachableSections:null};gpu.View.prototype._updateVisibility.call(view,layer,null,false,{eye:[0,8,0],viewDistance:1});assert.equal(layer.visibleCount,2,'complete GPU preview ignored view distance');
 gpu.View.prototype._updateVisibility.call(view,layer,null,false,{orbit:true,viewDistance:1});assert.equal(layer.visibleCount,3,'complete orbit preview was clipped');
 console.log('PASS: default/invalid settings, persisted 1–128 chunks and 32–16384 MB, complete GPU preview distance');
})().catch(error=>{console.error(error);process.exitCode=1;});
