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

const SIGNATURE = 'LMESH4';
const SIGNATURE_LENGTH = 8;
const FLOATS_PER_VERTEX = 9;
/** Unique corners; triangle indices share the 0-2 diagonal, matching the CPU renderer. */
const VERTICES_PER_QUAD = 4;
const INDICES_PER_QUAD = 6;
const QUAD_TRIANGLES = [0,1,2,0,2,3];
const INDEX16_QUADS = 16384;
/** SceneMesh.Layer ordinals, in draw order. */
const LAYER_NAMES = ['SOLID', 'CUTOUT', 'PORTAL', 'FLUID', 'TRANSLUCENT'];

const VERTEX_SHADER = `
attribute vec3 aPos;
attribute vec2 aUv;
attribute vec3 aColor;

uniform mat4 uViewProj;

varying vec2 vUv;
varying vec3 vColor;

void main() {
  gl_Position = uViewProj * vec4(aPos, 1.0);
  vUv = aUv;
  vColor = aColor;
}
`;

const FRAGMENT_SHADER = `
precision highp float;

uniform sampler2D uAtlas;
uniform float uAlphaCutoff;
uniform float uColorOnly;

varying vec2 vUv;
varying vec3 vColor;

void main() {
  vec4 tex = uColorOnly > 0.5 ? vec4(1.0) : texture2D(uAtlas, vUv);
  // Cutout layers discard fully transparent texels so they neither blend nor write depth. This is
  // what lets a glass pane's empty middle show what is behind it.
  if (uAlphaCutoff > 0.0 && tex.a < uAlphaCutoff) discard;
  if (tex.a <= 0.0) discard;
  vec3 rgb = tex.rgb * vColor;
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
      // Source-over produces premultiplied RGB; the browser compositor must use the same convention.
      premultipliedAlpha: true,
      preserveDrawingBuffer: false,
    });
    if (!gl) throw new Error('WebGL unavailable');
    this.gl = gl;
    this.program = this._program(VERTEX_SHADER, FRAGMENT_SHADER);
    this.attr = {
      pos: gl.getAttribLocation(this.program, 'aPos'),
      uv: gl.getAttribLocation(this.program, 'aUv'),
      color: gl.getAttribLocation(this.program, 'aColor'),
    };
    this.uni = {
      viewProj: gl.getUniformLocation(this.program, 'uViewProj'),
      atlas: gl.getUniformLocation(this.program, 'uAtlas'),
      alphaCutoff: gl.getUniformLocation(this.program, 'uAlphaCutoff'),
      colorOnly: gl.getUniformLocation(this.program, 'uColorOnly'),

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
    this.revision = 0;
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
    if (![SIGNATURE, 'LMESH3', 'LMESH2', 'LMESH1'].includes(sig)) throw new Error(`bad signature "${sig}"`);
    const fileVerticesPerQuad = sig === 'LMESH1' ? 6 : 4;

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
    this.uintIndices = !!gl.getExtension('OES_element_index_uint');
    // Bounds are accumulated across every layer. Initialising them from the first vertex inside
    // the loop instead would restart them per layer, leaving the box describing only the last one —
    // a wrong radius then frames the camera on a corner of the build.
    let seen = false;
    const lo = [0, 0, 0];
    const hi = [0, 0, 0];
    for (let i = 0; i < layerCount; i++) {
      const ordinal = view.getInt32(off, false); off += 4;
      const quads = view.getInt32(off, false); off += 4;
      const fileFloats = quads * fileVerticesPerQuad * FLOATS_PER_VERTEX;
      const floats = quads * VERTICES_PER_QUAD * FLOATS_PER_VERTEX;
      if (quads < 0 || off + fileFloats * 4 > buffer.byteLength) {
        throw new Error(`layer ${i} truncated: need ${floats * 4} bytes, `
          + `have ${buffer.byteLength - off}`);
      }
      // Big-endian floats must be swapped into host order before the GPU sees them.
      let data = new Float32Array(floats);
      for (let q=0;q<quads;q++) for(let corner=0;corner<4;corner++) for(let value=0;value<9;value++) {
        const sourceCorner=fileVerticesPerQuad===6 && corner===3 ? 5 : corner;
        data[q*36+corner*9+value]=view.getFloat32(off+(q*fileVerticesPerQuad*9+sourceCorner*9+value)*4,false);
      }
      off += fileFloats * 4;
      // Light, AO and biome tint are static. Bake their product once on upload; tint is
      // constant across each exported face, so interpolation matches the old shader.
      // Keep the wire stride for compatibility with all three existing mesh formats.
      for (let v = 0; v < floats; v += FLOATS_PER_VERTEX) {
        const shade = data[v + 5];
        data[v + 6] *= shade;
        data[v + 7] *= shade;
        data[v + 8] *= shade;
      }

      let vbo = null, indexBuffer = null, chunks = null;
      if (ordinal < 2 || ordinal === 5) {
        const partition=partitionGeometry(data,true);data=partition.data;chunks=partition.chunks;
        vbo = gl.createBuffer();
        gl.bindBuffer(gl.ARRAY_BUFFER, vbo);
        gl.bufferData(gl.ARRAY_BUFFER, data, gl.STATIC_DRAW);
        this.buffers.push(vbo);
        indexBuffer=gl.createBuffer();this.buffers.push(indexBuffer);
        const indices=this.uintIndices?new Uint32Array(quads*6):new Uint16Array(quads*6);
        for(let q=0;q<quads;q++)for(let k=0;k<6;k++)indices[q*6+k]=(this.uintIndices?q:q%INDEX16_QUADS)*4+QUAD_TRIANGLES[k];
        gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER,indexBuffer);
        gl.bufferData(gl.ELEMENT_ARRAY_BUFFER,indices,gl.STATIC_DRAW);
      }

      layers.push({ ordinal, quads, vbo, data, indexBuffer, chunks });
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
      if(ordinal<2 || ordinal===5)layers[layers.length-1].data=null;
    }
    // All blending materials share one depth order, including glass, water and portals.
    let bspTree=null;
    if(sig==='LMESH3'||sig==='LMESH4') {
      if(off+4>buffer.byteLength)throw new Error('missing BSP metadata');
      const nodeCount=view.getInt32(off,false);off+=4;
      if(nodeCount<0||off+nodeCount*48>buffer.byteLength||(sig==='LMESH3'&&off+nodeCount*48!==buffer.byteLength))throw new Error('invalid BSP metadata');
      if(nodeCount){bspTree=[];for(let i=0;i<nodeCount;i++) {
        const plane=[];for(let k=0;k<4;k++){plane.push(view.getFloat64(off,false));off+=8;}
        const node={plane};for(const key of ['first','count','front','back']){node[key]=view.getInt32(off,false);off+=4;}
        if(node.front>=nodeCount||node.back>=nodeCount||node.first<0||node.count<0)throw new Error('invalid BSP node');
        bspTree.push(node);
      }}
    }
    const blended = layers.filter(l => l.ordinal >= 2 && l.ordinal < 5 && l.quads > 0);
    const blendQuads = blended.reduce((n, l) => n + l.quads, 0);
    if (blendQuads) {
      const data = new Float32Array(blendQuads * VERTICES_PER_QUAD * FLOATS_PER_VERTEX);
      let offset = 0;
      for (const l of blended) { data.set(l.data, offset); offset += l.data.length; }
      const vbo = gl.createBuffer();
      gl.bindBuffer(gl.ARRAY_BUFFER, vbo);
      gl.bufferData(gl.ARRAY_BUFFER, data, gl.STATIC_DRAW);
      this.buffers.push(vbo);
      const centroids = new Float64Array(blendQuads * 3);
      const step = VERTICES_PER_QUAD * FLOATS_PER_VERTEX;
      // Average the four unique corners.
      for (let q = 0; q < blendQuads; q++) {
        for (let axis = 0; axis < 3; axis++) {
          const b = q * step + axis;
          const triangular=data[q*step+27]===data[q*step+18]&&data[q*step+28]===data[q*step+19]&&data[q*step+29]===data[q*step+20];
          centroids[q * 3 + axis] = (data[b] + data[b + 9] + data[b + 18] + (triangular?0:data[b + 27])) / (triangular?3:4);
        }
      }
      const uintIndices = this.uintIndices;
      const indexBuffer = uintIndices ? gl.createBuffer() : null;
      if (indexBuffer) this.buffers.push(indexBuffer);
      layers.splice(0, layers.length, ...layers.filter(l => l.ordinal < 2 || l.ordinal === 5), {
        ordinal: 4, quads: blendQuads, vbo, data, centroids, indexBuffer,
        indices: uintIndices ? new Uint32Array(blendQuads * INDICES_PER_QUAD) : null,
        sortedData: uintIndices ? null : new Float32Array(blendQuads*6*9),
        chunks:partitionGeometry(data,false).chunks,
        bspTree,
        order: Uint32Array.from({ length: blendQuads }, (_, i) => i),
        depths: new Float64Array(blendQuads), lastDirection: null,
      });
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
    if(sig==='LMESH4') {
      if(off+4>buffer.byteLength)throw new Error('missing section visibility');
      const count=view.getInt32(off,false);off+=4;
      if(count<0||off+count*530!==buffer.byteLength)throw new Error('invalid section visibility');
      const sections=[];
      for(let i=0;i<count;i++) {
        const position=[];for(let axis=0;axis<3;axis++){position.push(view.getInt32(off,false));off+=4;}
        const exits=bytes.slice(off,off+6);off+=6;
        const solid=bytes.slice(off,off+512);off+=512;
        sections.push({position,exits,solid});
      }
      this.sectionGraph=makeSectionGraph(sections,lo,hi,this.layers);
    }
    return { layers: layerCount, quads: uploaded, totalVertices, min: lo, max: hi, center: this.center, radius: this.radius };
  }

  /** Read large scenes in bounded batches; never retain the entire wire-format file. */
  async uploadMeshStream(read, byteLength, progress = () => {}) {
    const gl=this.gl, batchQuads=32768;
    const bytes=async(offset,length)=>{
      if(!Number.isSafeInteger(length)||length<0||offset+length>byteLength)throw new Error('模型文件不完整。');
      const result=new Uint8Array(length);
      for(let at=0;at<length;at+=8*1024*1024) {
        const count=Math.min(8*1024*1024,length-at),part=await read(offset+at,count);
        if(part.byteLength!==count)throw new Error('模型分块长度不正确。');
        result.set(new Uint8Array(part),at);
      }
      return result;
    };
    const header=new DataView((await bytes(0,16)).buffer);
    const sig=String.fromCharCode(...new Uint8Array(header.buffer,0,6));
    if(!['LMESH1','LMESH2','LMESH3','LMESH4'].includes(sig))throw new Error('无法识别模型格式。');
    const layerCount=header.getInt32(8,false),totalVertices=header.getInt32(12,false),fileCorners=sig==='LMESH1'?6:4;
    if(layerCount<1||layerCount>16)throw new Error('无效的模型层数。');
    let offset=16,total=0,blendQuads=0;
    const parts=[];
    for(let i=0;i<layerCount;i++) {
      const info=new DataView((await bytes(offset,8)).buffer),ordinal=info.getInt32(0,false),quads=info.getInt32(4,false);
      offset+=8;
      if(ordinal<0||ordinal>5||quads<0||offset+quads*fileCorners*36>byteLength)throw new Error('无效的模型层。');
      parts.push({ordinal,quads,offset});offset+=quads*fileCorners*36;total+=quads;
      if(ordinal>=2&&ordinal<5)blendQuads+=quads;
    }
    if(total*fileCorners!==totalVertices)throw new Error('模型顶点总数不正确。');
    const metadataOffset=offset;
    this.disposeBuffers();this.uintIndices=!!gl.getExtension('OES_element_index_uint');
    const layers=[],lo=[Infinity,Infinity,Infinity],hi=[-Infinity,-Infinity,-Infinity];
    let blendData=blendQuads?new Float32Array(blendQuads*36):null,blendAt=0,loaded=0;
    const checkUpload=()=>{
      const error=gl.getError();
      if(gl.isContextLost()||error!==gl.NO_ERROR)throw new Error('显卡无法容纳此模型（WebGL '+error+'）；请缩小投影后重试。');
    };
    try {
      for(const part of parts)for(let first=0;first<part.quads;first+=batchQuads) {
        const count=Math.min(batchQuads,part.quads-first),wire=await bytes(part.offset+first*fileCorners*36,count*fileCorners*36);
        const source=new DataView(wire.buffer),blending=part.ordinal>=2&&part.ordinal<5;
        let data=blending?blendData.subarray(blendAt*36,(blendAt+count)*36):new Float32Array(count*36);
        for(let q=0;q<count;q++)for(let corner=0;corner<4;corner++) {
          const from=(q*fileCorners+(fileCorners===6&&corner===3?5:corner))*36,to=q*36+corner*9;
          for(let k=0;k<9;k++)data[to+k]=source.getFloat32(from+k*4,false);
          for(let axis=0;axis<3;axis++){lo[axis]=Math.min(lo[axis],data[to+axis]);hi[axis]=Math.max(hi[axis],data[to+axis]);}
          const shade=data[to+5];for(let color=6;color<9;color++)data[to+color]*=shade;
        }
        if(blending)blendAt+=count;
        else {
          const partition=partitionGeometry(data,true);data=partition.data;
          const vbo=gl.createBuffer();this.buffers.push(vbo);gl.bindBuffer(gl.ARRAY_BUFFER,vbo);gl.bufferData(gl.ARRAY_BUFFER,data,gl.STATIC_DRAW);
          const indexBuffer=gl.createBuffer();this.buffers.push(indexBuffer);
          const indices=this.uintIndices?new Uint32Array(count*6):new Uint16Array(count*6);
          for(let q=0;q<count;q++)for(let k=0;k<6;k++)indices[q*6+k]=(this.uintIndices?q:q%INDEX16_QUADS)*4+QUAD_TRIANGLES[k];
          gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER,indexBuffer);gl.bufferData(gl.ELEMENT_ARRAY_BUFFER,indices,gl.STATIC_DRAW);
          layers.push({ordinal:part.ordinal,quads:count,vbo,indexBuffer,chunks:partition.chunks,data:null});checkUpload();
        }
        loaded+=count;progress(loaded,total);
      }
      offset=metadataOffset;let bspTree=null;
      if(sig==='LMESH3'||sig==='LMESH4') {
        const count=new DataView((await bytes(offset,4)).buffer).getInt32(0,false);offset+=4;
        if(count<0||offset+count*48>byteLength)throw new Error('无效的透明排序数据。');
        bspTree=count?[]:null;
        for(let first=0;first<count;first+=32768) {
          const n=Math.min(32768,count-first),view=new DataView((await bytes(offset,n*48)).buffer);offset+=n*48;
          for(let i=0;i<n;i++) {
            const at=i*48,node={plane:Array.from({length:4},(_,k)=>view.getFloat64(at+k*8,false)),first:view.getInt32(at+32,false),count:view.getInt32(at+36,false),front:view.getInt32(at+40,false),back:view.getInt32(at+44,false)};
            if(node.first<0||node.count<0||node.first+node.count>blendQuads||node.front>=count||node.back>=count||node.front< -1||node.back< -1)throw new Error('无效的透明排序节点。');
            bspTree.push(node);
          }
        }
      }
      if(blendQuads) {
        const data=blendData,vbo=gl.createBuffer();this.buffers.push(vbo);
        gl.bindBuffer(gl.ARRAY_BUFFER,vbo);gl.bufferData(gl.ARRAY_BUFFER,data,gl.STATIC_DRAW);checkUpload();
        const centroids=new Float64Array(blendQuads*3);
        for(let q=0;q<blendQuads;q++) {
          const b=q*36,triangular=data[b+27]===data[b+18]&&data[b+28]===data[b+19]&&data[b+29]===data[b+20];
          for(let axis=0;axis<3;axis++)centroids[q*3+axis]=(data[b+axis]+data[b+9+axis]+data[b+18+axis]+(triangular?0:data[b+27+axis]))/(triangular?3:4);
        }
        const indexBuffer=this.uintIndices?gl.createBuffer():null;if(indexBuffer)this.buffers.push(indexBuffer);
        layers.push({ordinal:4,quads:blendQuads,vbo,data,centroids,indexBuffer,
          indices:this.uintIndices?new Uint32Array(blendQuads*6):null,sortedData:this.uintIndices?null:new Float32Array(blendQuads*54),
          chunks:partitionGeometry(data,false).chunks,bspTree,order:Uint32Array.from({length:blendQuads},(_,i)=>i),depths:new Float64Array(blendQuads),lastDirection:null});
        blendData=null;
      }
      this.layers=layers;this.quadCount=total;
      if(!total){lo.fill(0);hi.fill(0);}
      this.min=lo;this.max=hi;this.center=lo.map((value,i)=>(value+hi[i])/2);
      this.radius=Math.max(.001,Math.hypot(...lo.map((value,i)=>(hi[i]-value)/2)));
      if(sig==='LMESH4') {
        const count=new DataView((await bytes(offset,4)).buffer).getInt32(0,false);offset+=4;
        if(count<0||offset+count*530!==byteLength)throw new Error('无效的区段可见性数据。');
        const sections=[];
        for(let first=0;first<count;first+=1024) {
          const n=Math.min(1024,count-first),data=await bytes(offset,n*530),view=new DataView(data.buffer);offset+=n*530;
          for(let i=0;i<n;i++){const at=i*530;sections.push({position:[view.getInt32(at,false),view.getInt32(at+4,false),view.getInt32(at+8,false)],exits:data.slice(at+12,at+18),solid:data.slice(at+18,at+530)});}
        }
        this.sectionGraph=makeSectionGraph(sections,lo,hi,layers);
      } else if(offset!==byteLength)throw new Error('模型文件尾部不正确。');
      return {layers:layerCount,quads:total,totalVertices,min:lo,max:hi,center:this.center,radius:this.radius};
    } catch(error) {this.disposeBuffers();throw error;}
  }

  /** World-space look direction, opposite the third ROW of the view rotation. */
  _viewDir() {
    if (this.cameraEye && this.cameraTarget) {
      const f = [
        this.cameraTarget[0] - this.cameraEye[0],
        this.cameraTarget[1] - this.cameraEye[1],
        this.cameraTarget[2] - this.cameraEye[2],
      ];
      const len = Math.hypot(f[0], f[1], f[2]) || 1;
      return [f[0] / len, f[1] / len, f[2] / len];
    }
    const yaw = (this.cameraRotationDeg || 0) * Math.PI / 180;
    const pitch = (this.cameraSlantDeg || 0) * Math.PI / 180;
    const cy = Math.cos(yaw), sy = Math.sin(yaw);
    const cp = Math.cos(pitch), sp = Math.sin(pitch);
    // r = RotX(pitch) * RotY(yaw), rows as in Camera.update() on the Java side.
    const r = [
      [cy, 0, sy],
      [sp * sy, cp, -sp * cy],
      [-cp * sy, sp, cp * cy],
    ];
    const f = [-r[2][0], -r[2][1], -r[2][2]];
    const len = Math.hypot(f[0], f[1], f[2]) || 1;
    return [f[0] / len, f[1] / len, f[2] / len];
  }

  _sortTranslucent(layer, dir) {
    const last = layer.lastDirection;
    const count=layer.visibleCount ?? layer.quads;
    const revision=layer.visibilityVersion || 0;
    if(layer.bspTree) {
      if(!this._orderBsp(layer,dir))return;
    } else {
    if (last && layer.lastVisibilityVersion===revision && dir.every((v, i) => Math.abs(v - last[i]) < 1e-10)) return;
    for (let i = 0; i < count; i++) {
      const q=layer.visibleIds ? layer.visibleIds[i] : i;
      const b = q * 3;
      layer.depths[q] = dir[0] * layer.centroids[b]
        + dir[1] * layer.centroids[b + 1] + dir[2] * layer.centroids[b + 2];
    }
    if(count>2048)sortDepthOrder(layer);
    else {
      for(let i=0;i<count;i++)layer.order[i]=layer.visibleIds?layer.visibleIds[i]:i;
      layer.order.subarray(0,count).sort((a,b)=>layer.depths[b]-layer.depths[a]||a-b);
    }
    }
    const gl = this.gl;
    if (layer.indexBuffer) {
      for (let n = 0; n < count; n++) {
        for (let v = 0; v < INDICES_PER_QUAD; v++) {
          layer.indices[n * INDICES_PER_QUAD + v] = layer.order[n] * VERTICES_PER_QUAD + QUAD_TRIANGLES[v];
        }
      }
      gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, layer.indexBuffer);
      gl.bufferData(gl.ELEMENT_ARRAY_BUFFER, layer.indices.subarray(0,count*6), gl.DYNAMIC_DRAW);
    } else {
      const step = VERTICES_PER_QUAD * FLOATS_PER_VERTEX;
      if(!layer.sortedData || layer.sortedData.length!==layer.quads*6*9)layer.sortedData=new Float32Array(layer.quads*6*9);
      for (let n = 0; n < count; n++) {
        const b = layer.order[n] * step;
        for(let k=0;k<6;k++)layer.sortedData.set(layer.data.subarray(b+QUAD_TRIANGLES[k]*9,b+QUAD_TRIANGLES[k]*9+9),n*54+k*9);
      }
      gl.bindBuffer(gl.ARRAY_BUFFER, layer.vbo);
      gl.bufferData(gl.ARRAY_BUFFER, layer.sortedData.subarray(0,count*54), gl.DYNAMIC_DRAW);
    }
    layer.lastDirection = [...dir];
    layer.lastVisibilityVersion=revision;
  }

  _orderBsp(layer,dir) {
    const tree=layer.bspTree,eye=this.cameraEye;
    if(!layer.bspSides)layer.bspSides=new Uint8Array(tree.length).fill(2);
    const probe=eye||dir;
    const sameProbe=layer.bspProbe&&probe.every((value,i)=>value===layer.bspProbe[i])&&layer.bspPerspective===!!eye;
    let changed=false,fallback=layer.bspFallback||false;
    if(!sameProbe)for(let i=0;i<tree.length;i++) {
      const p=tree[i].plane;
      const side=eye?p[0]*eye[0]+p[1]*eye[1]+p[2]*eye[2]-p[3]:-p[0]*dir[0]-p[1]*dir[1]-p[2]*dir[2];
      const front=side>=0?1:0;
      if(layer.bspSides[i]!==front){layer.bspSides[i]=front;changed=true;}
      fallback ||= p[0]===0&&p[1]===0&&p[2]===0;
    }
    layer.bspProbe=[...probe];layer.bspPerspective=!!eye;layer.bspFallback=fallback;
    const directionChanged=!layer.lastDirection||dir.some((value,i)=>Math.abs(value-layer.lastDirection[i])>=1e-10);
    if(!changed&&layer.lastVisibilityVersion===(layer.visibilityVersion||0)&&(!fallback||!directionChanged))return false;
    let at=0;
    const visit=index=>{
      const node=tree[index],front=layer.bspSides[index],far=front?node.back:node.front,near=front?node.front:node.back;
      if(far>=0)visit(far);
      const p=node.plane;
      if(p[0]===0&&p[1]===0&&p[2]===0) {
        const faces=[];for(let id=node.first;id<node.first+node.count;id++)if(!layer.faceVisible||layer.faceVisible[id])faces.push(id);
        for(const id of faces){const b=id*3;layer.depths[id]=dir[0]*layer.centroids[b]+dir[1]*layer.centroids[b+1]+dir[2]*layer.centroids[b+2];}
        faces.sort((a,b)=>layer.depths[b]-layer.depths[a]||a-b);for(const id of faces)layer.order[at++]=id;
      } else for(let id=node.first;id<node.first+node.count;id++)if(!layer.faceVisible||layer.faceVisible[id])layer.order[at++]=id;
      if(near>=0)visit(near);
    };
    visit(0);
    if(at!==(layer.visibleCount??layer.quads))throw new Error('BSP traversal lost visible faces');
    layer.bspSorts=(layer.bspSorts||0)+1;
    return true;
  }

  _updateVisibility(layer, matrix, enabled,cam) {
    const chunks=layer.chunks;
    if(!chunks)return;
    if(!layer.chunkVisible)layer.chunkVisible=new Uint8Array(chunks.length).fill(2);
    let changed=false,count=0;
    for(let i=0;i<chunks.length;i++) {
      const visible=inRenderDistance(chunks[i],cam)&&(!enabled || (boxInFrustum(chunks[i],matrix)
        &&(!this.reachableSections||!chunks[i].sectionIds||chunks[i].sectionIds.some(id=>this.reachableSections[id]))));
      if(layer.chunkVisible[i]!==+visible){layer.chunkVisible[i]=+visible;changed=true;}
      if(visible)count+=chunks[i].faces.length;
    }
    if(changed) {
      layer.visibilityVersion=(layer.visibilityVersion||0)+1;
      if(layer.centroids) {
        if(!layer.visibleIds)layer.visibleIds=new Uint32Array(layer.quads);
        if(!layer.faceVisible)layer.faceVisible=new Uint8Array(layer.quads);
        layer.faceVisible.fill(0);
        for(let i=0;i<chunks.length;i++)if(layer.chunkVisible[i])for(const face of chunks[i].faces)layer.faceVisible[face]=1;
        let at=0;for(let face=0;face<layer.quads;face++)if(layer.faceVisible[face])layer.visibleIds[at++]=face;
      } else {
        // Camera motion often leaves the same chunks visible. Reuse the draw ranges
        // until visibility actually changes, instead of allocating them every frame.
        layer.drawRanges = [];
        for (let i = 0; i < chunks.length; i++) if (layer.chunkVisible[i]) {
          const chunk = chunks[i], last = layer.drawRanges[layer.drawRanges.length - 1];
          if (last && last.start + last.count === chunk.start) last.count += chunk.faces.length;
          else layer.drawRanges.push({ start: chunk.start, count: chunk.faces.length });
        }
      }
    }
    layer.visibleCount=count;
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
    this.revision++;
  }

  disposeBuffers() {
    this.uploadGeneration=(this.uploadGeneration||0)+1;
    if(this.stream){this.stream.dispose();this.stream=null;}
    for (const b of this.buffers) this.gl.deleteBuffer(b);
    this.buffers = [];
    this.layers = [];
    this.sectionGraph=null;
    this.reachableSections=null;
    this.revision++;
  }
  /** Perspective screen ray, using the exact matrix that drew the preview. */
  async uploadLodStream(read,byteLength,options={}) {
    this.disposeBuffers();const generation=this.uploadGeneration;
    const stream=await LodStream.open(this,read,byteLength,options);
    if(generation!==this.uploadGeneration||options.isCurrent&&!options.isCurrent()){stream.dispose();throw Error('预览任务已更新');}
    this.stream=stream;const manifest=stream.manifest;
    this.min=manifest.min;this.max=manifest.max;this.center=this.min.map((v,i)=>(v+this.max[i])/2);
    this.radius=Math.max(.001,Math.hypot(...this.min.map((v,i)=>(this.max[i]-v)/2)));this.quadCount=manifest.quads;
    this.sectionGraph=makeSectionGraph(manifest.sections,this.min,this.max,[{chunks:manifest.sections}]);
    return {quads:manifest.quads,totalVertices:manifest.quads*4,min:this.min,max:this.max,center:this.center,radius:this.radius,sections:manifest.sections.length};
  }

  setFrameBounds(min,max) {
    if(!Array.isArray(min)||!Array.isArray(max)||min.length!==3||max.length!==3||min.some((v,i)=>!Number.isFinite(v)||!Number.isFinite(max[i])||v>max[i]))return;
    this.min=min.slice();this.max=max.slice();this.center=min.map((v,i)=>(v+max[i])/2);
    this.radius=Math.max(.001,Math.hypot(...min.map((v,i)=>(max[i]-v)/2)));this.revision++;
  }

  rayFromScreen(clientX, clientY) {
    if (!this.lastMatrix || this.lastMode !== 'fly') return null;
    const box=this.canvas.getBoundingClientRect();
    if (box.width<=0 || box.height<=0) return null;
    const x=(clientX-box.left)/box.width*2-1,y=1-(clientY-box.top)/box.height*2;
    if (Math.abs(x)>1 || Math.abs(y)>1) return null;
    const inverse=inverseMatrix(this.lastMatrix);
    if (!inverse) return null;
    const point=z=>{
      const v=[x,y,z,1],p=[0,0,0,0];
      for(let r=0;r<4;r++)for(let c=0;c<4;c++)p[r]+=inverse[c*4+r]*v[c];
      if(Math.abs(p[3])<1e-12)return null;
      return p.slice(0,3).map(value=>value/p[3]);
    };
    const origin=point(-1),middle=point(0);
    if (!origin || !middle) return null;
    return {origin,direction:normalize(middle.map((value,i)=>value-origin[i])),maximum:this.radius*8+64};
  }

  /**
   * Draws a frame, updating transparent indices when the camera direction changes.
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
    const displayW = Math.max(1, Math.round(cssW * dpr)), displayH = Math.max(1, Math.round(cssH * dpr));
    this.lodViewportWidth=displayW;this.lodViewportHeight=displayH;
    const scale = Math.max(0.1, Math.min(1, cam.renderScale || 1));
    const w = Math.max(1, Math.round(displayW * scale)), h = Math.max(1, Math.round(displayH * scale));
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

    // Use full-size projection dimensions so changing quality cannot move the camera
    // or the picking ray. Only the raster buffer becomes smaller during interaction.
    const aspect = displayW / displayH;
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
      const fit = this.fitOrtho(cam.rotationDeg, cam.slantDeg, displayW, displayH, cam.zoom);
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
      const scaleX = 2 * fit.scale / displayW;
      const scaleY = 2 * fit.scale / displayH;
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
      const distance=Number.isInteger(cam.viewDistance)?Math.max(1,Math.min(128,cam.viewDistance)):6;
      const far=Math.max(this.radius*8+64,(distance+1)*16*Math.SQRT2+64);
      const proj = perspective(cam.fovDeg * Math.PI / 180, aspect, 0.05, far);
      const view = lookAt(cam.eye, cam.target, cam.up || [0, 1, 0]);
      viewProj = multiply(proj, view);
    }

    gl.useProgram(this.program);
    gl.uniform1f(this.uni.colorOnly,0);
    gl.uniformMatrix4fv(this.uni.viewProj, false, viewProj);
    this.lastMatrix = viewProj;
    this.lastMode = cam.orbit ? 'orbit' : 'fly';
    // Remembered for _viewDir(), which orders the translucent buckets by true view depth.
    this.cameraRotationDeg = cam.rotationDeg || 0;
    this.cameraSlantDeg = cam.slantDeg || 0;
    this.cameraEye = cam.orbit ? null : cam.eye;
    this.cameraTarget = cam.orbit ? null : cam.target;

    gl.activeTexture(gl.TEXTURE0);
    gl.bindTexture(gl.TEXTURE_2D, this.texture);
    gl.uniform1i(this.uni.atlas, 0);

    const stride = FLOATS_PER_VERTEX * 4;
    const frustum = frustumPlanes(viewProj);
    this.reachableSections=!cam.orbit&&cam.culling!==false&&cam.occlusion!==false
      ? visibleSections(this.sectionGraph,cam.eye,frustum):null;
    this.drawStats={totalQuads:this.quadCount,visibleQuads:0,visibleChunks:0,totalChunks:0,drawCalls:0};
    if(this.stream){this.stream.draw(cam,frustum);return;}
    for (const layer of this.layers) {
      if (layer.quads === 0) continue;
      this._updateVisibility(layer,frustum,cam.culling!==false,cam);
      this.drawStats.visibleQuads+=layer.visibleCount??layer.quads;
      this.drawStats.totalChunks+=layer.chunks?.length||0;
      this.drawStats.visibleChunks+=layer.chunkVisible?.reduce((sum,value)=>sum+(value===1),0)||0;
      if(layer.visibleCount===0)continue;
      // PORTAL(2), FLUID(3) and TRANSLUCENT(4) blend; the rest are opaque.
      const mask=layer.ordinal===5;
      const blending = layer.ordinal >= 2&&!mask;
      if(blending)gl.enable(gl.BLEND);else gl.disable(gl.BLEND);
      gl.colorMask(!mask,!mask,!mask,!mask);
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

      const bindVertices = (vbo,base=0) => {
        gl.bindBuffer(gl.ARRAY_BUFFER, vbo);
        gl.enableVertexAttribArray(this.attr.pos);
        gl.vertexAttribPointer(this.attr.pos, 3, gl.FLOAT, false, stride, base);
        gl.enableVertexAttribArray(this.attr.uv);
        gl.vertexAttribPointer(this.attr.uv, 2, gl.FLOAT, false, stride, base+12);
        gl.enableVertexAttribArray(this.attr.color);
        gl.vertexAttribPointer(this.attr.color, 3, gl.FLOAT, false, stride, base+24);
      };

      if (layer.centroids) {
        this._sortTranslucent(layer, this._viewDir());
        if (layer.indexBuffer) {
          bindVertices(layer.vbo);
          gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, layer.indexBuffer);
          gl.drawElements(gl.TRIANGLES, (layer.visibleCount??layer.quads) * INDICES_PER_QUAD, gl.UNSIGNED_INT, 0);
          this.drawStats.drawCalls++;
        } else {
          bindVertices(layer.vbo);
          gl.drawArrays(gl.TRIANGLES,0,(layer.visibleCount??layer.quads)*6);
          this.drawStats.drawCalls++;
        }
      } else {
        gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER,layer.indexBuffer);
        // Merge adjacent visible chunks; cap 16-bit ranges at the local vertex-address boundary.
        for(const range of layer.drawRanges) {
          let start=range.start,remaining=range.count;
          while(remaining) {
            const batchBase=this.uintIndices?0:Math.floor(start/INDEX16_QUADS)*INDEX16_QUADS;
            const count=this.uintIndices?remaining:Math.min(remaining,INDEX16_QUADS-(start-batchBase));
            bindVertices(layer.vbo,batchBase*4*stride);
            gl.drawElements(gl.TRIANGLES,count*6,this.uintIndices?gl.UNSIGNED_INT:gl.UNSIGNED_SHORT,start*6*(this.uintIndices?4:2));
            this.drawStats.drawCalls++;start+=count;remaining-=count;
          }
        }
      }
    }
    gl.depthMask(true);
    gl.colorMask(true,true,true,true);
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

/** Conservative bounds of real vertices, including models extending outside their owning cell. */
function partitionGeometry(data,reorder) {
  const groups=new Map(),count=data.length/36;
  for(let q=0;q<count;q++) {
    let minX=Infinity,minY=Infinity,minZ=Infinity,maxX=-Infinity,maxY=-Infinity,maxZ=-Infinity,cx=0,cy=0,cz=0;
    for(let corner=0;corner<4;corner++) {
      const at=q*36+corner*9,x=data[at],y=data[at+1],z=data[at+2];
      minX=Math.min(minX,x);minY=Math.min(minY,y);minZ=Math.min(minZ,z);
      maxX=Math.max(maxX,x);maxY=Math.max(maxY,y);maxZ=Math.max(maxZ,z);
      cx+=x*.25;cy+=y*.25;cz+=z*.25;
    }
    const position=[Math.floor(cx/16),Math.floor(cy/16),Math.floor(cz/16)],key=position.join(',');
    let chunk=groups.get(key);
    if(!chunk){chunk={position,min:[minX,minY,minZ],max:[maxX,maxY,maxZ],faces:[]};groups.set(key,chunk);}
    else {
      chunk.min[0]=Math.min(chunk.min[0],minX);chunk.min[1]=Math.min(chunk.min[1],minY);chunk.min[2]=Math.min(chunk.min[2],minZ);
      chunk.max[0]=Math.max(chunk.max[0],maxX);chunk.max[1]=Math.max(chunk.max[1],maxY);chunk.max[2]=Math.max(chunk.max[2],maxZ);
    }
    chunk.faces.push(q);
  }
  const chunks=[...groups.values()];
  let result=data,at=0;
  if(reorder)result=new Float32Array(data.length);
  for(const chunk of chunks) {
    chunk.start=at;
    if(reorder)for(const face of chunk.faces){result.set(data.subarray(face*36,face*36+36),at*36);at++;}
    else at+=chunk.faces.length;
    chunk.faces=Uint32Array.from(chunk.faces);
  }
  return {data:result,chunks};
}

/** Grid includes an exterior air shell so paths around the build remain possible. */
function makeSectionGraph(sections,lo,hi,layers) {
  if(!sections.length)return null;
  const min=lo.map(value=>Math.floor(value/16)-1),max=hi.map(value=>Math.floor(value/16)+1);
  for(const section of sections)for(let axis=0;axis<3;axis++) {
    min[axis]=Math.min(min[axis],section.position[axis]-1);
    max[axis]=Math.max(max[axis],section.position[axis]+1);
  }
  const size=min.map((value,i)=>max[i]-value+1),[nx,ny,nz]=size,count=nx*ny*nz;
  // Widely separated regions use the existing frustum path instead of expanding empty space.
  if(!Number.isSafeInteger(count)||count>65536)return null;
  const index=(x,y,z)=>((y-min[1])*nz+z-min[2])*nx+x-min[0];
  const cells=new Array(count).fill(null),bounds=new Array(count);
  for(const section of sections)cells[index(...section.position)]=section;
  for(let y=min[1];y<=max[1];y++)for(let z=min[2];z<=max[2];z++)for(let x=min[0];x<=max[0];x++) {
    bounds[index(x,y,z)]={min:[x*16,y*16,z*16],max:[x*16+16,y*16+16,z*16+16]};
  }
  for(const layer of layers)for(const chunk of layer.chunks||[]) {
    const ids=[],a=chunk.min.map((v,i)=>Math.max(min[i],Math.floor((v-.001)/16))),b=chunk.max.map((v,i)=>Math.min(max[i],Math.floor((v+.001)/16)));
    for(let y=a[1];y<=b[1];y++)for(let z=a[2];z<=b[2];z++)for(let x=a[0];x<=b[0];x++)ids.push(index(x,y,z));
    chunk.sectionIds=Uint32Array.from(ids);
  }
  return {min,max,size,cells,bounds,index,reachable:new Uint8Array(count),entries:new Uint8Array(count),
    inFrustum:new Uint8Array(count),queue:new Uint32Array(count*6+1),voxelQueue:new Uint16Array(4096),voxelSeen:new Uint8Array(4096)};
}

const SECTION_STEPS=[[0,-1,0],[0,1,0],[0,0,-1],[0,0,1],[-1,0,0],[1,0,0]];
/** Faces reachable from the eye's actual air component; eye inside solid disables rejection. */
function eyeExits(graph,section,eye) {
  if(!section)return 63;
  const cell=(Math.floor(eye[0])&15)|((Math.floor(eye[2])&15)<<4)|((Math.floor(eye[1])&15)<<8);
  if(graph.eyeSection===section&&graph.eyeCell===cell)return graph.eyeFaces;
  const solid=i=>(section.solid[i>>3]&(1<<(i&7)))!==0;
  if(solid(cell))return -1;
  const seen=graph.voxelSeen,queue=graph.voxelQueue;seen.fill(0);seen[cell]=1;queue[0]=cell;
  let head=0,tail=1,faces=0;
  while(head<tail) {
    const i=queue[head++],x=i&15,z=(i>>4)&15,y=i>>8;
    if(y===0)faces|=1;if(y===15)faces|=2;if(z===0)faces|=4;if(z===15)faces|=8;if(x===0)faces|=16;if(x===15)faces|=32;
    for(let direction=0;direction<6;direction++) {
      const [dx,dy,dz]=SECTION_STEPS[direction],a=x+dx,b=y+dy,c=z+dz;
      if(a<0||a>15||b<0||b>15||c<0||c>15)continue;
      const next=a|(c<<4)|(b<<8);
      if(!seen[next]&&!solid(next)){seen[next]=1;queue[tail++]=next;}
    }
  }
  graph.eyeSection=section;graph.eyeCell=cell;graph.eyeFaces=faces;
  return faces;
}

function visibleSections(graph,eye,frustum) {
  if(!graph||graph.disabled||!eye)return null;
  const position=eye.map(v=>Math.floor(v/16));
  if(position.some((v,i)=>v<graph.min[i]||v>graph.max[i]))return null;
  const start=graph.index(...position),faces=eyeExits(graph,graph.cells[start],eye);
  if(faces<0)return null;
  const {reachable,entries,inFrustum,queue,cells,bounds,size}=graph,[nx,ny,nz]=size;
  reachable.fill(0);entries.fill(0);inFrustum.fill(2);
  let head=0,tail=1;queue[0]=start*8+6;reachable[start]=1;
  while(head<tail) {
    // A very open, huge graph can cost more than it saves. Keep the complete
    // frustum result for this scene rather than spending unbounded time per frame.
    if(head>8192){graph.disabled=true;return null;}
    const item=queue[head++],id=item>>>3,entry=item&7;
    const exits=entry===6?faces:cells[id]?cells[id].exits[entry]:63;
    const x=id%nx,z=Math.floor(id/nx)%nz,y=Math.floor(id/(nx*nz));
    for(let direction=0;direction<6;direction++)if(exits&(1<<direction)) {
      const [dx,dy,dz]=SECTION_STEPS[direction],a=x+dx,b=y+dy,c=z+dz;
      if(a<0||a>=nx||b<0||b>=ny||c<0||c>=nz)continue;
      const next=(b*nz+c)*nx+a,face=direction^1,bit=1<<face;
      if(entries[next]&bit)continue;
      if(inFrustum[next]===2)inFrustum[next]=+boxInFrustum(bounds[next],frustum);
      if(!inFrustum[next])continue;
      entries[next]|=bit;reachable[next]=1;queue[tail++]=next*8+face;
    }
  }
  return reachable;
}

/** Horizontal chunk radius; height is unrestricted and orbit previews retain the whole model. */
function inRenderDistance(section,cam) {
  if(!cam||cam.orbit||!cam.eye||!section.position)return true;
  const distance=Number.isInteger(cam.viewDistance)?Math.max(1,Math.min(128,cam.viewDistance)):6;
  return Math.abs(section.position[0]-Math.floor(cam.eye[0]/16))<=distance
    &&Math.abs(section.position[2]-Math.floor(cam.eye[2]/16))<=distance;
}

function frustumPlanes(matrix) {
  const planes = new Float64Array(24);
  let at = 0;
  for (let axis = 0; axis < 3; axis++) for (const sign of [-1, 1]) {
    for (let component = 0; component < 4; component++) {
      planes[at++] = matrix[component * 4 + 3] + sign * matrix[component * 4 + axis];
    }
  }
  return planes;
}

function boxInFrustum(box,planes) {
  // Test row 3 +/- rows 0,1,2. A box is rejected only when every corner is outside one plane.
  for(let plane=0;plane<24;plane+=4) {
    let maximum=planes[plane+3];
    for(let component=0;component<3;component++) {
      const normal=planes[plane+component];
      maximum+=normal*(normal>=0?box.max[component]+.001:box.min[component]-.001);
    }
    if(maximum<0)return false;
  }
  return true;
}

const DEPTH_LITTLE_ENDIAN=new Uint8Array(new Uint32Array([1]).buffer)[0]===1;
/** Stable radix order of exact 64-bit depths; preserves the previous depth/id comparator. */
function sortDepthOrder(layer) {
  const length=layer.visibleCount??layer.quads;
  const capacity=layer.quads;
  if(!layer.radix)layer.radix={
    words:new Uint32Array(layer.depths.buffer),low:new Uint32Array(capacity),high:new Uint32Array(capacity),
    scratch:new Uint32Array(capacity),positions:new Uint32Array(256),
  };
  const {words,low,high,positions}=layer.radix;
  const lo=DEPTH_LITTLE_ENDIAN?0:1,hi=1-lo;
  let order=layer.order,scratch=layer.radix.scratch;
  for(let i=0;i<length;i++) {
    const id=layer.visibleIds?layer.visibleIds[i]:i;
    order[i]=id; // Equal depths retain original face order, including after camera reversals.
    if(layer.depths[id]===0)layer.depths[id]=0; // +0 and -0 compare equal.
    const lower=words[id*2+lo],upper=words[id*2+hi];
    const negative=layer.depths[id]<0;
    low[id]=negative?lower:~lower;
    high[id]=negative?upper:upper^0x7fffffff;
  }
  for(let pass=0;pass<8;pass++) {
    const keys=pass<4?low:high,shift=(pass%4)*8;
    positions.fill(0);
    for(let i=0;i<length;i++)positions[(keys[order[i]]>>>shift)&255]++;
    let start=0;
    for(let byte=0;byte<256;byte++){const count=positions[byte];positions[byte]=start;start+=count;}
    for(let i=0;i<length;i++){const id=order[i];scratch[positions[(keys[id]>>>shift)&255]++]=id;}
    const previous=order;order=scratch;scratch=previous;
  }
  layer.order=order;layer.radix.scratch=scratch;
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
function inverseMatrix(matrix) {
  const rows=Array.from({length:4},(_,r)=>Array.from({length:8},(_,c)=>c<4?matrix[c*4+r]:Number(c-4===r)));
  for(let c=0;c<4;c++){
    let pivot=c;
    for(let r=c+1;r<4;r++)if(Math.abs(rows[r][c])>Math.abs(rows[pivot][c]))pivot=r;
    if(Math.abs(rows[pivot][c])<1e-12)return null;
    [rows[c],rows[pivot]]=[rows[pivot],rows[c]];
    const scale=rows[c][c];for(let k=0;k<8;k++)rows[c][k]/=scale;
    for(let r=0;r<4;r++)if(r!==c){const factor=rows[r][c];for(let k=0;k<8;k++)rows[r][k]-=factor*rows[c][k];}
  }
  return Array.from({length:16},(_,i)=>rows[i%4][4+Math.floor(i/4)]);
}

function cross(a, b) {
  return [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
}
function dot(a, b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
function normalize(v) {
  const len = Math.hypot(v[0], v[1], v[2]) || 1;
  return [v[0] / len, v[1] / len, v[2] / len];
}
