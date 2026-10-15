const assert=require('node:assert/strict'),fs=require('node:fs/promises'),path=require('node:path');
const {clearRenderingCache}=require('../../webapp/cache-cleanup');
(async()=>{
 const root=await fs.mkdtemp(path.join(__dirname,'cache-cleanup-test-'));
 try{
  for(const name of ['scenes','previews']){await fs.mkdir(path.join(root,'cache',name,'data'),{recursive:true});await fs.writeFile(path.join(root,'cache',name,'data','payload'),'generated');}
  for(const name of ['settings','skins/local','renders']){await fs.mkdir(path.join(root,name),{recursive:true});await fs.writeFile(path.join(root,name,'keep'),'user data');}
  await fs.mkdir(path.join(root,'renders/.gpu'),{recursive:true});await fs.writeFile(path.join(root,'renders/.gpu/mesh.bin'),'temporary GPU mesh');
  await clearRenderingCache(root);await clearRenderingCache(root);
  for(const name of ['scenes','previews'])assert.equal(await fs.stat(path.join(root,'cache',name)).then(()=>true,()=>false),false);
  for(const name of ['settings','skins/local','renders'])assert.equal(await fs.readFile(path.join(root,name,'keep'),'utf8'),'user data');
  assert.equal(await fs.stat(path.join(root,'renders/.gpu')).then(()=>true,()=>false),false);
  await fs.mkdir(path.join(root,'cache','scenes'),{recursive:true});await fs.symlink(path.join(root,'renders'),path.join(root,'cache','scenes','external'),'junction');
  await assert.rejects(clearRenderingCache(root),/链接/);assert.equal(await fs.readFile(path.join(root,'renders','keep'),'utf8'),'user data');
  console.log('PASS: complete render-cache cleanup, repeat exit, user settings/skins/exports retained, junction refused');
 }finally{await fs.rm(root,{recursive:true,force:true});}
})().catch(error=>{console.error(error);process.exitCode=1;});
