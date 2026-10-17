'use strict';

/**
 * Electron main process.
 *
 * Owns the window and one long-lived Java render-engine child process. The engine is driven over
 * line-based JSON-RPC on stdin/stdout; a request queue with a single worker keeps the engine's
 * single "current render" state consistent and gives the UI clean progress/cancel semantics.
 */

const { app, BrowserWindow, ipcMain, dialog, shell } = require('electron');
const path = require('node:path');
const fs = require('node:fs');
const { EngineClient } = require('./engine-client');
const { resolveRuntime } = require('./runtime');
const { UiSettings, AUTHOR_URL } = require('./ui-settings');
const { SceneTransfer, INLINE_LIMIT } = require('./scene-transfer');
const { clearRenderingCache,clearInterruptedCache } = require('./cache-cleanup');
const {BackgroundExports}=require('./background-exports');

/** The project root (…/litematic-render): one level above this file's directory. */
const APP_HOME = path.resolve(__dirname, '..');
const uiSettings = new UiSettings(APP_HOME);
const sceneTransfer = new SceneTransfer();
const selectionTransfer = new SceneTransfer();

function diagnostic(message) {
  try {
    fs.mkdirSync(path.join(APP_HOME, 'logs'), { recursive: true });
    const file = path.join(APP_HOME, 'logs', 'app.log');
    if (fs.existsSync(file) && fs.statSync(file).size > 2 * 1024 * 1024) {
      fs.copyFileSync(file, file + '.previous');fs.truncateSync(file, 0);
    }
    fs.appendFileSync(file, new Date().toISOString() + ' ' + message + '\n', 'utf8');
  } catch { /* diagnostics must not interrupt loading */ }
}
app.setName('投影预览');
app.setAppUserModelId('io.kindasus.projectionpreview');

let mainWindow = null;
let engine = null;
let renderStopPromise = null;
let openedSchematic=null,resourceRoot=null,pendingEdits=false;
const backgroundExports=new BackgroundExports({home:APP_HOME,runtime:resolveRuntime(APP_HOME),onUpdate:job=>{
  if(mainWindow&&!mainWindow.isDestroyed())mainWindow.webContents.send('exports:update',job);
}});
let shutdownPromise = null;
let shutdownComplete = false;

let startupFile = null;

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1360,
    height: 900,
    minWidth: 980,
    minHeight: 660,
    backgroundColor: '#14161a',
    show: false,
    title: '投影预览',
    icon: path.join(__dirname, 'app.ico'),
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
    },
  });
  // The app has its own File / Keys / Commands bar. A hidden native menu still
  // takes focus on Alt release and interrupts pointer-locked selection tools.
  mainWindow.setMenu(null);
  mainWindow.once('ready-to-show', () => mainWindow.show());
  mainWindow.webContents.on('render-process-gone', (_event, details) => diagnostic('preview process exited: ' + JSON.stringify(details)));
  mainWindow.webContents.once('destroyed', () => { sceneTransfer.close().catch(() => {});selectionTransfer.close().catch(()=>{}); });
  mainWindow.on('enter-full-screen', () => mainWindow.webContents.send('window:fullscreen', true));
  mainWindow.on('leave-full-screen', () => mainWindow.webContents.send('window:fullscreen', false));
  mainWindow.loadFile(path.join(__dirname, 'renderer', 'index.html'));
  if (process.argv.includes('--dev')) mainWindow.webContents.openDevTools({ mode: 'detach' });
}

/** Starts the Java engine, resolving the bundled runtime first so no system Java is required. */
function startEngine() {
  const runtime = resolveRuntime(APP_HOME);
  const client = new EngineClient({
    javaExe: runtime.javaExe,
    classpath: runtime.classpath,
    home: APP_HOME,
    onEvent: (evt) => {
      if (engine !== client) return;
      if (mainWindow && !mainWindow.isDestroyed()) {
        mainWindow.webContents.send('engine:event', evt);
      }
    },
    onExit: (info) => {
      diagnostic('render engine exited: '+JSON.stringify(info));
      if (engine !== client || info.forced) return;
      if (mainWindow && !mainWindow.isDestroyed()) {
        mainWindow.webContents.send('engine:exit', info);
      }
    },
  });
  engine = client;
  return client.start();
}

