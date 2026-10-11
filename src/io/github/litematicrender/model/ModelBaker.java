package io.github.litematicrender.model;

import io.github.litematicrender.resource.ResourceManager;
import io.github.litematicrender.util.MiniJson;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Model baker: resolves blockstates -> models -> quads.
 * Mirrors ModelBakery/ModelDiscovery/FaceBakery/SimpleModelWrapper (data parts).
 */
public final class ModelBaker {
    /** baked model: quads by cull face + AO flag + particle sprite */
    public static final class BakedModel {
        public final List<Quad> all = new ArrayList<>();
        public final List<Quad>[] byFace = new List[6];
        public final List<Quad> unculled = new ArrayList<>();
        public boolean useAmbientOcclusion = true;
        public String particleSprite;

        @SuppressWarnings("unchecked")
        public void addCulled(int face, Quad q) {
            if (byFace[face] == null) byFace[face] = new ArrayList<>();
            byFace[face].add(q);
            all.add(q);
        }

        public void addUnculled(Quad q) {
            unculled.add(q);
            all.add(q);
        }

        public List<Quad> quadsForFace(int face) {
            List<Quad> l = byFace[face];
            return l == null ? List.of() : l;
        }
    }

    /** A variant entry in blockstates: model + x/y rotation + uvlock */
    public record Variant(String modelId, int xRot, int yRot, int zRot, boolean uvlock) {}

    /**
     * A blockstate can resolve to several models at once (multipart).
     * Mirrors vanilla MultiPartBlockStateModel / MultiVariant: the resolved model is a LIST of variants.
     */
    public record VariantList(List<Variant> variants) {
        public static VariantList of(Variant v) { return new VariantList(List.of(v)); }
    }

    private final ResourceManager resources;
    private final Map<String, ModelDefinition> definitions = new ConcurrentHashMap<>();
    private final Map<String, BakedModel> bakedCache = new ConcurrentHashMap<>();
    /** blockId -> BlockInfo (from BlockRegistry) */
    private final io.github.litematicrender.world.BlockRegistry registry;

    public ModelBaker(ResourceManager resources, io.github.litematicrender.world.BlockRegistry registry) {
        this.resources = resources;
        this.registry = registry;
    }

    // ------------------------------------------------------------------
    // Model definition loading + parent chain

    public ModelDefinition definition(String modelId) {
        return this.definitions.computeIfAbsent(modelId, this::loadDefinition);
    }

    private ModelDefinition loadDefinition(String modelId) {
        String ns = "minecraft";
        String path = modelId;
        int colon = modelId.indexOf(':');
        if (colon >= 0) {
            ns = modelId.substring(0, colon);
            path = modelId.substring(colon + 1);
        }
        byte[] data = this.resources.getResource(ns, "models/" + path + ".json").orElse(null);
        if (data == null) return null;
        try {
            Map<String, Object> json = MiniJson.parseObject(new String(data, java.nio.charset.StandardCharsets.UTF_8));
            return ModelDefinition.parse(modelId, json);
        } catch (Exception e) {
            System.err.println("Failed to parse model " + modelId + ": " + e);
            return null;
        }
    }

    /**
     * Resolves the top-of-chain data following parent links.
     */
    private static final class Resolved {
        List<ModelDefinition.Element> elements;
        Map<String, String> textures;       // merged with child priority
        boolean ambientOcclusion = true;
        String particle;
        /** Slots whose reference asked for force_translucent. */
        java.util.Set<String> forceTranslucentSlots = java.util.Set.of();
    }

    private final Map<String, Resolved> resolvedCache = new ConcurrentHashMap<>();

    private Resolved resolveChain(String modelId) {
        Resolved r = this.resolvedCache.get(modelId);
        if (r != null) return r;
        // cycle guard
        if (this.resolving.contains(modelId)) return null;
        this.resolving.add(modelId);
        try {
            r = resolveChainInner(modelId);
        } finally {
            this.resolving.remove(modelId);
        }
        if (r != null) this.resolvedCache.put(modelId, r);
        return r;
    }

    private final java.util.Set<String> resolving = ConcurrentHashMap.newKeySet();

