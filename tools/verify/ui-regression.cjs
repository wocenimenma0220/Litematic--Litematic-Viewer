const { app, BrowserWindow, dialog, shell } = require('electron');
const fs = require('node:fs');
const path = require('node:path');
const [packageRoot, schematic, outputDirectory] = process.argv.slice(2);
process.argv = process.argv.slice(0,2);
fs.mkdirSync(outputDirectory, { recursive: true });
app.setPath('userData', path.join(outputDirectory, 'electron'));
const report = { pass: false, checks: [] };
const reportFile = path.join(outputDirectory, 'ui-result.json');
const selected = [];
const links = [];
BrowserWindow.prototype.show = function () {};
dialog.showOpenDialog = async () => selected.length
  ? { canceled: false, filePaths: [selected.shift()] } : { canceled: true, filePaths: [] };
shell.openExternal = async url => { links.push(url); };
let testWindow;
const check = (ok, message) => { if (!ok) throw new Error(message); report.checks.push(message); };
async function execute(code) { return testWindow.webContents.executeJavaScript('(async () => {'+code+'})()'); }
async function reload() {
  await new Promise(resolve => { testWindow.webContents.once('did-finish-load', resolve); testWindow.webContents.reload(); });
  await execute('await window.__uiReady;');
}
app.once('browser-window-created', (_event, win) => {
  testWindow = win;
  win.webContents.once('did-finish-load', async () => {
    try {
      let first = await execute(`await window.__uiReady;
        return { welcome: els.welcome.open, text: els.welcome.textContent, link: els.authorLink.textContent,
          mode: els.bgMode.value, checker: els.viewport.classList.contains('checker'), title: document.title,
          icon: document.querySelector('.brand-icon').naturalWidth };`);
      check(first.welcome && first.text.includes('作者 kinda sus') && first.text.includes('喜欢的话支持一下作者吧'), 'first-launch author dialog');
      check(first.mode === 'checker' && first.checker, 'default checker background');
      check(first.title === '投影预览' && first.icon === 256, 'Chinese application name and custom icon');
      check(first.link === '网页链接', 'four-character author link');
      await execute(`els.authorLink.click(); await new Promise(r=>setTimeout(r,100)); await dismissWelcome(false);`);
      check(links.length === 1 && links[0] === 'https://www.bilibili.com/video/BV1UT42167xb/?share_source=copy_web&vd_source=d1e157c09b3321a7ecaf103abcde1586', 'specified link opens externally');
      check((await execute('return await window.appUi.settings();')).showWelcome, 'close keeps next-launch dialog');
      await reload();
      check(await execute('return els.welcome.open;'), 'dialog appears again after close');
      await execute('await dismissWelcome(true);');
      await reload();
      check(await execute('return !els.welcome.open && !(await window.appUi.settings()).showWelcome;'), 'do-not-show preference survives reload');
      selected.push(path.join(outputDirectory, 'background.png'));
      let imported = await execute(`const bg = await window.appUi.importBackground(); useBackground(bg);
        return { mode: els.bgMode.value, image: els.viewport.style.backgroundImage, settings: (await window.appUi.settings()).background };`);
      check(imported.mode === 'image' && imported.image.includes('data:image/png'), 'background imports into preview');
      await reload();
      check(await execute('return els.bgMode.value === "image" && !!state.background && els.viewport.classList.contains("image-background");'), 'imported background survives reload');
      selected.push(path.join(outputDirectory, 'oversize.png'));
      check(await execute('try { await window.appUi.importBackground(); return false; } catch(e) { return e.message.includes("10 MB"); }'), 'over-10-MB background rejected');
      selected.push(path.join(outputDirectory, 'invalid.png'));
      check(await execute('try { await window.appUi.importBackground(); return false; } catch(e) { return e.message.includes("PNG"); }'), 'invalid image rejected');
      check(await execute('return await window.appUi.importBackground() === null;'), 'cancel import keeps current background');
      await execute(`els.fullscreen.click(); await new Promise(r=>setTimeout(r,250));`);
      check(win.isFullScreen() && await execute('return state.fullscreen && els.fullscreen.textContent.includes("退出");'), 'fullscreen button enters native fullscreen');
      await execute(`window.dispatchEvent(new KeyboardEvent('keydown',{code:'Escape',bubbles:true})); await new Promise(r=>setTimeout(r,250));`);
      check(!win.isFullScreen(), 'Escape exits fullscreen');
      await execute(`window.dispatchEvent(new KeyboardEvent('keydown',{code:'F11',bubbles:true})); await new Promise(r=>setTimeout(r,250));`);
      check(win.isFullScreen(), 'F11 enters fullscreen');
      await execute('await setFullscreen(false);');
      win.hide();
      const pngOut = path.join(outputDirectory, 'image-export.png');
      const gifOut = path.join(outputDirectory, 'image-export.gif');
      const gridOut = path.join(outputDirectory, 'grid-export.png');
      let render = await execute(`await window.engine.loadResources(null); await loadFile(${JSON.stringify(schematic)});
        if (!state.viewReady) throw new Error('actual WebGL preview unavailable');
        els.transparent.checked = false;
        els.bgMode.value = 'image'; applyBackdrop(); await window.appUi.setBackgroundMode('image');
        const imageParams = renderParams(192,128);
        if (imageParams.backgroundMode !== 'image') throw new Error('Image mode changed before export: '+JSON.stringify(imageParams));
        await window.engine.render({...renderParams(192,128),out:${JSON.stringify(pngOut)}});
        await window.engine.renderAnimation({...renderParams(96,64),out:${JSON.stringify(gifOut)},frames:2,delay:5});
        els.bgMode.value = 'checker'; applyBackdrop(); await window.appUi.setBackgroundMode('checker');
        await window.engine.render({...renderParams(192,128),out:${JSON.stringify(gridOut)}});
        els.transparent.checked = true;
        const preview = await window.engine.renderPreview(renderParams(192,128));
        const bitmap = await createImageBitmap(base64ToBlob('data:image/png;base64,'+preview.pngBase64));
        const canvas = document.createElement('canvas'); canvas.width=192; canvas.height=128;
        const ctx = canvas.getContext('2d'); ctx.drawImage(bitmap,0,0);
        return { imageParams, alpha: ctx.getImageData(0,0,1,1).data[3], error: state.view.gl.getError(), quads: state.view.quadCount, title: document.title };
      `);
      check(fs.existsSync(pngOut) && fs.existsSync(gifOut) && fs.existsSync(gridOut), 'PNG and GIF background exports');
      check(render.alpha === 0, 'transparent export overrides selected background');
      check(render.error === 0 && render.quads > 0 && render.title.includes('投影预览'), 'real WebGL preview remains healthy');
      report.render = render;
      const preview = await execute('drawView(); return els.gl.toDataURL("image/png");');
      fs.writeFileSync(path.join(outputDirectory,'preview.png'),Buffer.from(preview.split(',')[1],'base64'));
      report.pass = true;
      fs.writeFileSync(reportFile, JSON.stringify(report,null,2));
      app.quit();
    } catch(error) {
      report.error = error.stack;
      fs.writeFileSync(reportFile, JSON.stringify(report,null,2));
      app.quit();
    }
  });
});
require(path.join(packageRoot, 'webapp/main.js'));
setTimeout(()=>{
  if (report.pass || report.error) return;
  report.error='UI regression timed out'; fs.writeFileSync(reportFile,JSON.stringify(report,null,2)); app.quit();
},120000).unref();
