package io.github.litematicrender.serve;

import io.github.litematicrender.render.Image;
import io.github.litematicrender.render.RenderSettings;
import io.github.litematicrender.util.MiniJson;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Line-based JSON-RPC over stdin/stdout, used by the Electron shell to drive the render engine.
 *
 * <p>Protocol: one JSON object per line. A request is
 * <code>{"id":N,"method":"...","params":{...}}</code>; the reply is
 * <code>{"id":N,"ok":true,"result":{...}}</code> or <code>{"id":N,"ok":false,"error":"..."}</code>.
 * Unsolicited progress/log lines have no <code>id</code>.
 *
 * <p>stdout carries protocol traffic only; anything a renderer prints must go to stderr, or it
 * would corrupt the stream.
 */
public final class JsonRpcServer {
    private final RenderEngine engine;
    private final BufferedReader in;
    private final PrintStream out;
    private final AtomicLong seq = new AtomicLong();

    public JsonRpcServer(RenderEngine engine, BufferedReader in, PrintStream out) {
        this.engine = engine;
        this.in = in;
        this.out = out;
    }

    public static void main(String[] args) throws Exception {
        Path home = Path.of(System.getProperty("litematicrender.home", ".")).toAbsolutePath();
        RenderEngine engine = new RenderEngine(home);
        // The protocol is written straight to file descriptor 1 with an explicit UTF-8 encoder.
        //
        // Since Java 18 System.out encodes with stdout.encoding, which defaults to the platform
        // charset (GBK on a zh-CN Windows) and is NOT changed by -Dfile.encoding. Going through
        // System.out therefore mangled every non-ASCII string in a reply — a Chinese resource-pack
        // filename arrived as replacement characters. Encoding here makes the wire format
        // independent of the launcher's flags and the host locale.
        PrintStream protocol = new PrintStream(
            new java.io.BufferedOutputStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), 8192),
            false, StandardCharsets.UTF_8);
        // Keep protocol stdout clean: everything else goes to stderr.
        System.setOut(System.err);
        JsonRpcServer server = new JsonRpcServer(engine,
            new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)), protocol);
        server.run();
    }

    /** Reads requests until EOF or a {@code shutdown} call. */
    public void run() {
        emit(Map.of("event", "ready", "pid", ProcessHandle.current().pid(),
            "home", this.engine.home().toString()));
        String line;
        try {
            while ((line = this.in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (!handle(line)) break;
            }
        } catch (Exception e) {
            log("server loop ended: " + e);
        } finally {
            this.engine.close();
        }
    }

    /** @return false when the server should stop. */
    private boolean handle(String line) {
        long id = -1;
        try {
            Map<String, Object> req = MiniJson.parseObject(line);
            Object idObj = req.get("id");
            if (idObj instanceof Number n) id = n.longValue();
            String method = MiniJson.asString(req.get("method"), "");
            Map<String, Object> params = MiniJson.asObject(req.get("params"));
            if (params == null) params = Map.of();

            Map<String, Object> result = dispatch(method, params);
            if (result == SHUTDOWN) return false;
            reply(id, result);
        } catch (Exception e) {
            fail(id, e);
        }
        return true;
    }

    private static final Map<String, Object> SHUTDOWN = Map.of("__shutdown", true);

    private Map<String, Object> dispatch(String method, Map<String, Object> p) throws Exception {
        switch (method) {
            case "ping":
                return Map.of("pong", true, "home", this.engine.home().toString());

            case "loadResources": {
                String root = MiniJson.asString(p.get("root"), null);
                Path path = root == null ? this.engine.defaultPackRoot() : Path.of(root);
                // "imports" is the user's pack list, highest priority last.
                java.util.List<String> imports = new java.util.ArrayList<>();
                java.util.List<Object> arr = MiniJson.asArray(p.get("imports"));
                if (arr != null) {
                    for (Object o : arr) {
                        if (o instanceof String s) imports.add(s);
                    }
                }
                return this.engine.loadResources(path, imports);
            }

            case "open": {
                String file = require(p, "path");
                return this.engine.open(Path.of(file));
            }

            case "materials": return this.engine.materials(MiniJson.asBool(p.get("waterAsIce"),false));
            case "exportMaterials": return this.engine.exportMaterials(Path.of(require(p,"out")),MiniJson.asString(p.get("scope"),"combined"),MiniJson.asBool(p.get("waterAsIce"),false));
            case "skinSettings": return this.engine.skinSettings();
            case "pickBlock": return this.engine.pickBlock(vector(p,"origin"),vector(p,"direction"),MiniJson.asDouble(p.get("maximum"),4096));
            case "setSkinOnline": return this.engine.setSkinOnline(MiniJson.asBool(p.get("online"),true));
            case "importSkin": return this.engine.importSkin(require(p,"identity"),Path.of(require(p,"path")));

            case "render": {
                RenderSettings s = settingsFrom(p);
                Path out = Path.of(require(p, "out"));
                boolean diag = MiniJson.asBool(p.get("diagnostics"), true);
                return this.engine.render(out, s, diag);
            }

            case "renderPreview": {
                if(MiniJson.asBool(p.get("force"),false))this.engine.invalidateScene();
                // Renders to a temp PNG and returns the bytes inline so the UI can show it
                // without another file round-trip.
                RenderSettings s = settingsFrom(p);
                s.previewZoom = (float)Math.max(.05,Math.min(20,MiniJson.asDouble(p.get("previewZoom"),1)));
                Path tmp = java.nio.file.Files.createTempFile("litematic-preview", ".png");
                try {
                    Map<String, Object> r = this.engine.render(tmp, s, false);
                    byte[] bytes = java.nio.file.Files.readAllBytes(tmp);
                    Map<String, Object> out = new LinkedHashMap<>(r);
                    out.put("pngBase64", Base64.getEncoder().encodeToString(bytes));
                    return out;
                } finally {
                    java.nio.file.Files.deleteIfExists(tmp);
                }
            }

            case "renderAnimation": {
                RenderSettings s = settingsFrom(p);
                Path out = Path.of(require(p, "out"));
                int ticks = MiniJson.asInt(p.get("frames"), 24);
                int delayCs = MiniJson.asInt(p.get("delay"), 5);
                return this.engine.renderAnimation(out, s, ticks, delayCs);
            }


            case "exportCachedScene": return this.engine.exportCachedStreamScene(MiniJson.asBool(p.get("force"),false),MiniJson.asBool(p.get("realLighting"),false),MiniJson.asBool(p.get("lightingEnabled"),true));
            case "beginPreview": return this.engine.beginPreview(MiniJson.asBool(p.get("force"),false),MiniJson.asBool(p.get("realLighting"),false),MiniJson.asBool(p.get("lightingEnabled"),true));
            case "previewStatus": return this.engine.previewStatus(require(p,"id"),MiniJson.asInt(p.get("after"),-1),MiniJson.asObject(p.get("view")));
            case "previewLive": return this.engine.previewLive(require(p,"id"));
            case "previewResult": return this.engine.previewResult(require(p,"id"),MiniJson.asBool(p.get("overview"),false));
            case "cancelPreview": return this.engine.cancelPreview();
            case "sceneCacheStats": return this.engine.sceneCacheStats();
            case "configureSceneCache": return this.engine.configureSceneCache(MiniJson.asInt(p.get("limitMB"),4096),MiniJson.asBool(p.get("clear"),false));
            case "exportScene": {
                Path atlasOut = Path.of(require(p, "atlas"));
                Path meshOut = Path.of(require(p, "mesh"));
                if(MiniJson.asBool(p.get("streaming"),false))return this.engine.exportStreamScene(atlasOut,meshOut,MiniJson.asBool(p.get("force"),false),MiniJson.asBool(p.get("realLighting"),false),MiniJson.asBool(p.get("lightingEnabled"),true));
                return this.engine.exportScene(atlasOut, meshOut, MiniJson.asBool(p.get("force"),false), MiniJson.asBool(p.get("realLighting"),false),MiniJson.asBool(p.get("lightingEnabled"),true));
            }

            case "cancel":
                this.engine.cancel();
                return Map.of("cancelled", true);
            case "releaseStreaming":
                this.engine.releaseStreaming();return Map.of("released",true);

            case "shutdown":
                return SHUTDOWN;

            default:
                throw new IllegalArgumentException("unknown method: " + method);
        }
    }

    private static RenderSettings settingsFrom(Map<String, Object> p) throws java.io.IOException {
        RenderSettings s = new RenderSettings();
        s.rotationDeg = (float) MiniJson.asDouble(p.get("rotation"), s.rotationDeg);
        s.slantDeg = (float) MiniJson.asDouble(p.get("slant"), s.slantDeg);
        s.width = MiniJson.asInt(p.get("width"), s.width);
        s.height = MiniJson.asInt(p.get("height"), s.height);
        s.backgroundMode = MiniJson.asString(p.get("backgroundMode"), "color");
        s.transparentBackground = "checker".equals(s.backgroundMode)
            || MiniJson.asBool(p.get("transparent"), p.get("background") == null && s.transparentBackground);
        if (!s.transparentBackground && p.get("background") != null) {
            s.backgroundColor = MiniJson.asInt(p.get("background"), 0) | 0xFF000000;
        }
        s.checkerSize = Math.max(1, MiniJson.asInt(p.get("checkerSize"), 20));
        if (!s.transparentBackground && "image".equals(s.backgroundMode)) {
            Path image = Path.of(require(p, "backgroundImage"));
            if (java.nio.file.Files.size(image) > 10L*1024*1024) throw new java.io.IOException("背景图片不能超过 10 MB");
            s.backgroundImage = io.github.litematicrender.render.Image.read(image);
        }
        s.ambientShadeScale = (float) MiniJson.asDouble(p.get("ambientShadeScale"), s.ambientShadeScale);
        s.lightingEnabled = MiniJson.asBool(p.get("lightingEnabled"),true);
        s.realLighting = MiniJson.asBool(p.get("realLighting"),false);
        s.animationTick = MiniJson.asInt(p.get("tick"), s.animationTick);
        s.interactiveMaxEdge = MiniJson.asInt(p.get("interactive"), s.interactiveMaxEdge);
        return s;
    }

    private static String require(Map<String, Object> p, String key) {
        String v = MiniJson.asString(p.get(key), null);
        if (v == null) throw new IllegalArgumentException("missing parameter: " + key);
        return v;
    }
    private static double[] vector(Map<String,Object> p,String key) {
        var list=MiniJson.asArray(p.get(key));
        if(list==null||list.size()!=3)throw new IllegalArgumentException("无效的点选参数："+key);
        double[] result=new double[3];
        for(int i=0;i<3;i++){if(!(list.get(i) instanceof Number number))throw new IllegalArgumentException("点选坐标必须为数字");result[i]=number.doubleValue();}
        return result;
    }

    // ------------------------------------------------------------------

    private void reply(long id, Map<String, Object> result) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("id", id);
        msg.put("ok", true);
        msg.put("result", result);
        emit(msg);
    }

    private void fail(long id, Exception e) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("id", id);
        msg.put("ok", false);
        msg.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        msg.put("errorType", e.getClass().getSimpleName());
        emit(msg);
    }

    private void log(String message) {
        emit(Map.of("event", "log", "message", message));
    }

    /** Writes one JSON line and flushes; synchronized so concurrent writers can't interleave. */
    private synchronized void emit(Map<String, Object> msg) {
        this.out.println(toJson(msg));
        this.out.flush();
    }

    // ------------------------------------------------------------------
    // Minimal JSON writer (MiniJson only parses).

    @SuppressWarnings("unchecked")
    static String toJson(Object o) {
        StringBuilder sb = new StringBuilder();
        write(sb, o);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object o) {
        if (o == null) { sb.append("null"); return; }
        if (o instanceof String s) { writeString(sb, s); return; }
        if (o instanceof Boolean || o instanceof Number) { sb.append(o); return; }
        if (o instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
            return;
        }
        if (o instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object v : it) {
                if (!first) sb.append(',');
                first = false;
                write(sb, v);
            }
            sb.append(']');
            return;
        }
        if (o instanceof Object[] arr) {
            sb.append('[');
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) sb.append(',');
                write(sb, arr[i]);
            }
            sb.append(']');
            return;
        }
        writeString(sb, String.valueOf(o));
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    /** Renders a PNG to a data URL; helper for callers that embed images. */
    public static String dataUrl(Image image) throws java.io.IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image.toBuffered(), "png", bos);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(bos.toByteArray());
    }
}
