'use strict';

/**
 * Renderer UI.
 *
 * Talks to the Java engine only through the preload bridge. Camera changes trigger a cheap
 * low-resolution preview; "导出 PNG" re-renders at the selected resolution and writes the file.
 *
 * The drag/zoom interaction follows quickcraft's preview: 0.015 radians per pixel for rotation
 * (with pitch clamped to ±85 degrees) and an exponential zoom of exp(scroll * 0.12).
 */

const $ = (id) => document.getElementById(id);

const els = {
  status: $('engineStatus'),
  dot: document.querySelector('.brand .dot'),
  open: $('btnOpen'),
  fileInfo: $('fileInfo'),
  rotation: $('rotation'),
  rotationVal: $('rotationVal'),
  slant: $('slant'),
  slantVal: $('slantVal'),
  zoom: $('zoom'),
  zoomVal: $('zoomVal'),
  size: $('size'),
  transparent: $('transparent'),
  bg: $('bg'),
  render: $('btnRender'),
  export: $('btnExport'),
  preview: $('preview'),
  gl: $('gl'),
  flyBtn: $('btnFly'),
  flyHint: $('flyHint'),
  placeholder: $('placeholder'),
  viewport: $('viewport'),
  busy: $('busy'),
  busyText: $('busyText'),
  log: $('log'),
  stats: $('stats'),
  checker: $('checker'),
  packList: $('packList'),
  menuFile: $('menuFile'),
  menuFileDrop: $('menuFileDrop'),
  menuOpenSchematic: $('menuOpenSchematic'),
  menuImportPack: $('menuImportPack'),
  menuExportPng: $('menuExportPng'),
  menuExportGif: $('menuExportGif'),
  menuQuit: $('menuQuit'),
};

const state = {
  file: null,
  info: null,
  lastResult: null,
  previewGeneration: 0,
  rendering: false,
  zoom: 1,
  packs: [],
  /** WebGL viewer; once ready, camera changes never reach the JVM. */
  view: null,
  viewReady: false,
  /** Fly (inside-the-build) mode state. */
  fly: false,
  flyPos: [0, 0, 0],
  flyYaw: 0,
  flyPitch: 0,
  keys: new Set(),
  animHandle: 0,
};

const PREVIEW_FULL_SIZE = 2048;
/**
 * While dragging, the preview renders small so it keeps up with the cursor; the full-size render
 * follows on release. Back-face culling means this is only about fill rate now — the geometry cost
 * is paid once per camera change regardless of resolution.
 */
const INTERACTIVE_MAX_EDGE = 512;

// --------------------------------------------------------------------- log

function log(message, cls) {
  const line = document.createElement('div');
  if (cls) line.className = cls;
  const t = new Date().toLocaleTimeString('zh-CN', { hour12: false });
  line.textContent = `[${t}] ${message}`;
  els.log.appendChild(line);
  els.log.scrollTop = els.log.scrollHeight;
  while (els.log.childElementCount > 300) els.log.removeChild(els.log.firstChild);
}

function setStatus(text, cls) {
  els.status.textContent = text;
  els.dot.className = 'dot' + (cls ? ' ' + cls : '');
}

function setBusy(on, text) {
  state.rendering = on;
  els.busy.classList.toggle('hidden', !on);
  if (text) els.busyText.textContent = text;
  updateButtons();
}

function updateButtons() {
  const ready = !!state.file && !state.rendering;
  els.render.disabled = !ready;
  els.export.disabled = !ready;
  els.menuExportPng.disabled = !ready;
  els.menuExportGif.disabled = !ready;
}

// ------------------------------------------------------------------ engine

function renderParams(width, height) {
  const transparent = els.transparent.checked;
  const params = {
    rotation: Number(els.rotation.value),
    slant: Number(els.slant.value),
    width,
    height,
    transparent,
  };
  if (!transparent) params.background = hexToInt(els.bg.value);
  return params;
}