    private Resolved resolveChainInner(String modelId) {
        ModelDefinition def = this.definition(modelId);
        if (def == null) return null;
        if ("builtin/generated".equals(def.parent) || "builtin/entity".equals(def.parent)) {
            // special: item generated models are handled separately
        }
        Resolved parentResolved = def.parent != null && !def.parent.startsWith("builtin/") ? this.resolveChain(def.parent) : null;
        Resolved result = new Resolved();
        // textures: child overrides parent (child wins)
        Map<String, String> textures = new LinkedHashMap<>();
        if (parentResolved != null) textures.putAll(parentResolved.textures);
        textures.putAll(def.textures);
        result.textures = textures;
        // force_translucent slots follow the same inheritance as the textures themselves.
        java.util.Set<String> forceTranslucent = new java.util.LinkedHashSet<>();
        if (parentResolved != null) forceTranslucent.addAll(parentResolved.forceTranslucentSlots);
        forceTranslucent.addAll(def.forceTranslucentSlots);
        result.forceTranslucentSlots = forceTranslucent;
        // elements: nearest parent with elements (child's own elements win; child with elements ignores deeper parents' elements)
        if (def.elements != null) {
            result.elements = def.elements;
        } else if (parentResolved != null) {
            result.elements = parentResolved.elements;
        }
        // ambient occlusion: first defined wins
        if (def.ambientOcclusion != null) {
            result.ambientOcclusion = def.ambientOcclusion;
        } else if (parentResolved != null) {
            result.ambientOcclusion = parentResolved.ambientOcclusion;
        }
        // particle: resolve texture slot at the end
        String particle = textures.get("particle");
        result.particle = particle != null ? this.resolveTextureReference(particle, textures) : null;
        return result;
    }

    /**
     * Walks the same "#ref" chain the sprite lookup does, and reports whether the slot it lands on
     * declared {@code force_translucent}.
     */
    private static boolean resolvesToForceTranslucent(String value, Map<String, String> textures,
                                                      java.util.Set<String> forceSlots) {
        if (forceSlots == null || forceSlots.isEmpty() || value == null) return false;
        String slot = value;
        int depth = 0;
        while (slot.startsWith("#") && depth++ < 16) {
            String name = slot.substring(1);
            if (forceSlots.contains(name)) return true;
            slot = textures.get(name);
            if (slot == null) return false;
        }
        return false;
    }

    /** Resolves "#ref" chains to a concrete sprite id ("ns:path"). */
    public String resolveTextureReference(String value, Map<String, String> textures) {
        int depth = 0;
        while (value != null && value.startsWith("#") && depth++ < 16) {
            value = textures.get(value.substring(1));
        }
        return value;
    }

    // ------------------------------------------------------------------
    // Blockstate variant selection

