'use strict';
const fs=require('node:fs/promises'),path=require('node:path'),os=require('node:os'),{randomUUID}=require('node:crypto');
const {EngineClient}=require('./engine-client');

/** One isolated worker at a time; previews keep their own engine and open schematic. */
class BackgroundExports {
  constructor({home,runtime,onUpdate=()=>{},Client=EngineClient}){
    this.home=home;this.runtime=runtime;this.onUpdate=onUpdate;this.Client=Client;this.jobs=[];this.running=null;this.closed=false;
  }
  status(job){return {id:job.id,kind:job.kind,name:path.basename(job.out),path:job.out,state:job.state,stage:job.stage,completed:job.completed||0,total:job.total||0,error:job.error,result:job.result};}
  list(){return this.jobs.map(job=>this.status(job));}
  publish(job){this.onUpdate(this.status(job));}
  async submit({kind,source,params,resources}){
    if(this.closed)throw Error('应用正在退出');
    if(!['png','gif'].includes(kind)||!source||!params?.out||!path.isAbsolute(params.out))throw Error('导出参数无效');
    if(this.jobs.some(job=>!['completed','failed','cancelled'].includes(job.state)&&path.resolve(job.out).toLowerCase()===path.resolve(params.out).toLowerCase()))throw Error('这个文件已有导出任务，请选择其他文件名');
    const out=path.resolve(params.out),directory=await fs.mkdtemp(path.join(path.dirname(out),'.projection-export-'));
    try{
      const input=path.join(directory,'source.litematic');await fs.copyFile(source,input);
      params=structuredClone(params);resources=structuredClone(resources);
      if(params.backgroundImage){const image=path.join(directory,'background'+path.extname(params.backgroundImage));await fs.copyFile(params.backgroundImage,image);params.backgroundImage=image;}
      if(this.closed)throw Error('应用正在退出');
      const job={id:randomUUID(),kind,out,directory,input,params,resources,state:'queued',stage:'queued'};
      this.jobs.push(job);this.publish(job);
      // Keep recent results, together with every pending export.
      const finished=this.jobs.filter(j=>['completed','failed','cancelled'].includes(j.state));
      for(const old of finished.slice(0,-8))this.jobs.splice(this.jobs.indexOf(old),1);
      this.pump();return this.status(job);
    }catch(error){await fs.rm(directory,{recursive:true,force:true});throw error;}
  }
  pump(){
    if(this.running||this.closed)return;
    const job=this.jobs.find(j=>j.state==='queued');if(!job)return;
    this.running=this.run(job).finally(()=>{this.running=null;this.pump();});
  }
  async run(job){
    const alive=()=>{if(job.cancelled||this.closed)throw Error('导出已取消');};
    job.state='running';job.stage='resources';this.publish(job);
    const worker=job.worker=new this.Client({...this.runtime,home:this.home,onEvent:event=>{
      if(event.event==='exportProgress'&&!job.cancelled){job.stage=event.stage;job.completed=event.completed;job.total=event.total;this.publish(job);}
    }});
    try{
      await worker.start();alive();
      try{os.setPriority(worker.proc.pid,os.constants.priority.PRIORITY_BELOW_NORMAL);}catch{/* Optional on restricted hosts. */}
      await worker.call('loadResources',job.resources);alive();
      job.stage='opening';this.publish(job);await worker.call('open',{path:job.input});alive();
      job.stage='building';this.publish(job);
      const output=path.join(job.directory,'result.'+job.kind);
      const result=await worker.call(job.kind==='gif'?'renderAnimation':'render',{...job.params,out:output});alive();
      job.stage='saving';this.publish(job);
      await fs.rename(output,job.out);
      job.result={...result,path:job.out};job.state='completed';job.stage='completed';
    }catch(error){job.state=job.cancelled||this.closed?'cancelled':'failed';job.stage=job.state;if(job.state==='failed')job.error=error.message;}
    finally{
      await worker.stop();job.worker=null;
      try{await fs.rm(job.directory,{recursive:true,force:true});}catch{/* A locked temp directory must not change a successful result. */}
      this.publish(job);
    }
  }
  async cancel(id){
    const job=this.jobs.find(j=>j.id===id);if(!job)throw Error('找不到导出任务');
    if(['completed','failed','cancelled'].includes(job.state))return this.status(job);
    if(job.stage==='saving')return this.status(job);
    job.cancelled=true;
    if(job.state==='queued'){
      job.state=job.stage='cancelled';await fs.rm(job.directory,{recursive:true,force:true});
    }else {job.state=job.stage='cancelling';if(job.worker)await job.worker.stop();}
    this.publish(job);return this.status(job);
  }
  async close(){
    this.closed=true;
    await Promise.all(this.jobs.filter(j=>!['completed','failed','cancelled'].includes(j.state)).map(j=>this.cancel(j.id)));
    if(this.running)await this.running;
  }
}
module.exports={BackgroundExports};
