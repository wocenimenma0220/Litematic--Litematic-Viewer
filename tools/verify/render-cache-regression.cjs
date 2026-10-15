const { app, BrowserWindow } = require('electron');
const fs = require('node:fs');
const path = require('node:path');
const repoRoot = path.resolve(__dirname, '../..');
const root = process.argv[2] || repoRoot;
process.argv = process.argv.slice(0, 2);
const output = path.join(__dirname, 'optimization-output/render-cache.json');
const reference = fs.readdirSync(path.resolve(repoRoot, '../..')).find(name => name.endsWith('.litematic'));
if (!reference) throw new Error('Reference schematic missing');
app.setPath('userData', path.join(__dirname, 'optimization-output/render-cache-electron'));
BrowserWindow.prototype.show = function () {};
const report = { pass: false };
app.once('browser-window-created', (_event, win) => {
  win.webContents.once('did-finish-load', async () => {
    try {
      const result = await win.webContents.executeJavaScript(`(async () => {
        await window.__uiReady;
        if (els.welcome.open) await dismissWelcome(false);
        await window.engine.loadResources(null);
        await window.skins.setOnline(false);
        await loadFile(${JSON.stringify(path.resolve(repoRoot, '../..', reference))});
        if (!state.viewReady) throw new Error('WebGL unavailable');
        const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
        await delay(200);
        drawView(); // settle any layout change caused by the loaded scene's stats
        const view = state.view, gl = view.gl, draw = view.draw.bind(view);
        let draws = 0;
        view.draw = cam => { draws++; return draw(cam); };
        // Neither camera interaction nor idle refinement may rebuild the engine scene.
        window.engine.exportScene = () => { throw new Error('Camera rebuilt scene'); };
        window.engine.renderPreview = () => { throw new Error('Camera rendered CPU preview'); };
        drawView(); drawView();
        if (draws !== 0) throw new Error('Unchanged view was redrawn');
        const width = els.gl.width, height = els.gl.height;
        els.zoom.value = '1.25';
        els.zoom.dispatchEvent(new Event('input'));
        await new Promise(resolve => requestAnimationFrame(resolve));
        const moving = [els.gl.width, els.gl.height];
        if (draws !== 1 || state.zoom !== 1.25 || Math.max(...moving) > INTERACTION_EDGE) throw new Error('Zoom did not render a bounded interaction frame');
        await delay(REFINE_DELAY + 100);
        if (draws !== 2 || els.gl.width !== width || els.gl.height !== height) throw new Error('Idle refinement did not restore native resolution once');
        const afterRefine = draws;
        await delay(300); drawView();
        if (draws !== afterRefine) throw new Error('Idle view was redrawn');
        setFly(true);
        const stopTick = () => { cancelAnimationFrame(state.animHandle); state.animHandle = 0; };
        stopTick();
        state.keys.clear();
        await delay(200);
        const ray = () => { const box = els.gl.getBoundingClientRect(); return view.rayFromScreen(box.left + box.width/2, box.top + box.height/2); };
        const beforeRay = ray();
        drawView(true);
        if (JSON.stringify(ray()) !== JSON.stringify(beforeRay)) throw new Error('Interaction changed the crosshair ray');
        await delay(REFINE_DELAY + 100);
        const flyIdle = draws;
        state.keys.add('ControlLeft');
        flyTick(performance.now()); stopTick(); state.keys.clear();
        if (draws !== flyIdle) throw new Error('Modifier key caused idle redraws');
        state.flyYaw += .15; state.flyDirty = true;
        flyTick(performance.now()); stopTick();
        if (draws <= flyIdle || Math.max(els.gl.width, els.gl.height) > INTERACTION_EDGE) throw new Error('Fly turn did not use interaction resolution');
        await delay(REFINE_DELAY + 100);
        if (els.gl.width !== width || els.gl.height !== height) throw new Error('Fly idle quality did not recover');
        setFly(false);
        // Measure completed GPU work on the actual large model, keeping the same
        // camera and cached transparent ordering at both resolutions.
        const cam = orbitCamera(); cam.background = backdropColor();
        const benchmark = scale => {
          draw({ ...cam, renderScale: scale }); gl.finish();
          const start = performance.now();
          for (let frame = 0; frame < 6; frame++) { draw({ ...cam, renderScale: scale }); gl.finish(); }
          return (performance.now() - start) / 6;
        };
        const nativeMillis = benchmark(1);
        const scale = Math.min(1, INTERACTION_EDGE / Math.max(width, height));
        const interactionMillis = benchmark(scale);
        drawView();
        if (gl.getError() !== 0) throw new Error('WebGL error');
        return { quads: view.quadCount, nativeSize: [width, height], interactionSize: moving,
          nativeMillis, interactionMillis, rasterPixelRatio: scale * scale,
          idleDraws: 0, refineDraws: 1, cameraRebuilds: 0, zoomWorks: true, flyRefines: true, pickingPreserved: true };
      })()`);
      Object.assign(report, { pass: true, ...result });
      fs.writeFileSync(output, JSON.stringify(report, null, 2));
      console.log(JSON.stringify(report));
      app.quit();
    } catch (error) {
      report.error = error.stack;
      fs.writeFileSync(output, JSON.stringify(report, null, 2));
      console.error(error.stack);
      app.exit(1);
    }
  });
});
require(path.join(root, 'webapp/main.js'));
setTimeout(() => { console.error('Render-cache regression timed out'); app.exit(1); }, 120000).unref();
