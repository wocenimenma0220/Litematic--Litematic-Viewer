package io.github.litematicrender.render.skin;

import io.github.litematicrender.nbt.*;
import io.github.litematicrender.render.Image;
import io.github.litematicrender.util.MiniJson;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Public Minecraft skins, with immutable texture caching and an offline/local-file path. */
public final class SkinResolver {
    public record Skin(String spriteId, Image image, boolean translucentOverlay) {}
    public record Request(String textureUrl, String uuid, String name) {
        public boolean empty() { return textureUrl == null && uuid == null && name == null; }
        public String identity() { return uuid != null ? uuid : name; }
    }
    @FunctionalInterface public interface Fetcher { byte[] get(URI uri, int maxBytes) throws IOException; }
    private final Path cache;
    private final boolean online;
    private final Fetcher fetcher;
    private final Map<Request, CompletableFuture<Skin>> requests = new ConcurrentHashMap<>();
    private final Map<String, Object> textureLocks = new ConcurrentHashMap<>();
    private final AtomicInteger downloads = new AtomicInteger(), cacheHits = new AtomicInteger(), localHits = new AtomicInteger();
    private int unidentified;
    private static final int IMAGE_LIMIT = 2 * 1024 * 1024;

    public SkinResolver(Path home) { this(home, onlineEnabled(home), SkinResolver::download); }
    public SkinResolver(Path home, boolean online, Fetcher fetcher) {
        this.cache = home.toAbsolutePath().normalize().resolve("skins");
        this.online = online && !Boolean.getBoolean("litematicrender.skins.offline");
        this.fetcher = fetcher;
    }

    public static boolean onlineEnabled(Path home) {
        if (Boolean.getBoolean("litematicrender.skins.offline")) return false;
        try {
            Path file = home.resolve("skins/settings.json");
            if (Files.isRegularFile(file)) return MiniJson.asBool(MiniJson.parseObject(Files.readString(file)).get("online"), true);
        } catch (Exception ignored) { }
        return true;
    }
    public static void setOnline(Path home, boolean online) throws IOException {
        atomicWrite(home.resolve("skins/settings.json"), ("{\"online\":" + online + "}\n").getBytes(StandardCharsets.UTF_8));
    }
    public static void importLocal(Path home, String identity, Path png) throws IOException {
        String key = identityKey(identity);
        if (key == null) throw new IOException("请输入有效的玩家名或 UUID");
        if (Files.size(png) > IMAGE_LIMIT) throw new IOException("皮肤 PNG 文件过大");
        Image skin = normalizeSkin(Files.readAllBytes(png));
        Path directory = home.resolve("skins/local");
        Files.createDirectories(directory);
        Path tmp = Files.createTempFile(directory, "skin-", ".png");
        try { skin.writePng(tmp); move(tmp, directory.resolve(key + ".png")); }
        finally { Files.deleteIfExists(tmp); }
    }