    /** Blockstate → variant model for a concrete state. */
    public Variant selectVariant(String blockId, io.github.litematicrender.world.BlockState state) {
        List<Variant> all = this.selectVariants(blockId, state);
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * Blockstate → ALL models applying to a concrete state.
     * Variants: exactly one entry matches (or a weighted list). Multipart: every part whose
     * "when" condition holds contributes a model — vanilla MultiPartBlockStateModel.
     */
    public List<Variant> selectVariants(String blockId, io.github.litematicrender.world.BlockState state) {
        var info = this.registry.get(blockId);
        if (info == null) return List.of();
        Map<String, Object> json = info.definition;
        Map<String, Object> variants = MiniJson.asObject(json.get("variants"));
        if (variants != null) {
            Variant v = selectFromVariants(variants, state);
            return v == null ? List.of() : List.of(v);
        }
        List<Object> multipart = MiniJson.asArray(json.get("multipart"));
        if (multipart != null) {
            return selectFromMultipart(multipart, state);
        }
        return List.of();
    }

    // ------------------------------------------------------------------
    // Multipart (vanilla MultiPartBlockStateModel)

    /** Collects every multipart part whose condition passes. */
    private List<Variant> selectFromMultipart(List<Object> multipart, io.github.litematicrender.world.BlockState state) {
        List<Variant> out = new ArrayList<>();
        for (Object mpObj : multipart) {
            Map<String, Object> mp = MiniJson.asObject(mpObj);
            if (mp == null) continue;
            Map<String, Object> when = MiniJson.asObject(mp.get("when"));
            if (when != null && !conditionMatches(when, state)) continue;
            Object apply = mp.get("apply");
            addApplied(out, apply);
        }
        return out;
    }

    /** "apply" is either one variant object or a list of them. */
    private static void addApplied(List<Variant> out, Object apply) {
        if (apply instanceof List<?> list) {
            for (Object o : list) {
                Variant v = variantOf(o);
                if (v != null) out.add(v);
            }
        } else {
            Variant v = variantOf(apply);
            if (v != null) out.add(v);
        }
    }

    /**
     * Multipart condition semantics (vanilla MultiPartModelHelper):
     *   { "north": "true|up", "AND": [ {...}, ... ] }
     * A key maps to a value, a "|"-separated list of alternatives, or a boolean.
     * Missing properties in the state simply don't match.
     * "AND"/"OR" nest (OR is implicit between top-level keys).
     */
    static boolean conditionMatches(Map<String, Object> when, io.github.litematicrender.world.BlockState state) {
        for (Map.Entry<String, Object> e : when.entrySet()) {
            String key = e.getKey();
            if (key.equals("AND")) {
                List<Object> sub = MiniJson.asArray(e.getValue());
                if (sub != null) {
                    for (Object o : sub) {
                        Map<String, Object> m = MiniJson.asObject(o);
                        if (m != null && !conditionMatches(m, state)) return false;
                    }
                }
                continue;
            }
            if (key.equals("OR")) {
                List<Object> sub = MiniJson.asArray(e.getValue());
                boolean any = false;
                if (sub != null) {
                    for (Object o : sub) {
                        Map<String, Object> m = MiniJson.asObject(o);
                        if (m != null && conditionMatches(m, state)) { any = true; break; }
                    }
                }
                if (!any) return false;
                continue;
            }
            // plain property: value may be "a|b|c"
            String actual = state.getValue(key);
            if (actual == null) return false;
            // MiniJson may hand back a Boolean for `"waterlogged": true`
            String expected = e.getValue() instanceof Boolean b ? b.toString() : MiniJson.asString(e.getValue(), null);
            if (expected == null) return false;
            boolean ok = false;
            for (String alt : expected.split("\\|")) {
                if (alt.trim().equals(actual)) { ok = true; break; }
            }
            if (!ok) return false;
        }
        return true;
    }

    private Variant selectFromVariants(Map<String, Object> variants, io.github.litematicrender.world.BlockState state) {
        // 1) exact key match
        String exact = state.propertyString();
        if (!exact.isEmpty()) {
            Object direct = variants.get(exact.substring(1, exact.length() - 1));
            if (direct != null) {
                return variantOf(direct);
            }
        }
        // 2) partial match: all keys whose conditions are satisfied (vanilla picks best match)
        String bestKey = null;
        int bestScore = -1;
        for (String key : variants.keySet()) {
            if (key.isEmpty()) {
                if (bestScore < 0) { bestKey = key; bestScore = 0; }
                continue;
            }
            int score = matchScore(key, state);
            if (score >= 0 && score > bestScore) {
                bestKey = key;
                bestScore = score;
            }
        }
        if (bestKey == null) return null;
        return variantOf(variants.get(bestKey));
    }

    /** Number of matching properties, or -1 if any mismatch. */
    private static int matchScore(String key, io.github.litematicrender.world.BlockState state) {
        int score = 0;
        for (String part : key.split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0) return -1;
            String name = part.substring(0, eq).trim();
            String value = part.substring(eq + 1).trim();
            String actual = state.getValue(name);
            if (actual == null || !actual.equals(value)) return -1;
            score++;
        }
        return score;
    }

    private static Variant variantOf(Object obj) {
        // value can be a single variant object or a list (weighted)
        Map<String, Object> m = null;
        if (obj instanceof Map) {
            m = MiniJson.asObject(obj);
        } else if (obj instanceof List<?> list && !list.isEmpty()) {
            m = MiniJson.asObject(list.get(0));
        }
        if (m == null) return null;
        String model = MiniJson.asString(m.get("model"), null);
        if (model == null) return null;
        int x = MiniJson.asInt(m.get("x"), 0);
        int y = MiniJson.asInt(m.get("y"), 0);
        int z = MiniJson.asInt(m.get("z"), 0);
        boolean uvlock = MiniJson.asBool(m.get("uvlock"), false);
        return new Variant(model, x, y, z, uvlock);
    }

    // ------------------------------------------------------------------
    // Baking