function requireCurrentEngine(client) {
  if (!client || client !== engine || client.stopping || shutdownPromise) throw Error('渲染已强制停止');
}
// Capture the engine before any await: an old request must never reach its replacement.
function handleEngine(channel, handler) {
  ipcMain.handle(channel, (event, ...args) => {
    const client = engine;
    requireCurrentEngine(client);
    if (renderStopPromise) throw Error('正在停止渲染，请稍候');
    return handler(client, event, ...args);
  });
}
async function loadEngineResources(client, root) {
  const imports = await packStore.read();
  requireCurrentEngine(client);
  const result = await client.call('loadResources', { ...(root ? { root } : {}), imports });
  requireCurrentEngine(client);
  client.resourcesLoaded = true;
  resourceRoot = root || null;
  return result;
}

ipcMain.handle('engine:forceStop', () => {
  if (renderStopPromise) return renderStopPromise;
  const previous = engine;
  const previousPid=previous?.proc?.pid;
  openedSchematic = null;pendingEdits=false;
  // Kill first, independently of outstanding RPC calls and file transfers.
  const stopped = previous ? previous.forceStop() : Promise.resolve();
  renderStopPromise = (async () => {
    await Promise.all([stopped, sceneTransfer.close(), selectionTransfer.close()]);
    try{await clearInterruptedCache(APP_HOME,previousPid,previous?.temporaryCaches);}catch(error){diagnostic('interrupted cache cleanup failed: '+error.message);}
    if (!shutdownPromise) await startEngine();
    diagnostic('preview force-stopped; replacement engine ready');
    return { stopped: true };
  })().finally(() => { renderStopPromise = null; });
  return renderStopPromise;
});

// ---------------------------------------------------------------------------
// IPC surface

ipcMain.handle('engine:status', async () => {
  const runtime = resolveRuntime(APP_HOME);
  return {
    home: APP_HOME,
    java: runtime.javaExe,
    javaSource: runtime.source,
    running: engine ? engine.isRunning() : false,
  };
});

handleEngine('engine:ping', client => client.call('ping', {}));
handleEngine('engine:selectBlock',(client,_event,ray)=>client.call('selectBlock',ray));
handleEngine('engine:loadResources', (client, _e, root) => loadEngineResources(client, root));
handleEngine('engine:open', async (client, _e, file) => {
  if(typeof file!=='string'||path.extname(file).toLowerCase()!=='.litematic'||!(await fs.promises.stat(file)).isFile())throw Error('请选择 .litematic 投影文件');
  requireCurrentEngine(client);
  if(!client.resourcesLoaded)await loadEngineResources(client,resourceRoot);
  diagnostic('opening schematic: '+file);
  try {await sceneTransfer.close();requireCurrentEngine(client);await selectionTransfer.close();await client.call('cancelPreview',{});const result=await client.call('open',{path:file});requireCurrentEngine(client);openedSchematic=file;pendingEdits=false;diagnostic('schematic opened: '+file);return result;}
  catch(error){diagnostic('schematic open failed: '+file+'; '+error.message);throw error;}
});
handleEngine('engine:render', async (client, _e, params) => client.call('render', await uiSettings.renderParams(params)));
handleEngine('engine:renderPreview', async (client, _e, params) => client.call('renderPreview', await uiSettings.renderParams(params)));
handleEngine('engine:renderAnimation', async (client, _e, params) => client.call('renderAnimation', await uiSettings.renderParams(params)));
ipcMain.handle('exports:start',async(_event,{kind,params})=>{
  if(!openedSchematic)throw Error('请先打开投影');
  const committed=await engine.call('commitEdits',{});if(committed.changed)openedSchematic=committed.path;pendingEdits=false;
  const source=openedSchematic;
  return backgroundExports.submit({kind,source,params:await uiSettings.renderParams(params),resources:{...(resourceRoot?{root:resourceRoot}:{}),imports:await packStore.read()}});
});
ipcMain.handle('exports:list',()=>backgroundExports.list());
ipcMain.handle('exports:cancel',(_event,id)=>backgroundExports.cancel(id));
handleEngine('engine:cancel', client => client.call('cancel', {}));
handleEngine('engine:releaseStreaming', async client => {await sceneTransfer.close();await client.call('cancelPreview',{});return client.call('releaseStreaming', {});});
handleEngine('engine:materials',(client,_event,request)=>client.call('materials',{waterAsIce:request?.waterAsIce===true}));
handleEngine('engine:exportMaterials',(client,_event,request)=>client.call('exportMaterials',request));
handleEngine('engine:sceneCacheStats',client=>client.call('sceneCacheStats',{}));
handleEngine('engine:configureSceneCache',(client,_event,request)=>client.call('configureSceneCache',request));
ipcMain.handle('dialog:saveMaterials',async(_event,{suggested,format,scope})=>{
  format=format==='csv'?'csv':'xlsx';
  const res=await dialog.showSaveDialog(mainWindow,{defaultPath:suggested||('材料清单.'+format),filters:[{name:format==='csv'?'CSV 表格':'Excel 工作簿',extensions:[format]}]});
  if(res.canceled)return null;
  let file=res.filePath;if(!/\.(xlsx|csv)$/i.test(file))file+='.'+format;
  if(scope==='separate'){
    const extension=path.extname(file),stem=file.slice(0,-extension.length);
    const existing=['-建材','-容器内容物'].map(suffix=>stem+suffix+extension).filter(p=>fs.existsSync(p));
    if(existing.length){const choice=await dialog.showMessageBox(mainWindow,{type:'question',buttons:['取消','替换'],defaultId:0,cancelId:0,message:'替换已有材料表？',detail:existing.join('\n')});if(choice.response!==1)return null;}
  }
  return file;
});
handleEngine('engine:pickBlock', (client, _e, ray) => client.call('pickBlock', ray));

