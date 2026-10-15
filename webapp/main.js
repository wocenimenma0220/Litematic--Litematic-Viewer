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
const { clearRenderingCache } = require('./cache-cleanup');

/** The project root (…/litematic-render): one level above this file's directory. */
const APP_HOME = path.resolve(__dirname, '..');
const uiSettings = new UiSettings(APP_HOME);
const sceneTransfer = new SceneTransfer();
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
  mainWindow.once('ready-to-show', () => mainWindow.show());
  mainWindow.webContents.on('render-process-gone', (_event, details) => diagnostic('preview process exited: ' + JSON.stringify(details)));
  mainWindow.webContents.once('destroyed', () => { sceneTransfer.close().catch(() => {}); });
  mainWindow.on('enter-full-screen', () => mainWindow.webContents.send('window:fullscreen', true));
  mainWindow.on('leave-full-screen', () => mainWindow.webContents.send('window:fullscreen', false));
  mainWindow.loadFile(path.join(__dirname, 'renderer', 'index.html'));
  if (process.argv.includes('--dev')) mainWindow.webContents.openDevTools({ mode: 'detach' });
}

/** Starts the Java engine, resolving the bundled runtime first so no system Java is required. */
function startEngine() {
  const runtime = resolveRuntime(APP_HOME);
  engine = new EngineClient({
    javaExe: runtime.javaExe,
    classpath: runtime.classpath,
    home: APP_HOME,
    onEvent: (evt) => {
      if (mainWindow && !mainWindow.isDestroyed()) {
        mainWindow.webContents.send('engine:event', evt);
      }
    },
    onExit: (info) => {
      diagnostic('render engine exited: '+JSON.stringify(info));
      if (mainWindow && !mainWindow.isDestroyed()) {
        mainWindow.webContents.send('engine:exit', info);
      }
    },
  });
  return engine.start();
}

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

ipcMain.handle('engine:ping', () => engine.call('ping', {}));
ipcMain.handle('engine:loadResources', async (_e, root) => engine.call('loadResources', {
  ...(root ? { root } : {}), imports: await packStore.read(),
}));
ipcMain.handle('engine:open', async (_e, file) => {
  diagnostic('opening schematic: '+file);
  try {await sceneTransfer.close();await engine.call('cancelPreview',{});const result=await engine.call('open',{path:file});diagnostic('schematic opened: '+file);return result;}
  catch(error){diagnostic('schematic open failed: '+file+'; '+error.message);throw error;}
});
ipcMain.handle('engine:render', async (_e, params) => engine.call('render', await uiSettings.renderParams(params)));
ipcMain.handle('engine:renderPreview', async (_e, params) => engine.call('renderPreview', await uiSettings.renderParams(params)));
ipcMain.handle('engine:renderAnimation', async (_e, params) => engine.call('renderAnimation', await uiSettings.renderParams(params)));
ipcMain.handle('engine:cancel', () => engine.call('cancel', {}));
ipcMain.handle('engine:releaseStreaming', async () => {await sceneTransfer.close();await engine.call('cancelPreview',{});return engine.call('releaseStreaming', {});});
ipcMain.handle('engine:materials',(_event,request)=>engine.call('materials',{waterAsIce:request?.waterAsIce===true}));
ipcMain.handle('engine:exportMaterials',(_event,request)=>engine.call('exportMaterials',request));
ipcMain.handle('engine:sceneCacheStats',()=>engine.call('sceneCacheStats',{}));
ipcMain.handle('engine:configureSceneCache',(_event,request)=>engine.call('configureSceneCache',request));
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
ipcMain.handle('engine:pickBlock', (_e, ray) => engine.call('pickBlock', ray));

ipcMain.handle('ui:settings', () => uiSettings.settings());
ipcMain.handle('ui:previewBackend', async (_event, backend) => {
  if (backend !== 'cpu' && backend !== 'gpu') throw new Error('未知的预览渲染方式。');
  await uiSettings.save({ previewBackend: backend });
  return { previewBackend: backend };
});
for(const key of ['largeProjection','lightingEnabled','showCoordinates','clearCacheOnExit']) {
  ipcMain.handle('ui:'+key,async(_event,enabled)=>{await uiSettings.save({[key]:enabled===true});return {[key]:enabled===true};});
}
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