    /** Bakes a model at a specific rotation. Keyed by modelId+rotation. */
    public BakedModel bake(String modelId, int xRot, int yRot, int zRot, boolean uvlock) {
        String key = modelId + "#" + xRot + "," + yRot + "," + zRot + (uvlock ? "u" : "");
        return this.bakedCache.computeIfAbsent(key, k -> this.bakeInner(modelId, xRot, yRot, zRot, uvlock));
    }

    public BakedModel bake(Variant variant) {
        return this.bake(variant.modelId(), variant.xRot(), variant.yRot(), variant.zRot(), variant.uvlock());
    }

    private BakedModel bakeInner(String modelId, int xRot, int yRot, int zRot, boolean uvlock) {
        Resolved resolved = this.resolveChain(modelId);
        BakedModel out = new BakedModel();
        if (resolved == null || resolved.elements == null) {
            out.particleSprite = "minecraft:missingno";
            return out;
        }
        out.useAmbientOcclusion = resolved.ambientOcclusion;
        out.particleSprite = resolved.particle != null ? resolved.particle : "minecraft:missingno";

        Transform t = Transform.of(xRot, yRot, zRot);
        for (ModelDefinition.Element el : resolved.elements) {
            this.bakeElement(out, modelId, resolved.textures, resolved.forceTranslucentSlots, el, t, uvlock);
        }
        return out;
    }

    private void bakeElement(BakedModel out, String modelId, Map<String, String> textures,
                             java.util.Set<String> forceTranslucentSlots,
                             ModelDefinition.Element el, Transform t, boolean uvlock) {
        float[] from = el.from();
        float[] to = el.to();
        // degenerate: zero-thickness axis only draws two faces
        boolean drawX = from[0] != to[0];
        boolean drawY = from[1] != to[1];
        boolean drawZ = from[2] != to[2];

        for (Map.Entry<String, ModelDefinition.Face> e : el.faces().entrySet()) {
            String faceName = e.getKey();
            ModelDefinition.Face face = e.getValue();
            int dir = faceIndex(faceName);
            if (dir < 0) continue;
            // vanilla: skip faces perpendicular to a flat element (thickness 0 on that axis)
            boolean shouldDraw = switch (dir) {
                case Quad.DOWN, Quad.UP -> drawX && drawZ;
                case Quad.NORTH, Quad.SOUTH -> drawX && drawY;
                case Quad.WEST, Quad.EAST -> drawY && drawZ;
                default -> true;
            };
            if (!shouldDraw) continue;

            String texRef = face.texture();
            String spriteId = this.resolveTextureReference(texRef, textures);
            if (spriteId == null || spriteId.startsWith("#")) {
                io.github.litematicrender.render.RenderDiagnostics.note("unresolved texture slot", modelId + " -> " + texRef);
                spriteId = "minecraft:missingno";
            }
            if (!spriteId.contains(":")) spriteId = "minecraft:" + spriteId;

            float[] uv = face.uv();
            if (uv == null) uv = defaultFaceUV(from, to, dir);

            // rotation of the cullface by the model transform
            String cullface = face.cullface();
            int cullDir = cullface != null ? t.rotateFace(faceIndex(cullface)) : -1;

            // Did the texture reference this face points at ask for force_translucent? The flag
            // lives on the texture slot, and the face may reach it through "#ref" indirection.
            boolean forceTranslucent = resolvesToForceTranslucent(texRef, textures, forceTranslucentSlots);
            Quad q = bakeQuad(from, to, uv, dir, spriteId, face.tintindex(), el, t, uvlock,
                face.rotationDegrees(), forceTranslucent);
            if (cullDir >= 0) {
                out.addCulled(cullDir, q);
            } else {
                out.addUnculled(q);
            }
        }
    }

