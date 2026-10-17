'use strict';

state.selection=new RenderSelection();
const selectionUi={enabled:$('selectionEnabled'),renderEnabled:$('selectionRenderEnabled'),mode:$('selectionMode'),active:$('selectionActiveCorner'),layer:$('selectionLayer'),slider:$('selectionSlider'),info:$('selectionInfo'),command:$('commandInput'),bar:$('commandBar'),feedback:$('commandFeedback')};
let selectionRequest=[0,0],selectionSession=0,commandResume=false,commandRunning=false;
function selectionWorld(){return state.viewReady&&state.view?{min:state.view.min,max:state.view.max}:null;}
function updateSelectionUi(){
  const selection=state.selection,world=selectionWorld(),available=state.viewReady&&!state.cpuPreview;
  selectionUi.enabled.checked=selection.enabled;selectionUi.renderEnabled.checked=selection.renderEnabled;selectionUi.mode.value=selection.mode;
  selectionUi.enabled.disabled=selectionUi.renderEnabled.disabled=!available;selectionUi.mode.disabled=!available;
  const layer=selection.mode==='layer';$('selectionLayerControls').classList.toggle('hidden',!layer);
  $('selectionCornerControls').classList.toggle('hidden',layer);selectionUi.active.value=selection.active;selectionUi.active.disabled=!available;
  if(world){selectionUi.slider.min=Math.floor(world.min[1]);selectionUi.slider.max=Math.max(Number(selectionUi.slider.min),Math.ceil(world.max[1])-1);selection.layer=Math.max(Number(selectionUi.slider.min),Math.min(Number(selectionUi.slider.max),selection.layer));}
  selectionUi.slider.value=selectionUi.layer.value=selection.layer;
  selectionUi.slider.disabled=selectionUi.layer.disabled=!available;
  const text=selection.corners.map((p,i)=>`角点 ${i+1}${selection.active===i?' ◀':''}：${p?p.join(', '):'未选择'}`).join('　');
  selectionUi.info.textContent=(layer?`当前 Y = ${selection.layer}。`:text+'。')+(selection.renderEnabled?'选择渲染已开启，范围外不透明度 10%。':'选择渲染已关闭，显示完整投影。')+(selection.enabled?' 选点工具已开启。':` 按 ${shortcutHint('selection')} 可启用选点；鼠标操作已恢复。`);
  els.viewport.classList.toggle('selecting',selection.enabled&&!state.fly);
  els.crosshair.classList.toggle('hidden',!available||!state.fly);
}
function selectionChanged(){state.selection.revision++;lastViewKey=null;updateSelectionUi();scheduleSelectionMesh();drawView();}
function toggleSelectionRender(value=!state.selection.renderEnabled){
  if(!state.viewReady||state.cpuPreview){log('选择渲染需要 GPU 预览就绪。','err');return;}
  state.selection.renderEnabled=value;selectionChanged();
}
async function toggleSelection(value=!state.selection.enabled){
  if(!state.viewReady||state.cpuPreview){log('选择渲染工具需要 GPU 预览就绪。','err');return;}
  if(value&&window.projectionEditor?.enabled)await window.projectionEditor.toggle(false);
  state.selection.enabled=value;selectionSession++;dragging=false;flyPress=null;closeInspector();selectionChanged();
}
function resetSelection(){selectionSession++;state.selection.reset();selectionChanged();}
async function selectCorner(index,clientX,clientY,allowCamera=false){
  if(!state.selection.enabled)throw Error('请先开启选择渲染工具');
  if(!state.viewReady||state.cpuPreview)throw Error('请等待 GPU 预览就绪');
  const session=selectionSession,request=++selectionRequest[index];let position;
  if(!state.scenePreparing&&!state.overview){
    const box=els.gl.getBoundingClientRect(),ray=state.view.rayFromScreen(clientX??box.left+box.width/2,clientY??box.top+box.height/2);
    const result=ray?await window.engine.selectBlock(ray):null;
    if(result?.hit&&result.kind!=='entity')position=result.position.map(Math.floor);
  }
  if(!position&&allowCamera&&state.fly)position=state.flyPos.map(Math.floor);
  if(!position)throw Error(state.scenePreparing?'完整材质准备中；可输入 //select 1 X Y Z 设置坐标':'未指向方块；可在命令后输入 X Y Z 坐标');
  if(session!==selectionSession||request!==selectionRequest[index]||!state.selection.enabled)return;
  state.selection.setCorner(index,position);selectionChanged();log(`角点 ${index+1}：${position.join(', ')}`,'ok');
}
async function runSelectionCommand(text){
  const result=state.selection.command(text);
  if(!result.position)await selectCorner(result.index,undefined,undefined,true);
  else {selectionRequest[result.index]++;selectionChanged();log(`角点 ${result.index+1}：${result.position.join(', ')}`,'ok');}
}
function commandFeedback(text=''){
  selectionUi.feedback.textContent=text;selectionUi.feedback.classList.toggle('hidden',!text);
}
function beginCommandInput(){
  commandResume=commandResume||state.fly&&(state.flyLookActive||state.flyLockPending);state.keys.clear();shortcutHeldKeys.clear();pauseFlyLook();
}
function openCommand(){
  setConsoleHidden(false);
  beginCommandInput();
  selectionUi.command.focus();if(!selectionUi.command.value)selectionUi.command.value='//';
  selectionUi.command.setSelectionRange(selectionUi.command.value.length,selectionUi.command.value.length);
}
function closeCommand(){selectionUi.command.blur();state.keys.clear();if(commandResume&&state.fly)resumeFlyLook();commandResume=false;}
selectionUi.command.addEventListener('focus',beginCommandInput);
async function submitCommand(){
  const text=selectionUi.command.value.trim();if(!text||commandRunning)return;
  commandRunning=true;selectionUi.command.readOnly=true;$('commandSubmit').disabled=true;commandFeedback();log('> '+text);
  try{await runSelectionCommand(text);selectionUi.command.value='';}
  catch(error){commandFeedback(error.message);log(error.message,'err');}
  finally{commandRunning=false;selectionUi.command.readOnly=false;$('commandSubmit').disabled=false;if(document.activeElement===selectionUi.command||document.activeElement===$('commandSubmit'))selectionUi.command.focus();}
}
selectionUi.bar.addEventListener('submit',event=>{event.preventDefault();submitCommand();});
selectionUi.enabled.addEventListener('change',()=>toggleSelection(selectionUi.enabled.checked));
selectionUi.renderEnabled.addEventListener('change',()=>toggleSelectionRender(selectionUi.renderEnabled.checked));
selectionUi.mode.addEventListener('change',()=>{state.selection.mode=selectionUi.mode.value;selectionChanged();});
selectionUi.active.addEventListener('change',()=>{state.selection.active=Number(selectionUi.active.value);updateSelectionUi();});
for(const input of [selectionUi.layer,selectionUi.slider])input.addEventListener('input',()=>{const value=Number(input.value);if(Number.isSafeInteger(value)){state.selection.layer=value;selectionChanged();}});
$('selectionReset').addEventListener('click',resetSelection);
$('openCommand').addEventListener('click',openCommand);
selectionUi.command.addEventListener('keydown',event=>{
  event.stopPropagation();
  if(isShortcut('close',event)){event.preventDefault();closeCommand();}
  if(isShortcut('submit',event)){
    event.preventDefault();submitCommand();
  }
});
window.addEventListener('keydown',event=>{
  if(bindingCaptureAction||document.querySelector('dialog[open]')||shortcutInputTarget(event))return;
  if(isShortcut('selection',event)){event.preventDefault();event.stopImmediatePropagation();if(!event.repeat)toggleSelection();return;}
  if(isShortcut('command',event)){event.preventDefault();event.stopImmediatePropagation();if(!event.repeat)openCommand();return;}
  if(state.selection.enabled&&(isShortcut('corner1',event)||isShortcut('corner2',event))){event.preventDefault();event.stopImmediatePropagation();if(!event.repeat)selectCorner(isShortcut('corner1',event)?0:1,undefined,undefined,true).catch(error=>log(error.message,'err'));}
},true);
for(const type of ['pointerdown','mousedown','mouseup'])els.viewport.addEventListener(type,event=>{
  if(!state.selection.enabled||event.target.closest('.block-inspector')||!mouseShortcut('corner1',event)&&!mouseShortcut('corner2',event))return;
  event.preventDefault();event.stopImmediatePropagation();
  if(type!=='pointerdown')return;
  if(state.fly){if(!state.flyLookActive)resumeFlyLook();else els.viewport.focus({preventScroll:true});}
  selectCorner(mouseShortcut('corner1',event)?0:1,state.fly?undefined:event.clientX,state.fly?undefined:event.clientY).catch(error=>log(error.message,'err'));
},true);
els.viewport.addEventListener('wheel',event=>{
  const modifier=heldShortcut('nudge')||shortcutBindings.nudge.includes('Alt')&&event.altKey||shortcutBindings.nudge.includes('Control')&&event.ctrlKey||shortcutBindings.nudge.includes('Shift')&&event.shiftKey;
  if(!state.selection.enabled||!state.viewReady||state.cpuPreview||!modifier||!event.deltaY||event.target.closest('.block-inspector'))return;
  event.preventDefault();event.stopImmediatePropagation();
  try{const camera=state.fly?flyCamera():null,direction=camera?camera.target.map((v,i)=>v-camera.eye[i]):state.view._viewDir();state.selection.nudge(direction,event.deltaY<0?1:-1);selectionChanged();}catch(error){log(error.message,'err');}
},{capture:true,passive:false});
let selectionMeshTimer=0,selectionMeshRevision=0,selectionMeshWanted=null,selectionMeshRunning=false;
function invalidateSelectionMesh(){
  ++selectionMeshRevision;clearTimeout(selectionMeshTimer);selectionMeshTimer=0;selectionMeshWanted=null;
  state.view?.disposeSelection();window.engine.releaseSelection().catch(()=>{});
  $('selectionMeshInfo').textContent='';
}
function scheduleSelectionMesh(){
  const bounds=state.selection.bounds(selectionWorld());
  if(!bounds||!state.viewReady||state.cpuPreview){if(selectionMeshWanted||state.view?.selectionView)invalidateSelectionMesh();return;}
  if(state.scenePreparing||state.rendering||state.overview){$('selectionMeshInfo').textContent='完整模型就绪后计算选区六面。';return;}
  const key=JSON.stringify([gpuBuildGeneration,bounds,els.realLighting.checked,els.lightingEnabled.checked]);
  if(selectionMeshWanted?.key===key)return;
  const revision=++selectionMeshRevision;state.view.disposeSelection();state.view.revision++;
  selectionMeshWanted={key,revision,bounds,view:state.view,generation:gpuBuildGeneration,realLighting:els.realLighting.checked,lightingEnabled:els.lightingEnabled.checked};
  $('selectionMeshInfo').textContent='正在单独计算选区的六个边界面…';clearTimeout(selectionMeshTimer);selectionMeshTimer=setTimeout(prepareSelectionMesh,150);
}
async function prepareSelectionMesh(){
  selectionMeshTimer=0;if(selectionMeshRunning||!selectionMeshWanted)return;
  const job=selectionMeshWanted;selectionMeshRunning=true;let selected=null,bitmap=null;
  const current=()=>job.revision===selectionMeshRevision&&job.view===state.view&&job.generation===gpuBuildGeneration&&state.selection.renderEnabled;
  try{
    const result=await window.engine.exportSelectionScene({selection:job.bounds,realLighting:job.realLighting,lightingEnabled:job.lightingEnabled});
    if(!current())return;
    bitmap=await createImageBitmap(base64ToBlob(result.atlasDataUrl),{premultiplyAlpha:'none',colorSpaceConversion:'none'});if(!current())return;
    selected=new GpuView(els.gl);
    if(result.meshTransfer)await selected.uploadMeshStream((offset,length)=>{
      if(!current())throw Error('选区已更新');return window.engine.readSelectionChunk(result.meshTransfer.id,offset,length);
    },result.meshTransfer.size,undefined,current);
    else selected.uploadMesh(result.meshArrayBuffer);
    selected.uploadAtlas(bitmap);if(!current())return;
    job.view.disposeSelection();job.view.selectionView=selected;job.view.selectionBoundsKey=JSON.stringify(job.bounds);selected=null;job.view.revision++;
    $('selectionMeshInfo').textContent=`选区独立网格：${result.quads.toLocaleString()} 面，六面与光照已独立计算。`;
    lastViewKey=null;drawView();
  }catch(error){if(current()){$('selectionMeshInfo').textContent='选区网格生成失败：'+error.message;log('选区渲染失败：'+error.message,'err');selectionMeshWanted=null;}}
  finally{bitmap?.close();selected?.destroy();selectionMeshRunning=false;if(selectionMeshWanted&&selectionMeshWanted.revision!==job.revision)selectionMeshTimer=setTimeout(prepareSelectionMesh,0);}
}
updateSelectionUi();