function hexToInt(hex) {
  return parseInt(hex.replace('#', ''), 16) & 0xFFFFFF;
}

/**
 * Renders a preview at the viewport's exact pixel size.
 *
 * <p>Rendering at a fixed size and letting the browser scale it destroyed texture quality: a
 * 2048px image shown in an 880px viewport is resampled by the compositor, and bilinear
 * downsampling of 16x16 pixel art produces exactly the soft, muddy look this renderer exists to
 * avoid. Sizing the render to the display area means every texel maps to a screen pixel, so the
 * texture arrives at its native sharpness. Compression is a separate, later step — only the
 * exported file is compressed.
 *
 * @param interactive when true, renders at reduced resolution so the frame returns during a drag
 */
async function requestPreview(interactive) {
  if (!state.file) return;
  // Once the GPU scene is resident, a camera change is a matrix update — no JVM round trip.
  if (state.viewReady) { drawView(); return; }
  const generation = ++state.previewGeneration;
  const box = viewportPixelSize();
  if (!interactive) setBusy(true, '生成预览…');
  try {
    const params = renderParams(box.w, box.h);
    if (interactive) params.interactive = INTERACTIVE_MAX_EDGE;
    const res = await window.engine.renderPreview(params);
    if (generation !== state.previewGeneration) return; // a newer request superseded this one
    els.preview.src = 'data:image/png;base64,' + res.pngBase64;
    els.preview.classList.remove('hidden');
    els.placeholder.classList.add('hidden');
    // A dragging frame is deliberately smaller; let it stretch so the composition does not jump.
    els.preview.classList.toggle('upscaled', interactive);
    els.stats.textContent = `预览 ${res.width}×${res.height} · 渲染 ${res.renderMillis} ms`;
  } catch (err) {
    if (generation === state.previewGeneration) {
      log('预览失败：' + err.message, 'err');
    }
  } finally {
    if (generation === state.previewGeneration) setBusy(false);
  }
}

/**
 * The viewport's size in device pixels, so a render maps one texel to one screen pixel.
 *
 * <p>The upper bound was removed on request so a large schematic can be debugged at whatever size
 * the viewport actually is. Only a floor remains, so a tiny window still produces something legible
 * rather than a handful of pixels.
 */
function viewportPixelSize() {
  const dpr = window.devicePixelRatio || 1;
  const rect = els.viewport.getBoundingClientRect();
  const w = Math.round(rect.width * dpr);
  const h = Math.round(rect.height * dpr);
  return {
    w: Math.max(256, w),
    h: Math.max(256, h),
  };
}

// ------------------------------------------------------------- WebGL viewer

/**
 * Prepares the WebGL viewer: the engine bakes geometry once, the browser uploads it, and every
 * later camera change is only a matrix update. That is the point of the exercise — the JVM is not
 * involved in a drag at all.
 */
async function buildView() {
  setBusy(true, '准备 3D 视图…');
  try {
    const res = await window.engine.exportScene();
    // Decode the atlas without fetch: the page's CSP has no `data:` in connect-src, so fetching a
    // data URL is blocked. Building the Blob locally avoids the network path entirely.
    const atlasImage = await createImageBitmap(base64ToBlob(res.atlasDataUrl));

    if (!state.view) state.view = new GpuView(els.gl);
    const stats = state.view.uploadMesh(res.meshArrayBuffer);
    state.view.uploadAtlas(atlasImage);
    state.viewReady = true;

    els.gl.classList.remove('hidden');
    els.preview.classList.add('hidden');
    els.placeholder.classList.add('hidden');
    resetFlyToCenter();
    drawView();
    const mib = (res.meshBytes / 1048576).toFixed(1);
    const v = state.view;
    els.stats.textContent = `${stats.quads.toLocaleString()} 面 · ${(stats.totalVertices / 1000).toFixed(0)}k 顶点 · ${mib} MB`;
    reportFraming();
    log(`3D 视图就绪：${stats.quads.toLocaleString()} 面，${mib} MB，导出 ${res.exportMillis} ms（拖动不再重新渲染）`, 'ok');
  } catch (err) {
    state.viewReady = false;
    els.gl.classList.add('hidden');
    // Surface this prominently: a silent fallback once hid a total WebGL failure behind the CPU
    // path, which looked like "WebGL does not work" rather than a bug worth reading.
    log('3D 视图不可用，回退到 CPU 渲染：' + (err && err.stack ? err.stack : err), 'err');
    setStatus('3D 视图失败：' + (err && err.message ? err.message : err), 'err');
    // Also mirror the failure into the title so it can be read without a screenshot.
    const stack = (err && err.stack ? err.stack : '').split('\n').slice(0, 4).join(' <- ');
    document.title = 'LR err: ' + (err && err.message ? err.message : String(err)) + ' || ' + stack;
    await requestPreview(false);
  } finally {
    setBusy(false);
  }
}

