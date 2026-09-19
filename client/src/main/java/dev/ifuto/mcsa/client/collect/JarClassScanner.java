package dev.ifuto.mcsa.client.collect;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.ifuto.mcsa.client.McsaConfig;
import dev.ifuto.mcsa.client.integrity.Findings;
import dev.ifuto.mcsa.client.util.Patterns;

import java.io.File;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * classpath 上の jar の中身（エントリ名）を走査して、チート系のクラス名を探す。
 *
 * <p>{@code Class.forName} では「ロード済みのクラス」しか見えない。
 * 起動時だけ動いてその後アンロードされるものや、クラス名を直接触らずに
 * リフレクションで使うものは取りこぼすので、<b>jar の中身を見る</b>。
 * ロードはしないので重くなく、副作用もない。
 *
 * <p>走査対象の名前パターンはサーバーが {@code /ac probe add} で動的に増やせる
 * （{@code class:} / {@code prefix:} / {@code contains:} / {@code regex:}）。
 */
public final class JarClassScanner {

    private static final int MAX_JARS = 96;
    private static final int MAX_ENTRIES = 400_000;
    private static final int MAX_MATCHES = 64;

    /** 有名チートクライアントの既定パターン（サーバー側で拡張できる） */
    private static final String[] DEFAULT_CLASS_PATTERNS = {
            "prefix:meteordevelopment.meteorclient",
            "prefix:net.ccbluex.liquidbounce",
            "prefix:com.liquidbounce",
            "prefix:net.wurstclient",
            "prefix:net.impactclient",
            "prefix:me.rhys",
            "prefix:me.zero",
            "prefix:baritone.api",
            "prefix:com.darkmagician6",
            "contains:xray",
            "contains:killaura",
            "contains:aimbot",
            "contains:autoclick"
    };

    private JarClassScanner() {
    }

    public static void collect(JsonObject probes, Findings findings, List<String> serverPatterns) {
        List<String> patterns = new ArrayList<>(List.of(DEFAULT_CLASS_PATTERNS));
        List<String> extra = McsaConfig.get().extraClassPatterns;
        if (extra != null) {
            patterns.addAll(extra);
        }
        if (serverPatterns != null) {
            for (String pattern : serverPatterns) {
                if (pattern == null) {
                    continue;
                }
                if (pattern.startsWith("class:")) {
                    patterns.add(pattern.substring(6));
                } else if (pattern.startsWith("prefix:") || pattern.startsWith("contains:")
                        || pattern.startsWith("regex:")) {
                    patterns.add(pattern);
                }
            }
        }

        JsonArray matched = new JsonArray();
        JsonArray scanned = new JsonArray();
        int jars = 0;
        long entries = 0;
        int skipped = 0;

        outer:
        for (URL url : LibraryScanner.libraryUrls()) {
            if (jars >= MAX_JARS || entries >= MAX_ENTRIES) {
                break;
            }
            if (!"file".equalsIgnoreCase(url.getProtocol())) {
                continue;
            }
            File file;
            try {
                file = new File(URI.create(url.toString().replace(" ", "%20")));
            } catch (Exception e) {
                skipped++;
                continue;
            }
            if (!file.isFile() || !file.getName().endsWith(".jar")) {
                continue;
            }
            jars++;
            if (scanned.size() < MAX_JARS) {
                scanned.add(file.getName());
            }
            try (ZipFile zip = new ZipFile(file)) {
                Enumeration<? extends ZipEntry> enumeration = zip.entries();
                while (enumeration.hasMoreElements()) {
                    ZipEntry entry = enumeration.nextElement();
                    entries++;
                    if (entries >= MAX_ENTRIES) {
                        break outer;
                    }
                    String name = entry.getName();
                    if (!name.endsWith(".class") || name.startsWith("META-INF/")) {
                        continue;
                    }
                    String className = name.substring(0, name.length() - 6).replace('/', '.');
                    for (String pattern : patterns) {
                        if (Patterns.matches(pattern, className)) {
                            String hit = className + "@" + file.getName();
                            matched.add(hit);
                            findings.add("CHEAT_CLASS_IN_JAR", hit);
                            if (matched.size() >= MAX_MATCHES) {
                                break outer;
                            }
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                skipped++;
            }
        }

        JsonObject scan = new JsonObject();
        scan.addProperty("scannedJars", jars);
        scan.addProperty("scannedEntries", entries);
        scan.addProperty("skippedJars", skipped);
        scan.addProperty("patternCount", patterns.size());
        scan.add("scannedJarNames", scanned);
        scan.add("matchedClasses", matched);
        probes.add("jarScan", scan);
    }
}
