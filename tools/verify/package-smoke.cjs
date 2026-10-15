const { app, BrowserWindow, dialog } = require('electron');
const fs = require('fs');
const path = require('path');
const [packageRoot, schematic, output, localSkin] = process.argv.slice(2);
const skinTest = path.basename(schematic) === 'player-skin.litematic';
app.setPath('userData', output.replace(/\.json$/, '-electron'));
if (localSkin) dialog.showOpenDialog = async () => ({ canceled: false, filePaths: [localSkin] });
process.argv = process.argv.slice(0, 2);
const report = { pass: false };
let windowRef;
// Load the real packaged main/preload/UI while keeping its test window hidden.
BrowserWindow.prototype.show = function () {};
app.on('browser-window-created', (_event, win) => {
  windowRef = win;
  win.webContents.on('did-finish-load', async () => {
    try {
      const state = await win.webContents.executeJavaScript(`(async () => {
        const info = await window.engine.status();
        await window.engine.loadResources(null);
        await window.__uiReady;
        if (els.welcome && els.welcome.open) await dismissWelcome(false);
        await loadFile(${JSON.stringify(schematic)});
        if (!state.viewReady) throw new Error('packaged 3D view did not load');
        let skinChecks = null;
        if (${skinTest}) {
          // Status text changes the surrounding layout; compare at a fixed viewport size.
          const viewport = state.view.canvas.parentElement;
          viewport.style.width = viewport.style.minWidth = viewport.style.maxWidth = '512px';
          viewport.style.height = viewport.style.minHeight = viewport.style.maxHeight = '512px';
          viewport.style.flex = '0 0 auto';
          const first = await window.engine.exportScene();
          if (first.skins.downloaded !== 1) throw new Error('Fresh live player skin was not downloaded');
          state.view.draw({ orbit: true, rotationDeg: 0, slantDeg: 8, zoom: 1, background: [0.15,0.17,0.2,1] });
          const onlineImage = state.view.canvas.toDataURL('image/png');
          window.__skinImages = { online: onlineImage };
          els.skinOnline.checked = false;
          els.skinOnline.dispatchEvent(new Event('change'));
          const waitFor = async predicate => {
            const deadline = Date.now()+60000;
            while (!predicate()) { if (Date.now()>deadline) throw new Error('Skin UI action timed out'); await new Promise(r=>setTimeout(r,50)); }
          };
          await waitFor(() => !els.skinOnline.disabled && !state.rendering);
          const cached = await window.engine.exportScene();
          if (cached.skins.online || cached.skins.cached !== 1 || cached.skins.downloaded !== 0) throw new Error('Offline switch did not reuse skin cache');
          state.view.draw({ orbit: true, rotationDeg: 0, slantDeg: 8, zoom: 1, background: [0.15,0.17,0.2,1] });
          const offlineImage = state.view.canvas.toDataURL('image/png');
          window.__skinImages.offline = offlineImage;
          if (offlineImage !== onlineImage) throw new Error('Offline skin preview differs from downloaded skin');
          els.skinPlayer.value = 'Notch';
          els.skinImport.click();
          await waitFor(() => !els.skinImport.disabled && !state.rendering);
          const imported = await window.engine.exportScene();
          if (imported.skins.local !== 1) throw new Error('Local PNG import did not override skin');
          state.view.draw({ orbit: true, rotationDeg: 0, slantDeg: 8, zoom: 1, background: [0.15,0.17,0.2,1] });
          if (state.view.canvas.toDataURL('image/png') === onlineImage) throw new Error('PNG override did not change actual WebGL pixels');
          skinChecks = { downloaded: first.skins, offline: cached.skins, imported: imported.skins, info: els.skinInfo.textContent };
        }
        state.view.draw({ orbit: true, rotationDeg: 0, slantDeg: 8, zoom: 1, background: [0.15,0.17,0.2,1] });
        const gl = state.view.gl;
        const pixels = new Uint8Array(state.view.canvas.width * state.view.canvas.height * 4);
        gl.readPixels(0,0,state.view.canvas.width,state.view.canvas.height,gl.RGBA,gl.UNSIGNED_BYTE,pixels);
        let lime = 0, blackOutline = 0;
        for (let i = 0; i < pixels.length; i += 4) {
          if (pixels[i] === 191 && pixels[i+1] === 255 && pixels[i+2] === 0) lime++;
          if (pixels[i] === 240 && pixels[i+1] === 235 && pixels[i+2] === 204) blackOutline++;
        }
        const signLayers = state.view.layers.reduce((n,l) => n + (l.ordinal === 1 ? l.quads : 0),0);
        return { status: info, quads: state.view.quadCount, title: document.title,
          lime, blackOutline, signLayers, screenshot: state.view.canvas.toDataURL('image/png'),
          error: gl.getError(), glNoError: gl.NO_ERROR, skinChecks };
      })()`);
      report.state = state;
      fs.writeFileSync(output.replace(/\.json$/, '.png'), Buffer.from(state.screenshot.split(',')[1], 'base64'));
      delete state.screenshot;
      if (state.error !== state.glNoError || state.quads < 1 || !state.status.running) {
        throw new Error('Invalid packaged UI state: ' + JSON.stringify(state));
      }
      if (path.basename(schematic) === 'entities.litematic' && (state.lime < 10 || state.blackOutline < 10)) {
        throw new Error('Glow text did not survive actual WebGL rendering: '+JSON.stringify(state));
      }
      report.pass = true;
      fs.writeFileSync(output, JSON.stringify(report, null, 2));
      // Let the real app's before-quit handler terminate the engine.
      app.quit();
    } catch (e) {
      report.error = e.stack;
      const images = await win.webContents.executeJavaScript('window.__skinImages || {}');
      for (const [name, data] of Object.entries(images)) fs.writeFileSync(output.replace(/\.json$/, '-'+name+'.png'), Buffer.from(data.split(',')[1], 'base64'));
      fs.writeFileSync(output, JSON.stringify(report, null, 2));
      app.quit();
    }
  });
});
require(path.join(packageRoot, 'webapp/main.js'));
setTimeout(() => {
  if (report.pass || report.error) return;
  report.error = 'Packaged UI verification timed out';
  fs.writeFileSync(output, JSON.stringify(report, null, 2));
  app.quit();
}, 90000).unref();