/** Turns a `data:image/png;base64,...` URL into a Blob, without going through fetch. */
function base64ToBlob(dataUrl) {
  const comma = dataUrl.indexOf(',');
  const meta = dataUrl.slice(0, comma);
  const binary = atob(dataUrl.slice(comma + 1));
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  const mime = /:(.*?);/.exec(meta);
  return new Blob([bytes], { type: mime ? mime[1] : 'image/png' });
}

/**
 * Mirrors the framing numbers into the window title.
 *
 * <p>Screenshots proved unreliable for reading these — the header truncates and the interesting
 * half is off screen — so the title carries them where a test harness can read them directly.
 */
function reportFraming() {
  const v = state.view;
  if (!v) return;
  const m = v.lastM;
  if (!m) return;
  // Push the model centre and one corner through the matrix the GPU actually received.
  const c = v.center;
  const proj = (X, Y, Z) => {
    const x = m[0] * X + m[4] * Y + m[8] * Z + m[12];
    const y = m[1] * X + m[5] * Y + m[9] * Z + m[13];
    const w2 = m[3] * X + m[7] * Y + m[11] * Z + m[15];
    return [x / w2, y / w2];
  };
  const pc = proj(c[0], c[1], c[2]);
  const p0 = proj(v.min[0], v.min[1], v.min[2]);
  const p1 = proj(v.max[0], v.max[1], v.max[2]);
  const d = v.diag || {};
  document.title = `LR diag | vpC=${(d.vpClient || []).join('x')} vpR=${(d.vpRect || []).join('x')}`
    + ` | cvC=${(d.cvClient || []).join('x')} cvR=${(d.cvRect || []).join('x')}`
    + ` | buf=${(d.buf || []).join('x')} dpr=${d.dpr}`
    + ` | fit=${(v.fitScale || 0).toFixed(2)}`
    + ` | centre=(${pc[0].toFixed(2)},${pc[1].toFixed(2)})`;
}
/** Draws one frame from the current camera. No geometry work, so it is safe per pointermove. */
function drawView() {
  if (!state.view || !state.viewReady) return;
  const transparent = els.transparent.checked;
  let background = [0, 0, 0, 0];
  if (!transparent) {
    const v = hexToInt(els.bg.value);
    background = [((v >> 16) & 255) / 255, ((v >> 8) & 255) / 255, (v & 255) / 255, 1];
  }
  const view = state.view;
  const cam = state.fly ? flyCamera() : orbitCamera();
  cam.background = background;
  view.draw(cam);
}

/**
 * Orbit camera: the whole build stays framed and the two sliders drive the angles.
 *
 * <p>This carries no eye position on purpose. quickcraft's preview builds its model-view as
 * {@code translate(centre) * rotate * scale * translate(-centre)} under a fixed orthographic box,
 * so the camera direction lives in the rotation and the framing lives in the scale — deriving an
 * eye point as well would apply the fit twice.
 */
