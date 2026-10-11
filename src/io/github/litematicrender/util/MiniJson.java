package io.github.litematicrender.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON parser (RFC 8259 subset sufficient for resource pack files:
 * models, blockstates, mcmeta, pack.mcmeta, lang).
 * Values map to: Map&lt;String,Object&gt;, List&lt;Object&gt;, String, Double, Boolean, null.
 */
public final class MiniJson {
    private MiniJson() {}

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Object value = p.parseValue();
        p.skipWs();
        if (!p.atEnd()) throw p.error("trailing content");
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (v instanceof Map) return (Map<String, Object>) v;
        throw new IllegalArgumentException("Expected JSON object, got: " + v.getClass().getSimpleName());
    }

    public static final class JsonException extends RuntimeException {
        public JsonException(String message) { super(message); }
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) { this.s = s; }

        JsonException error(String msg) {
            int line = 1, col = 1;
            for (int i = 0; i < Math.min(pos, s.length()); i++) {
                if (s.charAt(i) == '\n') { line++; col = 1; } else col++;
            }
            return new JsonException("JSON error at line " + line + ":" + col + ": " + msg);
        }

        boolean atEnd() { return pos >= s.length(); }

        void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else break;
            }
        }

        char peek() {
            if (atEnd()) throw error("unexpected end of input");
            return s.charAt(pos);
        }

        char next() {
            if (atEnd()) throw error("unexpected end of input");
            return s.charAt(pos++);
        }

        void expect(char c) {
            if (atEnd() || s.charAt(pos) != c) throw error("expected '" + c + "'");
            pos++;
        }

        Object parseValue() {
            skipWs();
            char c = peek();
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> { expectWord("true"); yield Boolean.TRUE; }
                case 'f' -> { expectWord("false"); yield Boolean.FALSE; }
                case 'n' -> { expectWord("null"); yield null; }
                default -> parseNumber();
            };
        }

        void expectWord(String word) {
            if (pos + word.length() > s.length() || !s.startsWith(word, pos)) {
                throw error("expected '" + word + "'");
            }
            pos += word.length();
        }

        Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> result = new LinkedHashMap<>();
            skipWs();
            if (peek() == '}') { pos++; return result; }
            while (true) {
                skipWs();
                String key = parseString();
                skipWs();
                expect(':');
                Object value = parseValue();
                result.put(key, value);
                skipWs();
                char c = next();
                if (c == '}') return result;
                if (c != ',') throw error("expected ',' or '}'");
            }
        }

        List<Object> parseArray() {
            expect('[');
            List<Object> result = new ArrayList<>();
            skipWs();
            if (peek() == ']') { pos++; return result; }
            while (true) {
                Object value = parseValue();
                result.add(value);
                skipWs();
                char c = next();
                if (c == ']') return result;
                if (c != ',') throw error("expected ',' or ']'");
            }
        }

        String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char e = next();
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > s.length()) throw error("bad unicode escape");
                            String hex = s.substring(pos, pos + 4);
                            pos += 4;
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                            } catch (NumberFormatException ex) {
                                throw error("bad unicode escape");
                            }
                        }
                        default -> throw error("bad escape '\\" + e + "'");
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        Object parseNumber() {
            int start = pos;
            if (peek() == '-') pos++;
            while (!atEnd()) {
                char c = s.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.' || c == 'e' || c == 'E') pos++;
                else break;
            }
            if (pos == start) throw error("invalid number");
            String num = s.substring(start, pos);
            try {
                return Double.parseDouble(num);
            } catch (NumberFormatException e) {
                throw error("invalid number '" + num + "'");
            }
        }
    }

    // ---------- convenience accessors ----------

    public static String asString(Object o, String def) {
        return o instanceof String s ? s : def;
    }

    public static double asDouble(Object o, double def) {
        return o instanceof Number n ? n.doubleValue() : def;
    }

    public static int asInt(Object o, int def) {
        return o instanceof Number n ? n.intValue() : def;
    }

    public static boolean asBool(Object o, boolean def) {
        return o instanceof Boolean b ? b : def;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asArray(Object o) {
        return o instanceof List ? (List<Object>) o : null;
    }
}
