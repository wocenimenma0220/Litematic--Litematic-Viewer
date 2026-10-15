struct Camera { matrix: mat4x4<f32> };
@group(0) @binding(0) var<uniform> camera: Camera;
@group(0) @binding(1) var atlas: texture_2d<f32>;
@group(0) @binding(2) var atlas_sampler: sampler;
@group(0) @binding(3) var<storage, read> tiles: array<vec4<f32>>;
struct Out {
    @builtin(position) position: vec4<f32>,
    @location(0) uv: vec2<f32>,
    @location(1) color: vec4<f32>,
    @location(2) tile: vec4<f32>,
};
@vertex fn vs_main(@location(0) position: vec3<f32>, @location(1) uv: vec2<f32>, @location(2) color: vec4<f32>, @location(3) tile_id: u32) -> Out {
    var out: Out;
    out.position = camera.matrix * vec4(position, 1.0);
    out.uv = uv;
    out.color = color;
    out.tile = tiles[tile_id];
    return out;
}
fn sample_tile(in: Out) -> vec4<f32> {
    var uv = in.uv;
    var scale = vec2(1.0);
    if (in.tile.z > 0.0) {
        uv = in.tile.xy + fract(in.uv) * in.tile.zw;
        scale = in.tile.zw;
    }
    // Gradients are evaluated before fract: tile boundaries must not select
    // coarse mip levels just because local coordinates wrap from 1 to 0.
    return textureSampleGrad(atlas, atlas_sampler, uv, dpdx(in.uv) * scale, dpdy(in.uv) * scale);
}
@fragment fn fs_solid(in: Out) -> @location(0) vec4<f32> {
    let texel = sample_tile(in);
    if (texel.a < 0.5) { discard; }
    return vec4(texel.rgb * in.color.rgb, 1.0);
}
@fragment fn fs_blend(in: Out) -> @location(0) vec4<f32> {
    let texel = sample_tile(in);
    let alpha = texel.a * in.color.a;
    if (alpha < 0.004) { discard; }
    return vec4(texel.rgb * in.color.rgb, alpha);
}
struct VoxelOut {
    @builtin(position) position: vec4<f32>,
    @location(0) color: vec4<f32>,
};
@vertex fn vs_voxel(@location(0) packed: vec4<u32>, @location(1) origin: vec3<f32>) -> VoxelOut {
    var out: VoxelOut;
    out.position = camera.matrix * vec4(origin + vec3<f32>(packed.xyz) - vec3(0.5), 1.0);
    let key = packed.w & 127u;
    var rgb = vec3(f32(key) / 7.0);
    if (key >= 8u) {
        var code = key - 7u;
        if (code >= 31u) { code += 1u; }
        if (code >= 62u) { code += 1u; }
        if (code >= 93u) { code += 1u; }
        rgb = vec3(f32(code / 25u), f32((code / 5u) % 5u), f32(code % 5u)) / 4.0;
    }
    out.color = vec4(rgb, select(1.0, 128.0 / 255.0, (packed.w & 128u) != 0u));
    return out;
}
@vertex fn vs_outline(@location(0) position: vec3<f32>) -> VoxelOut {
    var out: VoxelOut;
    out.position = camera.matrix * vec4(position, 1.0);
    out.color = vec4(1.0, 225.0 / 255.0, 55.0 / 255.0, 160.0 / 255.0);
    return out;
}
@fragment fn fs_voxel(in: VoxelOut) -> @location(0) vec4<f32> {
    return in.color;
}
camera uniformshared atlas tile rectanglesMinecraft texture atlasvalidated atlas pixels���@