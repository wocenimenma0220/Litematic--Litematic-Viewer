'use strict';
const SHORTCUT_ACTIONS=[
  ['edit','开启 / 关闭更改投影',['KeyG']],['fly','进入 / 退出漫游',['KeyV']],['selection','开启 / 关闭选择工具',['KeyB']],
  ['editorDebug','更改投影：切换调试棒',['Digit0']],
  ...Array.from({length:9},(_,i)=>['editorSlot'+(i+1),'更改投影：快捷栏 '+(i+1),['Digit'+(i+1)]]),
  ['editorPrevious','更改投影：上一个快捷栏物品',['WheelUp']],['editorNext','更改投影：下一个快捷栏物品',['WheelDown']],
  ['command','输入指令',['Slash']],['submit','执行指令',['Enter']],['fullscreen','切换全屏',['F11']],['close','退出全屏 / 释放鼠标',['Escape']],
  ['forward','向前',['KeyW']],['backward','向后',['KeyS']],['left','向左',['KeyA']],['right','向右',['KeyD']],
  ['up','上升',['Space']],['down','下降',['Shift','KeyC']],['boost','加速',['Control']],
  ['inspect','查看方块 / 实体（漫游）',['Mouse0']],['corner1','设置角点 1',['Mouse0']],['corner2','设置角点 2',['Mouse2']],['nudge','微调角点 / 切换层的滚轮修饰键',['Alt']],
];
function normalizedKey(code){return /^(Shift|Control|Alt)(Left|Right)$/.test(code)?code.replace(/(Left|Right)$/,''):code;}
function validShortcut(code){return typeof code==='string'&&/^(Key[A-Z]|Digit[0-9]|F([1-9]|1[0-9]|2[0-4])|Arrow(Up|Down|Left|Right)|Space|Tab|Escape|Enter|Backspace|Delete|Insert|Home|End|PageUp|PageDown|Shift|Control|Alt|Slash|Backslash|BracketLeft|BracketRight|Semicolon|Quote|Comma|Period|Minus|Equal|Backquote|Mouse[012]|Wheel(Up|Down))$/.test(code);}
function wheelShortcutAction(action){return action.startsWith('editor');}
function cleanShortcutBindings(input){const result={};for(const [id,,defaults]of SHORTCUT_ACTIONS){const values=input?.[id];result[id]=Array.isArray(values)&&values.length>0&&values.length<=3&&values.every(validShortcut)?[...new Set(values)]:defaults.slice();}return result;}
let shortcutBindings=cleanShortcutBindings(),shortcutHeldKeys=new Set();
function setShortcutBindings(values){shortcutBindings=cleanShortcutBindings(values);}
function isShortcut(action,event){return shortcutBindings[action].includes(normalizedKey(event.code));}
function heldShortcut(action,keys=shortcutHeldKeys){return shortcutBindings[action].some(key=>keys.has(key));}
function mouseShortcut(action,event){return shortcutBindings[action].includes('Mouse'+event.button);}
function shortcutInputTarget(event){return event.target?.isContentEditable||!!event.target?.closest?.('input,textarea,select');}
function shortcutLabel(code){return ({Mouse0:'鼠标左键',Mouse1:'鼠标中键',Mouse2:'鼠标右键',WheelUp:'滚轮上滚',WheelDown:'滚轮下滚',PageUp:'Page Up',PageDown:'Page Down',Space:'空格',Control:'Ctrl',Escape:'Esc',Slash:'/',Backslash:'\\',BracketLeft:'[',BracketRight:']',Semicolon:';',Quote:"'",Comma:',',Period:'.',Minus:'-',Equal:'=',Backquote:'`',ArrowUp:'↑',ArrowDown:'↓',ArrowLeft:'←',ArrowRight:'→'})[code]||code.replace(/^Key|^Digit/,'');}
function shortcutHint(action){return shortcutBindings[action].map(shortcutLabel).join(' / ');}
if(typeof module!=='undefined')module.exports={SHORTCUT_ACTIONS,cleanShortcutBindings,normalizedKey,validShortcut,wheelShortcutAction};
