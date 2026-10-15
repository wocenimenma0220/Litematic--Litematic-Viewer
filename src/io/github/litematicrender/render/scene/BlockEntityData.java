package io.github.litematicrender.render.scene;

import io.github.litematicrender.nbt.NbtCompound;
import io.github.litematicrender.nbt.NbtList;

import java.util.ArrayList;
import java.util.List;

/**
 * Decodes the parts of block-entity NBT that a static render can actually show.
 *
 * <p>Vanilla draws these with a {@code BlockEntityRenderer} we do not have, so the values are
 * read straight out of the schematic's {@code TileEntities} and turned into something the scene
 * builder can place.
 */
public final class BlockEntityData {
    /** A sign: up to four lines per side, plus the dye colour. */
    public record Sign(List<String> frontLines, List<String> backLines, int frontColor, int backColor,
                       boolean glowingFront, boolean glowingBack) {
        public boolean hasText() {
            return frontLines.stream().anyMatch(line -> !line.isBlank()) || backLines.stream().anyMatch(line -> !line.isBlank());
        }
    }

    private BlockEntityData() {}

    public static List<String> potPatterns(NbtCompound tag) {
        var result = new ArrayList<String>();
        var allowed = java.util.Set.of("angler","archer","arms_up","blade","brewer","burn","danger","explorer","flow","friend","guster","heart","heartbreak","howl","miner","mourner","plenty","prize","scrape","sheaf","shelter","skull","snort");
        io.github.litematicrender.nbt.NbtElement decorations = tag == null ? null : tag.get("sherds").orElse(null);
        if (decorations == null && tag != null) {
            var components = tag.getCompound("components").orElse(null);
            if (components != null) decorations = components.get("minecraft:pot_decorations").orElse(null);
        }
        String[] keys={"back","left","right","front"};
        for(int i=0;i<4;i++) {
            io.github.litematicrender.nbt.NbtElement item = null;
            if(decorations instanceof NbtList list && i<list.size()) item=list.get(i);
            else if(decorations instanceof NbtCompound compound) item=compound.get(keys[i]).orElse(null);
            String id=item instanceof io.github.litematicrender.nbt.NbtScalars.NbtString text ? text.value
                : item instanceof NbtCompound stack ? stack.getStringOrDefault("id","") : "";
            String name=id.replace("minecraft:","").replace("_pottery_sherd","");
            result.add(allowed.contains(name) ? name : "blank");
        }
        return result;
    }

    /**
     * Reads a sign block entity.
     *
     * <p>Two layouts exist: 1.20+ uses {@code front_text}/{@code back_text} compounds with a
     * {@code messages} list of JSON strings; older files store {@code Text1..Text4} on the root.
     */
    public static Sign readSign(NbtCompound te) {
        List<String> front = new ArrayList<>();
        List<String> back = new ArrayList<>();
        int frontColor = 0x000000;
        int backColor = 0x000000;
        boolean glowFront = false, glowBack = false;

        NbtCompound frontTag = te.getCompound("front_text").orElse(null);
        NbtCompound backTag = te.getCompound("back_text").orElse(null);
        if (frontTag != null || backTag != null) {
            if (frontTag != null) {
                readMessages(frontTag, front);
                frontColor = dyeColor(frontTag.getStringOrDefault("color", "black"));
                glowFront = frontTag.getBooleanOrDefault("has_glowing_text", false);
            }
            if (backTag != null) {
                readMessages(backTag, back);
                backColor = dyeColor(backTag.getStringOrDefault("color", "black"));
                glowBack = backTag.getBooleanOrDefault("has_glowing_text", false);
            }
        } else {
            // Pre-1.20 layout: Text1..Text4 on the block entity itself.
            for (int i = 1; i <= 4; i++) {
                String s = te.getStringOrDefault("Text" + i, "");
                front.add(plainText(s));
            }
            frontColor = dyeColor(te.getStringOrDefault("Color", "black"));
            glowFront = te.getBooleanOrDefault("GlowingText", false);
        }
        return new Sign(front, back, frontColor, backColor, glowFront, glowBack);
    }

    private static void readMessages(NbtCompound side, List<String> out) {
        NbtList messages = side.getList("messages").orElse(null);
        if (messages == null) return;
        for (int i = 0; i < messages.size(); i++) {
            out.add(nbtText(messages.get(i)));
        }
    }

    /** Read visible component text only; preserve Unicode escapes, literal strings and extra arrays. */
    public static String plainText(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        try { return componentText(io.github.litematicrender.util.MiniJson.parse(raw)); }
        catch (RuntimeException e) { return raw; }
    }

    /** Newer game versions may store components directly as NBT instead of JSON strings. */
    public static String nbtText(io.github.litematicrender.nbt.NbtElement value) {
        if (value instanceof io.github.litematicrender.nbt.NbtScalars.NbtString text) return plainText(text.value);
        if (value instanceof NbtCompound compound) {
            String text = compound.getStringOrDefault("text", "");
            NbtList extra = compound.getList("extra").orElse(null);
            if (extra != null) for (var item : extra.getElements()) text += nbtText(item);
            return text;
        }
        if (value instanceof NbtList list) {
            StringBuilder result = new StringBuilder();
            for (var item : list.getElements()) result.append(nbtText(item));
            return result.toString();
        }
        return "";
    }

    private static String componentText(Object value) {
        if (value instanceof String text) return text;
        if (value instanceof List<?> list) {
            StringBuilder result = new StringBuilder();
            for (Object item : list) result.append(componentText(item));
            return result.toString();
        }
        if (value instanceof java.util.Map<?, ?> map) {
            String text = map.get("text") instanceof String s ? s : "";
            return text + componentText(map.get("extra"));
        }
        return "";
    }

    /** Vanilla dye colours, used for the text colour of a sign. */
    static int dyeColor(String name) {
        return switch (name) {
            case "white" -> 0xFFFFFF;
            case "orange" -> 0xFF681F;
            case "magenta" -> 0xFF00FF;
            case "light_blue" -> 0x9AC0CD;
            case "yellow" -> 0xFFFF00;
            case "lime" -> 0xBFFF00;
            case "pink" -> 0xFF69B4;
            case "gray" -> 0x808080;
            case "light_gray" -> 0xD3D3D3;
            case "cyan" -> 0x00FFFF;
            case "purple" -> 0xA020F0;
            case "blue" -> 0x0000FF;
            case "brown" -> 0x8B4513;
            case "green" -> 0x00FF00;
            case "red" -> 0xFF0000;
            case "black" -> 0x000000;
            default -> 0x000000;
        };
    }
}