function orbitCamera() {
  return {
    orbit: true,
    rotationDeg: Number(els.rotation.value),
    slantDeg: Number(els.slant.value),
    zoom: state.zoom,
  };
}

/**
 * Fly camera for inspecting the interior: a perspective view from inside the build, driven by WASD
 * plus the mouse. Perspective is what makes an interior legible — once you are among the blocks an
 * orthographic projection carries no depth cue at all.
 */
function flyCamera() {
  const yaw = state.flyYaw, pitch = state.flyPitch;
  const dir = [
    -Math.sin(yaw) * Math.cos(pitch),
    Math.sin(pitch),
    -Math.cos(yaw) * Math.cos(pitch),
  ];
  const p = state.flyPos;
  return {
    eye: p,
    target: [p[0] + dir[0], p[1] + dir[1], p[2] + dir[2]],
    up: [0, 1, 0],
    fovDeg: 70,
    orthoHeight: 1,
  };
}

/** Places the fly camera just outside the build, looking at its centre. */
function resetFlyToCenter() {
  const view = state.view;
  if (!view) return;
  const c = view.center;
  state.flyPos = [c[0], c[1], c[2] + view.radius * 2];
  state.flyYaw = 0;
  state.flyPitch = 0;
}

/** Moves the fly camera from the held keys; runs only while that mode is active. */
function flyTick() {
  if (!state.fly || !state.viewReady) {
    state.animHandle = 0;
    return;
  }
  const speed = (state.keys.has('ShiftLeft') ? 24 : 9) / 60;
  const yaw = state.flyYaw;
  const forward = [-Math.sin(yaw), 0, -Math.cos(yaw)];
  const right = [Math.cos(yaw), 0, -Math.sin(yaw)];
  const p = state.flyPos;
  const step = (vec, sign, scale) => {
    p[0] += vec[0] * sign * scale;
    p[1] += vec[1] * sign * scale;
    p[2] += vec[2] * sign * scale;
  };
  if (state.keys.has('KeyW')) step(forward, 1, speed);
  if (state.keys.has('KeyS')) step(forward, -1, speed);
  if (state.keys.has('KeyD')) step(right, 1, speed);
  if (state.keys.has('KeyA')) step(right, -1, speed);
  if (state.keys.has('Space')) p[1] += speed;
  if (state.keys.has('KeyC') || state.keys.has('ControlLeft')) p[1] -= speed;
  drawView();
  state.animHandle = requestAnimationFrame(flyTick);
}

// ------------------------------------------------------------------- events

function syncAngleLabels() {
  els.rotationVal.textContent = Math.round(els.rotation.value) + '°';
  els.slantVal.textContent = Math.round(els.slant.value) + '°';
  els.zoomVal.textContent = state.zoom.toFixed(2) + '×';
}

// A slider never floods the engine: wait for it to settle, then render at full size.
let sliderTimer = null;
function onSliderCommit() {
  clearTimeout(sliderTimer);
  sliderTimer = setTimeout(() => drawView(), 40);
}

els.rotation.addEventListener('input', syncAngleLabels);
els.slant.addEventListener('input', syncAngleLabels);
els.rotation.addEventListener('change', onSliderCommit);
els.slant.addEventListener('change', onSliderCommit);

els.zoom.addEventListener('input', () => {
  state.zoom = Number(els.zoom.value);
  syncAngleLabels();
  applyZoomToPreview();
});

/** Zoom is applied to the displayed image, so it stays instant and needs no re-render. */
function applyZoomToPreview() {
  els.preview.style.transform = `scale(${state.zoom})`;
}

// --- drag to rotate, wheel to zoom (quickcraft's coefficients) ---------------

const DRAG_RADIANS_PER_PIXEL = 0.015;
const MAX_PITCH = 85;
let dragging = false;
let dragX = 0;
let dragY = 0;

