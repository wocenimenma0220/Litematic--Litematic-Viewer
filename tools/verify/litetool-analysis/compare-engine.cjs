const path=require('node:path'),fs=require('node:fs');
const root=path.resolve(__dirname,'../../..');const {EngineClient}=require(path.join(root,'webapp/engine-client'));
const engine=new EngineClient({javaExe:path.resolve(root,'../../jdk-25.0.4.1+1/bin/java.exe'),classpath:path.join(root,'bin'),home:root,onEvent:()=>{},onExit:()=>{}});
const out=path.join(__dirname,'artifacts');const results=[];
async function timed(fn){const start=performance.now();const result=await fn();return {milliseconds:performance.now()-start,result};}
(async()=>{
 try {
  await engine.start();const resources=await timed(()=>engine.call('loadResources',{}));
  console.log('resourcesMillis='+resources.milliseconds.toFixed(1));
  for(const [label,file,streaming] of [['small','曼波mis_(咸鱼lite_v2+)_v2.4.2.litematic',false],['large','璃月港.litematic',true]]){
   const open=await timed(()=>engine.call('open',{path:path.resolve(root,'../..',file)}));
   console.log(label+' parsed='+open.milliseconds.toFixed(1)+' ms');
   const materials=await timed(()=>engine.call('materials',{}));console.log(label+' materials='+materials.milliseconds.toFixed(1)+' ms');
   const mesh=await timed(()=>engine.call('exportScene',{atlas:path.join(out,'our-'+label+'-atlas.png'),mesh:path.join(out,'our-'+label+'.bin'),streaming,realLighting:true,lightingEnabled:true}));
   const row={label,file,resourcesMillis:resources.milliseconds,openMillis:open.milliseconds,materialsMillis:materials.milliseconds,sceneMillis:mesh.milliseconds,scene:mesh.result,buildingTypes:materials.result.building.length};results.push(row);
   fs.writeFileSync(path.join(out,'our-engine.json'),JSON.stringify({pass:false,inProgress:true,results},null,2));console.log(JSON.stringify({label,milliseconds:mesh.milliseconds,quads:mesh.result.quads,meshBytes:mesh.result.meshBytes}));
  }
  fs.writeFileSync(path.join(out,'our-engine.json'),JSON.stringify({pass:true,results},null,2));
 }catch(error){fs.writeFileSync(path.join(out,'our-engine.json'),JSON.stringify({pass:false,error:error.stack,results},null,2));console.error(error.stack);process.exitCode=1;}
 finally{engine.stop();}
})();setTimeout(()=>{console.error('Benchmark exceeded 5 minutes');engine.stop();},300000).unref();
