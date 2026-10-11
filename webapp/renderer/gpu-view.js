'use strict';

/**
 * WebGL viewer for an exported scene.
 *
 * <p>The CPU rasteriser re-transformed and re-shaded every vertex on each camera change, which is
 * why dragging was never smooth. Here the geometry is uploaded once and a drag only writes a new
 * matrix. That is the same split Minecraft uses — its `renderpearl` layer abstracts a `GpuDevice`
 * over a Vulkan or OpenGL backend, and quickcraft's preview draws from resident `VertexBuffer`s —
 * and WebGL is the browser's OpenGL, so it is the closest equivalent available without native
 * bindings.
 *
 * <p>The mesh layout is defined by `MeshExporter`. Two details matter and both caused a total
 * failure the first time: the signature is padded to 8 bytes, and every number is **big-endian**
 * because Java's DataOutputStream writes it that way.
 */

const SIGNATURE = 'LMESH1';
const SIGNATURE_LENGTH = 8;
const FLOATS_PER_VERTEX = 9;
/** Vertices per quad: two triangles sharing the 0-2 diagonal, matching MeshExporter. */
const VERTICES_PER_QUAD = 6;
/** SceneMesh.Layer ordinals, in draw order. */
const LAYER_NAMES = ['SOLID', 'CUTOUT', 'PORTAL', 'FLUID', 'TRANSLUCENT'];

const VERTEX_SHADER = `
attribute vec3 aPos;
attribute vec2 aUv;
attribute float aShade;
attribute vec3 aTint;

uniform mat4 uViewProj;

varying vec2 vUv;
varying float vShade;
varying vec3 vTint;

void main() {
  gl_Position = uViewProj * vec4(aPos, 1.0);
  vUv = aUv;
  vShade = aShade;
  vTint = aTint;
}
`;

const FRAGMENT_SHADER = `
precision highp float;

uniform sampler2D uAtlas;
uniform float uAlphaCutoff;

varying vec2 vUv;
varying float vShade;
varying vec3 vTint;

void main() {
  vec4 tex = texture2D(uAtlas, vUv);
  // Cutout layers discard fully transparent texels so they neither blend nor write depth. This is
  // what lets a glass pane's empty middle show what is behind it.
  if (uAlphaCutoff > 0.0 && tex.a < uAlphaCutoff) discard;
  if (tex.a <= 0.0) discard;
  vec3 rgb = tex.rgb * vTint * vShade;
  gl_FragColor = vec4(rgb, tex.a);
}
`;

class GpuView {
  constructor(canvas) {
    this.canvas = canvas;
    const gl = canvas.getContext('webgl', {
      alpha: true,
      antialias: true,
      depth: true,
      premultipliedAlpha: false,
      preserveDrawingBuffer: false,
    });
    if (!gl) throw new Error('WebGL unavailable');
    this.gl = gl;
    this.program = this._program(VERTEX_SHADER, FRAGMENT_SHADER);
    this.attr = {
      pos: gl.getAttribLocation(this.program, 'aPos'),
      uv: gl.getAttribLocation(this.program, 'aUv'),
      shade: gl.getAttribLocation(this.program, 'aShade'),
      tint: gl.getAttribLocation(this.program, 'aTint'),
    };
    this.uni = {
      viewProj: gl.getUniformLocation(this.program, 'uViewProj'),
      atlas: gl.getUniformLocation(this.program, 'uAtlas'),
      alphaCutoff: gl.getUniformLocation(this.program, 'uAlphaCutoff'),

    };
    this.buffers = [];
    this.texture = null;
    this.min = [0, 0, 0];
    this.max = [1, 1, 1];
    this.center = [0, 0, 0];
    this.radius = 1;
    this.quadCount = 0;
    /** Last uploaded view-projection matrix, for diagnostics. */
    this.lastMatrix = null;
    this.lastMode = '';
  }