ipcMain.handle('skins:settings', () => engine.call('skinSettings', {}));
ipcMain.handle('skins:setOnline', (_event, online) => engine.call('setSkinOnline', { online: !!online }));
ipcMain.handle('skins:importLocal', async (_event, identity) => {
  if (typeof identity !== 'string' || !/^(?:[A-Za-z0-9_]{1,16}|[0-9a-fA-F]{32}|[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12})$/.test(identity.trim())) {
    throw new Error('请先填写玩家名或 UUID');
  }
  const choice = await dialog.showOpenDialog(mainWindow, {
    title: '导入玩家皮肤 PNG', filters: [{ name: 'Minecraft 皮肤 PNG', extensions: ['png'] }], properties: ['openFile'],
  });
  if (choice.canceled) return { imported: false };
  return engine.call('importSkin', { identity: identity.trim(), path: choice.filePaths[0] });
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
ipcMain.handle('engine:readScenePage',(event,request={})=>sceneTransfer.readPage(event.sender.id,request.id,request.page));
ipcMain.handle('engine:beginPreview',async (_event,options={})=>{await sceneTransfer.close();return engine.call('beginPreview',options);});
ipcMain.handle('engine:previewStatus',async(event,request)=>{
  const query=typeof request==='string'?{id:request}:request;
  const status=await engine.call('previewStatus',query);sceneTransfer.commit(event.sender.id,query.id,status.committedBytes);return status;
});
ipcMain.handle('engine:previewLive',async(event,id)=>scenePayload(event,await engine.call('previewLive',{id}),{retain:true,growing:true,key:id}));
ipcMain.handle('engine:cancelPreview',async()=>{await sceneTransfer.close();return engine.call('cancelPreview',{});});
ipcMain.handle('engine:previewResult',async(event,request)=>scenePayload(event,await engine.call('previewResult',request)));
ipcMain.handle('engine:previewComplete',(_event,id)=>engine.call('previewResult',{id,overview:false}));
ipcMain.handle('engine:exportScene', async (event, options = {}) => {
  const dir = path.join(APP_HOME, 'renders', '.gpu');
  await fs.promises.mkdir(dir, { recursive: true });
  await sceneTransfer.close();
  diagnostic('building preview scene');
  await engine.call('cancelPreview',{});
  const res = await engine.call(options.streaming===true?'exportCachedScene':'exportScene', {
    atlas: path.join(dir, 'atlas.png'),
    mesh: path.join(dir, 'mesh.bin'),
    force: options.force === true,
    realLighting: options.realLighting === true,
    lightingEnabled: options.lightingEnabled !== false,
    streaming: options.streaming === true,
  });
  return scenePayload(event,res);
});
async function scenePayload(event,res,options={}) {
  const atlasBuffer = await fs.promises.readFile(res.atlas);
  const meshBytes = (await fs.promises.stat(res.mesh)).size;
  diagnostic(options.growing?'progressive detail stream opened: '+res.committedBytes+' committed bytes':'scene exported: ' + meshBytes + ' bytes, ' + res.quads + ' faces');
  let payload;
  if (['LMESH5','LMESH6'].includes(res.meshFormat) || meshBytes > INLINE_LIMIT) {
    payload = { meshTransfer: await sceneTransfer.open(res.mesh, event.sender.id,{...options,committedBytes:res.committedBytes}) };
    diagnostic('using chunked scene transfer');
  } else {
    const meshBuffer = await fs.promises.readFile(res.mesh);
    payload = { meshArrayBuffer: meshBuffer.buffer.slice(meshBuffer.byteOffset, meshBuffer.byteOffset + meshBuffer.byteLength) };
  }
  return {
    ...res,
    ...payload,
    atlasDataUrl: 'data:image/png;base64,' + atlasBuffer.toString('base64'),
  };
}
ipcMain.handle('engine:previewDiagnostic', (_event, message) => { diagnostic(String(message).slice(0, 1500)); });

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
      if(engine)await engine.stop();
      await uiSettings.queue;
      if((await uiSettings.read()).clearCacheOnExit){await clearRenderingCache(APP_HOME);diagnostic('render cache cleared on exit');}
    }catch(error){diagnostic('exit cleanup failed: '+(error.stack||error.message));}
    finally{shutdownComplete=true;app.quit();}
  })();
});