els.viewport.addEventListener('pointerdown', (e) => {
  // Right button (2) and left button (0) both drag, per the request.
  if (e.button !== 0 && e.button !== 2) return;
  dragging = true;
  dragX = e.clientX;
  dragY = e.clientY;
  els.viewport.setPointerCapture(e.pointerId);
  els.viewport.classList.add('grabbing');
});

els.viewport.addEventListener('pointermove', (e) => {
  if (!dragging) return;
  const dx = e.clientX - dragX;
  const dy = e.clientY - dragY;
  dragX = e.clientX;
  dragY = e.clientY;

  // Rotation is in degrees on the slider; convert quickcraft's radians-per-pixel.
  const degPerPixel = DRAG_RADIANS_PER_PIXEL * (180 / Math.PI);
  if (state.fly) {
    // Inside the build the mouse steers the head instead of orbiting the model.
    state.flyYaw -= dx * DRAG_RADIANS_PER_PIXEL;
    // Screen Y grows downward while pitch grows upward, so dragging down must decrease the pitch.
    // Adding dy inverted the look direction and made the vertical axis feel backwards.
    state.flyPitch = Math.max(-MAX_PITCH * Math.PI / 180,
      Math.min(MAX_PITCH * Math.PI / 180, state.flyPitch - dy * DRAG_RADIANS_PER_PIXEL));
    drawView();
    return;
  }
  let rotation = Number(els.rotation.value) + dx * degPerPixel;
  let slant = Number(els.slant.value) + dy * degPerPixel;

  rotation = ((rotation % 360) + 360) % 360;
  slant = Math.max(-MAX_PITCH, Math.min(MAX_PITCH, slant));

  els.rotation.value = String(Math.round(rotation));
  els.slant.value = String(Math.round(slant));
  syncAngleLabels();

  // Coalesce to one request per animation frame. Firing per pointermove event would queue far
  // more renders than the engine can retire, and the display only refreshes this often anyway.
  if (!dragPending) {
    dragPending = true;
    requestAnimationFrame(() => {
      dragPending = false;
      if (dragging) drawView();
    });
  }
});

let dragPending = false;

function endDrag(e) {
  if (!dragging) return;
  dragging = false;
  els.viewport.classList.remove('grabbing');
  if (e && e.pointerId !== undefined && els.viewport.hasPointerCapture(e.pointerId)) {
    els.viewport.releasePointerCapture(e.pointerId);
  }
  // Nothing to re-render on release: the GPU frame is already exact at display resolution.
  drawView();
}

els.viewport.addEventListener('pointerup', endDrag);
els.viewport.addEventListener('pointercancel', endDrag);
els.viewport.addEventListener('contextmenu', (e) => e.preventDefault());

els.viewport.addEventListener(
  'wheel',
  (e) => {
    e.preventDefault();
    // quickcraft: scale *= exp(amount * 0.12). Wheel deltas are large, so normalise by 100.
    state.zoom *= Math.exp(-e.deltaY / 100 * 0.12);
    state.zoom = Math.max(0.05, Math.min(20, state.zoom));
    els.zoom.value = String(state.zoom);
    syncAngleLabels();
    applyZoomToPreview();
  },
  { passive: false }
);

document.querySelectorAll('[data-preset]').forEach((btn) => {
  btn.addEventListener('click', () => {
    const p = btn.dataset.preset;
    const map = {
      iso: [45, 35.264],
      dimetric: [45, 30],
      front: [0, 0],
      top: [0, 89],
      back: [180, 0],
      left: [-90, 0],
    }[p];
    if (!map) return;
    els.rotation.value = String(map[0]);
    els.slant.value = String(map[1]);
    syncAngleLabels();
    requestPreview(false);
  });
});

els.transparent.addEventListener('change', () => {
  els.bg.disabled = els.transparent.checked;
  drawView();
});
els.bg.addEventListener('change', () => drawView());
els.checker.addEventListener('change', () => {
  els.viewport.classList.toggle('checker', els.checker.checked);
});