    /** Download distinct profiles in parallel, bounding the entire scene's network wait. */
    public void prepare(List<NbtCompound> heads) {
        var pool = Executors.newFixedThreadPool(4, task -> {
            Thread t = new Thread(task, "minecraft-skin-loader"); t.setDaemon(true); return t;
        });
        try {
            for (NbtCompound head : heads) {
                Request request = readRequest(head);
                if (request.empty()) { unidentified++; continue; }
                requests.computeIfAbsent(request, key -> CompletableFuture.supplyAsync(() -> loadSafely(key), pool));
            }
            CompletableFuture.allOf(requests.values().toArray(CompletableFuture[]::new)).get(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException ignored) {
            // Anything that does not finish within the scene budget uses Steve this frame.
        } finally {
            requests.values().forEach(f -> { if (!f.isDone()) f.cancel(true); });
            pool.shutdownNow();
        }
    }
    public Skin resolved(NbtCompound head) {
        CompletableFuture<Skin> result = requests.get(readRequest(head));
        if (result == null || result.isCancelled() || result.isCompletedExceptionally()) return null;
        return result.getNow(null);
    }
    /** Blocking form for verification and single-profile use. Failures always return Steve/null. */
    public Skin load(NbtCompound head) { return loadSafely(readRequest(head)); }
    public Map<String, Object> summary() {
        int fallback = unidentified;
        for (var future : requests.values()) if (future.isCancelled() || future.isCompletedExceptionally() || future.getNow(null) == null) fallback++;
        return Map.of("online",online,"downloaded",downloads.get(),"cached",cacheHits.get(),"local",localHits.get(),"fallback",fallback);
    }
    /** Identity of the resolved pixels actually used by this immutable scene snapshot. */
    public String contentFingerprint() {
        try {
            var hash=MessageDigest.getInstance("SHA-256");hash.update((byte)(online?1:0));hash.update(Integer.toString(unidentified).getBytes(StandardCharsets.US_ASCII));
            var entries=new ArrayList<>(requests.entrySet());entries.sort(Comparator.comparing(e->e.getKey().toString()));
            for(var entry:entries){hash.update(entry.getKey().toString().getBytes(StandardCharsets.UTF_8));hash.update((byte)0);var future=entry.getValue();Skin skin=future.isCancelled()||future.isCompletedExceptionally()?null:future.getNow(null);if(skin==null){hash.update((byte)0);continue;}hash.update((byte)1);var pixels=ByteBuffer.allocate(skin.image.width*skin.image.height*4);for(int pixel:skin.image.pixels())pixels.putInt(pixel);hash.update(pixels.array());}
            return HexFormat.of().formatHex(hash.digest());
        }catch(java.security.NoSuchAlgorithmException e){throw new AssertionError(e);}
    }
    private Skin loadSafely(Request request) {
        if (request.empty()) return null;
        try { return load(request); }
        catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            System.err.println("[SkinResolver] 皮肤暂不可用，使用 Steve：" + e.getMessage());
            return null;
        }
    }

    private Skin load(Request request) throws IOException {
        for (String key : localKeys(request)) {
            Path file = cache.resolve("local").resolve(key + ".png");
            if (!Files.isRegularFile(file)) continue;
            try { byte[] bytes = readPng(file); Skin skin = skin(normalizeSkin(bytes), "local_" + digest(bytes)); localHits.incrementAndGet(); return skin; }
            catch (Exception ignored) { /* a bad local override must not break other heads */ }
        }
        String url = request.textureUrl();
        if (url == null) url = profileTexture(request);
        if (url == null) return null;
        URI uri = textureUri(url);
        String hash = uri.getPath().substring("/texture/".length());
        synchronized (textureLocks.computeIfAbsent(hash, key -> new Object())) {
            Path png = cache.resolve("textures").resolve(hash + ".png");
            if (Files.isRegularFile(png)) {
                try { Skin skin = skin(normalizeSkin(readPng(png)), hash); cacheHits.incrementAndGet(); return skin; }
                catch (Exception ignored) { /* retry a corrupt texture when networking is enabled */ }
            }
            if (!online) return null;
            byte[] bytes = fetcher.get(uri, IMAGE_LIMIT);
            Image image = normalizeSkin(bytes);
            downloads.incrementAndGet();
            try {
                Files.createDirectories(png.getParent());
                Path tmp = Files.createTempFile(png.getParent(), "download-", ".png");
                try { image.writePng(tmp); move(tmp, png); } finally { Files.deleteIfExists(tmp); }
            } catch (IOException e) { System.err.println("[SkinResolver] 皮肤已加载，但无法写入缓存：" + e.getMessage()); }
            return skin(image, hash);
        }
    }

