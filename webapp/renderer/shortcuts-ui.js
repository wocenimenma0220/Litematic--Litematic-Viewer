'use strict';
let bindingCaptureAction=null;
const keysDialog=$('shortcutDialog'),commandsDialog=$('commandsDialog');
function refreshShortcutHints(){
  window.projectionEditor?.refreshHints();
  els.flyBtn.textContent=`${state.fly?'退出':'进入'}漫游 (${shortcutHint('fly')})`;
  els.fullscreen.textContent=`${state.fullscreen?'退出全屏':'全屏'} (${shortcutHint('fullscreen')})`;
  selectionUi.enabled.parentElement.lastChild.textContent=`选择渲染工具 (${shortcutHint('selection')})`;
  $('openCommand').textContent=`输入指令 (${shortcutHint('command')})`;
  selectionUi.command.placeholder=`输入指令，例如 //select 1（${shortcutHint('command')} 聚焦）`;
  selectionUi.command.title=`${shortcutHint('submit')} 执行，${shortcutHint('close')} 返回预览`;
  els.flyHint.textContent=`${shortcutHint('forward')} / ${shortcutHint('left')} / ${shortcutHint('backward')} / ${shortcutHint('right')} 移动，${shortcutHint('up')} 上升，${shortcutHint('down')} 下降，${shortcutHint('boost')} 加速；移动鼠标转向，${shortcutHint('close')} 释放鼠标，${shortcutHint('fly')} 退出漫游。`;
  $('selectionHelp').textContent=`开启后 ${shortcutHint('corner1')} 选角点 1，${shortcutHint('corner2')} 选角点 2，也可在“移动角点”中切换；${shortcutHint('nudge')}＋滚轮每格移动一个方块，上滚沿观察方向、下滚反向。抬头或低头超过 60° 时上下移动，逐层模式切换高度。漫游照常，选择优先。`;
  updateSelectionUi();
}
function renderShortcutRows(){
  const rows=$('shortcutRows');rows.replaceChildren();
  for(const [action,label]of SHORTCUT_ACTIONS){
    const row=document.createElement('div');row.className='shortcut-row';const name=document.createElement('span');name.textContent=label;
    const button=document.createElement('button');button.type='button';button.dataset.action=action;button.textContent=bindingCaptureAction===action?'请按新键…':shortcutHint(action);
    button.addEventListener('click',()=>{bindingCaptureAction=action;$('cancelBinding').classList.remove('hidden');$('shortcutFeedback').textContent='等待新按键；点击“取消本次绑定”退出。';renderShortcutRows();});row.append(name,button);rows.append(row);
  }
}
function cancelBinding(){bindingCaptureAction=null;$('cancelBinding').classList.add('hidden');renderShortcutRows();}
async function bindShortcut(action,code){
  if(!validShortcut(code)){$('shortcutFeedback').textContent='该按键暂不支持，请换一个。';return;}
  if(code.startsWith('Mouse')&&!['corner1','corner2','inspect'].includes(action)){$('shortcutFeedback').textContent='这个操作请选择键盘按键。';return;}
  if(code.startsWith('Wheel')&&!wheelShortcutAction(action)){$('shortcutFeedback').textContent='滚轮可用于更改投影的物品选择和背包操作。';return;}
  const separateScope=id=>id==='inspect'&&['corner1','corner2'].includes(action)||action==='inspect'&&['corner1','corner2'].includes(id);
  const conflict=SHORTCUT_ACTIONS.find(([id])=>id!==action&&!separateScope(id)&&shortcutBindings[id].includes(code));
  if(conflict){$('shortcutFeedback').textContent=`${shortcutLabel(code)} 已用于“${conflict[1]}”，请先修改该项或选择其他键。`;return;}
  try{const result=await window.appUi.setKeybindings({...shortcutBindings,[action]:[code]});setShortcutBindings(result.keybindings);cancelBinding();refreshShortcutHints();$('shortcutFeedback').textContent=`已保存：${SHORTCUT_ACTIONS.find(([id])=>id===action)[1]} → ${shortcutLabel(code)}`;}
  catch(error){$('shortcutFeedback').textContent='保存失败：'+error.message;}
}
function openShortcutDialog(dialog){
  if(document.activeElement===selectionUi.command){commandResume=false;closeCommand();}
  pauseFlyLook();state.keys.clear();shortcutHeldKeys.clear();closeInspector();els.menuFileDrop.classList.add('hidden');dialog.showModal();
}
$('menuKeys').addEventListener('click',()=>{renderShortcutRows();openShortcutDialog(keysDialog);});
$('closeShortcuts').addEventListener('click',()=>{cancelBinding();keysDialog.close();});
$('cancelBinding').addEventListener('click',cancelBinding);
$('resetShortcuts').addEventListener('click',async()=>{try{const result=await window.appUi.setKeybindings(cleanShortcutBindings());setShortcutBindings(result.keybindings);cancelBinding();refreshShortcutHints();$('shortcutFeedback').textContent='已恢复默认按键。';}catch(error){$('shortcutFeedback').textContent=error.message;}});
keysDialog.addEventListener('close',()=>{cancelBinding();shortcutHeldKeys.clear();state.keys.clear();});
$('menuCommands').addEventListener('click',()=>openShortcutDialog(commandsDialog));
$('closeCommands').addEventListener('click',()=>commandsDialog.close());
$('commandsInput').addEventListener('click',()=>{commandsDialog.close();openCommand();});
for(const [syntax,description]of [['//select 1 [X Y Z]','设置渲染范围角点 1'],['//select 2 [X Y Z]','设置渲染范围角点 2']]){
  const row=document.createElement('div');row.className='shortcut-row';const code=document.createElement('code');code.textContent=syntax;const desc=document.createElement('span');desc.textContent=description;row.append(code,desc);$('commandRows').append(row);
}
window.addEventListener('keydown',event=>{
  if(bindingCaptureAction){event.preventDefault();event.stopImmediatePropagation();if(!event.repeat)bindShortcut(bindingCaptureAction,normalizedKey(event.code));return;}
  if(keysDialog.open||commandsDialog.open){event.stopImmediatePropagation();if(event.key==='Escape'){event.preventDefault();(keysDialog.open?keysDialog:commandsDialog).close();}return;}
  if(!shortcutInputTarget(event))shortcutHeldKeys.add(normalizedKey(event.code));
},true);
window.addEventListener('keyup',event=>shortcutHeldKeys.delete(normalizedKey(event.code)),true);
window.addEventListener('blur',()=>shortcutHeldKeys.clear());
keysDialog.addEventListener('mousedown',event=>{if(bindingCaptureAction&&!event.target.closest('button')){event.preventDefault();bindShortcut(bindingCaptureAction,'Mouse'+event.button);}});
keysDialog.addEventListener('contextmenu',event=>event.preventDefault());
keysDialog.addEventListener('wheel',event=>{if(bindingCaptureAction&&event.deltaY){event.preventDefault();event.stopImmediatePropagation();bindShortcut(bindingCaptureAction,event.deltaY<0?'WheelUp':'WheelDown');}},{passive:false});
window.__uiReady.then(()=>{refreshShortcutHints();renderShortcutRows();});