// --- WASD fly mode ----------------------------------------------------------

/**
 * Fly mode swaps the orbit camera for a perspective one inside the build. Movement is driven by a
 * requestAnimationFrame loop rather than by key events, so a held key moves continuously instead of
 * once per repeat, and the frame rate follows the display.
 */
function setFly(on) {
  state.fly = on;
  if (on) {
    resetFlyToCenter();
    document.body.classList.add('flying');
    els.flyBtn.textContent = '退出漫游 (V)';
    els.flyHint.classList.remove('hidden');
    if (!state.animHandle) state.animHandle = requestAnimationFrame(flyTick);
  } else {
    document.body.classList.remove('flying');
    els.flyBtn.textContent = '进入漫游 (V)';
    els.flyHint.classList.add('hidden');
    if (state.animHandle) {
      cancelAnimationFrame(state.animHandle);
      state.animHandle = 0;
    }
  }
  drawView();
}

els.flyBtn.addEventListener('click', () => setFly(!state.fly));

window.addEventListener('keydown', (e) => {
  // Never swallow typing in a form control.
  if (e.target && /^(INPUT|SELECT|TEXTAREA)$/.test(e.target.tagName)) return;
  if (e.code === 'KeyV') {
    setFly(!state.fly);
    e.preventDefault();
    return;
  }
  if (!state.fly) return;
  if (['KeyW', 'KeyA', 'KeyS', 'KeyD', 'Space', 'KeyC', 'ShiftLeft', 'ControlLeft'].includes(e.code)) {
    state.keys.add(e.code);
    e.preventDefault();          // Space would otherwise scroll the panel
    if (!state.animHandle) state.animHandle = requestAnimationFrame(flyTick);
  }
});

window.addEventListener('keyup', (e) => state.keys.delete(e.code));
// A window that loses focus never delivers the keyup, which would leave the camera drifting.
window.addEventListener('blur', () => state.keys.clear());

// --- viewport resize --------------------------------------------------------

/**
 * The canvas is sized from its CSS box on every frame, so it follows the window automatically. The
 * observer only exists to repaint immediately on resize rather than waiting for the next input.
 */
if (typeof ResizeObserver !== 'undefined') {
  new ResizeObserver(() => drawView()).observe(els.viewport);
}

// ------------------------------------------------------------ File menu

function toggleFileMenu(force) {
  const show = force === undefined ? els.menuFileDrop.classList.contains('hidden') : force;
  els.menuFileDrop.classList.toggle('hidden', !show);
}

els.menuFile.addEventListener('click', (e) => {
  e.stopPropagation();
  toggleFileMenu();
});

document.addEventListener('click', () => toggleFileMenu(false));
els.menuFileDrop.addEventListener('click', (e) => e.stopPropagation());

els.menuOpenSchematic.addEventListener('click', () => {
  toggleFileMenu(false);
  openSchematic();
});
els.menuImportPack.addEventListener('click', async () => {
  toggleFileMenu(false);
  await importPack();
});
els.menuExportPng.addEventListener('click', () => {
  toggleFileMenu(false);
  exportPng();
});
els.menuExportGif.addEventListener('click', () => {
  toggleFileMenu(false);
  exportGif();
});
els.menuQuit.addEventListener('click', () => {
  window.close();
});

async function openSchematic() {
  const file = await window.shellApi.openSchematic();
  if (!file) return;
  await loadFile(file);
}

async function loadFile(file) {
  els.open.disabled = true;
  setBusy(true, '读取投影…');
  // Frame each new build from a known state; a leftover zoom would open the file mid-detail.
  state.zoom = 1;
  els.zoom.value = '1';
  syncAngleLabels();
  try {
    const info = await window.engine.open(file);
    state.file = file;
    state.info = info;
    showFileInfo(info);
    log(`已打开 ${info.name}（${info.regions.length} 个区域，${info.totalBlocks} 方块）`, 'ok');
    if (info.unresolved) log('存在无法解析的资源：' + JSON.stringify(info.unresolved), 'err');
    setBusy(false);
    await buildView();
  } catch (err) {
    log('打开失败：' + err.message, 'err');
    setBusy(false);
  } finally {
    els.open.disabled = false;
    updateButtons();
  }
}

