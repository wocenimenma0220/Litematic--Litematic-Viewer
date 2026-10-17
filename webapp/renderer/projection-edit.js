
'use strict';
(() => {
  const el=id=>document.getElementById(id),tools=el('projectionTools'),palette=el('projectionPalette'),info=el('projectionStatus');
  const catalog=window.BoardCatalog,defs=new Map(catalog.map(b=>['minecraft:'+b.id,b])),atlas=new Image();atlas.src='board-atlas.png';
  const stick=new ProjectionEditModel.DebugStick(window.DebugStickProperties),audio=new BoardAudio(window.BoardSounds,{onError:()=>{}});
  const inventory=new EditorInventoryModel.EditorInventory(catalog.map(block=>block.id));
  const inventoryDragType='application/x-litematic-block';let inventoryDrag=null;
  let flyHotbarKey=null;
  let hoverTimer=0,hoverPoint=null,hoverRunning=false,hoverKey=null;
  let selected=null,category='building_blocks',tool='block',busy=false,session=0,originalFile=null,undo=0,redo=0,rotationGesture=false;
  const api=window.projectionEditor={enabled:false,dirty:false,target:null,toggle,reset,refreshHints,refreshVisibility,interact,history,flush,export:exportProjection};
  const categories=[['building_blocks','建筑方块'],['colored_blocks','染色方块'],['natural_blocks','自然方块'],['functional_blocks','功能方块'],['redstone_blocks','红石方块'],['op_blocks','管理员方块'],['special','水 / 岩浆 / 地狱门'],['all','全部']];
  function status(message){info.textContent=message;}
  function refreshHints(){
    el('menuEditProjection').textContent='更改投影 ('+shortcutHint('edit')+')';el('projectionClose').textContent='退出更改 ('+shortcutHint('edit')+')';
    el('projectionDebug').lastChild.textContent='调试棒 ('+shortcutHint('editorDebug')+')';
    el('projectionHotbar').title=shortcutHint('editorPrevious')+' / '+shortcutHint('editorNext')+' 切换物品';
    drawInventory();drawFlyHotbar();
  }
  function refreshVisibility(){
    const immersive=api.enabled&&state.fly&&(state.flyLookActive||state.flyLockPending);
    tools.classList.toggle('hidden',!api.enabled||immersive);el('projectionFlyHotbar').classList.toggle('hidden',!immersive);
  }
  function update(){
    el('menuEditProjection').setAttribute('aria-pressed',String(api.enabled));refreshVisibility();document.body.classList.toggle('projection-editing',api.enabled);
    el('projectionSelected').textContent=tool==='debug'?'调试棒':selected?.name||'空手';el('projectionPlace').setAttribute('aria-pressed',String(tool==='block'));el('projectionDebug').setAttribute('aria-pressed',String(tool==='debug'));
    el('projectionUndo').disabled=busy||undo===0;el('projectionRedo').disabled=busy||redo===0;el('projectionExport').disabled=busy;refreshHints();
    for(const button of palette.children)button.setAttribute('aria-pressed',String(button.dataset.block===selected?.id&&tool==='block'));
  }
  async function toggle(value=!api.enabled){
    if(busy)return;
    if(value&&(!state.viewReady||state.cpuPreview||state.scenePreparing||state.overview)){log('更改投影需要完整材质的 GPU 预览就绪。','err');return;}
    if(value&&!api.enabled){originalFile=originalFile||state.file;if(state.selection.enabled)toggleSelection(false);pauseFlyLook();state.keys.clear();shortcutHeldKeys.clear();closeInspector();}
    clearInventoryDrag();api.enabled=value;api.target=null;hoverKey=null;clearTimeout(hoverTimer);if(value)hoverTimer=setTimeout(updateTarget,120);dragging=false;flyPress=null;rotationGesture=false;update();drawView();
    if(!value&&api.dirty){busy=true;update();try{await flush(true);}catch(error){status('统一更新失败：'+error.message);log(info.textContent,'err');}finally{busy=false;update();}}
  }
  function reset(){++session;clearInventoryDrag();clearTimeout(hoverTimer);api.target=null;hoverKey=null;api.enabled=false;api.dirty=false;state.view?.clearEditPreview();undo=redo=0;busy=false;originalFile=null;stick.selected.clear();status('选择方块后在三维视图中操作，状态用调试棒修改。导出作者 LiteKinda。');update();}
  function icon(block){const canvas=document.createElement('canvas');canvas.width=canvas.height=32;const visual=block.visuals.find(v=>Object.entries(v.when).every(([k,v])=>block.defaults[k]===v))||block.visuals[0];if(atlas.naturalWidth&&visual){const index=visual.icon??visual.sprite;canvas.getContext('2d').drawImage(atlas,index%64*32,Math.floor(index/64)*32,32,32,0,0,32,32);}return canvas;}
  function inventoryChanged(message){
    selected=defs.get('minecraft:'+inventory.selected)||null;tool='block';hoverKey=null;update();
    window.appUi.setEditorInventory(inventory.snapshot()).catch(error=>status('背包保存失败：'+error.message));
    if(message)status(message);
  }
  function equip(block){inventory.equip(block.id);inventoryChanged('已选择 '+block.name+'；朝向随观察方向放置；铁轨用调试棒调整。');}
  function selectSlot(slot){inventory.select(slot);inventoryChanged('快捷栏 '+(slot+1)+' · '+(defs.get('minecraft:'+inventory.selected)?.name||'空槽'));}
  function setTool(value){tool=value;hoverKey=null;update();if(value==='debug')status('调试棒：左键选择属性，右键轮换值，Shift 反向；每类方块分别记忆所选属性。');}
  function drawFlyHotbar(){
    const items=Array.from({length:9},(_,index)=>inventory.get(EditorInventoryModel.HOTBAR_START+index)),hints=items.map((_,index)=>shortcutHint('editorSlot'+(index+1))),debugHint=shortcutHint('editorDebug');
    const key=JSON.stringify([items,hints,debugHint,inventory.slot,tool,atlas.naturalWidth]);if(key===flyHotbarKey)return;flyHotbarKey=key;
    const bar=el('projectionFlySlots');bar.replaceChildren();
    for(let index=0;index<9;index++){
      const block=defs.get('minecraft:'+items[index]),button=document.createElement('button'),hint=document.createElement('span');
      button.type='button';button.className='projection-fly-slot';button.dataset.slot=index;button.dataset.block=items[index]||'';button.title='物品栏 '+(index+1)+' ('+hints[index]+') · '+(block?.name||'空槽');button.setAttribute('aria-label',button.title);button.setAttribute('aria-pressed',String(tool==='block'&&inventory.slot===index));
      if(block)button.append(icon(block));hint.className='projection-fly-key';hint.textContent=hints[index];button.append(hint);button.onclick=()=>selectSlot(index);bar.append(button);
    }
    const debug=el('projectionFlyDebug');debug.title='调试棒 ('+debugHint+')';debug.setAttribute('aria-label',debug.title);debug.setAttribute('aria-pressed',String(tool==='debug'));debug.querySelector('span').textContent=debugHint;
    el('projectionFlyItemName').textContent=tool==='debug'?'调试棒':selected?.name||'空手';
  }
  el('projectionFlyDebug').onclick=()=>setTool(tool==='debug'?'block':'debug');
  for(const type of ['pointerdown','pointerup','mousedown','mouseup','click','dblclick'])el('projectionFlyHotbar').addEventListener(type,event=>event.stopPropagation());
  el('projectionFlyHotbar').addEventListener('contextmenu',event=>{event.preventDefault();event.stopPropagation();});
  function clearInventoryDrag(){inventoryDrag=null;for(const button of tools.querySelectorAll('.is-dragging,.is-drop-target'))button.classList.remove('is-dragging','is-drop-target');}
  function dragItem(button,block,source=null){
    button.draggable=!!block;if(!block)return;
    button.ondragstart=event=>{
      if(!api.enabled||!event.dataTransfer){event.preventDefault();return;}
      inventoryDrag={id:block.id,source};event.dataTransfer.effectAllowed=source===null?'copy':'move';event.dataTransfer.setData(inventoryDragType,block.id);button.classList.add('is-dragging');
    };
    button.ondragend=clearInventoryDrag;
  }
  for(const type of ['dragenter','dragover'])tools.addEventListener(type,event=>{
    const target=event.target.closest('.projection-slot');if(!inventoryDrag||!target)return;
    event.preventDefault();event.stopPropagation();event.dataTransfer.dropEffect=inventoryDrag.source===null?'copy':'move';
    for(const button of tools.querySelectorAll('.is-drop-target'))if(button!==target)button.classList.remove('is-drop-target');target.classList.add('is-drop-target');
  });
  tools.addEventListener('dragleave',event=>{const target=event.target.closest('.projection-slot');if(target&&!target.contains(event.relatedTarget))target.classList.remove('is-drop-target');});
  tools.addEventListener('drop',event=>{
    const target=event.target.closest('.projection-slot'),drag=inventoryDrag;if(!drag||!target)return;
    event.preventDefault();event.stopPropagation();clearInventoryDrag();
    if(event.dataTransfer.getData(inventoryDragType)!==drag.id||!defs.has('minecraft:'+drag.id))return;
    const index=Number(target.dataset.slot);
    if(drag.source===null)inventory.set(index,drag.id);
    else{if(inventory.get(drag.source)!==drag.id)return;inventory.move(drag.source,index);}
    inventoryChanged('已整理背包；拖动可交换位置，右键可移除物品。');
  });
  function drawInventory(){
    if(inventoryDrag)return;
    const bag=el('projectionInventory'),bar=el('projectionHotbar');bag.replaceChildren();bar.replaceChildren();
    for(let index=0;index<36;index++){
      const id=inventory.get(index),block=defs.get('minecraft:'+id),hotbar=index>=27,slot=index-27,button=document.createElement('button');
      button.type='button';button.className='projection-slot';button.dataset.slot=index;button.dataset.block=id||'';
      button.title=(hotbar?'快捷栏 '+(slot+1)+' ('+shortcutHint('editorSlot'+(slot+1))+')':'背包槽位 '+(index+1))+' · '+(block?block.name+' · '+block.id:'空槽');button.setAttribute('aria-label',button.title);
      if(block)button.append(icon(block));dragItem(button,block,index);
      button.title+='；拖动交换位置，右键移除';button.setAttribute('aria-label',button.title);
      button.oncontextmenu=event=>{event.preventDefault();if(block){inventory.set(index,null);inventoryChanged('已移除背包槽位 '+(index+1)+' 的物品。');}};
      if(hotbar){button.setAttribute('aria-pressed',String(slot===inventory.slot&&tool==='block'));const key=document.createElement('span');key.className='projection-slot-key';key.textContent=shortcutHint('editorSlot'+(slot+1));button.append(key);button.onclick=()=>selectSlot(slot);bar.append(button);}
      else{button.onclick=()=>{inventory.swap(index);inventoryChanged('已交换背包物品与快捷栏 '+(inventory.slot+1));};bag.append(button);}
    }
  }
  function inventoryAction(action){
    if(action==='editorDebug'){setTool(tool==='debug'?'block':'debug');return;}
    const slot=/^editorSlot([1-9])$/.exec(action);if(slot){selectSlot(+slot[1]-1);return;}
    if(action==='editorPrevious'||action==='editorNext'){inventory.cycle(action==='editorNext'?1:-1);inventoryChanged();return;}
  }
  function drawPalette(){
    const text=el('projectionSearch').value.trim().toLowerCase();palette.replaceChildren();
    for(const block of catalog){if(category!=='all'&&!block.categories.includes(category)||text&&!block.id.includes(text)&&!block.name.toLowerCase().includes(text))continue;
      const button=document.createElement('button');button.type='button';button.dataset.block=block.id;button.title=block.name+' · '+block.id+'；可拖入背包';button.setAttribute('aria-label',button.title);button.setAttribute('aria-pressed',String(selected===block&&tool==='block'));button.append(icon(block));button.onclick=()=>equip(block);dragItem(button,block);palette.append(button);
    }
  }
  for(const [id,label] of categories){const button=document.createElement('button');button.textContent=label;button.type='button';button.setAttribute('aria-pressed',String(id===category));button.onclick=()=>{category=id;for(const b of el('projectionTabs').children)b.setAttribute('aria-pressed',String(b===button));drawPalette();};el('projectionTabs').append(button);}
  atlas.onload=()=>{drawPalette();drawInventory();drawFlyHotbar();};el('projectionSearch').oninput=drawPalette;
  el('projectionPlace').onclick=()=>setTool('block');el('projectionDebug').onclick=()=>setTool('debug');
  el('menuEditProjection').onclick=()=>toggle();el('projectionClose').onclick=()=>toggle(false);
  const handle=el('projectionToolsHandle');let pull=null,suppress=false;
  function expand(value){tools.classList.toggle('is-expanded',value);handle.setAttribute('aria-expanded',String(value));}
  handle.onpointerdown=e=>{if(e.button!==0)return;e.preventDefault();suppress=false;pull={id:e.pointerId,y:e.clientY,expanded:tools.classList.contains('is-expanded')};handle.setPointerCapture(e.pointerId);};
  handle.onpointermove=e=>{if(pull?.id===e.pointerId&&Math.abs(e.clientY-pull.y)>10){suppress=true;expand(e.clientY<pull.y);}};
  handle.onpointerup=()=>{pull=null;};handle.onpointercancel=()=>{if(pull)expand(pull.expanded);pull=null;};handle.onclick=()=>{if(suppress){suppress=false;return;}expand(!tools.classList.contains('is-expanded'));};
  function targetBox(position){return {min:position,max:position.map(v=>v+1)};}
  els.viewport.addEventListener('pointermove',event=>{hoverPoint={clientX:event.clientX,clientY:event.clientY};});
  els.viewport.addEventListener('pointerleave',()=>{hoverPoint=null;if(!state.fly){api.target=null;drawView();}});
  async function updateTarget(){
    clearTimeout(hoverTimer);if(!api.enabled)return;const token=session;
    try{
      if(hoverRunning||busy||state.rendering||state.scenePreparing||!state.viewReady||!state.fly&&!hoverPoint)return;
      const rect=els.gl.getBoundingClientRect(),point=state.fly?{clientX:rect.left+rect.width/2,clientY:rect.top+rect.height/2}:hoverPoint;
      const ray=state.view.rayFromScreen(point.clientX,point.clientY,true);if(!ray)return;const bounds=state.selection.bounds(selectionWorld()),request={...ray,selection:bounds||undefined},key=JSON.stringify([request,tool,selected?.id]);if(key===hoverKey)return;
      hoverRunning=true;const hit=await window.engine.editPick(request);if(token!==session||!api.enabled||busy)return;hoverKey=key;
      api.target=hit.hit?targetBox(hit.position):null;drawView();
    }catch{}finally{hoverRunning=false;if(token===session&&api.enabled)hoverTimer=setTimeout(updateTarget,180);}
  }
  async function refreshPatch(token=session){
    const view=state.view,bounds=state.selection.bounds(selectionWorld()),result=await window.engine.editPatch({selection:bounds||undefined});if(token!==session||view!==state.view||result.empty)return;
    const image=await createImageBitmap(base64ToBlob(result.atlasDataUrl),{premultiplyAlpha:'none',colorSpaceConversion:'none'});let full=null,selected=null;
    try{
      full=new GpuView(els.gl);full.uploadMesh(result.meshArrayBuffer);full.uploadAtlas(image);
      if(result.selectionArrayBuffer){selected=new GpuView(els.gl);selected.uploadMesh(result.selectionArrayBuffer);selected.uploadAtlas(image);}
      if(token!==session||view!==state.view)return;view.clearEditPreview();view.editPreview=full;view.editSelectionPreview=selected;full=null;selected=null;view.setEditMask(result.cells);view.revision++;lastViewKey=null;drawView();
    }finally{image.close();full?.destroy();selected?.destroy();}
  }
  async function refresh(result,token){
    if(token!==session)return;undo=result.undo;redo=result.redo;if(!result.changed)return;
    state.info={...state.info,...result.info};api.dirty=true;window.blockSearch?.clear();await refreshPatch(token);if(token!==session)return;showFileInfo(state.info);hoverKey=null;
  }
  async function flush(rebuild=false){
    if(!api.dirty)return;const result=await window.engine.commitEdits();if(result.path)state.file=result.path;if(result.info)state.info=result.info;state.engineNeedsOpen=false;
    api.dirty=false;state.view?.clearEditPreview();if(rebuild)await buildView({force:true,preserveCamera:true,editsCommitted:true});showFileInfo(state.info);status('修改已统一更新。');
  }
  async function neighbors(position){return (await window.engine.editStates([[position[0]+1,position[1],position[2]],[position[0]-1,position[1],position[2]],[position[0],position[1]+1,position[2]],[position[0],position[1]-1,position[2]],[position[0],position[1],position[2]+1],[position[0],position[1],position[2]-1]])).blocks;}
  async function mutate(request,message){
    if(busy)return;busy=true;update();const token=session;
    try{const result=await window.engine.editBlock(request);await refresh(result,token);if(token===session)status(message+(result.changed?'':'（无变化）'));return result;}
    catch(error){if(token===session){status('修改失败：'+error.message);log(info.textContent,'err');}}
    finally{if(token===session){busy=false;update();}}
  }
  async function interact(button,event={}){
    if(!api.enabled||busy||!state.viewReady||state.rendering||state.scenePreparing||state.overview)return;
    const token=session,activeTool=tool,activeBlock=selected,rect=els.gl.getBoundingClientRect(),x=state.fly?rect.left+rect.width/2:event.clientX??rect.left+rect.width/2,y=state.fly?rect.top+rect.height/2:event.clientY??rect.top+rect.height/2;
    drawView();const ray=state.view.rayFromScreen(x,y,true);if(!ray)return;const bounds=state.selection.bounds(selectionWorld());
    busy=true;update();let hit;
    try{hit=await window.engine.editPick({...ray,selection:bounds||undefined});}catch(error){status('拾取失败：'+error.message);return;}finally{if(token===session){busy=false;update();}}
    if(token!==session||!api.enabled)return;
    if(!hit?.hit){status('未指向可编辑方块；部分渲染开启时只拾取选区内部。');return;}
    if(button===1){const definition=defs.get(hit.id);if(definition){equip(definition);status('已吸取 '+definition.name+'（默认状态）');}else status('此方块未收录到工具箱：'+hit.id);return;}
    if(activeTool==='debug'){
      const definition=defs.get(hit.id);hit.states={...definition?.defaults,...hit.states};const result=stick.interact(hit,button===2,!!event.shiftKey);const message=hit.position.join(', ')+' · '+hit.id+' · '+result.message;
      if(result.changed)await mutate({position:hit.position,id:hit.id,states:result.states},message);else status(message);return;
    }
    if(button===0){const changes=ProjectionEditModel.removal(hit,await neighbors(hit.position));await mutate({changes},'已删除 '+hit.position.join(', '));return;}
    if(button===2){
      if(!event.shiftKey&&ProjectionEditModel.container(hit.id)){const result=await window.engine.inspectPosition(hit.position);if(result.hit)showInspection(result);return;}
      if(!activeBlock){status('当前快捷栏为空，请从背包或方块工具栏选择物品。');return;}
      const position=ProjectionEditModel.adjacent(hit);if(!hit.face.some(Boolean)){status('当前位于方块内部，请从外侧点击表面。');return;}if(!ProjectionEditModel.within(position,bounds)){status('放置位置超出当前渲染选区，请扩大范围或关闭部分渲染。');return;}
      const look=state.view._viewDir(),changes=ProjectionEditModel.placement(activeBlock,{position,face:hit.face,look,shift:!!event.shiftKey,neighbors:await neighbors(position),hitHeight:hit.point?hit.point[1]-position[1]:.5});
      if(changes.some(change=>!ProjectionEditModel.within(change.position,bounds))){status('箱子另一半超出当前选区，请扩大范围。');return;}
      const result=await mutate({changes},'已放置 '+activeBlock.name+' · '+position.join(', ')+'；退出后统一更新模型。');if(result?.changed)audio.play(activeBlock.id);
    }
  }
  async function history(action){if(!api.enabled||busy||state.scenePreparing||state.rendering)return;return mutate({action},action==='undo'?'已撤销上一步修改。':'已重做上一步修改。');}
  async function exportProjection(){
    if(!state.file||busy)return;const token=session;busy=true;update();
    try{const source=(originalFile||state.file).split(/[\\/]/).pop().replace(/\.litematic$/i,'');const result=await window.engine.exportEdited({suggested:source+'-修改.litematic'});if(result&&token===session)status('已导出：'+result.path+' · 作者 '+result.author);return result;}
    catch(error){if(token===session)status('导出失败：'+error.message);}
    finally{if(token===session){busy=false;update();}}
  }
  el('projectionUndo').onclick=()=>history('undo');el('projectionRedo').onclick=()=>history('redo');el('projectionExport').onclick=exportProjection;
  window.addEventListener('keydown',e=>{
    if(bindingCaptureAction||document.querySelector('dialog[open]')||shortcutInputTarget(e))return;
    if(isShortcut('edit',e)){e.preventDefault();e.stopImmediatePropagation();if(!e.repeat)toggle();return;}
    if(api.enabled){const action=SHORTCUT_ACTIONS.find(([id])=>wheelShortcutAction(id)&&isShortcut(id,e))?.[0];if(action){e.preventDefault();e.stopImmediatePropagation();if(!e.repeat)inventoryAction(action);return;}}
    if(api.enabled&&(e.ctrlKey||e.metaKey)&&e.code==='KeyZ'){e.preventDefault();e.stopImmediatePropagation();if(!e.repeat)history(e.shiftKey?'redo':'undo');}
  },true);
  function wheel(event){
    if(!api.enabled||bindingCaptureAction||document.querySelector('dialog[open]')||shortcutInputTarget(event)||event.target.closest('.block-inspector')||event.altKey||!event.deltaY)return;
    const code=event.deltaY<0?'WheelUp':'WheelDown',action=SHORTCUT_ACTIONS.find(([id])=>wheelShortcutAction(id)&&shortcutBindings[id].includes(code))?.[0];
    if(!action)return;event.preventDefault();event.stopImmediatePropagation();inventoryAction(action);
  }
  els.viewport.addEventListener('wheel',wheel,{capture:true,passive:false});el('projectionHotbar').addEventListener('wheel',wheel,{passive:false});
  for(const type of ['pointerdown','mousedown','mouseup'])els.viewport.addEventListener(type,e=>{
    if(!api.enabled||e.target.closest('.block-inspector,.projection-fly-hotbar')||![0,1,2].includes(e.button))return;
    if(e.altKey&&!state.fly){rotationGesture=true;return;}if(rotationGesture&&type!=='pointerdown')return;rotationGesture=false;
    e.preventDefault();e.stopImmediatePropagation();if(type!=='pointerdown')return;
    if(state.fly&&!state.flyLookActive){resumeFlyLook();return;}
    interact(e.button,e).catch(error=>status(error.message));
  },true);
  update();drawPalette();
  window.__uiReady.then(async()=>{const settings=await window.appUi.settings();inventory.restore(settings.editorInventory);selected=defs.get('minecraft:'+inventory.selected)||null;update();}).catch(error=>status('背包读取失败：'+error.message));
})();
