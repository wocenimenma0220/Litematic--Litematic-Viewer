'use strict';

/** Exposes a narrow, explicit API to the renderer; no Node objects cross the boundary. */

const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('exportsApi',{
  start:(kind,params)=>ipcRenderer.invoke('exports:start',{kind,params}),
  list:()=>ipcRenderer.invoke('exports:list'),
  cancel:id=>ipcRenderer.invoke('exports:cancel',id),
  onUpdate:callback=>{const handler=(_event,job)=>callback(job);ipcRenderer.on('exports:update',handler);return()=>ipcRenderer.removeListener('exports:update',handler);},
});
contextBridge.exposeInMainWorld('appUi', {
  settings: () => ipcRenderer.invoke('ui:settings'),
  setKeybindings: bindings=>ipcRenderer.invoke('ui:keybindings',bindings),
  setPreviewBackend: (backend) => ipcRenderer.invoke('ui:previewBackend', backend),
  setLargeProjection: (enabled)=>ipcRenderer.invoke('ui:largeProjection',enabled),
  setProjectionMode: mode=>ipcRenderer.invoke('ui:projectionMode',mode),
  setLightingEnabled: (enabled)=>ipcRenderer.invoke('ui:lightingEnabled',enabled),
  setShowCoordinates: (enabled)=>ipcRenderer.invoke('ui:showCoordinates',enabled),
  setClearCacheOnExit: (enabled)=>ipcRenderer.invoke('ui:clearCacheOnExit',enabled),
  setRealLighting: (enabled) => ipcRenderer.invoke('ui:realLighting', enabled),
  setLodPreview: (enabled) => ipcRenderer.invoke('ui:lodPreview', enabled),
  setViewDistance: (distance) => ipcRenderer.invoke('ui:viewDistance', distance),
  setGeometryBudget: (megabytes) => ipcRenderer.invoke('ui:geometryBudget', megabytes),
  setPreloadMode: (mode) => ipcRenderer.invoke('ui:preloadMode', mode),
  setSidebarCollapsed: (collapsed) => ipcRenderer.invoke('ui:sidebar', collapsed),
  dismissWelcome: (forever) => ipcRenderer.invoke('ui:dismissWelcome', forever),
  openAuthorLink: () => ipcRenderer.invoke('ui:authorLink'),
  importBackground: () => ipcRenderer.invoke('ui:importBackground'),
  setBackgroundMode: (mode, color) => ipcRenderer.invoke('ui:backgroundMode', mode, color),
  fullscreen: (enabled) => ipcRenderer.invoke('window:fullscreen', enabled),
  onFullscreen: (cb) => {
    const handler = (_event, fullscreen) => cb(fullscreen);
    ipcRenderer.on('window:fullscreen', handler);
    return () => ipcRenderer.removeListener('window:fullscreen', handler);
  },
});