    private String profileTexture(Request request) throws IOException {
        String key = request.identity();
        if (key == null) return null;
        Path file = cache.resolve("profiles").resolve(key + ".json");
        if (Files.isRegularFile(file)) {
            try {
                String url = MiniJson.asString(MiniJson.parseObject(Files.readString(file)).get("url"), null);
                if (url != null) return textureUri(url).toString();
            } catch (Exception ignored) { }
        }
        if (!online) return null;
        String uuid = request.uuid();
        if (uuid == null) {
            var lookup = json(fetcher.get(URI.create("https://api.mojang.com/users/profiles/minecraft/" + request.name()), 65536));
            uuid = uuid(MiniJson.asString(lookup.get("id"), ""));
            if (uuid == null) throw new IOException("玩家资料没有 UUID");
        }
        var profile = json(fetcher.get(URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid + "?unsigned=true"), 262144));
        String url = textureFromProperties(profile.get("properties"));
        if (url == null) return null;
        String normalized = textureUri(url).toString();
        try { atomicWrite(file, ("{\"url\":\"" + normalized + "\"}\n").getBytes(StandardCharsets.UTF_8)); }
        catch (IOException e) { System.err.println("[SkinResolver] 无法缓存玩家资料：" + e.getMessage()); }
        return normalized;
    }

    public static Request readRequest(NbtCompound head) {
        if (head == null) return new Request(null,null,null);
        NbtElement profile = head.get("profile").orElseGet(() -> head.get("SkullOwner").orElse(null));
        if (profile instanceof NbtScalars.NbtString text) return new Request(null,null,name(text.value));
        if (!(profile instanceof NbtCompound p)) return new Request(null,null,null);
        String name = name(p.getStringOrDefault("name", p.getStringOrDefault("Name", "")));
        String uuid = uuid(p.getStringOrDefault("id", p.getStringOrDefault("Id", "")));
        NbtScalars.NbtIntArray id = p.getIntArray("id").orElseGet(() -> p.getIntArray("Id").orElse(null));
        if (id != null && id.value.length == 4) uuid = new UUID(((long)id.value[0] << 32) | (id.value[1] & 0xFFFFFFFFL),
            ((long)id.value[2] << 32) | (id.value[3] & 0xFFFFFFFFL)).toString().replace("-", "");
        String url = null;
        NbtList properties = p.getList("properties").orElse(null);
        if (properties != null) for (NbtCompound property : properties.getCompounds()) {
            if (property.getStringOrDefault("name", "").equals("textures")) url = decodeTexture(property.getStringOrDefault("value", ""));
            if (url != null) break;
        }
        NbtCompound legacy = p.getCompound("Properties").orElseGet(() -> p.getCompound("properties").orElse(null));
        if (url == null && legacy != null) {
            NbtList values = legacy.getList("textures").orElse(null);
            if (values != null) for (NbtCompound property : values.getCompounds()) {
                url = decodeTexture(property.getStringOrDefault("Value", property.getStringOrDefault("value", "")));
                if (url != null) break;
            }
        }
        return new Request(url,uuid,name);
    }
    private static String textureFromProperties(Object value) {
        List<Object> properties = MiniJson.asArray(value);
        if (properties == null) return null;
        for (Object item : properties) {
            var property = MiniJson.asObject(item);
            if (property != null && "textures".equals(property.get("name"))) {
                String url = decodeTexture(MiniJson.asString(property.get("value"), ""));
                if (url != null) return url;
            }
        }
        return null;
    }
    public static String decodeTexture(String base64) {
        if (base64 == null || base64.length() > 65536) return null;
        try {
            var root = json(Base64.getDecoder().decode(base64.trim()));
            var textures = MiniJson.asObject(root.get("textures"));
            var skin = textures == null ? null : MiniJson.asObject(textures.get("SKIN"));
            String url = skin == null ? null : MiniJson.asString(skin.get("url"), null);
            return url == null ? null : textureUri(url).toString();
        } catch (Exception ignored) { return null; }
    }
    public static URI textureUri(String url) throws IOException {
        try {
            URI uri = URI.create(url);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || !"textures.minecraft.net".equalsIgnoreCase(uri.getHost()) || uri.getPort() != -1 || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null || !uri.getPath().matches("/texture/[0-9a-fA-F]{32,128}")) {
                throw new IOException("无效的 Minecraft 皮肤地址");
            }
            return URI.create("https://textures.minecraft.net" + uri.getPath().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) { throw new IOException("无效的皮肤地址", e); }
    }

    public static Image normalizeSkin(byte[] png) throws IOException {
        if (png.length < 24 || png.length > IMAGE_LIMIT || png[0] != (byte)137 || png[1] != 'P' || png[2] != 'N' || png[3] != 'G') {
            throw new IOException("皮肤必须是 PNG 文件");
        }
        ByteBuffer header = ByteBuffer.wrap(png);
        int width = header.getInt(16), height = header.getInt(20);
        if (width != 64 || height != 32 && height != 64) throw new IOException("皮肤尺寸应为 64×64 或旧版 64×32");
        Image source = Image.read(png), image = new Image(64,64);
        if (source.width != width || source.height != height) throw new IOException("皮肤尺寸不匹配");
        for (int y=0;y<height;y++) for (int x=0;x<64;x++) image.setPixel(x,y,source.pixel(x,y));
        // Vanilla's legacy-skin transparency repair, applied only to old 64x32 skins.
        if (height == 32) {
            boolean opaque = true;
            for (int y=0;y<32;y++) for (int x=32;x<64;x++) if (Image.alpha(image.pixel(x,y)) < 128) opaque=false;
            if (opaque) for (int y=0;y<32;y++) for (int x=32;x<64;x++) image.setPixel(x,y,image.pixel(x,y) & 0xFFFFFF);
        }
        for (int y=0;y<16;y++) for (int x=0;x<32;x++) image.setPixel(x,y,image.pixel(x,y) | 0xFF000000);
        return image;
    }
    private static Skin skin(Image image, String key) {
        boolean translucent = false;
        for (int y=0;y<16;y++) for (int x=32;x<64;x++) {
            int alpha = Image.alpha(image.pixel(x,y)); if (alpha > 0 && alpha < 255) translucent = true;
        }
        return new Skin("litematicrender:skin/" + key,image,translucent);
    }
    private static List<String> localKeys(Request request) {
        List<String> keys = new ArrayList<>();
        if (request.uuid()!=null) keys.add(request.uuid());
        if (request.name()!=null) keys.add(request.name());
        if (request.textureUrl()!=null) try { keys.add(textureUri(request.textureUrl()).getPath().substring(9)); } catch (IOException ignored) { }
        return keys;
    }
    public static String identityKey(String text) {
        String uuid=uuid(text); return uuid != null ? uuid : name(text);
    }
    private static String name(String text) { return text != null && text.matches("[A-Za-z0-9_]{1,16}") ? text.toLowerCase(Locale.ROOT) : null; }
    private static String uuid(String text) {
        if (text == null) return null;
        String id=text.replace("-", ""); return id.matches("[0-9a-fA-F]{32}") ? id.toLowerCase(Locale.ROOT) : null;
    }
    private static Map<String,Object> json(byte[] bytes) { return MiniJson.parseObject(new String(bytes,StandardCharsets.UTF_8)); }
    private static byte[] readPng(Path path) throws IOException {
        if (Files.size(path) > IMAGE_LIMIT) throw new IOException("皮肤 PNG 文件过大");
        return Files.readAllBytes(path);
    }
    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static byte[] download(URI uri, int maxBytes) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("皮肤下载已结束");
        HttpURLConnection connection = (HttpURLConnection)uri.toURL().openConnection();
        connection.setConnectTimeout(4000); connection.setReadTimeout(4000); connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent","LitematicRender/1.0.3");
        try {
            int status=connection.getResponseCode();
            if (status!=200) throw new IOException("皮肤服务返回 HTTP " + status);
            if (connection.getContentLengthLong()>maxBytes) throw new IOException("皮肤资源过大");
            try (InputStream in=connection.getInputStream()) {
                byte[] data=in.readNBytes(maxBytes+1);
                if (data.length>maxBytes) throw new IOException("皮肤资源过大");
                return data;
            }
        } finally { connection.disconnect(); }
    }
    private static void atomicWrite(Path path, byte[] data) throws IOException {
        Files.createDirectories(path.getParent());
        Path temp=Files.createTempFile(path.getParent(),"skin-",".tmp");
        try { Files.write(temp,data); move(temp,path); } finally { Files.deleteIfExists(temp); }
    }
    private static void move(Path from, Path to) throws IOException {
        try { Files.move(from,to,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(from,to,StandardCopyOption.REPLACE_EXISTING); }
    }
}
