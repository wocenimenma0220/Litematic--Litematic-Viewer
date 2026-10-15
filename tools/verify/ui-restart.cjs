const { app, BrowserWindow } = require('electron');
const fs = require('node:fs');
const path = require('node:path');
const [packageRoot, output] = process.argv.slice(2);
process.argv = process.argv.slice(0,2);
app.setPath('userData',path.join(path.dirname(output),'restart-electron'));
BrowserWindow.prototype.show = function () {};
app.once('browser-window-created',(_event,win)=>{
  win.webContents.once('did-finish-load',async()=>{
    try {
      const result=await win.webContents.executeJavaScript(`(async()=>{
        await window.__uiReady;
        return { welcome:els.welcome.open,settings:await window.appUi.settings(),mode:els.bgMode.value };
      })()`);
      if (result.welcome || result.settings.showWelcome || !result.settings.background.dataUrl || result.mode!=='checker') {
        throw new Error('Settings did not survive an actual application restart');
      }
      delete result.settings.background.dataUrl;
      fs.writeFileSync(output,JSON.stringify({pass:true,...result},null,2));
    } catch(error) { fs.writeFileSync(output,JSON.stringify({pass:false,error:error.stack},null,2)); }
    app.quit();
  });
});
require(path.join(packageRoot,'webapp/main.js'));