    /**
     * Bakes a single quad (port of {@code FaceBakery.bakeQuad}).
     *
     * <p>Vertex positions follow {@code FaceInfo}; UVs follow {@code CuboidFace.getU/getV}:
     *
     * <pre>
     *   rawU = uvs.getVertexU(rotation.rotateVertexIndex(i))
     *   rawV = uvs.getVertexV(rotation.rotateVertexIndex(i))
     *   getVertexU(i) = (i == 0 || i == 1) ? minU : maxU
     *   getVertexV(i) = (i == 0 || i == 3) ? minV : maxV
     *   rotateVertexIndex(i) = (i + shift) % 4,  shift = faceRotation / 90
     * </pre>
     *
     * <p>The min/max form matters: a model may write {@code "uv": [0, 16, 16, 0]} (observer's top
     * face does), which is a legitimate flipped range, not a mistake. Treating the four numbers as
     * an ordered corner list instead of named min/max turns such a face upside-down.
     */
    private Quad bakeQuad(float[] from, float[] to, float[] uvIn, int face, String spriteId, int tintIndex,
                          ModelDefinition.Element el, Transform t, boolean uvlock, int faceRotationDeg,
                          boolean forceTranslucent) {
        // FaceInfo vertex selection: per face pick min/max coords
        int[][] faceVerts = FACE_VERTICES[face];
        float[] px = new float[4], py = new float[4], pz = new float[4];
        for (int i = 0; i < 4; i++) {
            px[i] = faceVerts[i][0] == 0 ? from[0] : to[0];
            py[i] = faceVerts[i][1] == 0 ? from[1] : to[1];
            pz[i] = faceVerts[i][2] == 0 ? from[2] : to[2];
            px[i] /= 16f; py[i] /= 16f; pz[i] /= 16f;
            // element rotation about origin
            if (el != null && el.rotation() != null) {
                double[] r = rotateVertex(px[i], py[i], pz[i], el.rotation());
                px[i] = (float) r[0]; py[i] = (float) r[1]; pz[i] = (float) r[2];
            }
            // model rotation about block center (0.5,0.5,0.5)
            double[] r = t.transformPoint(px[i] - 0.5, py[i] - 0.5, pz[i] - 0.5);
            px[i] = (float) (r[0] + 0.5); py[i] = (float) (r[1] + 0.5); pz[i] = (float) (r[2] + 0.5);
        }

        int effectiveFace = t.rotateFace(face);
        // UVs in 0..1 sprite space, exactly as CuboidFace.getU/getV produces them.
        float[] pu = new float[4], pv = new float[4];
        for (int i = 0; i < 4; i++) {
            int idx = (i + (faceRotationDeg / 90)) % 4;
            float rawU = (idx == 0 || idx == 1) ? uvIn[0] : uvIn[2];
            float rawV = (idx == 0 || idx == 3) ? uvIn[1] : uvIn[3];
            pu[i] = rawU / 16f;
            pv[i] = rawV / 16f;
        }
        // uvlock rotates the UVs with the model state, not the geometry.
        if (uvlock && (xRotOf(t) != 0 || yRotOf(t) != 0)) {
            float[][] rot = uvLockCorners(pu, pv, face, effectiveFace, t);
            pu = rot[0];
            pv = rot[1];
        }

        int shadeOverride = -1;
        if (el != null && el.shadeDirectionOverride() != null) {
            int so = faceIndex(el.shadeDirectionOverride());
            if (so >= 0) shadeOverride = t.rotateFace(so);
        }

        Quad q = new Quad(px, py, pz, pu, pv, effectiveFace, spriteId, tintIndex, shadeOverride, el != null ? el.lightEmission() : 0, forceTranslucent);
        // fix winding if the transform flipped it: reverse vertex order keeping uv attached
        if (!isCounterClockwise(q, effectiveFace)) {
            float[] rpx = {px[3], px[2], px[1], px[0]};
            float[] rpy = {py[3], py[2], py[1], py[0]};
            float[] rpz = {pz[3], pz[2], pz[1], pz[0]};
            float[] rpu = {pu[3], pu[2], pu[1], pu[0]};
            float[] rpv = {pv[3], pv[2], pv[1], pv[0]};
            q = new Quad(rpx, rpy, rpz, rpu, rpv, effectiveFace, spriteId, tintIndex, shadeOverride, el != null ? el.lightEmission() : 0, forceTranslucent);
        }
        return q;
    }

    /**
     * Applies the uvlock transform: the face's own rotation by the inverse of the model state's
     * face transformation, so the texture stays axis-aligned in world space instead of rotating
     * with the block. Corners rotate about the UV centre (0.5, 0.5), as vanilla does with
     * {@code cornerToCenter} / {@code centerToCorner}.
     */
    private static float[][] uvLockCorners(float[] u, float[] v, int face, int effectiveFace, Transform t) {
        // Which quarter-turns map the original face onto the rotated one.
        int turnsX = ((t.xRot % 360) + 360) % 360 / 90;
        int turnsY = ((t.yRot % 360) + 360) % 360 / 90;
        int steps = (turnsY * 3 + turnsX) % 4;      // inverse rotation, in 90-degree steps
        if (turnsX != 0 && turnsY != 0) {
            // Combined X+Y rotation does not decompose into a single quarter turn; leave as-is.
            return new float[][]{u, v};
        }
        float[] nu = new float[4], nv = new float[4];
        for (int i = 0; i < 4; i++) {
            float cu = u[i] - 0.5f, cv = v[i] - 0.5f;
            float ru = cu, rv = cv;
            for (int s = 0; s < steps; s++) {
                float tmp = ru;
                ru = -rv;
                rv = tmp;
            }
            nu[i] = ru + 0.5f;
            nv[i] = rv + 0.5f;
        }
        return new float[][]{nu, nv};
    }

