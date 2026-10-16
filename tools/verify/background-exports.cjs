'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs/promises'),path=require('node:path');
const {BackgroundExports}=require('../../webapp/background-exports');
const wait=ms=>new Promise(r=>setTimeout(r,ms));
async function until(fn){const end=Date.now()+5000;while(!fn()){assert.ok(Date.now()<end,'background job timeout');await wait(5);}}
const clients=[];
class FakeClient{
 constructor(options){this.options=options;this.calls=[];clients.push(this);}
 async start(){}
 async call(method,params){this.calls.push({method,params});if(method==='open')this.input=await fs.readFile(params.path,'utf8');if(method==='render'||method==='renderAnimation')return new Promise((resolve,reject)=>{this.reject=reject;this.finish=async()=>{await fs.writeFile(params.out,'completed:'+this.input);resolve({path:params.out,width:params.width});};});return {};}
 async stop(){this.reject?.(Error('worker stopped'));}
}
(async()=>{
 const root=path.resolve(__dirname,'selection-output/background-unit');await fs.mkdir(root,{recursive:true});
 const source=path.join(root,'source.litematic');await fs.writeFile(source,'original');
 const updates=[],manager=new BackgroundExports({home:root,runtime:{},Client:FakeClient,onUpdate:job=>updates.push(job)});
 const submit=(name='result',kind='gif')=>manager.submit({kind,source,params:{out:path.join(root,name+'.'+kind),width:96,selection:{min:[1,2,3],max:[4,5,6]}},resources:{imports:['pack.zip']}});
 const first=await submit();await until(()=>clients[0]?.finish);await fs.writeFile(source,'changed');
 const second=await submit('second','png');assert.equal(second.state,'queued');assert.equal(clients.length,1,'queue launched multiple export workers');
 assert.equal(clients[0].input,'original','source file was not snapshotted');
 clients[0].options.onEvent({event:'exportProgress',stage:'rendering',completed:2,total:4});assert.equal(manager.list()[0].completed,2);
 await assert.rejects(submit(),'duplicate target was accepted');
 await clients[0].finish();await until(()=>manager.list()[0].state==='completed'&&clients[1]?.finish);
 assert.equal(await fs.readFile(first.path,'utf8'),'completed:original');assert.equal(clients[1].input,'changed');
 await clients[1].finish();await until(()=>manager.list()[1].state==='completed');
 const target=path.join(root,'cancel.gif');await fs.writeFile(target,'keep existing');const cancelled=await submit('cancel');await until(()=>clients[2]?.finish);
 await manager.cancel(cancelled.id);await until(()=>manager.list()[2].state==='cancelled');assert.equal(await fs.readFile(target,'utf8'),'keep existing','cancel damaged the existing target');
 const failed=await submit('failed');await until(()=>clients[3]?.finish);clients[3].reject(Error('disk full'));await until(()=>manager.list().find(j=>j.id===failed.id).state==='failed');
 const queued=await submit('shutdown');await until(()=>clients[4]?.finish);const pending=await submit('pending');await manager.cancel(pending.id);await manager.close();
 assert.equal(manager.list().find(j=>j.id===queued.id).state,'cancelled');
 assert.equal((await fs.readdir(root)).filter(n=>n.startsWith('.projection-export-')).length,0,'temporary snapshot or frame files leaked');
 assert.ok(updates.some(j=>j.stage==='rendering'));console.log('PASS: isolated export queue, snapshots, progress, duplicate protection, cancellation, existing file safety, failures, shutdown and temp cleanup');
})().catch(error=>{console.error(error);process.exitCode=1;});