  _program(vs, fs) {
    const gl = this.gl;
    const make = (type, source) => {
      const s = gl.createShader(type);
      gl.shaderSource(s, source);
      gl.compileShader(s);
      if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) {
        throw new Error('shader: ' + gl.getShaderInfoLog(s));
      }
      return s;
    };
    const p = gl.createProgram();
    gl.attachShader(p, make(gl.VERTEX_SHADER, vs));
    gl.attachShader(p, make(gl.FRAGMENT_SHADER, fs));
    gl.linkProgram(p);
    if (!gl.getProgramParameter(p, gl.LINK_STATUS)) {
      throw new Error('link: ' + gl.getProgramInfoLog(p));
    }
    return p;
  }

  /**
   * Parses the mesh and uploads one buffer per layer.
   *
   * <p>Validation is explicit because a mis-read header cannot be detected later: the draw call
   * would simply render nothing, and a silent failure here previously sent the viewer down a
   * fallback path that hid the canvas.
   */
  uploadMesh(buffer) {
    const gl = this.gl;
    if (buffer.byteLength < SIGNATURE_LENGTH + 8) {
      throw new Error(`mesh too small: ${buffer.byteLength} bytes`);
    }
    const bytes = new Uint8Array(buffer);
    const sig = String.fromCharCode(...bytes.subarray(0, SIGNATURE.length));
    if (sig !== SIGNATURE) throw new Error(`bad signature "${sig}"`);

    const view = new DataView(buffer);
    let off = SIGNATURE_LENGTH;
    const layerCount = view.getInt32(off, false); off += 4;
    const totalVertices = view.getInt32(off, false); off += 4;
    if (layerCount <= 0 || layerCount > 16) {
      throw new Error(`implausible layerCount ${layerCount}; is the file big-endian?`);
    }

    this.disposeBuffers();
    let uploaded = 0;
    const layers = [];
    // Bounds are accumulated across every layer. Initialising them from the first vertex inside
    // the loop instead would restart them per layer, leaving the box describing only the last one —
    // a wrong radius then frames the camera on a corner of the build.
    let seen = false;
    const lo = [0, 0, 0];
    const hi = [0, 0, 0];
    for (let i = 0; i < layerCount; i++) {
      const ordinal = view.getInt32(off, false); off += 4;
      const quads = view.getInt32(off, false); off += 4;
      const floats = quads * VERTICES_PER_QUAD * FLOATS_PER_VERTEX;
      if (quads < 0 || off + floats * 4 > buffer.byteLength) {
        throw new Error(`layer ${i} truncated: need ${floats * 4} bytes, `
          + `have ${buffer.byteLength - off}`);
      }
      // Big-endian floats must be swapped into host order before the GPU sees them.
      const data = new Float32Array(floats);
      for (let k = 0; k < floats; k++) data[k] = view.getFloat32(off + k * 4, false);
      off += floats * 4;

      const vbo = gl.createBuffer();
      gl.bindBuffer(gl.ARRAY_BUFFER, vbo);
      gl.bufferData(gl.ARRAY_BUFFER, data, gl.STATIC_DRAW);
      this.buffers.push(vbo);

      // Translucent layers are split into depth buckets along Y. Blending is order-dependent and a
      // static vertex order is only correct for the one camera the CPU rasteriser sorted for, so a
      // dragged 3D view showed glass over water in arbitrary order — the "grey glass" gap between
      // the export PNG and the live view. Buckets can be re-ordered per frame cheaply; quads within
      // a bucket are near each other, so the residual error is small.
      let layerEntry;
      if (ordinal >= 2) {
        const BUCKETS = 32;
        const yLo = this._bucketScanY(data);
        const yHi = this._bucketScanY(data, true);
        const step = Math.max(1e-3, (yHi - yLo) / BUCKETS);
        const parts = [];
        for (let b = 0; b < BUCKETS; b++) {
          const yMin = yLo + b * step, yMax = yMin + step;
          const idx = [];
          for (let q = 0; q < quads; q++) {
            let sum = 0;
            for (let vert = 0; vert < VERTICES_PER_QUAD; vert++) {
              sum += data[q * VERTICES_PER_QUAD * FLOATS_PER_VERTEX + vert * FLOATS_PER_VERTEX + 1];
            }
            const cy = sum / VERTICES_PER_QUAD;
            const bi = Math.min(BUCKETS - 1, Math.max(0, Math.floor((cy - yLo) / step)));
            if (bi === b) idx.push(q);
          }
          if (!idx.length) continue;
          const sub = new Float32Array(idx.length * VERTICES_PER_QUAD * FLOATS_PER_VERTEX);
          for (let n = 0; n < idx.length; n++) {
            sub.set(data.subarray(idx[n] * VERTICES_PER_QUAD * FLOATS_PER_VERTEX,
              (idx[n] + 1) * VERTICES_PER_QUAD * FLOATS_PER_VERTEX),
              n * VERTICES_PER_QUAD * FLOATS_PER_VERTEX);
          }
          const bVbo = gl.createBuffer();
          gl.bindBuffer(gl.ARRAY_BUFFER, bVbo);
          gl.bufferData(gl.ARRAY_BUFFER, sub, gl.STATIC_DRAW);
          this.buffers.push(bVbo);
          // A bucket's midpoint drives its draw order relative to the camera.
          const midY = yMin + step / 2;
          parts.push({ vbo: bVbo, quads: idx.length, key: midY });
        }
        layerEntry = { ordinal, quads, vbo, buckets: parts };
      } else {
        layerEntry = { ordinal, quads, vbo };
      }
      layers.push(layerEntry);
      uploaded += quads;

      for (let v = 0; v < floats; v += FLOATS_PER_VERTEX) {
        const x = data[v], y = data[v + 1], z = data[v + 2];
        if (!seen) {
          lo[0] = hi[0] = x; lo[1] = hi[1] = y; lo[2] = hi[2] = z;
          seen = true;
        }
        if (x < lo[0]) lo[0] = x;
        if (x > hi[0]) hi[0] = x;
        if (y < lo[1]) lo[1] = y;
        if (y > hi[1]) hi[1] = y;
        if (z < lo[2]) lo[2] = z;
        if (z > hi[2]) hi[2] = z;
      }
    }
    this.layers = layers;
    this.quadCount = uploaded;
    this.min = lo;
    this.max = hi;
    this.center = [(lo[0] + hi[0]) / 2, (lo[1] + hi[1]) / 2, (lo[2] + hi[2]) / 2];
    const dx = (hi[0] - lo[0]) / 2;
    const dy = (hi[1] - lo[1]) / 2;
    const dz = (hi[2] - lo[2]) / 2;
    this.radius = Math.max(1e-3, Math.sqrt(dx * dx + dy * dy + dz * dz));
    return { layers: layerCount, quads: uploaded, totalVertices, min: lo, max: hi, center: this.center, radius: this.radius };
  }

  /** Smallest (or largest) vertex Y in a layer's float data, for bucket bounds. */
  _bucketScanY(data, max = false) {
    let best = max ? -Infinity : Infinity;
    for (let v = 1; v < data.length; v += FLOATS_PER_VERTEX) {
      const y = data[v];
      if (max ? y > best : y < best) best = y;
    }
    return best === (max ? -Infinity : Infinity) ? 0 : best;
  }

  uploadAtlas(image) {
    const gl = this.gl;
    if (this.texture) gl.deleteTexture(this.texture);
    const tex = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, tex);
    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, image);
    // NEAREST keeps pixel art crisp and stops neighbouring atlas tiles bleeding into each other,
    // which is exactly what a linear filter would do at tile edges.
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    this.texture = tex;
  }

  disposeBuffers() {
    for (const b of this.buffers) this.gl.deleteBuffer(b);
    this.buffers = [];
    this.layers = [];
  }

  /**
   * Draws a frame. Cheap: no geometry is touched, only uniforms.
   *
   * @param cam.eye       camera position in world space
   * @param cam.target    look-at point
   * @param cam.up        up vector
   * @param cam.fovDeg    vertical field of view; 0 selects an orthographic projection
   * @param cam.orthoHeight half-height of the orthographic view volume
   */
  draw(cam) {
    const gl = this.gl;
    // Size from the viewport's own layout box, and never let the canvas influence it.
    //
    // Reading the parent's getBoundingClientRect() looked equivalent but was not: the canvas is
    // absolutely positioned inside that parent, so once its style width became the device-pixel
    // size the parent's rect grew to match and the next frame measured the child instead of the
    // viewport. The canvas then rendered at twice the visible size and only its top-left corner
    // showed — the model appeared shoved into the bottom-right although the projection was exact.
    // clientWidth/clientHeight are layout sizes and ignore an overflowing child.
    const dpr = window.devicePixelRatio || 1;
    const box = this.canvas.parentElement || this.canvas;
    const cssW = Math.max(1, Math.round(box.clientWidth || box.getBoundingClientRect().width));
    const cssH = Math.max(1, Math.round(box.clientHeight || box.getBoundingClientRect().height));
    const w = Math.round(cssW * dpr), h = Math.round(cssH * dpr);
    if (this.canvas.width !== w || this.canvas.height !== h) {
      this.canvas.width = w;
      this.canvas.height = h;
    }
    // The element's CSS size stays the layout size while the buffer above is the device-pixel size;
    // keeping the two in step is what makes one render texel land on one device pixel.
    if (this.canvas.style.width !== cssW + 'px') this.canvas.style.width = cssW + 'px';
    if (this.canvas.style.height !== cssH + 'px') this.canvas.style.height = cssH + 'px';
    this.diag = {
      vpClient: [box.clientWidth, box.clientHeight],
      vpRect: [Math.round(box.getBoundingClientRect().width), Math.round(box.getBoundingClientRect().height)],
      cvClient: [this.canvas.clientWidth, this.canvas.clientHeight],
      cvRect: [Math.round(this.canvas.getBoundingClientRect().width), Math.round(this.canvas.getBoundingClientRect().height)],
      buf: [w, h],
      dpr,
    };
    gl.viewport(0, 0, w, h);
    const bg = cam.background || [0, 0, 0, 0];
    gl.clearColor(bg[0], bg[1], bg[2], bg[3]);
    gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);
    if (!this.layers || !this.texture) return;

    gl.enable(gl.DEPTH_TEST);
    gl.depthFunc(gl.LEQUAL);
    gl.enable(gl.BLEND);
    gl.blendFuncSeparate(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA, gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
    gl.enable(gl.CULL_FACE);
    gl.cullFace(gl.BACK);

    const aspect = w / h;
    // Orbit mode follows quickcraft's preview exactly: the projection is a FIXED +-1 box (widened
    // by aspect), and all scaling happens in the model-view matrix. Its PreviewDimensions
    // .scaleFactor() shrinks the model's bounding-box diagonal to fill that box:
    //     scale = previewSize * 2 * padding / (diagonal * screenHeight)
    // Doing it the other way round — sizing the orthographic box to the model — double-counts the
    // fit and leaves the build off screen.
    let viewProj;
    if (cam.orbit) {
      // Same fit the CPU renderer uses (SchematicRenderer.fitCameraToMesh), which DiagCamera
      // verifies: rotate the bounding box, measure the real view-space span, then scale by
      // min(w/spanX, h/spanY) so neither axis overflows. A bounding-sphere radius over-estimates
      // for flat builds and under-estimates for long ones; the measured span is always right.
      const fit = this.fitOrtho(cam.rotationDeg, cam.slantDeg, w, h, cam.zoom);
      this.fitScale = fit.scale;
      this.spanX = fit.spanX;
      this.spanY = fit.spanY;
      this.offsetX = fit.offsetX;
      this.offsetY = fit.offsetY;

      // Build the matrix from the CPU renderer's screen mapping, which is simply
      //     screenX = width/2 + (viewX * zoom) + offsetX
      //     screenY = height/2 - (viewY * zoom) + offsetY
      // (see Rasterizer.drawQuad). Converting that to clip space — NDC is +-1 with y up, and the
      // rasteriser's y already grows downward — gives the three row scales below, with the offsets
      // expressed in NDC. Deriving it here rather than re-deriving a "fit" keeps the two renderers
      // in agreement by construction.
      const yaw = cam.rotationDeg * Math.PI / 180;
      const pitch = cam.slantDeg * Math.PI / 180;
      const cy = Math.cos(yaw), sy = Math.sin(yaw);
      const cp = Math.cos(pitch), sp = Math.sin(pitch);
      // RotX(pitch) * RotY(yaw), matching Camera.update() on the Java side.
      const r = [
        [cy, 0, sy],
        [sp * sy, cp, -sp * cy],
        [-cp * sy, sp, cp * cy],
      ];
      // Pixels per world unit converted to NDC per world unit.
      const scaleX = 2 * fit.scale / w;
      const scaleY = 2 * fit.scale / h;
      // Keep z in a sane range: scale it down so the model cannot fall outside the depth volume.
      const scaleZ = -2 / (this.radius * 8 + 64);
      const c = this.center;
      const m = new Float32Array(16);
      for (let col = 0; col < 3; col++) {
        m[col * 4 + 0] = r[0][col] * scaleX;
        m[col * 4 + 1] = r[1][col] * scaleY;
        m[col * 4 + 2] = r[2][col] * scaleZ;
      }
      // Translation: move the rotated model centre to the origin, then apply the fit's centring
      // offsets. The y offset keeps the rasteriser's downward sense.
      const rx = r[0][0] * c[0] + r[0][1] * c[1] + r[0][2] * c[2];
      const ry = r[1][0] * c[0] + r[1][1] * c[1] + r[1][2] * c[2];
      const rz = r[2][0] * c[0] + r[2][1] * c[1] + r[2][2] * c[2];
      m[12] = -rx * scaleX + fit.offsetX;
      m[13] = -ry * scaleY + fit.offsetY;
      m[14] = -rz * scaleZ;
      m[15] = 1;
      viewProj = m;
      this.lastM = Array.from(m);
      this.lastW = w;
      this.lastH = h;
    } else {
      const proj = perspective(cam.fovDeg * Math.PI / 180, aspect, 0.05, this.radius * 8 + 64);
      const view = lookAt(cam.eye, cam.target, cam.up || [0, 1, 0]);
      viewProj = multiply(proj, view);
    }

    gl.useProgram(this.program);
    gl.uniformMatrix4fv(this.uni.viewProj, false, viewProj);
    this.lastMatrix = viewProj;
    this.lastMode = cam.orbit ? 'orbit' : 'fly';

    gl.activeTexture(gl.TEXTURE0);
    gl.bindTexture(gl.TEXTURE_2D, this.texture);
    gl.uniform1i(this.uni.atlas, 0);

    const stride = FLOATS_PER_VERTEX * 4;
    for (const layer of this.layers) {
      // PORTAL(2), FLUID(3) and TRANSLUCENT(4) blend; the rest are opaque.
      const blending = layer.ordinal >= 2;
      gl.uniform1f(this.uni.alphaCutoff, layer.ordinal === 1 ? 0.1 : 0.0);

      // Culling stays ON for every layer. The exporter emits each face once, with the winding the
      // baker produced, so a face turned away from the camera is genuinely one we should not draw.
      // Disabling culling for blending layers made water draw its front and back surface into the
      // same pixels, and since blending layers do not write depth those two half-transparent
      // passes cancelled out — water became almost invisible.
      gl.enable(gl.CULL_FACE);
      gl.cullFace(gl.BACK);

      // Opaque layers write depth so later geometry is hidden correctly. Blending layers only read
      // depth: they must not occlude each other, because a single sorted order cannot be correct
      // for every viewpoint without an order-independent pass.
      gl.depthMask(!blending);

      const drawSlice = (vbo, count) => {
        gl.bindBuffer(gl.ARRAY_BUFFER, vbo);
        gl.enableVertexAttribArray(this.attr.pos);
        gl.vertexAttribPointer(this.attr.pos, 3, gl.FLOAT, false, stride, 0);
        gl.enableVertexAttribArray(this.attr.uv);
        gl.vertexAttribPointer(this.attr.uv, 2, gl.FLOAT, false, stride, 12);
        gl.enableVertexAttribArray(this.attr.shade);
        gl.vertexAttribPointer(this.attr.shade, 1, gl.FLOAT, false, stride, 20);
        gl.enableVertexAttribArray(this.attr.tint);
        gl.vertexAttribPointer(this.attr.tint, 3, gl.FLOAT, false, stride, 24);
        gl.drawArrays(gl.TRIANGLES, 0, count * VERTICES_PER_QUAD);
      };

      if (layer.buckets) {
        // Blending layers draw far-to-near relative to the camera so nearer glass composites over
        // farther water. The bucket key is a world-space Y; transform it by the view matrix and
        // sort by view-space depth. Only the buckets are ordered — quads inside one bucket are
        // within a slab of the build and their mutual order error is negligible.
        const m = viewProj;
        const ordered = layer.buckets.slice().sort((a, b) => {
          const za = m[2] * a.key + m[14];
          const zb = m[2] * b.key + m[14];
          return zb - za;    // larger view Z = farther = drawn first
        });
        for (const b of ordered) drawSlice(b.vbo, b.quads);
      } else {
        drawSlice(layer.vbo, layer.quads);
      }
    }
    gl.depthMask(true);
  }

  /**
   * Measures the build in view space and returns the scale that fits it.
   *
   * <p>Ported from {@code SchematicRenderer.fitCameraToMesh}, which the CPU renderer uses and
   * {@code DiagCamera} verifies. The eight corners of the axis-aligned box are rotated, the real
   * view-space extents are measured, and the smaller of the two axis fits wins — so a wide, flat
   * schematic is limited by width and a tall one by height, instead of both being approximated by a
   * single bounding-sphere radius.
   *
   * @returns {{scale:number, spanX:number, spanY:number, offsetX:number, offsetY:number}}
   *          scale is in pixels per world unit; offsets are in NDC.
   */
  fitOrtho(rotationDeg, slantDeg, width, height, zoom) {
    const yaw = rotationDeg * Math.PI / 180;
    const pitch = slantDeg * Math.PI / 180;
    const cy = Math.cos(yaw), sy = Math.sin(yaw);
    const cp = Math.cos(pitch), sp = Math.sin(pitch);
    // RotX(pitch) * RotY(yaw), matching Camera.update() on the Java side.
    const r = [
      [cy, 0, sy],
      [sp * sy, cp, -sp * cy],
      [-cp * sy, sp, cp * cy],
    ];
    const dx = (this.max[0] - this.min[0]) / 2;
    const dy = (this.max[1] - this.min[1]) / 2;
    const dz = (this.max[2] - this.min[2]) / 2;
    let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
    for (let i = 0; i < 8; i++) {
      const px = (i & 1) ? dx : -dx;
      const py = (i & 2) ? dy : -dy;
      const pz = (i & 4) ? dz : -dz;
      const vx = r[0][0] * px + r[0][1] * py + r[0][2] * pz;
      const vy = r[1][0] * px + r[1][1] * py + r[1][2] * pz;
      if (vx < minX) minX = vx;
      if (vx > maxX) maxX = vx;
      if (vy < minY) minY = vy;
      if (vy > maxY) maxY = vy;
    }
    const spanX = Math.max(1e-3, maxX - minX);
    const spanY = Math.max(1e-3, maxY - minY);
    // 0.92 leaves the same margin the CPU renderer uses.
    const scale = Math.min(width / spanX, height / spanY) * 0.92 * Math.max(0.05, zoom);
    const offsetX = -((minX + maxX) / 2) * scale / (width / 2);
    const offsetY = -((minY + maxY) / 2) * scale / (height / 2);
    return { scale, spanX, spanY, offsetX, offsetY };
  }
}