    private static int xRotOf(Transform t) { return t.xRot; }
    private static int yRotOf(Transform t) { return t.yRot; }

    private static void swap(Quad q, int a, int b) {
        throw new UnsupportedOperationException("use rebuildQuad");
    }


    private static boolean isCounterClockwise(Quad q, int face) {
        // compute normal from first three verts and compare with face normal
        float ax = q.x1 - q.x0, ay = q.y1 - q.y0, az = q.z1 - q.z0;
        float bx = q.x2 - q.x0, by = q.y2 - q.y0, bz = q.z2 - q.z0;
        float nx = ay * bz - az * by;
        float ny = az * bx - ax * bz;
        float nz = ax * by - ay * bx;
        float dot = nx * q.nx() + ny * q.ny() + nz * q.nz();
        return dot > 0;
    }

    // ------------------------------------------------------------------
    // Rotations

    /**
     * Rotation transform for a blockstate's x/y/z values, built by lookup instead of trigonometry.
     *
     * <p>The sign convention here is easy to get backwards and expensive to debug — every oriented
     * block (observer, dropper, piston, comparator, wall torch, climbing redstone dust) inherits
     * it — so this follows the game's own table, {@code OctahedralGroup}'s {@code BLOCK_ROT_*}
     * constants, rather than a from-scratch rotation formula.
     *
     * <p>A vanilla rotation is an axis permutation plus optional sign flips, and the axis order is
     * <strong>x, then y, then z</strong> (each applied to the result of the previous one), because
     * {@code OctahedralGroup.compose} reads that way in the Cayley table the game builds from it.
     *
     * <p>Sourced from {@code OctahedralGroup}: {@code BLOCK_ROT_Y_90 = ROT_90_Y_NEG}, which maps
     * +X to +Z; {@code BLOCK_ROT_Y_270 = ROT_90_Y_POS}, which maps +X to -Z. A rotation matrix
     * built the other way round swaps those two, which turns every east-facing block into a
     * west-facing one.
     */
    public static final class Transform {
        public final int xRot, yRot, zRot; // degrees
        private final double[][] mat = new double[3][3];

        public static Transform of(int x, int y, int z) {
            return new Transform(x, y, z);
        }

        private Transform(int x, int y, int z) {
            this.xRot = x;
            this.yRot = y;
            this.zRot = z;
            // Column i is where basis vector i lands, in world space. Vanilla applies the rotations
            // to the model in the order x, then y, then z (OctahedralGroup.compose reads x-first),
            // so the combined matrix that a column vector meets is Z * Y * X.
            double[][] m = identity();
            m = composeZ(m, norm(z));
            m = composeY(m, norm(y));
            m = composeX(m, norm(x));
            this.mat[0] = m[0];
            this.mat[1] = m[1];
            this.mat[2] = m[2];
        }

        private static int norm(int deg) {
            return ((deg % 360) + 360) % 360;
        }

