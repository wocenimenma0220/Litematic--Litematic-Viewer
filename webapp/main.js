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

/** The project root (…/litematic-render): one level above this file's directory. */
const APP_HOME = path.resolve(__dirname, '..');

let mainWindow = null;
let engine = null;

let startupFile = null;

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1360,
    height: 900,
    minWidth: 980,
    minHeight: 660,
    backgroundColor: '#14161a',
    show: false,
    // No fixed title: the renderer sets it, which is also how the framing diagnostics are read
    // back during testing.
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
    },
  });
  mainWindow.once('ready-to-show', () => mainWindow.show());
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
ipcMain.handle('engine:loadResources', (_e, root) => engine.call('loadResources', root ? { root } : {}));
ipcMain.handle('engine:open', (_e, file) => engine.call('open', { path: file }));
ipcMain.handle('engine:render', (_e, params) => engine.call('render', params));
ipcMain.handle('engine:renderPreview', (_e, params) => engine.call('renderPreview', params));
ipcMain.handle('engine:renderAnimation', (_e, params) => engine.call('renderAnimation', params));
ipcMain.handle('engine:cancel', () => engine.call('cancel', {}));

/**
 * Bakes the scene once and returns what the WebGL viewer needs: the atlas as a data URL and the
 * mesh as an ArrayBuffer.
 *
 * <p>The bytes travel over IPC rather than as {@code file://} URLs, because a file-backed page may
 * not fetch other file URLs. Keeping the mesh binary matters — a data URL would inflate 30 MB of
 * vertices to roughly 40 MB of text.
 */
ipcMain.handle('engine:exportScene', async () => {
  const dir = path.join(APP_HOME, 'renders', '.gpu');
  await fs.promises.mkdir(dir, { recursive: true });
  const res = await engine.call('exportScene', {
    atlas: path.join(dir, 'atlas.png'),
    mesh: path.join(dir, 'mesh.bin'),
  });
  const meshBuffer = await fs.promises.readFile(res.mesh);
  const atlasBuffer = await fs.promises.readFile(res.atlas);
  return {
    ...res,
    meshArrayBuffer: meshBuffer.buffer.slice(
      meshBuffer.byteOffset, meshBuffer.byteOffset + meshBuffer.byteLength),
    atlasDataUrl: 'data:image/png;base64,' + atlasBuffer.toString('base64'),
  };
});

// --- resource pack management ------------------------------------------------

/**
 * User-imported pack paths, persisted so they survive restarts. The engine loads vanilla, then the
 * built-in packs, then these — in this order — so an import can override a built-in.
 */
const packStore = {
  file: () => path.join(APP_HOME, 'renders', 'packs.json'),
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

ipcMain.handle('packs:import', async (_e, path) => {
  const list = await packStore.read();
  if (!list.includes(path)) list.push(path);
  await packStore.write(list);
  return list;
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

ipcMain.handle('dialog:openPack', async () => {
  const res = await dialog.showOpenDialog(mainWindow, {
    title: '导入资源包（zip 或文件夹）',
    filters: [
      { name: '资源包', extensions: ['zip'] },
      { name: '所有文件', extensions: ['*'] },
    ],
    properties: ['openFile', 'openDirectory'],
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
  if (engine) engine.stop();
  if (process.platform !== 'darwin') app.quit();
});

app.on('before-quit', () => {
  if (engine) engine.stop();
});