ipcMain.handle('ui:settings', () => uiSettings.settings());
ipcMain.handle('ui:editorInventory',async(_event,inventory)=>{
  const {cleanInventory}=require('./renderer/editor-inventory');
  const editorInventory=cleanInventory(inventory);await uiSettings.save({editorInventory});return {editorInventory};
});
ipcMain.handle('ui:keybindings',async(_event,bindings)=>{
  const {cleanShortcutBindings}=require('./renderer/keyboard-shortcuts');
  const keybindings=cleanShortcutBindings(bindings);await uiSettings.save({keybindings});return {keybindings};
});
ipcMain.handle('ui:previewBackend', async (_event, backend) => {
  if (backend !== 'cpu' && backend !== 'gpu') throw new Error('未知的预览渲染方式。');
  await uiSettings.save({ previewBackend: backend });
  return { previewBackend: backend };
});
for(const key of ['largeProjection','lightingEnabled','showCoordinates','clearCacheOnExit','consoleHidden']) {
  ipcMain.handle('ui:'+key,async(_event,enabled)=>{await uiSettings.save({[key]:enabled===true,...(key==='largeProjection'?{projectionMode:enabled?'streaming':'full'}:{})});return {[key]:enabled===true};});
}
ipcMain.handle('ui:projectionMode',async(_event,mode)=>{
  if(!['auto','full','streaming'].includes(mode))throw Error('无效的加载方式');
  await uiSettings.save({projectionMode:mode});return {projectionMode:mode};
});
ipcMain.handle('ui:realLighting', async (_event, enabled) => {
  await uiSettings.save({ realLighting: enabled === true });
  return { realLighting: enabled === true };
});
ipcMain.handle('ui:lodPreview', async (_event, enabled) => {
  if(typeof enabled!=='boolean')throw new Error('无效的预览设置');
  await uiSettings.save({lodPreview:enabled});return {lodPreview:enabled};
});
ipcMain.handle('ui:viewDistance',async(_event,distance)=>{
  if(!Number.isInteger(distance)||distance<1||distance>128)throw new Error('视距需为 1 到 128 区块。');
  await uiSettings.save({viewDistance:distance});return {viewDistance:distance};
});
ipcMain.handle('ui:geometryBudget',async(_event,megabytes)=>{
  if(!Number.isInteger(megabytes)||megabytes<32||megabytes>16384)throw new Error('几何缓存上限应为 32–16384 MB 的整数');
  await uiSettings.save({geometryBudgetMB:megabytes});return {geometryBudgetMB:megabytes};
});
ipcMain.handle('ui:preloadMode',async(_event,mode)=>{
  if(!['distance','full','streaming'].includes(mode))throw new Error('未知的预渲染方式');
  await uiSettings.save({preloadMode:mode});return {preloadMode:mode};
});
ipcMain.handle('ui:sidebar', async (_e, collapsed) => {
  await uiSettings.save({ sidebarCollapsed: collapsed === true });
  return { collapsed: collapsed === true };
});
ipcMain.handle('ui:dismissWelcome', async (_e, forever) => {
  if (forever === true) await uiSettings.save({ hideWelcome: true });
  return { dismissed: true };
});
ipcMain.handle('ui:authorLink', () => shell.openExternal(AUTHOR_URL));
ipcMain.handle('ui:backgroundMode', (_e, mode, color) => uiSettings.setBackgroundMode(mode, color));
ipcMain.handle('ui:importBackground', async () => {
  const choice = await dialog.showOpenDialog(mainWindow, {
    title: '导入背景图片（10 MB 以内）',
    filters: [{ name: '背景图片', extensions: ['png', 'jpg', 'jpeg'] }], properties: ['openFile'],
  });
  if (choice.canceled) return null;
  return uiSettings.importBackground(choice.filePaths[0]);
});
ipcMain.handle('window:fullscreen', (_e, enabled) => {
  const fullscreen = typeof enabled === 'boolean' ? enabled : !mainWindow.isFullScreen();
  mainWindow.setFullScreen(fullscreen);
  return { fullscreen };
});

