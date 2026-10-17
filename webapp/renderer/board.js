'use strict';
(() => {
  const $=id=>document.getElementById(id),dialog=$('boardDialog'),viewDialog=$('boardViewDialog');
  const canvas=$('boardCanvas'),ctx=canvas.getContext('2d'),catalog=window.BoardCatalog,defs=new Map(catalog.map(b=>[b.id,b]));
  const world=new BoardModel.BoardWorld(catalog),atlas=new Image(),tileCache=new Map(),sessions=new Map();
  const categories=[['building_blocks','建筑方块'],['colored_blocks','染色方块'],['natural_blocks','自然方块'],['functional_blocks','功能方块'],['redstone_blocks','红石方块'],['op_blocks','管理员方块'],['special','水 / 岩浆 / 地狱门'],['all','全部']];
  const names={facing:'方向',axis:'轴向',rotation:'旋转',shape:'形状',half:'半部',type:'类型',face:'附着',attachment:'附着',part:'部位',hinge:'铰链',mode:'模式',delay:'延迟',waterlogged:'含水',powered:'激活',triggered:'激活',extended:'伸出',enabled:'启用',lit:'点亮',open:'打开',power:'信号',locked:'锁定',in_wall:'嵌墙',north:'↑',south:'↓',east:'→',west:'←',up:'上连接',down:'下连接',age:'生长',stage:'阶段',layers:'层数',level:'液位',moisture:'湿度',distance:'距离',persistent:'持久',occupied:'占用',snowy:'积雪',berries:'浆果',bloom:'开花',candles:'数量',eggs:'数量',pickles:'数量',hatch:'孵化',bites:'食用',charges:'充能',honey_level:'蜂蜜',instrument:'乐器',note:'音高',sculk_sensor_phase:'阶段',short:'短臂',has_book:'书本',has_record:'唱片',orientation:'朝向',attached:'连接',disarmed:'解除',bottom:'底部',hanging:'悬挂',inverted:'反转',conditional:'条件',unstable:'不稳定',cracked:'开裂',crafting:'合成',ominous:'不祥',can_summon:'召唤',dusted:'刷扫',thickness:'粗细',vertical_direction:'方向',tilt:'倾斜',leaves:'叶片',drag:'下沉'};
  const values={true:'开',false:'关',bottom:'下',top:'上',lower:'下',upper:'上',double:'双层',single:'单体',left:'左',right:'右',head:'头部',foot:'尾部',floor:'地面',wall:'墙面',ceiling:'顶部',straight:'直线',inner_left:'内弯 ↰',inner_right:'内弯 ↱',outer_left:'外弯 ↰',outer_right:'外弯 ↱',compare:'比较',subtract:'减法',inactive:'静止',active:'激活',cooldown:'冷却',none:'无',side:'平接',up:'↑',low:'低',tall:'高'};
  let selected=defs.get('stone')||catalog[0],placement,category='building_blocks',zoom=40,pan={x:0,y:0};
  let erase=false,paused=false,pointer=null,hover=null,history=[],future=[],dirty=true,lastTick=0,savedTimer,animation;
  let screenWidth=1,screenHeight=1;
  const status=text=>$('boardStatus').textContent=text;
  const audio=new BoardAudio(window.BoardSounds,{onError:()=>status('音效播放失败。')});
  const tools=$('boardTools'),toolsHandle=$('boardToolsHandle');
  let toolsExpanded=false,toolsDrag=null,suppressToolsClick=false;
  function expandTools(expanded){toolsExpanded=expanded;tools.classList.toggle('is-expanded',expanded);toolsHandle.setAttribute('aria-expanded',String(expanded));toolsHandle.setAttribute('aria-label',expanded?'收起方块工具栏':'展开方块工具栏');dirty=true;}
  toolsHandle.addEventListener('pointerdown',event=>{if(event.button!==0)return;event.preventDefault();suppressToolsClick=false;toolsDrag={id:event.pointerId,y:event.clientY,expanded:toolsExpanded,moved:false};toolsHandle.setPointerCapture(event.pointerId);});
  toolsHandle.addEventListener('pointermove',event=>{if(toolsDrag?.id!==event.pointerId)return;const delta=event.clientY-toolsDrag.y;if(Math.abs(delta)>10){toolsDrag.moved=true;expandTools(delta<0);}});
  toolsHandle.addEventListener('pointerup',event=>{if(toolsDrag?.id!==event.pointerId)return;suppressToolsClick=toolsDrag.moved;toolsDrag=null;});
  for(const name of ['pointercancel','lostpointercapture'])toolsHandle.addEventListener(name,event=>{if(toolsDrag?.id!==event.pointerId)return;expandTools(toolsDrag.expanded);toolsDrag=null;suppressToolsClick=true;});
  toolsHandle.addEventListener('click',event=>{if(suppressToolsClick&&event.detail>0){suppressToolsClick=false;return;}expandTools(!toolsExpanded);});
  const capture=()=>({version:2,view:world.view,cells:world.snapshot(),zoom,pan:{...pan}});
  function updateHistory(){$('boardUndo').disabled=!history.length;$('boardRedo').disabled=!future.length;}
  function remember(){history.push(capture());if(history.length>60)history.shift();future=[];updateHistory();}
  function persist(flush=false){const packet=capture();sessions.set(packet.view,packet);clearTimeout(savedTimer);const write=()=>{try{localStorage.setItem('litematic-board-v2-'+packet.view,JSON.stringify(packet));}catch{status('暂存失败，请保存画布。');}};if(flush)write();else savedTimer=setTimeout(write,250);}
  function updateTitle(){$('boardTitle').textContent='二维搭建 · '+(world.view==='top'?'俯视':'侧视');}
  function restore(packet){world.view=packet.view==='top'?'top':'side';world.restore(packet.cells);if(Number.isFinite(packet.zoom))zoom=Math.max(10,Math.min(160,packet.zoom));if(Number.isFinite(packet.pan?.x)&&Number.isFinite(packet.pan?.y))pan={...packet.pan};updateTitle();drawOptions();drawPalette();dirty=true;}
  function choose(block){selected=block;placement=world.normalize({id:block.id,states:{...block.defaults}});erase=false;updateBrush();drawOptions();drawPalette();dirty=true;}
  function matches(condition,states){return Object.entries(condition).every(([k,v])=>k==='OR'?v.some(c=>matches(c,states)):k==='AND'?v.every(c=>matches(c,states)):String(v).split('|').includes(states[k]));}
  function sprites(block,cell,view){
    const index=p=>view==='icon'?(p.icon??p.sprite):view==='top'?p.top:p.sprite;
    if(block.multipart?.length)return block.multipart.filter(p=>matches(p.when,cell.states)).map(index);
    const visual=block.visuals.find(v=>matches(v.when,cell.states))||block.visuals[0];return visual?[index(visual)]:[];
  }
  function tileImage(raw,view=world.view){
    const cell=raw.states?raw:world.normalize(raw),key=view+':'+cell.id+':'+JSON.stringify(cell.states);
    if(tileCache.has(key))return tileCache.get(key);
    const block=defs.get(cell.id),c=document.createElement('canvas');c.width=c.height=32;const context=c.getContext('2d');context.imageSmoothingEnabled=false;
    for(const index of sprites(block,cell,view))if(Number.isInteger(index))context.drawImage(atlas,index%64*32,Math.floor(index/64)*32,32,32,0,0,32,32);
    if(cell.id==='redstone_wire'){context.globalCompositeOperation='source-atop';context.fillStyle=`rgba(${Math.round(100+155*Number(cell.states.power||0)/15)},15,5,.6)`;context.fillRect(0,0,32,32);context.globalCompositeOperation='source-over';}
    if(cell.waterlogged){context.fillStyle='rgba(49,130,235,.38)';context.fillRect(0,0,32,32);}
    if(tileCache.size>6000)tileCache.clear();tileCache.set(key,c);return c;
  }
  function drawTile(context,cell,x,y,size,alpha=1){if(!defs.has(cell?.id)||!atlas.naturalWidth)return;context.save();context.globalAlpha=alpha;context.imageSmoothingEnabled=false;context.drawImage(tileImage(cell),x,y,size,size);context.restore();}
  function tile(cell){const c=document.createElement('canvas');c.width=c.height=32;if(atlas.naturalWidth)c.getContext('2d').drawImage(tileImage(cell,'icon'),0,0);return c;}
  function setState(property,value){const states={...placement.states,[property]:value};
    if(property==='powered'&&('open' in states))states.open=value;
    placement=world.normalize({id:selected.id,states});drawOptions();dirty=true;
  }
  function drawOptions(){
    $('boardSelected').textContent=selected.name;const host=$('boardState');host.replaceChildren();
    const priority=['facing','axis','rotation','shape','half','face','type','waterlogged','powered','triggered','open','lit','extended','mode','delay'];
    const properties=Object.entries(selected.properties||{}).sort(([a],[b])=>(priority.includes(a)?priority.indexOf(a):99)-(priority.includes(b)?priority.indexOf(b):99));
    for(const [property,options] of properties){
      if(selected.id==='observer'&&property==='powered')continue;
      const field=document.createElement('fieldset'),legend=document.createElement('legend');field.dataset.property=property;legend.textContent=names[property]||property;field.append(legend);
      const iconOnly=['facing','axis','shape','rotation','orientation','vertical_direction'].includes(property);
      if(options.length>12||options.length>6&&options.every(v=>/^\d+$/.test(v))){
        const select=document.createElement('select');select.setAttribute('aria-label',names[property]||property);for(const value of options){const option=document.createElement('option');option.value=value;option.textContent=values[value]||value;select.append(option);}select.value=placement.states[property];select.onchange=()=>setState(property,select.value);field.append(select);
      }else for(const value of options){
        const button=document.createElement('button');button.type='button';button.className='board-option';button.title=property+'='+value;button.setAttribute('aria-label',button.title);button.setAttribute('aria-pressed',String(placement.states[property]===value));
        button.disabled=property==='waterlogged'&&value==='true'&&placement.states.type==='double'&&selected.id.endsWith('_slab');
        button.append(tile(world.normalize({id:selected.id,states:{...placement.states,[property]:value}})));if(!iconOnly)button.append(document.createTextNode(values[value]||value));button.onclick=()=>setState(property,value);field.append(button);
      }
      host.append(field);
    }
  }
  function drawPalette(){const query=$('boardSearch').value.trim().toLowerCase(),host=$('boardPalette');host.replaceChildren();
    const shown=catalog.filter(b=>(category==='all'||b.categories.includes(category))&&(!query||b.name.toLowerCase().includes(query)||b.id.includes(query)));
    for(const block of shown){const button=document.createElement('button');button.type='button';button.title=block.name;button.dataset.block=block.id;button.setAttribute('aria-label',block.name+' · '+block.id);button.setAttribute('aria-pressed',String(block===selected));button.append(tile({id:block.id}));button.onclick=()=>choose(block);host.append(button);}
  }
  function updateBrush(){$('boardBrush').setAttribute('aria-pressed',String(!erase));$('boardEraser').setAttribute('aria-pressed',String(erase));}
  function resize(){const rect=canvas.getBoundingClientRect();screenWidth=rect.width;screenHeight=rect.height;const dpr=window.devicePixelRatio||1;canvas.width=Math.round(rect.width*dpr);canvas.height=Math.round(rect.height*dpr);ctx.setTransform(dpr,0,0,dpr,0,0);dirty=true;}
  function location(e){const r=canvas.getBoundingClientRect();return{x:e.clientX-r.left,y:e.clientY-r.top};}
  function cellAt(p){return{x:Math.floor((p.x-pan.x)/zoom),y:Math.floor((p.y-pan.y)/zoom)};}
  function paint(cell){const k=BoardModel.key(cell.x,cell.y);if(pointer?.last===k)return;if(pointer)pointer.last=k;const before=JSON.stringify(world.get(cell.x,cell.y));world.set(cell.x,cell.y,erase?null:placement);if(!erase&&before!==JSON.stringify(world.get(cell.x,cell.y)))audio.play(placement.id);dirty=true;}
  function draw(){ctx.clearRect(0,0,screenWidth,screenHeight);ctx.imageSmoothingEnabled=false;
    const minX=Math.max(-BoardModel.LIMIT,Math.floor(-pan.x/zoom)),maxX=Math.min(BoardModel.LIMIT,Math.ceil((screenWidth-pan.x)/zoom)),minY=Math.max(-BoardModel.LIMIT,Math.floor(-pan.y/zoom)),maxY=Math.min(BoardModel.LIMIT,Math.ceil((screenHeight-pan.y)/zoom));
    ctx.strokeStyle='rgba(148,163,184,.12)';ctx.lineWidth=1;ctx.beginPath();for(let x=minX;x<=maxX+1;x++){const xx=Math.round(pan.x+x*zoom)+.5;ctx.moveTo(xx,Math.max(0,pan.y+minY*zoom));ctx.lineTo(xx,Math.min(screenHeight,pan.y+(maxY+1)*zoom));}for(let y=minY;y<=maxY+1;y++){const yy=Math.round(pan.y+y*zoom)+.5;ctx.moveTo(Math.max(0,pan.x+minX*zoom),yy);ctx.lineTo(Math.min(screenWidth,pan.x+(maxX+1)*zoom),yy);}ctx.stroke();
    for(let y=minY;y<=maxY;y++)for(let x=minX;x<=maxX;x++){
      const cell=world.get(x,y),moving=pointer?.mode==='move'&&pointer.from.x===x&&pointer.from.y===y;
      if(cell&&cell.id!=='water')drawTile(ctx,cell,pan.x+x*zoom,pan.y+y*zoom,zoom,moving?.3:1);
      const water=world.water.get(BoardModel.key(x,y))||(world.source(x,y)?{level:0,falling:false}:null);if(water){const height=world.view==='top'||water.falling||water.level===0?1:Math.max(.125,(8-water.level)/8),left=pan.x+x*zoom,top=pan.y+(y+1-height)*zoom;ctx.save();ctx.beginPath();ctx.rect(left,top,zoom,zoom*height);ctx.clip();drawTile(ctx,{id:'water'},left,pan.y+y*zoom,zoom,moving?.3:world.view==='top'?.55+(8-water.level)*.025:.72);ctx.restore();}
    }
    if(hover&&Math.abs(hover.x)<=BoardModel.LIMIT&&Math.abs(hover.y)<=BoardModel.LIMIT&&pointer?.mode!=='pan'){
      const x=pan.x+hover.x*zoom,y=pan.y+hover.y*zoom,moving=pointer?.mode==='move',blocked=moving&&!!world.get(hover.x,hover.y);
      if(moving)drawTile(ctx,pointer.cell,x,y,zoom,.7);else if(!erase)drawTile(ctx,placement,x,y,zoom,.45);
      ctx.strokeStyle=erase||blocked?'#fb7185':'#60a5fa';ctx.lineWidth=2;ctx.strokeRect(x+1,y+1,zoom-2,zoom-2);
    }dirty=false;
  }
  function frame(now){if(!dialog.open)return;if(!paused&&pointer?.mode!=='move'&&now-lastTick>=150){world.tick();lastTick=now;dirty=true;}if(dirty&&atlas.naturalWidth)draw();animation=requestAnimationFrame(frame);}
  function home(){pan={x:Math.round(screenWidth/2),y:Math.round(screenHeight/3)};dirty=true;}
  function enter(view){viewDialog.close();pointer=null;hover=null;world.view=view;world.restore([]);dialog.showModal();resize();home();zoom=40;history=[];future=[];updateHistory();status('');
    const packet=sessions.get(view);if(packet)restore({...packet,view});
    updateTitle();drawOptions();drawPalette();canvas.focus();lastTick=performance.now();cancelAnimationFrame(animation);animation=requestAnimationFrame(frame);
  }
  $('menuBoard').onclick=()=>{if(document.pointerLockElement)document.exitPointerLock();viewDialog.showModal();};
  $('boardViewSide').onclick=()=>enter('side');$('boardViewTop').onclick=()=>enter('top');$('boardViewCancel').onclick=()=>viewDialog.close();
  function close(){pointer=null;persist(true);cancelAnimationFrame(animation);dialog.close();}
  $('boardClose').onclick=close;dialog.addEventListener('cancel',e=>{e.preventDefault();close();});
  $('boardBrush').onclick=()=>{erase=false;updateBrush();dirty=true;};$('boardEraser').onclick=()=>{erase=true;updateBrush();dirty=true;};$('boardSearch').oninput=drawPalette;
  for(const [id,label]of categories){const button=document.createElement('button');button.type='button';button.textContent=label;button.setAttribute('aria-pressed',String(category===id));button.onclick=()=>{category=id;for(const b of $('boardTabs').children)b.setAttribute('aria-pressed',String(b===button));drawPalette();};$('boardTabs').append(button);}
  $('boardPause').onclick=()=>{paused=!paused;$('boardPause').textContent=paused?'继续水流':'暂停水流';$('boardPause').setAttribute('aria-pressed',String(paused));};$('boardHome').onclick=()=>{zoom=40;home();persist();};
  $('boardUndo').onclick=()=>{if(!history.length)return;future.push(capture());restore(history.pop());updateHistory();persist();};$('boardRedo').onclick=()=>{if(!future.length)return;history.push(capture());restore(future.pop());updateHistory();persist();};
  canvas.addEventListener('contextmenu',e=>e.preventDefault());
  canvas.addEventListener('pointerdown',e=>{if(e.button>2)return;e.preventDefault();canvas.focus();canvas.setPointerCapture(e.pointerId);const point=location(e),from=cellAt(point),cell=world.get(from.x,from.y);pointer={id:e.pointerId,mode:e.button===1?'pan':e.button===2?(cell?'move':'pan'):'paint',point,from,cell,last:null};hover=from;if(pointer.mode==='paint'){remember();paint(from);}canvas.style.cursor=pointer.mode==='pan'?'grabbing':pointer.mode==='move'?'move':'crosshair';});
  canvas.addEventListener('pointermove',e=>{const p=location(e);hover=cellAt(p);if(pointer?.id===e.pointerId){if(pointer.mode==='pan'){pan.x+=p.x-pointer.point.x;pan.y+=p.y-pointer.point.y;}else if(pointer.mode==='paint'){const from=cellAt(pointer.point),to=cellAt(p),steps=Math.max(Math.abs(to.x-from.x),Math.abs(to.y-from.y));for(let i=0;i<=steps;i++)paint({x:Math.round(from.x+(to.x-from.x)*i/(steps||1)),y:Math.round(from.y+(to.y-from.y)*i/(steps||1))});}pointer.point=p;}dirty=true;});
  function endPointer(e,commit){if(!pointer||pointer.id!==e.pointerId)return;if(commit&&pointer.mode==='move'){const to=cellAt(location(e));if(!world.get(to.x,to.y)&&Math.abs(to.x)<=BoardModel.LIMIT&&Math.abs(to.y)<=BoardModel.LIMIT){remember();if(world.move(pointer.from,to))audio.play(pointer.cell.id);}}pointer=null;canvas.style.cursor='crosshair';dirty=true;persist();}
  canvas.addEventListener('pointerup',e=>endPointer(e,true));canvas.addEventListener('pointercancel',e=>endPointer(e,false));canvas.addEventListener('lostpointercapture',e=>endPointer(e,false));canvas.addEventListener('pointerleave',()=>{if(!pointer)hover=null;dirty=true;});
  canvas.addEventListener('wheel',e=>{e.preventDefault();if(pointer)return;const p=location(e),wx=(p.x-pan.x)/zoom,wy=(p.y-pan.y)/zoom;zoom=Math.max(10,Math.min(160,zoom*Math.exp(-e.deltaY*.0015)));pan={x:p.x-wx*zoom,y:p.y-wy*zoom};dirty=true;persist();},{passive:false});
  dialog.addEventListener('keydown',e=>{if(e.target.closest('input,select'))return;if((e.ctrlKey||e.metaKey)&&e.code==='KeyZ'){e.preventDefault();(e.shiftKey?$('boardRedo'):$('boardUndo')).click();}if((e.ctrlKey||e.metaKey)&&e.code==='KeyS'){e.preventDefault();$('boardSave').click();}});
  $('boardSave').onclick=()=>{const blob=new Blob([JSON.stringify(capture(),null,2)],{type:'application/json'}),url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download='二维搭建.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),10000);};
  $('boardLoad').onclick=()=>$('boardFile').click();$('boardFile').onchange=async()=>{const file=$('boardFile').files[0];if(!file)return;try{if(file.size>20*1024*1024)throw new Error('文件超过 20 MB');const data=JSON.parse(await file.text());if(![1,2].includes(data.version))throw new Error('不支持的格式');const test=new BoardModel.BoardWorld(catalog);test.restore(data.cells);remember();persist();restore({...data,view:data.view||world.view,cells:test.snapshot()});persist();status('');}catch(error){status('打开失败：'+error.message);}finally{$('boardFile').value='';}};
  new ResizeObserver(()=>{if(dialog.open)resize();}).observe($('boardStage'));
  atlas.onload=()=>{tileCache.clear();drawOptions();drawPalette();dirty=true;};atlas.onerror=()=>status('贴图加载失败。');
  atlas.src='board-atlas.png';updateHistory();choose(selected);
})();