contextBridge.exposeInMainWorld('engine', {
  status: () => ipcRenderer.invoke('engine:status'),
  ping: () => ipcRenderer.invoke('engine:ping'),
  selectBlock: ray=>ipcRenderer.invoke('engine:selectBlock',ray),
  exportSelectionScene: options=>ipcRenderer.invoke('engine:exportSelectionScene',options),
  releaseSelection: ()=>ipcRenderer.invoke('engine:releaseSelection'),
  readSelectionChunk:(id,offset,length)=>ipcRenderer.invoke('engine:readSelectionChunk',{id,offset,length}),
  loadResources: (root) => ipcRenderer.invoke('engine:loadResources', root),
  open: (file) => ipcRenderer.invoke('engine:open', file),
  render: (params) => ipcRenderer.invoke('engine:render', params),
  renderPreview: (params) => ipcRenderer.invoke('engine:renderPreview', params),
  renderAnimation: (params) => ipcRenderer.invoke('engine:renderAnimation', params),
  onOpenFile: (cb) => {
    const h = (_e, file) => cb(file);
    ipcRenderer.on('app:openFile', h);
    return () => ipcRenderer.removeListener('app:openFile', h);
  },
  exportScene: (options = {}) => ipcRenderer.invoke('engine:exportScene', { force: options.force === true, realLighting: options.realLighting === true, streaming: options.streaming === true, lightingEnabled: options.lightingEnabled !== false }),
  releaseStreaming: () => ipcRenderer.invoke('engine:releaseStreaming'),
  beginPreview: (options)=>ipcRenderer.invoke('engine:beginPreview',options),
  previewStatus: (id,options={})=>ipcRenderer.invoke('engine:previewStatus',{id,...options}),
  previewLive: (id)=>ipcRenderer.invoke('engine:previewLive',id),
  previewComplete: (id)=>ipcRenderer.invoke('engine:previewComplete',id),
  previewResult: (id,overview)=>ipcRenderer.invoke('engine:previewResult',{id,overview}),
  cancelPreview: ()=>ipcRenderer.invoke('engine:cancelPreview'),
  readSceneChunk: (id, offset, length) => ipcRenderer.invoke('engine:readSceneChunk', { id, offset, length }),
  readScenePage: (id,page)=>ipcRenderer.invoke('engine:readScenePage',{id,page}),
  readScenePages: (id,pages)=>ipcRenderer.invoke('engine:readScenePages',{id,pages}),
  previewDiagnostic: (message) => ipcRenderer.invoke('engine:previewDiagnostic', message),
  cancel: () => ipcRenderer.invoke('engine:cancel'),
  materials: (request)=>ipcRenderer.invoke('engine:materials',request),
  exportMaterials: (request)=>ipcRenderer.invoke('engine:exportMaterials',request),
  sceneCacheStats: ()=>ipcRenderer.invoke('engine:sceneCacheStats'),
  configureSceneCache: (request)=>ipcRenderer.invoke('engine:configureSceneCache',request),
  pickBlock: (ray) => ipcRenderer.invoke('engine:pickBlock', ray),
  onEvent: (cb) => {
    const h = (_e, evt) => cb(evt);
    ipcRenderer.on('engine:event', h);
    return () => ipcRenderer.removeListener('engine:event', h);
  },
  onExit: (cb) => {
    const h = (_e, info) => cb(info);
    ipcRenderer.on('engine:exit', h);
    return () => ipcRenderer.removeListener('engine:exit', h);
  },
});

contextBridge.exposeInMainWorld('packs', {
  list: () => ipcRenderer.invoke('packs:list'),
  import: (path) => ipcRenderer.invoke('packs:import', path),
  remove: (path) => ipcRenderer.invoke('packs:remove', path),
  removeBuiltin: (name) => ipcRenderer.invoke('packs:removeBuiltin', name),
  move: (path, direction) => ipcRenderer.invoke('packs:move', { path, direction }),
});

contextBridge.exposeInMainWorld('skins', {
  settings: () => ipcRenderer.invoke('skins:settings'),
  setOnline: (online) => ipcRenderer.invoke('skins:setOnline', online),
  importLocal: (identity) => ipcRenderer.invoke('skins:importLocal', identity),
});

contextBridge.exposeInMainWorld('shellApi', {
  openSchematic: () => ipcRenderer.invoke('dialog:openSchematic'),
  saveMaterials: (request)=>ipcRenderer.invoke('dialog:saveMaterials',request),
  savePng: (suggested) => ipcRenderer.invoke('dialog:savePng', suggested),
  saveGif: (suggested) => ipcRenderer.invoke('dialog:saveGif', suggested),
  openPack: (kind) => ipcRenderer.invoke('dialog:openPack', kind),
  reveal: (target) => ipcRenderer.invoke('shell:reveal', target),
  writeBase64: (file, base64) => ipcRenderer.invoke('file:writeBase64', { file, base64 }),
});