/**
 * Builds the orbit model-view matrix the way quickcraft does:
 * {@code translate(centre) * rotateX(pitch) * rotateY(yaw) * scale * translate(-centre)}.
 *
 * <p>The scale comes from {@code PreviewDimensions.scaleFactor}: the model's bounding-box diagonal
 * is fitted into the projection's fixed 2-unit height, measured in the preview's own pixels. That
 * is what keeps the build framed at any size and any rotation — a rotating box needs its diagonal,
 * not its widest side.
 */
function orbitModelView(view, rotationDeg, slantDeg, zoom, screenHeight) {
  const dx = view.max[0] - view.min[0];
  const dy = view.max[1] - view.min[1];
  const dz = view.max[2] - view.min[2];
  const diagonal = Math.max(1e-3, Math.sqrt(dx * dx + dy * dy + dz * dz));
  const padding = 0.95;
  // quickcraft normalises by the screen height because its projection is a fixed 2-unit box;
  // here the box is 2 units tall as well, so the same expression applies.
  const scale = (2 * padding) / diagonal * Math.max(0.05, zoom);

  const yaw = rotationDeg * Math.PI / 180;
  const pitch = slantDeg * Math.PI / 180;
  const cy = Math.cos(yaw), sy = Math.sin(yaw);
  const cp = Math.cos(pitch), sp = Math.sin(pitch);
  // RotX(pitch) * RotY(yaw), matching the CPU camera's convention.
  const r = [
    [cy, 0, sy],
    [sp * sy, cp, -sp * cy],
    [-cp * sy, sp, cp * cy],
  ];
  const c = view.center;
  const m = new Float32Array(16);
  for (let row = 0; row < 3; row++) {
    for (let col = 0; col < 3; col++) {
      m[col * 4 + row] = r[row][col] * scale;
    }
  }
  // Translation: rotate(-centre * scale), so the model centre lands at the origin.
  const t = [0, 0, 0];
  for (let row = 0; row < 3; row++) {
    t[row] = -(r[row][0] * c[0] + r[row][1] * c[1] + r[row][2] * c[2]) * scale;
  }
  m[12] = t[0]; m[13] = t[1]; m[14] = t[2]; m[15] = 1;
  return m;
}