handleEngine('skins:settings', client => client.call('skinSettings', {}));
handleEngine('skins:setOnline', (client, _event, online) => client.call('setSkinOnline', { online: !!online }));
handleEngine('skins:importLocal', async (client, _event, identity) => {
  if (typeof identity !== 'string' || !/^(?:[A-Za-z0-9_]{1,16}|[0-9a-fA-F]{32}|[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12})$/.test(identity.trim())) {
    throw new Error('请先填写玩家名或 UUID');
  }
  const choice = await dialog.showOpenDialog(mainWindow, {
    title: '导入玩家皮肤 PNG', filters: [{ name: 'Minecraft 皮肤 PNG', extensions: ['png'] }], properties: ['openFile'],
  });
  if (choice.canceled) return { imported: false };
  return client.call('importSkin', { identity: identity.trim(), path: choice.filePaths[0] });
});

/**
 * Bakes the scene once and returns what the WebGL viewer needs: the atlas as a data URL and the
 * mesh as an ArrayBuffer.
 *
 * <p>The bytes travel over IPC rather than as {@code file://} URLs, because a file-backed page may
 * not fetch other file URLs. Keeping the mesh binary matters — a data URL would inflate 30 MB of
 * vertices to roughly 40 MB of text.
 */
ipcMain.handle('engine:readSceneChunk', (event, request = {}) => sceneTransfer.read(event.sender.id, request.id, request.offset, request.length));
ipcMain.handle('engine:readSelectionChunk',(event,request={})=>selectionTransfer.read(event.sender.id,request.id,request.offset,request.length));
ipcMain.handle('engine:releaseSelection',()=>selectionTransfer.close());
handleEngine('engine:exportSelectionScene',async(client,event,options={})=>{
  const dir=path.join(APP_HOME,'renders','.gpu','selection');await fs.promises.mkdir(dir,{recursive:true});requireCurrentEngine(client);await selectionTransfer.close();
  const result=await client.call('exportSelectionScene',{selection:options.selection,realLighting:options.realLighting===true,lightingEnabled:options.lightingEnabled!==false,atlas:path.join(dir,'atlas.png'),mesh:path.join(dir,'mesh.bin')});
  return scenePayload(client,event,result,{transfer:selectionTransfer});
});
ipcMain.handle('engine:readScenePage',(event,request={})=>sceneTransfer.readPage(event.sender.id,request.id,request.page));
ipcMain.handle('engine:readScenePages',(event,request={})=>sceneTransfer.readPages(event.sender.id,request.id,request.pages));
handleEngine('engine:beginPreview',async (client,_event,options={})=>{await sceneTransfer.close();return client.call('beginPreview',options);});
handleEngine('engine:previewStatus',async(client,event,request)=>{
  const query=typeof request==='string'?{id:request}:request;
  const status=await client.call('previewStatus',query);requireCurrentEngine(client);sceneTransfer.commit(event.sender.id,query.id,status.committedBytes);return status;
});
handleEngine('engine:previewLive',async(client,event,id)=>scenePayload(client,event,await client.call('previewLive',{id}),{retain:true,growing:true,key:id}));
handleEngine('engine:cancelPreview',async client=>{await sceneTransfer.close();return client.call('cancelPreview',{});});
handleEngine('engine:previewResult',async(client,event,request)=>scenePayload(client,event,await client.call('previewResult',request)));
handleEngine('engine:previewComplete',(client,_event,id)=>client.call('previewResult',{id,overview:false}));
handleEngine('engine:exportScene', async (client, event, options = {}) => {
  const dir = path.join(APP_HOME, 'renders', '.gpu');
  await fs.promises.mkdir(dir, { recursive: true });
  requireCurrentEngine(client);
  await sceneTransfer.close();
  diagnostic('building preview scene');
  await client.call('cancelPreview',{});
  const res = await client.call(options.streaming===true?'exportCachedScene':'exportScene', {
    atlas: path.join(dir, 'atlas.png'),
    mesh: path.join(dir, 'mesh.bin'),
    force: options.force === true,
    realLighting: options.realLighting === true,
    lightingEnabled: options.lightingEnabled !== false,
    streaming: options.streaming === true,
  });
  return scenePayload(client,event,res);
});
async function scenePayload(client,event,res,options={}) {
  requireCurrentEngine(client);
  const atlasBuffer = await fs.promises.readFile(res.atlas);
  const meshBytes = (await fs.promises.stat(res.mesh)).size;
  requireCurrentEngine(client);
  diagnostic(options.growing?'progressive detail stream opened: '+res.committedBytes+' committed bytes':'scene exported: ' + meshBytes + ' bytes, ' + res.quads + ' faces');
  let payload;
  if (['LMESH5','LMESH6','LMESH7'].includes(res.meshFormat) || meshBytes > INLINE_LIMIT) {
    payload = { meshTransfer: await (options.transfer||sceneTransfer).open(res.mesh, event.sender.id,{...options,committedBytes:res.committedBytes}) };
    diagnostic('using chunked scene transfer');
  } else {
    const meshBuffer = await fs.promises.readFile(res.mesh);
    payload = { meshArrayBuffer: meshBuffer.buffer.slice(meshBuffer.byteOffset, meshBuffer.byteOffset + meshBuffer.byteLength) };
  }
  requireCurrentEngine(client);
  return {
    ...res,
    ...payload,
    atlasDataUrl: 'data:image/png;base64,' + atlasBuffer.toString('base64'),
  };
}
ipcMain.handle('engine:previewDiagnostic', (_event, message) => { diagnostic(String(message).slice(0, 1500)); });

