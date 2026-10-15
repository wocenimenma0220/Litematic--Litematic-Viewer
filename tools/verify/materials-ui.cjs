const {app,BrowserWindow,dialog}=require('electron'),fs=require('node:fs'),path=require('node:path');
const root=process.argv[2]||path.resolve(__dirname,'../..'),out=path.join(__dirname,'material-output/ui');process.argv=process.argv.slice(0,2);fs.mkdirSync(out,{recursive:true});for(const name of ['export-1.xlsx','export-2.csv','export-3-建材.xlsx','export-3-容器内容物.xlsx','export-4.csv']){try{fs.unlinkSync(path.join(out,name));}catch{}}app.setPath('userData',path.join(out,'electron'));
app.commandLine.appendSwitch('disable-renderer-backgrounding');BrowserWindow.prototype.show=function(){this.setPosition(-20000,-20000,false);this.showInactive();};
const settings=require(path.join(root,'webapp/ui-settings')),Original=settings.UiSettings;settings.UiSettings=class extends Original{constructor(){super(out);}};
const choices=[];dialog.showSaveDialog=async(_window,options)=>{choices.push(options);return {canceled:false,filePath:path.join(out,'export-'+choices.length+'.'+options.filters[0].extensions[0])};};
app.once('browser-window-created',(_event,win)=>{win.webContents.setBackgroundThrottling(false);win.webContents.once('did-finish-load',async()=>{
 try {
  const result=await win.webContents.executeJavaScript(`(async()=>{
   const check=(ok,message)=>{if(!ok)throw Error(message);};const wait=ms=>new Promise(r=>setTimeout(r,ms));const until=async(test)=>{const end=Date.now()+120000;while(!test()){if(Date.now()>end)throw Error('UI timeout');await wait(50);}};
   await window.__uiReady;if(els.welcome.open)await dismissWelcome(false);await window.engine.loadResources(null);await loadFile(${JSON.stringify(path.join(__dirname,'material-output/materials-heads.litematic'))});check(state.viewReady,'heads preview not ready');
   check(!els.materials.disabled&&!els.menuMaterials.disabled,'material actions unavailable');await showMaterials();check($('materialDialog').open&&materialReport.building.length>0,'materials view did not open');check($('materialFormat').value==='xlsx','default format is not XLSX');
   check(materialReport.contents.find(r=>r.id==='minecraft:snowball').maxStack===16,'stack limits not available in UI');check(materialReport.contents.find(r=>r.id==='minecraft:dirt').boxes===undefined,'small quantity displays boxes');
   $('materialSearch').value='diamond_sword';$('materialSearch').dispatchEvent(new Event('input'));check($('containerMaterials').querySelectorAll('tbody tr').length===1,'search did not filter items');
   $('materialSearch').value='';drawMaterials();
   check(!$('materialDialog').textContent.includes('每组'),'UI labels per-stack limits');
   const rawWater=materialReport.building.find(r=>r.id==='minecraft:water_bucket').count,rawIce=materialReport.building.find(r=>r.id==='minecraft:ice').count;
   check(materialReport.building.find(r=>r.id==='minecraft:frosted_ice').maxStack===64,'frosted ice estimate is missing');
   check($('buildingMaterials').querySelectorAll('img').length===materialReport.building.length,'building icons are missing');
   const firstIcon=$('buildingMaterials').querySelector('img');await until(()=>firstIcon.complete&&firstIcon.naturalWidth===40);
   for(const enabled of [true,false,true]){
    $('materialConvertWater').click();check($('materialExport').disabled,'export was not blocked during conversion');await until(()=>materialWaterAsIce===enabled&&!$('materialConvertWater').disabled);
    if(enabled){check(!materialReport.building.some(r=>r.id==='minecraft:water_bucket'),'water buckets not replaced');check(materialReport.building.find(r=>r.id==='minecraft:ice').count===rawWater+rawIce,'ice was not merged/recomputed');}
    else check(materialReport.building.find(r=>r.id==='minecraft:water_bucket').count===rawWater,'restoring water changed original quantity');
    check(materialReport.contents.find(r=>r.id==='minecraft:water_bucket').count===2,'saved container water was converted');
   }
   for(const [scope,format] of [['combined','xlsx'],['contents','csv'],['separate','xlsx'],['building','csv']]){
    $('materialScope').value=scope;$('materialFormat').value=format;$('materialExport').click();await until(()=>$('materialStatus').textContent.startsWith('已导出：'));check(!$('materialExport').disabled,'export control not restored');
   }
   $('materialScope').value='combined';$('materialFormat').value='xlsx';await new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)));
   return {pass:true,building:materialReport.building.length,contents:materialReport.contents.length,waterAsIce:materialWaterAsIce,rawWater,convertedIce:materialReport.building.find(r=>r.id==='minecraft:ice').count,status:$('materialStatus').textContent,warnings:materialReport.warnings};
  })()`);
  if(choices[0].filters[0].extensions[0]!=='xlsx')throw Error('save dialog did not default to XLSX');for(const file of ['export-1.xlsx','export-2.csv','export-3-建材.xlsx','export-3-容器内容物.xlsx','export-4.csv'])if(!fs.existsSync(path.join(out,file)))throw Error('export file missing: '+file);
  fs.writeFileSync(path.join(out,'materials.png'),(await win.webContents.capturePage()).toPNG());fs.writeFileSync(path.join(out,'result.json'),JSON.stringify({...result,choices},null,2));console.log(JSON.stringify(result));app.quit();
 }catch(error){console.error(error.stack);fs.writeFileSync(path.join(out,'result.json'),JSON.stringify({pass:false,error:error.stack}));app.exit(1);}
});});require(path.join(root,'webapp/main.js'));setTimeout(()=>app.exit(1),180000).unref();
