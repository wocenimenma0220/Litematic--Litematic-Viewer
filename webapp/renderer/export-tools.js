'use strict';
const exportJobs=new Map(),exportRows=new Map();
function updateExportTask(job){
  const previous=exportJobs.get(job.id);exportJobs.set(job.id,job);
  const host=$('exportTasks');host.classList.remove('hidden');
  let row=exportRows.get(job.id);
  if(!row){
    const element=document.createElement('div');element.className='export-task';
    const content=document.createElement('div'),text=document.createElement('p'),progress=document.createElement('progress'),actions=document.createElement('div'),action=document.createElement('button'),dismiss=document.createElement('button');
    progress.max=100;action.type=dismiss.type='button';dismiss.textContent='×';dismiss.title='收起此任务';dismiss.setAttribute('aria-label','收起导出任务');actions.className='export-task-actions';actions.append(action,dismiss);content.append(text,progress);element.append(content,actions);host.prepend(element);
    row={element,text,progress,action,dismiss};exportRows.set(job.id,row);
    dismiss.addEventListener('click',()=>{exportRows.delete(job.id);exportJobs.delete(job.id);element.remove();host.classList.toggle('hidden',!exportRows.size);});
    action.addEventListener('click',async()=>{const current=exportJobs.get(job.id);if(current.state==='completed')window.shellApi.reveal(current.path);else if(['queued','running'].includes(current.state)){action.disabled=true;try{updateExportTask(await window.exportsApi.cancel(job.id));}catch(error){log('取消导出失败：'+error.message,'err');action.disabled=false;}}else{exportRows.delete(job.id);exportJobs.delete(job.id);element.remove();host.classList.toggle('hidden',!exportRows.size);}});
  }
  const labels={queued:'排队中',resources:'准备材质',opening:'读取投影',building:'构建导出模型',rendering:`渲染 ${job.completed} / ${job.total} 帧`,encoding:`编码 ${job.completed} / ${job.total} 帧`,saving:'保存文件',cancelling:'正在取消',cancelled:'已取消',completed:'已完成',failed:'失败'};
  const percent=job.state==='completed'?100:job.stage==='rendering'?10+Math.floor(job.completed/Math.max(1,job.total)*65):job.stage==='encoding'?75+Math.floor(job.completed/Math.max(1,job.total)*24):job.stage==='building'?8:job.stage==='opening'?5:0;
  row.text.textContent=`${job.name} · ${labels[job.stage]||job.stage}${['rendering','encoding'].includes(job.stage)?` · ${percent}%`:''}${job.error?'：'+job.error:''}`;
  row.text.title=job.path;row.text.classList.toggle('err',job.state==='failed');
  row.progress.value=percent;row.progress.classList.toggle('hidden',['completed','failed','cancelled'].includes(job.state));
  row.action.disabled=job.state==='cancelling'||job.stage==='saving';row.action.textContent=job.state==='completed'?'打开位置':['queued','running','cancelling'].includes(job.state)?'取消':'关闭';
  row.dismiss.classList.toggle('hidden',job.state!=='completed');
  if(previous?.state!==job.state&&['completed','failed','cancelled'].includes(job.state)){
    log(job.state==='completed'?`已导出：${job.path}`:job.state==='failed'?`导出失败：${job.error}`:`已取消导出：${job.name}`,job.state==='completed'?'ok':job.state==='failed'?'err':undefined);
  }
  const finished=[...exportRows.keys()].filter(id=>['completed','failed','cancelled'].includes(exportJobs.get(id)?.state));
  for(const id of finished.slice(0,-8)){exportRows.get(id).element.remove();exportRows.delete(id);exportJobs.delete(id);}
}
async function startBackgroundExport(kind,params){
  const job=await window.exportsApi.start(kind,params);updateExportTask(job);log(`已加入后台导出：${job.name}`);return job;
}
window.exportsApi.onUpdate(updateExportTask);
window.exportsApi.list().then(jobs=>jobs.forEach(updateExportTask)).catch(error=>log('读取导出任务失败：'+error.message,'err'));