els.open.addEventListener('click', openSchematic);
els.render.addEventListener('click', () => requestPreview(false));
els.export.addEventListener('click', exportPng);
els.menuExportPng.addEventListener('click', exportPng);
els.menuExportGif.addEventListener('click', exportGif);

async function exportPng() {
  if (!state.file) return;
  const size = Number(els.size.value);
  const suggested = ((state.info && state.info.name) || 'render') + `_${size}.png`;
  const out = await window.shellApi.savePng(suggested);
  if (!out) return;
  setBusy(true, `渲染 ${size}×${size}…`);
  try {
    const res = await window.engine.render({ ...renderParams(size, size), out, diagnostics: true });
    state.lastResult = res;
    els.stats.textContent = `${res.width}×${res.height} · 渲染 ${res.renderMillis} ms · 编码 ${res.encodeMillis} ms`;
    log(`已导出：${res.path}`, 'ok');
    if (res.unresolved) {
      const keys = Object.keys(res.unresolved);
      log(`注意：有 ${keys.length} 类资源未能解析（${keys.slice(0, 3).join('; ')}${keys.length > 3 ? ' …' : ''}）`, 'err');
    }
    window.shellApi.reveal(res.path);
  } catch (err) {
    log('导出失败：' + err.message, 'err');
  } finally {
    setBusy(false);
  }
}

async function exportGif() {
  if (!state.file) return;
  const suggested = ((state.info && state.info.name) || 'animation') + '.gif';
  const out = await window.shellApi.savePng(suggested);
  if (!out) return;
  const size = Number(els.size.value);
  setBusy(true, '渲染动画…');
  try {
    const res = await window.engine.renderAnimation({
      ...renderParams(size, size),
      out,
      frames: 64,
      delay: 5,
    });
    log(`已导出动画：${res.path}（${res.frames} 帧）`, 'ok');
  } catch (err) {
    log('动画导出失败：' + err.message, 'err');
  } finally {
    setBusy(false);
  }
}

function showFileInfo(info) {
  const size = info.size || {};
  const rows = [
    ['名称', info.name],
    ['作者', info.author],
    ['尺寸', `${size.x} × ${size.y} × ${size.z}`],
    ['区域', String(info.regionCount)],
    ['方块数', String(info.totalBlocks)],
  ];
  els.fileInfo.innerHTML = rows
    .map(([k, v]) => `<div class="row"><span>${escapeHtml(k)}</span><span>${escapeHtml(String(v))}</span></div>`)
    .join('');
  els.fileInfo.classList.remove('hidden');
}

