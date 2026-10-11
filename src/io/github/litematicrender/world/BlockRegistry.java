package io.github.litematicrender.world;

import io.github.litematicrender.resource.ResourceManager;
import io.github.litematicrender.util.MiniJson;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Data-driven block registry.
 *
 * Unlike vanilla (Block classes with hard-coded StateDefinitions), we build the
 * property table per block from its blockstates/*.json: every variant key or
 * multipart "when" condition contributes properties and values. This keeps the
 * runtime free of Minecraft code while matching variant selection semantics for
 * rendering purposes.
 */
public final class BlockRegistry {
    public static final class BlockInfo {
        public final String id;
        public final LinkedHashMap<String, BlockProperty> properties = new LinkedHashMap<>();
        public final BlockState defaultState;
        /** raw blockstates json (variants/multipart) — consumed by the model baker */
        public final Map<String, Object> definition;

        BlockInfo(String id, LinkedHashMap<String, BlockProperty> properties, Map<String, Object> definition) {
            this.id = id;
            this.properties.putAll(properties);
            this.definition = definition;
            BlockProperty[] props = properties.values().toArray(BlockProperty[]::new);
            int[] defaults = new int[props.length];
            for (int i = 0; i < props.length; i++) defaults[i] = 0; // first value acts as default
            this.defaultState = new BlockState(id, props, defaults);
        }

        public BlockState state(Map<String, String> values) {
            BlockState state = this.defaultState;
            for (Map.Entry<String, String> e : values.entrySet()) {
                state = state.tryWith(e.getKey(), e.getValue());
            }
            return state;
        }
    }

    private final Map<String, BlockInfo> blocks = new HashMap<>();
    private final ResourceManager resources;

    private BlockRegistry(ResourceManager resources) {
        this.resources = resources;
    }

    public static BlockRegistry load(ResourceManager resources) {
        BlockRegistry registry = new BlockRegistry(resources);
        // collect raw definitions: blockstates/<name>.json per namespace
        for (String[] pair : resources.listResources("blockstates", ".json")) {
            String ns = pair[0];
            String rel = pair[1];
            // rel is "blockstates/<name>.json" (relative to namespace)
            String name = rel.substring("blockstates/".length(), rel.length() - 5);
            String blockId = ns + ":" + name;
            try {
                byte[] data = resources.getResource(ns, rel).orElse(null);
                if (data == null) continue;
                Map<String, Object> json = MiniJson.parseObject(new String(data, java.nio.charset.StandardCharsets.UTF_8));
                registry.blocks.putIfAbsent(blockId, registry.build(blockId, json));
            } catch (Exception e) {
                System.err.println("[BlockRegistry] failed to load " + blockId + ": " + e);
            }
        }
        return registry;
    }

    private BlockInfo build(String blockId, Map<String, Object> json) {
        LinkedHashMap<String, BlockProperty> props = new LinkedHashMap<>();
        Map<String, LinkedHashSetSet> propValues = new LinkedHashMap<>();

        List<Map<String, Object>> variantMaps = new ArrayList<>();
        Map<String, Object> variants = MiniJson.asObject(json.get("variants"));
        if (variants != null) {
            for (Map.Entry<String, Object> e : variants.entrySet()) {
                // key like "facing=east,half=lower,hinge=left,open=false" or ""
                addKeyToProps(propValues, e.getKey());
            }
        }
        List<Object> multipart = MiniJson.asArray(json.get("multipart"));
        if (multipart != null) {
            for (Object mpObj : multipart) {
                Map<String, Object> mp = MiniJson.asObject(mpObj);
                if (mp == null) continue;
                Map<String, Object> when = MiniJson.asObject(mp.get("when"));
                if (when != null) {
                    collectConditionProps(propValues, when);
                }
            }
        }

        // Properties that affect appearance but never appear in the blockstate json.
        //
        // The registry derives its property table from the blockstate file, so any property that
        // is not part of a variant key or a multipart condition is invisible to it — and a palette
        // entry carrying that property is then silently dropped by tryWith(). Redstone wire is the
        // case that mattered: its multipart conditions only mention north/south/east/west, so
        // "power" (which vanilla's BlockColors.getColoringProperties() registers for tinting)
        // never made it into the table and every wire rendered as power 0, near-black red.
        List<String> extra = EXTRA_TINT_PROPERTIES.get(blockId);
        if (extra != null) {
            for (String name : extra) {
                if (!propValues.containsKey(name)) {
                    propValues.put(name, valuesFor(EXTRA_TINT_VALUES.get(name)));
                }
            }
        }

        for (Map.Entry<String, LinkedHashSetSet> e : propValues.entrySet()) {
            // stable order: boolean-ish false,true; numeric ascending; enum as encountered (sorted alphabetically for stability)
            List<String> values = new ArrayList<>(e.getValue().values);
            values.sort(BlockRegistry::compareValues);
            props.put(e.getKey(), new BlockProperty(e.getKey(), values));
        }

        return new BlockInfo(blockId, props, json);
    }

    private static LinkedHashSetSet valuesFor(List<String> values) {
        LinkedHashSetSet set = new LinkedHashSetSet();
        for (String v : values) set.add(v);
        return set;
    }

