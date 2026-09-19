package dev.ifuto.mcsa.client.collect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ifuto.mcsa.client.McsaConfig;
import dev.ifuto.mcsa.client.util.Hashing;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 導入 MOD の収集。
 *
 * <p>2 つの視点から集めるのが肝。
 * <ol>
 *   <li>{@code FabricLoader.getAllMods()} … 実際にロードされた MOD の一覧</li>
 *   <li>{@code mods/} ディレクトリの走査 … 実ファイルと、その中の {@code fabric.mod.json}</li>
 * </ol>
 * 2 つを突き合わせることで、
 * <ul>
 *   <li>MOD 名（id）を偽装した jar … id は既存 MOD と一致するのに SHA-256 が違う</li>
 *   <li>{@code fabric.mod.json} を持たない jar（＝MOD ローダに姿を見せない注入物）</li>
 *   <li>mods/ にあるのにロードされていない jar</li>
 * </ul>
 * をサーバ側で判定できる。
 */
public final class ModScanner {

    /** 1 ディレクトリあたり走査する jar の上限（DoS 防止） */
    private static final int MAX_JARS = 512;
    private static final int MAX_MODS = 1024;

    private ModScanner() {
    }

    /** mods/ 内の jar 1 つの情報。 */
    public static final class JarInfo {
        public String fileName;
        public long size;
        public String sha256;
        public final List<String> ids = new ArrayList<>();
        /** 同梱（jar-in-jar）されている MOD の id */
        public final List<String> nestedIds = new ArrayList<>();
        public boolean hasManifest;
    }

    public static void collect(JsonObject root, McsaConfig config) {
        Path modsDir = FabricLoader.getInstance().getGameDir().resolve("mods");
        List<JarInfo> jars = scanDirectory(modsDir, config);

        Map<String, JarInfo> byId = new HashMap<>();
        for (JarInfo jar : jars) {
            for (String id : jar.ids) {
                byId.putIfAbsent(id, jar);
            }
        }
        Set<String> nestedIds = new HashSet<>();
        for (JarInfo jar : jars) {
            nestedIds.addAll(jar.nestedIds);
        }

        Set<String> loadedIds = new HashSet<>();
        JsonArray mods = new JsonArray();
        int count = 0;
        for (ModContainer container : FabricLoader.getInstance().getAllMods()) {
            ModMetadata meta = container.getMetadata();
            String id = meta.getId();
            loadedIds.add(id);
            if (count++ >= MAX_MODS) {
                continue;
            }
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("name", safe(meta.getName()));
            o.addProperty("version", safe(meta.getVersion().getFriendlyString()));
            JarInfo jar = byId.get(id);
            if (jar != null) {
                o.addProperty("file", jar.fileName);
                o.addProperty("size", jar.size);
                if (jar.sha256 != null) {
                    o.addProperty("sha256", jar.sha256);
                }
            } else {
                // mods/ 直下に無い＝他 MOD に同梱されている（fabric-api のサブ MOD など）
                o.addProperty("nested", nestedIds.contains(id));
            }
            mods.add(o);
        }
        root.add("mods", mods);

        JsonArray jarArray = new JsonArray();
        for (JarInfo jar : jars) {
            JsonObject o = new JsonObject();
            o.addProperty("file", jar.fileName);
            o.addProperty("size", jar.size);
            if (jar.sha256 != null) {
                o.addProperty("sha256", jar.sha256);
            }
            o.addProperty("manifest", jar.hasManifest);
            JsonArray ids = new JsonArray();
            jar.ids.forEach(ids::add);
            o.add("ids", ids);
            JsonArray nested = new JsonArray();
            jar.nestedIds.forEach(nested::add);
            o.add("nestedIds", nested);
            boolean loaded = !jar.ids.isEmpty();
            for (String id : jar.ids) {
                loaded = loaded && loadedIds.contains(id);
            }
            o.addProperty("loaded", loaded);
            jarArray.add(o);
        }
        root.add("modJars", jarArray);
    }

    /** mods/ 直下の jar を走査する（再帰はしない。Minecraft は mods/ 直下しか読まない）。 */
    public static List<JarInfo> scanDirectory(Path modsDir, McsaConfig config) {
        List<JarInfo> result = new ArrayList<>();
        if (!Files.isDirectory(modsDir)) {
            return result;
        }
        try (Stream<Path> stream = Files.list(modsDir)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".jar"))
                    .sorted()
                    .limit(MAX_JARS)
                    .toList();
            for (Path file : files) {
                result.add(inspect(file, config));
            }
        } catch (IOException e) {
            // 読めなければ空のまま返す
        }
        return result;
    }

    private static JarInfo inspect(Path file, McsaConfig config) {
        JarInfo info = new JarInfo();
        info.fileName = file.getFileName().toString();
        try {
            info.size = Files.size(file);
        } catch (IOException e) {
            info.size = -1;
        }
        if (config.hashFiles) {
            info.sha256 = Hashing.sha256Hex(file, config.hashMaxBytes);
        }

        Map<String, String> manifests = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(file.toFile())) {
            String own = readEntry(zip, "fabric.mod.json");
            if (own != null) {
                info.hasManifest = true;
                String id = idOf(own);
                if (id != null) {
                    info.ids.add(id);
                }
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (entry.isDirectory() || !name.endsWith(".jar")) {
                        continue;
                    }
                    String nested = readEntry(zip, name);
                    if (nested != null) {
                        manifests.put(name, nested);
                    } else {
                        // 同梱 jar の fabric.mod.json を直接読む
                        try (InputStream in = zip.getInputStream(entry)) {
                            String nestedManifest = readManifestInside(in);
                            if (nestedManifest != null) {
                                manifests.put(name, nestedManifest);
                            }
                        } catch (IOException ignored) {
                            // 読めない同梱 jar は無視
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // 壊れた jar / zip 爆弾などは hasManifest=false のまま
        }

        for (Map.Entry<String, String> entry : manifests.entrySet()) {
            String id = idOf(entry.getValue());
            if (id != null && !info.ids.contains(id)) {
                info.nestedIds.add(id);
            }
        }
        return info;
    }

    /** 同梱 jar の中から fabric.mod.json を取り出す。 */
    private static String readManifestInside(InputStream jarIn) {
        Path tmp = null;
        try {
            // InputStream からは ZipFile を作れないため、一時ファイル経由で読む（同梱 jar は小さい）
            byte[] bytes = jarIn.readNBytes(16 * 1024 * 1024 + 1);
            if (bytes.length == 0 || bytes.length > 16 * 1024 * 1024) {
                return null;
            }
            tmp = Files.createTempFile("mcsa-nested-", ".jar");
            Files.write(tmp, bytes);
            try (ZipFile zip = new ZipFile(tmp.toFile())) {
                return readEntry(zip, "fabric.mod.json");
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // 一時ファイルの削除失敗は無視
                }
            }
        }
    }

    private static String readEntry(ZipFile zip, String name) {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null || entry.isDirectory()) {
            return null;
        }
        try (InputStream in = zip.getInputStream(entry)) {
            byte[] bytes = in.readNBytes(1024 * 1024);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static String idOf(String manifestJson) {
        try {
            JsonElement parsed = JsonParser.parseString(manifestJson);
            if (parsed.isJsonObject()) {
                JsonElement id = parsed.getAsJsonObject().get("id");
                if (id != null && id.isJsonPrimitive()) {
                    return id.getAsString();
                }
            }
        } catch (RuntimeException e) {
            // 壊れた JSON
        }
        return null;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