handleEngine('engine:searchBlocks',(client,_event,request)=>client.call('searchBlocks',request));
handleEngine('engine:editPick',(client,_event,request)=>client.call('editPick',request));
handleEngine('engine:editBlock',async(client,_event,request)=>{const result=await client.call('editBlock',request);requireCurrentEngine(client);if(result.changed)pendingEdits=true;return result;});
handleEngine('engine:editStates',(client,_event,positions)=>client.call('editStates',{positions}));
handleEngine('engine:inspectPosition',(client,_event,position)=>client.call('inspectPosition',{position}));
handleEngine('engine:commitEdits',async client=>{const result=await client.call('commitEdits',{});requireCurrentEngine(client);if(result.changed)openedSchematic=result.path;pendingEdits=false;return result;});
handleEngine('engine:editPatch',async(client,_event,options={})=>{
  const result=await client.call('editPatch',{directory:path.join(APP_HOME,'renders','.edit','preview'),selection:options.selection});requireCurrentEngine(client);if(result.empty)return result;
  const buffer=await fs.promises.readFile(result.mesh),atlas=await fs.promises.readFile(result.atlas),selected=result.selectionMesh?await fs.promises.readFile(result.selectionMesh):null;
  return {cells:result.cells,revision:result.revision,quads:result.quads,meshArrayBuffer:buffer.buffer.slice(buffer.byteOffset,buffer.byteOffset+buffer.byteLength),selectionArrayBuffer:selected?selected.buffer.slice(selected.byteOffset,selected.byteOffset+selected.byteLength):null,atlasDataUrl:'data:image/png;base64,'+atlas.toString('base64')};
});
handleEngine('engine:exportEdited',async(client,_event,request={})=>{
  const result=await dialog.showSaveDialog(mainWindow,{title:'导出修改后的投影',defaultPath:request.suggested||'更改投影.litematic',filters:[{name:'Litematica 投影',extensions:['litematic']}]});
  if(result.canceled||!result.filePath)return null;requireCurrentEngine(client);
  const output=/\.litematic$/i.test(result.filePath)?result.filePath:result.filePath+'.litematic';
  return client.call('exportEdited',{out:output});
});