    /**
     * Extra properties to accept beyond what the blockstate json mentions, by block id.
     * Mirrors the blocks vanilla's {@code BlockColors} registers a tint source for whose
     * {@code relevantProperties()} do not appear in the blockstate file.
     */
    private static final Map<String, List<String>> EXTRA_TINT_PROPERTIES = Map.of(
        "minecraft:redstone_wire", List.of("power"),
        "minecraft:melon_stem", List.of("age"),
        "minecraft:pumpkin_stem", List.of("age")
    );

    /** Value ranges for the extra properties, 0..15 for both POWER and AGE. */
    private static final Map<String, List<String>> EXTRA_TINT_VALUES = Map.of(
        "power", List.of("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15"),
        "age", List.of("0", "1", "2", "3", "4", "5", "6", "7")
    );

    @SuppressWarnings("unchecked")
    private static void collectConditionProps(Map<String, LinkedHashSetSet> props, Map<String, Object> when) {
        for (Map.Entry<String, Object> e : when.entrySet()) {
            if (e.getKey().equals("OR") || e.getKey().equals("AND")) {
                List<Object> sub = MiniJson.asArray(e.getValue());
                if (sub != null) {
                    for (Object o : sub) {
                        Map<String, Object> m = MiniJson.asObject(o);
                        if (m != null) collectConditionProps(props, m);
                    }
                }
                continue;
            }
            addValueToProps(props, e.getKey(), MiniJson.asString(e.getValue(), "true"));
        }
    }

    private static void addKeyToProps(Map<String, LinkedHashSetSet> props, String key) {
        if (key.isEmpty()) return;
        for (String part : key.split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            addValueToProps(props, part.substring(0, eq).trim(), part.substring(eq + 1).trim());
        }
    }

    private static void addValueToProps(Map<String, LinkedHashSetSet> props, String name, String value) {
        if (value.isEmpty()) return;
        // multipart values can be "true|false" or "1|2|3"
        for (String v : value.split("\\|")) {
            if (v.isEmpty()) continue;
            props.computeIfAbsent(name, k -> new LinkedHashSetSet()).add(v);
        }
    }

    /** value ordering: booleans (false<true), numbers ascending, strings alphabetical. */
    private static int compareValues(String a, String b) {
        Boolean ab = parseBool(a), bb = parseBool(b);
        if (ab != null && bb != null) return Boolean.compare(ab, bb);
        if (ab != null) return 1;    // bools after non-bools? keep groups: non-bools first if other is bool
        if (bb != null) return -1;
        Double ad = parseNum(a), bd = parseNum(b);
        if (ad != null && bd != null) return Double.compare(ad, bd);
        return a.compareTo(b);
    }

    private static Boolean parseBool(String s) {
        if (s.equals("true")) return Boolean.TRUE;
        if (s.equals("false")) return Boolean.FALSE;
        return null;
    }

    private static Double parseNum(String s) {
        try {
            if (s.matches("-?\\d+(\\.\\d+)?")) return Double.parseDouble(s);
        } catch (NumberFormatException ignored) {}
        return null;
    }

    /** Simple insertion-ordered string set. */
    private static final class LinkedHashSetSet {
        final java.util.LinkedHashSet<String> values = new java.util.LinkedHashSet<>();
        void add(String v) { this.values.add(v); }
    }

    public BlockInfo get(String blockId) {
        BlockInfo info = this.blocks.get(blockId);
        if (info != null) return info;
        // Schematics are saved by many game versions; when a block was renamed, follow the
        // rename so an older file still renders instead of showing a missing-model cube.
        String current = LEGACY_RENAMES.get(blockId);
        return current == null ? null : this.blocks.get(current);
    }

    /**
     * Blocks renamed across versions, old id → current id.
     * {@code minecraft:chain} became {@code minecraft:iron_chain} when the copper chain
     * variants were added.
     */
    private static final Map<String, String> LEGACY_RENAMES = Map.of(
        "minecraft:chain", "minecraft:iron_chain"
    );

    public BlockInfo getOrMissing(String blockId) {
        // Follow legacy renames first: an older schematic must resolve to the current block's
        // real property table, otherwise its variant conditions never match.
        BlockInfo info = this.get(blockId);
        if (info != null) return info;
        // missing block: create a stub info (missing model will render pink/black)
        return this.blocks.computeIfAbsent(blockId, id -> new BlockInfo(id, new LinkedHashMap<>(), new HashMap<>()));
    }

    public boolean has(String blockId) {
        return this.blocks.containsKey(blockId);
    }

    public int blockCount() {
        return this.blocks.size();
    }

    /** All known block ids, for diagnostics and audits. */
    public java.util.Set<String> blockIds() {
        return java.util.Collections.unmodifiableSet(this.blocks.keySet());
    }

    /** Parses "minecraft:oak_stairs[facing=east,half=bottom]" into a BlockState. */
    public BlockState parseState(String fullId) {
        String blockId = fullId;
        Map<String, String> propValues = new LinkedHashMap<>();
        int bracket = fullId.indexOf('[');
        if (bracket >= 0) {
            blockId = fullId.substring(0, bracket);
            String props = fullId.substring(bracket + 1, fullId.endsWith("]") ? fullId.length() - 1 : fullId.length());
            for (String part : props.split(",")) {
                int eq = part.indexOf('=');
                if (eq > 0) propValues.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
            }
        }
        int colon = blockId.indexOf(':');
        if (colon < 0) blockId = "minecraft:" + blockId;
        return this.getOrMissing(blockId).state(propValues);
    }

    public Optional<BlockInfo> lookup(String blockId) {
        return Optional.ofNullable(this.blocks.get(blockId));
    }
}
