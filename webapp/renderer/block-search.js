
'use strict';
(() => {
  const el=id=>document.getElementById(id),query=el('blockSearchQuery'),info=el('blockSearchStatus');
  let generation=0,page=null,index=0,searched='',running=false;
  const api=window.blockSearch={active:false,revision:0,clear};
  const box=p=>({min:p,max:p.map(v=>v+1)});
  function buttons(){el('blockSearchLocate').disabled=running||!page?.total;el('blockSearchPrev').disabled=el('blockSearchNext').disabled=running||!page?.total;el('blockSearchRun').disabled=running;}
  function clear(){++generation;page=null;running=false;api.active=false;++api.revision;if(state.view){state.view.searchBoxes=null;state.view.searchCurrent=null;state.view.searchPage=null;state.view.setSearchMatches(null);}info.textContent='打开投影后可按中文名称或 ID 搜索。';buttons();drawView();}
  function show(){
    const result=page.results[index];api.active=!!result;++api.revision;
    if(state.view){if(state.view.searchPage!==page){state.view.setSearchMatches(page.results.map(r=>r.position));state.view.searchPage=page;}state.view.searchBoxes=page.results.map(r=>box(r.position));state.view.searchCurrent=result?box(result.position):null;}
    info.textContent=result?`匹配 ${page.total.toLocaleString()} 个 · 当前 ${page.offset+index+1}\n${result.name} · ${result.id}\n坐标 ${result.position.join(', ')}${result.state?'\n'+result.state:''}`:'没有找到匹配方块。';
    if(page.total>page.limit)info.textContent+=`\n当前高亮第 ${page.offset+1}–${page.offset+page.results.length} 个，逐个定位会自动翻页。`;
    buttons();drawView();
  }
  function locate(){
    const hit=page?.results[index];if(!hit||!state.viewReady||state.cpuPreview)return;
    // Preserve normal orbit framing. A perspective camera places the target ahead of the viewer.
    setFly(true);state.flyYaw=0;state.flyPitch=-.12;state.flyPos=[hit.position[0]+.5,hit.position[1]+1.4,hit.position[2]+4];
    state.flyDirty=true;updateCoordinates();drawView();
  }
  async function search(offset=0,last=false){
    if(!state.file){info.textContent='请先打开投影。';return;}
    if(!state.viewReady||state.cpuPreview){info.textContent='请等待 GPU 三维预览就绪后搜索。';return;}
    const text=offset?searched:query.value.trim();if(!text){clear();return;}
    const token=++generation;running=true;buttons();info.textContent='正在搜索…';
    try{const result=await window.engine.searchBlocks({query:text,offset});if(token!==generation)return;searched=text;page=result;index=last?Math.max(0,result.results.length-1):0;show();}
    catch(error){if(token===generation){page=null;api.active=false;if(state.view){state.view.searchBoxes=null;state.view.searchCurrent=null;state.view.searchPage=null;state.view.setSearchMatches(null);}++api.revision;info.textContent='搜索失败：'+error.message;drawView();}}
    finally{if(token===generation){running=false;buttons();}}
  }
  async function next(delta){
    if(!page?.total||running)return;let target=(page.offset+index+delta+page.total)%page.total;
    if(target<page.offset||target>=page.offset+page.results.length){await search(Math.floor(target/page.limit)*page.limit,delta<0);if(!page||running)return;index=target-page.offset;}
    else index=target-page.offset;show();locate();
  }
  el('blockSearchRun').onclick=()=>search();query.onkeydown=e=>{if(e.key==='Enter'){e.preventDefault();search();}};
  el('blockSearchClear').onclick=clear;el('blockSearchDim').onchange=()=>{++api.revision;drawView();};
  el('blockSearchLocate').onclick=locate;el('blockSearchPrev').onclick=()=>next(-1);el('blockSearchNext').onclick=()=>next(1);
})();