        private static double[][] identity() {
            return new double[][]{{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
        }

        /** Left-multiplies m by the vanilla y-rotation of the given 0/90/180/270 value. */
        private static double[][] composeY(double[][] m, int deg) {
            // BLOCK_ROT_Y_90 = ROT_90_Y_NEG = P321 with invertX (OctahedralGroup line 36):
            // scaling(-1,1,1) · P321 = [[0,0,-1],[0,1,0],[1,0,0]] — a proper rotation (det +1) that
            // sends east→south and south→west. y=270 is ROT_90_Y_POS (invertZ): east→north.
            // Getting a row/column scale wrong here turns the rotation into a reflection, which is
            // exactly the "one side reversed" symptom on stairs, torches and levers.
            return switch (deg) {
                case 90 -> mul(new double[][]{{0, 0, -1}, {0, 1, 0}, {1, 0, 0}}, m);
                case 180 -> mul(new double[][]{{-1, 0, 0}, {0, 1, 0}, {0, 0, -1}}, m);
                case 270 -> mul(new double[][]{{0, 0, 1}, {0, 1, 0}, {-1, 0, 0}}, m);
                default -> m;
            };
        }

        /** Left-multiplies m by the vanilla x-rotation of the given 0/90/180/270 value. */
        private static double[][] composeX(double[][] m, int deg) {
            // BLOCK_ROT_X_90 = ROT_90_X_NEG: -Z -> -Y ... expressed as a matrix, +Y -> -Z, +Z -> +Y.
            return switch (deg) {
                case 90 -> mul(new double[][]{{1, 0, 0}, {0, 0, 1}, {0, -1, 0}}, m);
                case 180 -> mul(new double[][]{{1, 0, 0}, {0, -1, 0}, {0, 0, -1}}, m);
                case 270 -> mul(new double[][]{{1, 0, 0}, {0, 0, -1}, {0, 1, 0}}, m);
                default -> m;
            };
        }

        /** Left-multiplies m by the vanilla z-rotation of the given 0/90/180/270 value. */
        private static double[][] composeZ(double[][] m, int deg) {
            // BLOCK_ROT_Z_90 = ROT_90_Z_NEG: +X -> +Y, +Y -> -X.
            return switch (deg) {
                case 90 -> mul(new double[][]{{0, -1, 0}, {1, 0, 0}, {0, 0, 1}}, m);
                case 180 -> mul(new double[][]{{-1, 0, 0}, {0, -1, 0}, {0, 0, 1}}, m);
                case 270 -> mul(new double[][]{{0, 1, 0}, {-1, 0, 0}, {0, 0, 1}}, m);
                default -> m;
            };
        }

        private static double[][] mul(double[][] a, double[][] b) {
            double[][] out = new double[3][3];
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) {
                    double sum = 0;
                    for (int k = 0; k < 3; k++) sum += a[i][k] * b[k][j];
                    out[i][j] = sum;
                }
            }
            return out;
        }

        public double[] transformPoint(double x, double y, double z) {
            return new double[]{
                this.mat[0][0] * x + this.mat[0][1] * y + this.mat[0][2] * z,
                this.mat[1][0] * x + this.mat[1][1] * y + this.mat[1][2] * z,
                this.mat[2][0] * x + this.mat[2][1] * y + this.mat[2][2] * z};
        }

        /** Rotates a face index by this transform. */
        public int rotateFace(int face) {
            if (face < 0) return face;
            double[] n = FACE_NORMALS[face];
            double[] r = this.transformPoint(n[0], n[1], n[2]);
            return closestFace(r);
        }
    }

    private static double[] rotateVertex(double x, double y, double z, ModelDefinition.ElementRotation rot) {
        double angle = Math.toRadians(rot.angle());
        double cx = x - rot.origin()[0] / 16f, cy = y - rot.origin()[1] / 16f, cz = z - rot.origin()[2] / 16f;
        double nx = cx, ny = cy, nz = cz;
        double c = Math.cos(angle), s = Math.sin(angle);
        switch (rot.axis()) {
            case "x" -> {
                ny = cy * c - cz * s;
                nz = cy * s + cz * c;
            }
            case "y" -> {
                nx = cx * c + cz * s;
                nz = -cx * s + cz * c;
            }
            case "z" -> {
                nx = cx * c - cy * s;
                ny = cx * s + cy * c;
            }
        }
        // Rescale, as vanilla's CuboidRotation.computeRescale does. A rotated element shrinks by up
        // to 1/sqrt(2) at 45 degrees, which is exactly why two ascending rails failed to meet at a
        // slope joint: the rail plane no longer reached the neighbouring block. For each world axis
        // the axis unit vector is run through the same rotation and the reciprocal of its largest
        // component becomes that axis's scale, restoring the element's full reach.
        if (rot.rescale()) {
            nx *= axisRescale(rot.axis(), c, s, 0);
            ny *= axisRescale(rot.axis(), c, s, 1);
            nz *= axisRescale(rot.axis(), c, s, 2);
        }
        return new double[]{nx + rot.origin()[0] / 16f, ny + rot.origin()[1] / 16f, nz + rot.origin()[2] / 16f};
    }