// --- resource pack management ------------------------------------------------

/**
 * User-imported pack paths, persisted so they survive restarts. The engine loads vanilla, then the
 * built-in packs, then these — in this order — so an import can override a built-in.
 */
const packStore = {
  file: () => path.join(APP_HOME, 'renders', 'packs.json'),
  removedFile: () => path.join(APP_HOME, 'settings', 'removed-builtins.json'),
  async removed() {
    try {
      const parsed = JSON.parse(await fs.promises.readFile(this.removedFile(), 'utf8'));
      return Array.isArray(parsed) ? parsed.filter(p => typeof p === 'string') : [];
    } catch { return []; }
  },
  async writeRemoved(list) {
    const file = this.removedFile();
    await fs.promises.mkdir(path.dirname(file), { recursive: true });
    await fs.promises.writeFile(file + '.tmp', JSON.stringify(list, null, 2), 'utf8');
    await fs.promises.rename(file + '.tmp', file);
  },
  async read() {
    try {
      const parsed = JSON.parse(await fs.promises.readFile(this.file(), 'utf8'));
      return Array.isArray(parsed) ? parsed : [];
    } catch {
      return [];
    }
  },
  async write(list) {
    await fs.promises.mkdir(path.dirname(this.file()), { recursive: true });
    await fs.promises.writeFile(this.file(), JSON.stringify(list, null, 2), 'utf8');
  },
};

ipcMain.handle('packs:list', () => packStore.read());

ipcMain.handle('packs:import', async (_e, file) => {
  const name = path.basename(file);
  if (path.resolve(file) === path.resolve(APP_HOME, 'assets', 'builtin', name)) {
    await packStore.writeRemoved((await packStore.removed()).filter(p => p !== name));
    return packStore.read();
  }
  const list = await packStore.read();
  if (!list.includes(file)) list.push(file);
  await packStore.write(list);
  return list;
});

ipcMain.handle('packs:removeBuiltin', async (_e, name) => {
  if (typeof name !== 'string' || path.basename(name) !== name || !name.toLowerCase().endsWith('.zip')) {
    throw new Error('无法删除这个内置材质包');
  }
  const file = path.join(APP_HOME, 'assets', 'builtin', name);
  if (!(await fs.promises.stat(file)).isFile()) throw new Error('内置材质包不存在');
  const removed = await packStore.removed();
  if (!removed.includes(name)) removed.push(name);
  await packStore.writeRemoved(removed);
});

ipcMain.handle('packs:remove', async (_e, path) => {
  const list = (await packStore.read()).filter((p) => p !== path);
  await packStore.write(list);
  return list;
});

