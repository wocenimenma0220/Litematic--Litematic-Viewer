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
            return !frontLines.isEmpty() || !backLines.isEmpty();
        }
    }

    private BlockEntityData() {}

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
                if (s != null && !s.isEmpty()) front.add(s);
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
            String raw = messages.getStringAt(i);
            if (raw == null) continue;
            String text = plainText(raw);
            if (!text.isEmpty()) out.add(text);
        }
    }

    /**
     * Sign lines are stored as JSON text components. We only need the visible text, so this
     * strips the component structure: quoted strings are kept, keys and punctuation dropped.
     * Handles both {@code {"text":"hi"}} and legacy {@code {"extra":[{"text":"hi"}]}} shapes.
     */
    static String plainText(String json) {
        if (json == null) return "";
        String s = json.trim();
        if (s.isEmpty()) return "";
        // Fast path: a bare quoted string.
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            return unescape(s.substring(1, s.length() - 1));
        }
        if (!s.startsWith("{")) return s;

        // Prefer the "text" field when present, else concatenate quoted values in order.
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '"') {
                int end = i + 1;
                StringBuilder token = new StringBuilder();
                while (end < s.length()) {
                    char d = s.charAt(end);
                    if (d == '\\' && end + 1 < s.length()) {
                        token.append(d).append(s.charAt(end + 1));
                        end += 2;
                        continue;
                    }
                    if (d == '"') break;
                    token.append(d);
                    end++;
                }
                String value = unescape(token.toString());
                // Skip the value that belongs to a structural key.
                int colon = s.lastIndexOf(':', i);
                int brace = Math.max(s.lastIndexOf('{', i), s.lastIndexOf(',', i));
                boolean isKey = colon > brace;
                if (!isKey) sb.append(value);
                i = end + 1;
            } else {
                i++;
            }
        }
        return sb.toString();
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    default -> sb.append(n);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Vanilla dye colours, used for the text colour of a sign. */
    static int dyeColor(String name) {
        return switch (name) {
            case "white" -> 0xF9FFFE;
            case "orange" -> 0xF9801D;
            case "magenta" -> 0xC74EBD;
            case "light_blue" -> 0x3AB3DA;
            case "yellow" -> 0xFED83D;
            case "lime" -> 0x80C71F;
            case "pink" -> 0xF38BAA;
            case "gray" -> 0x474F52;
            case "light_gray" -> 0x9D9D97;
            case "cyan" -> 0x169C9C;
            case "purple" -> 0x8932B8;
            case "blue" -> 0x3C44AA;
            case "brown" -> 0x835432;
            case "green" -> 0x5E7C16;
            case "red" -> 0xB02E26;
            case "black" -> 0x1D1D21;
            default -> 0x1D1D21;
        };
    }
}
