'use strict';

const fs = require('node:fs/promises');
const path = require('node:path');
const { nativeImage } = require('electron');
const {memoryPolicy}=require('./performance-policy');
const {cleanShortcutBindings}=require('./renderer/keyboard-shortcuts');
const BACKGROUND_LIMIT = 10 * 1024 * 1024;
const BACKGROUND_MODES = new Set(['checker', 'sky', 'white', 'dark', 'image', 'color']);
const AUTHOR_URL = 'https://www.bilibili.com/video/BV1UT42167xb/?share_source=copy_web&vd_source=d1e157c09b3321a7ecaf103abcde1586';

class UiSettings {
  constructor(home) {
    this.directory = path.join(home, 'settings');
    this.file = path.join(this.directory, 'ui.json');
    this.imageFile = path.join(this.directory, 'background.img');
    this.state = null;
    this.queue = Promise.resolve();
  }
  async read() {
    if (!this.state) {
      let stored = {};
      try { stored = JSON.parse(await fs.readFile(this.file, 'utf8')) || {}; } catch { /* first launch */ }
      this.state = {
        hideWelcome: stored.hideWelcome === true,
        keybindings:cleanShortcutBindings(stored.keybindings),
        sidebarCollapsed: stored.sidebarCollapsed === true,
        realLighting: stored.realLighting !== false,
        lodPreview: stored.lodPreview !== false,
        largeProjection: stored.largeProjection === true,
        projectionMode: ['auto','full','streaming'].includes(stored.projectionMode)?stored.projectionMode:'auto',
        lightingEnabled: stored.lightingEnabled !== false,
        showCoordinates: stored.showCoordinates === true,
        clearCacheOnExit: stored.clearCacheOnExit !== false,
        viewDistance: Number.isInteger(stored.viewDistance)&&stored.viewDistance>=1&&stored.viewDistance<=128?stored.viewDistance:6,
        geometryBudgetMB: Number.isInteger(stored.geometryBudgetMB)&&stored.geometryBudgetMB>=32&&stored.geometryBudgetMB<=16384?stored.geometryBudgetMB:memoryPolicy().geometryBudgetMB,
        preloadMode: ['distance','full','streaming'].includes(stored.preloadMode)?stored.preloadMode:'streaming',
        previewBackend: stored.previewBackend === 'cpu' ? 'cpu' : 'gpu',
        backgroundMode: BACKGROUND_MODES.has(stored.backgroundMode) ? stored.backgroundMode : 'checker',
        backgroundName: typeof stored.backgroundName === 'string' ? stored.backgroundName : '',
        backgroundColor: /^#[0-9a-f]{6}$/i.test(stored.backgroundColor) ? stored.backgroundColor : '#ffffff',
      };
    }
    return this.state;
  }
  async save(values) {
    const action = this.queue.then(async () => {
      const updated = { ...await this.read(), ...values };
      await fs.mkdir(this.directory, { recursive: true });
      const temporary = this.file + '.tmp';
      await fs.writeFile(temporary, JSON.stringify(updated, null, 2), 'utf8');
      await fs.rename(temporary, this.file);
      this.state = updated;
    });
    this.queue = action.catch(() => {});
    await action;
  }
  async settings() {
    const stored = await this.read();
    let dataUrl = null;
    try {
      if ((await fs.stat(this.imageFile)).size <= BACKGROUND_LIMIT) dataUrl = this.decode(await fs.readFile(this.imageFile)).dataUrl;
    } catch { /* no imported background */ }
    return {
      showWelcome: !stored.hideWelcome,
      keybindings:stored.keybindings,
      sidebarCollapsed: stored.sidebarCollapsed,
      realLighting: stored.realLighting,
      lodPreview: stored.lodPreview,
      largeProjection: stored.largeProjection,
      projectionMode: stored.projectionMode,
      lightingEnabled: stored.lightingEnabled,
      showCoordinates: stored.showCoordinates,
      clearCacheOnExit: stored.clearCacheOnExit,
      viewDistance: stored.viewDistance,
      geometryBudgetMB: stored.geometryBudgetMB,
      preloadMode: stored.preloadMode,
      previewBackend: stored.previewBackend,
      background: { mode: stored.backgroundMode === 'image' && !dataUrl ? 'checker' : stored.backgroundMode,
        name: stored.backgroundName, color: stored.backgroundColor, dataUrl },
    };
  }
  decode(bytes) {
    if (bytes.length > BACKGROUND_LIMIT) throw new Error('背景图片不能超过 10 MB');
    const png = bytes.length > 8 && bytes.subarray(0, 8).equals(Buffer.from([137,80,78,71,13,10,26,10]));
    const jpeg = bytes.length > 3 && bytes[0] === 255 && bytes[1] === 216 && bytes[2] === 255;
    if (!png && !jpeg) throw new Error('请导入 PNG 或 JPG 图片');
    const image = nativeImage.createFromBuffer(bytes);
    if (image.isEmpty()) throw new Error('这张图片无法读取，请选择有效的 PNG 或 JPG');
    return { dataUrl: 'data:' + (png ? 'image/png' : 'image/jpeg') + ';base64,' + bytes.toString('base64') };
  }
  async importBackground(file) {
    const stat = await fs.stat(file);
    if (!stat.isFile() || stat.size > BACKGROUND_LIMIT) throw new Error('背景图片不能超过 10 MB');
    const bytes = await fs.readFile(file);
    const decoded = this.decode(bytes);
    await fs.mkdir(this.directory, { recursive: true });
    await fs.writeFile(this.imageFile + '.tmp', bytes);
    await fs.rename(this.imageFile + '.tmp', this.imageFile);
    const name = path.basename(file);
    await this.save({ backgroundMode: 'image', backgroundName: name });
    return { mode: 'image', name, ...decoded };
  }
  async setBackgroundMode(mode, color) {
    if (!BACKGROUND_MODES.has(mode)) throw new Error('未知的背景类型');
    if (mode === 'image') {
      const settings = await this.settings();
      if (!settings.background.dataUrl) throw new Error('请先导入背景图片');
    }
    await this.save({ backgroundMode: mode, ...(/^#[0-9a-f]{6}$/i.test(color) ? { backgroundColor: color } : {}) });
    return { mode };
  }
  async renderParams(params) {
    const result = { ...params };
    delete result.backgroundImage;
    if (result.transparent === true || result.backgroundMode === 'checker') {
      result.transparent = true;
      delete result.background;
    }
    if (!result.transparent && result.backgroundMode === 'image') result.backgroundImage = this.imageFile;
    return result;
  }
}
module.exports = { UiSettings, AUTHOR_URL, BACKGROUND_LIMIT };