function ortho(halfW, halfH, near, far) {
  return new Float32Array([
    1 / halfW, 0, 0, 0,
    0, 1 / halfH, 0, 0,
    0, 0, -2 / (far - near), 0,
    0, 0, -(far + near) / (far - near), 1,
  ]);
}

function perspective(fovy, aspect, near, far) {
  const f = 1 / Math.tan(fovy / 2);
  return new Float32Array([
    f / aspect, 0, 0, 0,
    0, f, 0, 0,
    0, 0, (far + near) / (near - far), -1,
    0, 0, (2 * far * near) / (near - far), 0,
  ]);
}

function lookAt(eye, target, up) {
  const z = normalize([eye[0] - target[0], eye[1] - target[1], eye[2] - target[2]]);
  const x = normalize(cross(up, z));
  const y = cross(z, x);
  return new Float32Array([
    x[0], y[0], z[0], 0,
    x[1], y[1], z[1], 0,
    x[2], y[2], z[2], 0,
    -dot(x, eye), -dot(y, eye), -dot(z, eye), 1,
  ]);
}

function multiply(a, b) {
  const out = new Float32Array(16);
  for (let c = 0; c < 4; c++) {
    for (let r = 0; r < 4; r++) {
      let s = 0;
      for (let k = 0; k < 4; k++) s += a[k * 4 + r] * b[c * 4 + k];
      out[c * 4 + r] = s;
    }
  }
  return out;
}

function cross(a, b) {
  return [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
}
function dot(a, b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
function normalize(v) {
  const len = Math.hypot(v[0], v[1], v[2]) || 1;
  return [v[0] / len, v[1] / len, v[2] / len];
}