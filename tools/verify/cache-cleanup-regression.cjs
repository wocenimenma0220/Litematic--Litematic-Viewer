const assert=require('node:assert/strict'),fs=require('node:fs/promises'),path=require('node:path');
const {clearRenderingCache,clearInterruptedCache}=require('../../webapp/cache-cleanup');
(async()=>{
 const root=await fs.mkdtemp(path.join(__dirname,'cache-cleanup-test-'));
 try{
  for(const name of ['scenes','previews','resources']){await fs.mkdir(path.join(root,'cache',name,'data'),{recursive:true});await fs.writeFile(path.join(root,'cache',name,'data','payload'),'generated');}
  for(const name of ['settings','skins/local','renders']){await fs.mkdir(path.join(root,name),{recursive:true});await fs.writeFile(path.join(root,name,'keep'),'user data');}
  await fs.mkdir(path.join(root,'renders/.gpu'),{recursive:true});await fs.writeFile(path.join(root,'renders/.gpu/mesh.bin'),'temporary GPU mesh');
  await clearRenderingCache(root);await clearRenderingCache(root);
  for(const name of ['scenes','previews','resources'])assert.equal(await fs.stat(path.join(root,'cache',name)).then(()=>true,()=>false),false);
  for(const name of ['settings','skins/local','renders'])assert.equal(await fs.readFile(path.join(root,name,'keep'),'utf8'),'user data');
  assert.equal(await fs.stat(path.join(root,'renders/.gpu')).then(()=>true,()=>false),false);
  const interruptedRoots={scenes:path.join(root,'cache/scenes'),previews:path.join(root,'cache/previews')};
  for(const [directory,names] of [[interruptedRoots.scenes,['.work-123-render','.work-1234-export','completed-scene']],[interruptedRoots.previews,['preview-123-render','preview-1234-export']]]){
    for(const name of names){await fs.mkdir(path.join(directory,name),{recursive:true});await fs.writeFile(path.join(directory,name,'payload'),'keep or remove');}
  }
  await clearInterruptedCache(root,123,interruptedRoots);await clearInterruptedCache(root,123,interruptedRoots);
  assert.deepEqual((await fs.readdir(interruptedRoots.scenes)).sort(),['.work-1234-export','completed-scene']);
  assert.deepEqual(await fs.readdir(interruptedRoots.previews),['preview-1234-export']);
  await assert.rejects(clearInterruptedCache(root,123,{scenes:__dirname}),/无效/);
  const interruptedLink=path.join(interruptedRoots.scenes,'.work-123-link');await fs.symlink(path.join(root,'renders'),interruptedLink,'junction');
  await assert.rejects(clearInterruptedCache(root,123,interruptedRoots),/无效/);await fs.unlink(interruptedLink);
  await fs.mkdir(path.join(root,'cache','scenes'),{recursive:true});await fs.symlink(path.join(root,'renders'),path.join(root,'cache','scenes','external'),'junction');
  await assert.rejects(clearRenderingCache(root),/链接/);assert.equal(await fs.readFile(path.join(root,'renders','keep'),'utf8'),'user data');
  console.log('PASS: render-cache cleanup, interrupted PID isolation, export/completed caches retained, repeated cleanup, outside paths and junctions refused');
 }finally{await fs.rm(root,{recursive:true,force:true});}
})().catch(error=>{console.error(error);process.exitCode=1;});