function escapeHtml(s) {
  return s.replace(/[&<>"']/g, (c) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c])
  );
}

// -------------------------------------------------------- resource packs

async function importPack() {
  const p = await window.shellApi.openPack();
  if (!p) return;
  setBusy(true, '应用材质包…');
  try {
    state.packs = await window.packs.import(p);
    const res = await window.engine.loadResources(null);
    log(`已导入材质包：${p}`, 'ok');
    log(`贴图 ${res.sprites} 张`, 'ok');
    if (state.file) await requestPreview(false);
  } catch (err) {
    log('导入材质包失败：' + err.message, 'err');
  } finally {
    setBusy(false);
  }
}

async function removePack(p) {
  setBusy(true, '应用材质包…');
  try {
    state.packs = await window.packs.remove(p);
    await window.engine.loadResources(null);
    log(`已移除材质包：${p}`, 'ok');
    if (state.file) await requestPreview(false);
  } catch (err) {
    log('移除失败：' + err.message, 'err');
  } finally {
    setBusy(false);
  }
}

async function movePack(p, direction) {
  setBusy(true, '应用材质包…');
  try {
    state.packs = await window.packs.move(p, direction);
    await window.engine.loadResources(null);
    if (state.file) await requestPreview(false);
  } catch (err) {
    log('调整优先级失败：' + err.message, 'err');
  } finally {
    setBusy(false);
  }
}

function renderPackList() {
  if (!els.packList) return;
  const items = [];
  // Built-ins are always on, read-only.
  (window.__builtinPacks || []).forEach((name) => {
    items.push(`
      <li class="pack builtin">
        <span class="packname">${escapeHtml(name)}</span>
        <span class="tag">内置</span>
      </li>`);
  });
  state.packs.forEach((p, i) => {
    const name = p.split(/[\\/]/).pop();
    items.push(`
      <li class="pack">
        <span class="packname" title="${escapeHtml(p)}">${escapeHtml(name)}</span>
        <span class="packbtns">
          <button class="mini" data-act="up" data-p="${escapeHtml(p)}" ${i === 0 ? 'disabled' : ''}>↑</button>
          <button class="mini" data-act="down" data-p="${escapeHtml(p)}" ${i === state.packs.length - 1 ? 'disabled' : ''}>↓</button>
          <button class="mini danger" data-act="remove" data-p="${escapeHtml(p)}">×</button>
        </span>
      </li>`);
  });
  els.packList.innerHTML = items.join('');

  els.packList.querySelectorAll('button[data-act]').forEach((b) => {
    b.addEventListener('click', async () => {
      const p = b.dataset.p;
      if (b.dataset.act === 'up') await movePack(p, 'up');
      if (b.dataset.act === 'down') await movePack(p, 'down');
      if (b.dataset.act === 'remove') await removePack(p);
    });
  });
}

// ------------------------------------------------------- wake-up

window.engine.onEvent((evt) => {
  if (evt.event === 'stderr' && evt.message && evt.message.trim()) {
    const text = evt.message.trim();
    if (/unresolved|error|exception|failed/i.test(text)) log(text, 'err');
  }
});

// A schematic passed on the command line opens once the engine is up.
window.engine.onOpenFile((file) => {
  log(`命令行指定投影：${file}`);
  pendingOpen = file;
});

let pendingOpen = null;

window.engine.onExit((info) => {
  if (info.error) {
    setStatus('渲染引擎启动失败：' + info.error, 'err');
    log('引擎启动失败：' + info.error + '\n请确认 Java 运行时可用（runtime/ 或 JAVA_HOME）。', 'err');
  } else {
    setStatus('渲染引擎已退出', 'err');
    log(`引擎退出（code=${info.code}）`, 'err');
    if (info.stderrTail && info.stderrTail.length) {
      log(info.stderrTail.slice(-8).join('\n'), 'err');
    }
  }
  updateButtons();
});

(async function init() {
  els.bg.disabled = els.transparent.checked;
  syncAngleLabels();
  try {
    const st = await window.engine.status();
    if (!st.running) {
      setStatus('渲染引擎未运行', 'err');
      return;
    }
    setStatus(`引擎就绪 · Java: ${st.javaSource}`, 'ok');
    log(`Java 运行时：${st.java}（${st.javaSource}）`);
    const t0 = performance.now();
    const res = await window.engine.loadResources(null);
    if (res.builtinPacks && res.builtinPacks.length) {
      window.__builtinPacks = res.builtinPacks;
      log(`内置材质包：${res.builtinPacks.join('，')}`, 'ok');
    }
    log(`资源包已加载（${Math.round(performance.now() - t0)} ms，贴图 ${res.sprites} 张）`, 'ok');
    renderPackList();
    if (pendingOpen) {
      await loadFile(pendingOpen);
      pendingOpen = null;
    }
  } catch (err) {
    setStatus('引擎初始化失败', 'err');
    log('初始化失败：' + err.message, 'err');
  }
})();