    /**
     * One entry of vanilla {@code CuboidRotation.computeRescale}: how long the given world axis
     * becomes under this rotation, normalised so the longest reach returns to 1.
     *
     * @param axisComponent 0 = x, 1 = y, 2 = z — the axis being scaled
     */
    private static double axisRescale(String rotAxis, double c, double s, int axisComponent) {
        // The rotation's own matrix, per axis, mirroring the switch in rotateVertex(). Reading the
        // transformed axis's component along `axisComponent` gives the shrink factor for it.
        double vx = 0, vy = 0, vz = 0;
        switch (rotAxis) {
            case "x" -> {
                if (axisComponent == 1) { vy = c; vz = s; }
                if (axisComponent == 2) { vy = -s; vz = c; }
            }
            case "y" -> {
                if (axisComponent == 0) { vx = c; vz = -s; }
                if (axisComponent == 2) { vx = s; vz = c; }
            }
            case "z" -> {
                if (axisComponent == 0) { vx = c; vy = s; }
                if (axisComponent == 1) { vx = -s; vy = c; }
            }
        }
        double ax = Math.abs(vx), ay = Math.abs(vy), az = Math.abs(vz);
        double max = Math.max(ax, Math.max(ay, az));
        return max < 1e-9 ? 1.0 : 1.0 / max;
    }

    // ------------------------------------------------------------------
    // Tables

    /** Per-face vertex selection: [vertex][axis] -> 0 = from, 1 = to (mirrors FaceInfo.java). */
    private static final int[][][] FACE_VERTICES = {
        /*DOWN*/  {{0,0,1},{0,0,0},{1,0,0},{1,0,1}},
        /*UP*/    {{0,1,0},{0,1,1},{1,1,1},{1,1,0}},
        /*NORTH*/ {{1,1,0},{1,0,0},{0,0,0},{0,1,0}},
        /*SOUTH*/ {{0,1,1},{0,0,1},{1,0,1},{1,1,1}},
        /*WEST*/  {{0,1,0},{0,0,0},{0,0,1},{0,1,1}},
        /*EAST*/  {{1,1,1},{1,0,1},{1,0,0},{1,1,0}},
    };

    private static final double[][] FACE_NORMALS = {
        {0,-1,0},{0,1,0},{0,0,-1},{0,0,1},{-1,0,0},{1,0,0}
    };

    public static int faceIndex(String name) {
        return switch (name) {
            case "down" -> Quad.DOWN;
            case "up" -> Quad.UP;
            case "north" -> Quad.NORTH;
            case "south" -> Quad.SOUTH;
            case "west" -> Quad.WEST;
            case "east" -> Quad.EAST;
            default -> -1;
        };
    }

    public static String faceName(int face) {
        return switch (face) {
            case Quad.DOWN -> "down";
            case Quad.UP -> "up";
            case Quad.NORTH -> "north";
            case Quad.SOUTH -> "south";
            case Quad.WEST -> "west";
            case Quad.EAST -> "east";
            default -> "none";
        };
    }

    private static int closestFace(double[] n) {
        int best = Quad.UP;
        double bestDot = -2;
        for (int f = 0; f < 6; f++) {
            double dot = n[0] * FACE_NORMALS[f][0] + n[1] * FACE_NORMALS[f][1] + n[2] * FACE_NORMALS[f][2];
            if (dot > bestDot) {
                bestDot = dot;
                best = f;
            }
        }
        return best;
    }

    /** Vanilla {@code FaceBakery.defaultFaceUV}, in 0..16 texture space. */
    public static float[] defaultFaceUV(float[] from, float[] to, int face) {
        return switch (face) {
            case Quad.DOWN  -> new float[]{from[0], 16 - to[2], to[0], 16 - from[2]};
            case Quad.UP    -> new float[]{from[0], from[2], to[0], to[2]};
            case Quad.NORTH -> new float[]{16 - to[0], 16 - to[1], 16 - from[0], 16 - from[1]};
            case Quad.SOUTH -> new float[]{from[0], 16 - to[1], to[0], 16 - from[1]};
            case Quad.WEST  -> new float[]{from[2], 16 - to[1], to[2], 16 - from[1]};
            case Quad.EAST  -> new float[]{16 - to[2], 16 - to[1], 16 - from[2], 16 - from[1]};
            default -> new float[]{0, 0, 16, 16};
        };
    }
}

