const { app } = require('electron');
const fs = require('node:fs/promises');
const path = require('node:path');
const [packageRoot, output, jpeg] = process.argv.slice(2);
app.setPath('userData',path.join(output,'formats-electron'));
app.whenReady().then(async()=>{
  try {
    const {UiSettings,BACKGROUND_LIMIT}=require(path.join(packageRoot,'webapp/ui-settings'));
    const settings=new UiSettings(path.join(output,'formats-fixture'));
    const boundary=Buffer.alloc(BACKGROUND_LIMIT);
    (await fs.readFile(path.join(output,'background.png'))).copy(boundary);
    const png=path.join(output,'exact-limit.png');
    await fs.writeFile(png,boundary);
    const exact=await settings.importBackground(png);
    if (exact.mode!=='image') throw new Error('Valid image at exactly 10 MiB was rejected');
    const imported=await settings.importBackground(jpeg);
    if (!imported.dataUrl.startsWith('data:image/jpeg')) throw new Error('JPG background was not accepted');
    await fs.writeFile(path.join(output,'format-result.json'),JSON.stringify({pass:true,exactLimit:BACKGROUND_LIMIT,jpeg:true},null,2));
  } catch(error) {
    await fs.writeFile(path.join(output,'format-result.json'),JSON.stringify({pass:false,error:error.stack},null,2));
  }
  app.quit();
});