ipcMain.handle('packs:move', async (_e, { path, direction }) => {
  const list = await packStore.read();
  const i = list.indexOf(path);
  const j = direction === 'up' ? i - 1 : i + 1;
  if (i >= 0 && j >= 0 && j < list.length) {
    list.splice(i, 1);
    list.splice(j, 0, path);
    await packStore.write(list);
  }
  return list;
});

ipcMain.handle('dialog:openSchematic', async () => {
  const res = await dialog.showOpenDialog(mainWindow, {
    title: '打开 .litematic 投影文件',
    filters: [
      { name: 'Litematica 投影', extensions: ['litematic'] },
      { name: '所有文件', extensions: ['*'] },
    ],
    properties: ['openFile'],
  });
  return res.canceled ? null : res.filePaths[0];
});

ipcMain.handle('dialog:savePng', async (_e, suggested) => {
  const res = await dialog.showSaveDialog(mainWindow, {
    title: '导出 PNG',
    defaultPath: suggested || 'render.png',
    filters: [{ name: 'PNG 图片', extensions: ['png'] }],
  });
  return res.canceled ? null : res.filePath;
});

ipcMain.handle('dialog:saveGif', async (_e, suggested) => {
  const res = await dialog.showSaveDialog(mainWindow, {
    title: '导出旋转 GIF',
    defaultPath: suggested || 'animation.gif',
    filters: [{ name: 'GIF 动画', extensions: ['gif'] }],
  });
  if (res.canceled) return null;
  return /\.gif$/i.test(res.filePath) ? res.filePath : res.filePath + '.gif';
});

ipcMain.handle('dialog:openPack', async (_event, kind) => {
  const folder=kind==='folder';
  const res = await dialog.showOpenDialog(mainWindow, {
    title: folder?'导入材质包文件夹':'导入材质包压缩包（zip）',
    filters: [
      { name: '资源包', extensions: ['zip'] },
      { name: '所有文件', extensions: ['*'] },
    ],
    properties: [folder?'openDirectory':'openFile'],
  });
  return res.canceled ? null : res.filePaths[0];
});

ipcMain.handle('shell:reveal', (_e, target) => {
  if (target && fs.existsSync(target)) shell.showItemInFolder(target);
});

/** Writes base64 image data (used by "save as" from the preview). */
ipcMain.handle('file:writeBase64', async (_e, { file, base64 }) => {
  await fs.promises.writeFile(file, Buffer.from(base64, 'base64'));
  return { path: file, bytes: Buffer.from(base64, 'base64').length };
});

// ---------------------------------------------------------------------------

app.whenReady().then(async () => {
  createWindow();
  try {
    await startEngine();
  } catch (err) {
    if (mainWindow) {
      mainWindow.webContents.once('did-finish-load', () => {
        mainWindow.webContents.send('engine:exit', { error: String(err && err.message ? err.message : err) });
      });
    }
  }

  // A .litematic passed on the command line opens on launch, which is what makes the app usable as
  // a file handler (drag a schematic onto the exe, or "open with").
  const cliFile = process.argv.slice(1).find((a) => a.toLowerCase().endsWith('.litematic'));
  if (cliFile && mainWindow) {
    startupFile = path.resolve(cliFile);
    mainWindow.webContents.once('did-finish-load', () => {
      mainWindow.webContents.send('app:openFile', startupFile);
    });
  }

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});

app.on('before-quit', event => {
  if(shutdownComplete)return;
  event.preventDefault();
  if(!shutdownPromise)shutdownPromise=(async()=>{
    try {
      await sceneTransfer.close();
      await selectionTransfer.close();
      await backgroundExports.close();
      if(renderStopPromise)await renderStopPromise.catch(()=>{});
      if(pendingEdits&&engine?.isRunning()&&!renderStopPromise)await engine.call('commitEdits',{});
      if(engine)await engine.stop();
      await uiSettings.queue;
      if((await uiSettings.read()).clearCacheOnExit){await clearRenderingCache(APP_HOME);diagnostic('render cache cleared on exit');}
    }catch(error){diagnostic('exit cleanup failed: '+(error.stack||error.message));}
    finally{shutdownComplete=true;app.quit();}
  })();
});
