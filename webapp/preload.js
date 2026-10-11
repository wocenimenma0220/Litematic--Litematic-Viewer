'use strict';

/** Exposes a narrow, explicit API to the renderer; no Node objects cross the boundary. */

const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('engine', {
  status: () => ipcRenderer.invoke('engine:status'),
  ping: () => ipcRenderer.invoke('engine:ping'),
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
  exportScene: () => ipcRenderer.invoke('engine:exportScene'),
  cancel: () => ipcRenderer.invoke('engine:cancel'),
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
  move: (path, direction) => ipcRenderer.invoke('packs:move', { path, direction }),
});

contextBridge.exposeInMainWorld('shellApi', {
  openSchematic: () => ipcRenderer.invoke('dialog:openSchematic'),
  savePng: (suggested) => ipcRenderer.invoke('dialog:savePng', suggested),
  openPack: () => ipcRenderer.invoke('dialog:openPack'),
  reveal: (target) => ipcRenderer.invoke('shell:reveal', target),
  writeBase64: (file, base64) => ipcRenderer.invoke('file:writeBase64', { file, base64 }),
});
