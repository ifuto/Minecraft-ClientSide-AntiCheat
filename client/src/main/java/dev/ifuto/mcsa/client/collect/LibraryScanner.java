package dev.ifuto.mcsa.client.collect;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.ifuto.mcsa.client.McsaConfig;
import dev.ifuto.mcsa.client.integrity.Findings;
import dev.ifuto.mcsa.client.util.Patterns;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 「いま読み込まれているライブラリ（jar）」の一覧を取る。
 *
 * <p>MOD として登録されていない注入は、たいてい jar として classpath に載っている。
 * ファイル名はチート側が自由に変えられるが、<b>一般配布されているチートは
 * 名前をそのままにしている</b>ことが多く、そこを狙う。
 *
 * <p>取得元は 3 つ。
 * <ol>
 *   <li>クラスローダのチェーン（Fabric の Knot は URLClassLoader なので URL 一覧が取れる）</li>
 *   <li>{@code java.class.path}</li>
 *   <li>{@code jdk.module.path} / {@code sun.boot.library.path} 系のプロパティ</li>
 * </ol>
 */
public final class LibraryScanner {

    /** レポートに載せる jar 名の上限 */
    private static final int MAX_LISTED = 256;

    /** 名前が引っかかったら報告するキーワード（サーバー側で追加できる） */
    private static final String[] DEFAULT_HINTS = {
            "agent", "inject", "hook", "hack", "cheat", "byte-buddy", "bytebuddy", "javassist",
            "cglib", "jvmti", "arthas", "frida", "dumper", "attach", "tools.jar", "xposed",
            "jndi", "exploit", "bypass", "patcher", "tweak", "coremod", "asm-"
    };

    private LibraryScanner() {
    }

    /** クラスローダとクラスパスから見える jar / ディレクトリの URL 一覧 */
    public static List<URL> libraryUrls() {
        Set<URL> urls = new LinkedHashSet<>();
        for (ClassLoader loader = LibraryScanner.class.getClassLoader(); loader != null;
             loader = loader.getParent()) {
            if (loader instanceof URLClassLoader urlClassLoader) {
                try {
                    for (URL url : urlClassLoader.getURLs()) {
                        urls.add(url);
                    }
                } catch (Throwable ignored) {
                    // セキュリティマネージャ等で拒否される環境
                }
            }
        }
        ClassLoader system = ClassLoader.getSystemClassLoader();
        if (system instanceof URLClassLoader urlClassLoader) {
            try {
                for (URL url : urlClassLoader.getURLs()) {
                    urls.add(url);
                }
            } catch (Throwable ignored) {
                // 同上
            }
        }
        return new ArrayList<>(urls);
    }

    /** 名前だけの一覧（重複除去・ソート済み） */
    public static List<String> libraryNames() {
        Set<String> names = new LinkedHashSet<>();
        for (URL url : libraryUrls()) {
            names.add(nameOf(url.toString()));
        }
        for (String property : new String[]{"java.class.path", "jdk.module.path", "sun.boot.library.path"}) {
            String value = System.getProperty(property, "");
            if (value.isEmpty()) {
                continue;
            }
            for (String entry : value.split(File.pathSeparator)) {
                if (!entry.isBlank()) {
                    names.add(nameOf(entry));
                }
            }
        }
        List<String> sorted = new ArrayList<>(names);
        sorted.sort(String.CASE_INSENSITIVE_ORDER);
        return sorted;
    }

    public static void collect(JsonObject probes, Findings findings, List<String> serverPatterns) {
        List<String> names = libraryNames();
        List<String> hints = new ArrayList<>(List.of(DEFAULT_HINTS));
        List<String> extra = McsaConfig.get().suspiciousLibraryPatterns;
        if (extra != null) {
            hints.addAll(extra);
        }
        if (serverPatterns != null) {
            for (String pattern : serverPatterns) {
                if (pattern != null && pattern.startsWith("lib:")) {
                    hints.add(pattern.substring(4));
                }
            }
        }

        JsonArray listed = new JsonArray();
        JsonArray suspicious = new JsonArray();
        for (String name : names) {
            if (listed.size() < MAX_LISTED) {
                listed.add(name);
            }
            String lower = name.toLowerCase(Locale.ROOT);
            for (String hint : hints) {
                if (hint == null || hint.isBlank()) {
                    continue;
                }
                boolean hit = Patterns.matches(hint.contains(":") || hint.contains("prefix:")
                        || hint.contains("regex:") ? hint : "contains:" + hint, name);
                if (hit) {
                    suspicious.add(name);
                    findings.add("LIBRARY_SUSPICIOUS", name);
                    break;
                }
            }
        }
        probes.addProperty("libraryCount", names.size());
        probes.addProperty("libraryListTruncated", names.size() > MAX_LISTED);
        probes.add("libraries", listed);
        probes.add("suspiciousLibraries", suspicious);
        findings.add("LIBRARY_COUNT", String.valueOf(names.size()));
    }

    /** mods/ 直下の jar（Fabric が読み込む前に置かれたもの）も名前として拾う */
    public static List<String> modsDirJars(Path gameDir) {
        List<String> names = new ArrayList<>();
        try {
            Path mods = gameDir.resolve("mods");
            if (Files.isDirectory(mods)) {
                try (var stream = Files.list(mods)) {
                    stream.filter(Files::isRegularFile)
                            .map(path -> path.getFileName().toString())
                            .sorted(String.CASE_INSENSITIVE_ORDER)
                            .forEach(names::add);
                }
            }
        } catch (Exception ignored) {
            // 読めない環境もある
        }
        return names;
    }

    private static String nameOf(String path) {
        String normalized = path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String name = slash < 0 ? normalized : normalized.substring(slash + 1);
        if (name.isEmpty() || name.endsWith("/")) {
            int previous = slash <= 0 ? -1 : normalized.lastIndexOf('/', slash - 1);
            name = previous < 0 ? normalized : normalized.substring(previous + 1, slash);
        }
        return name.isEmpty() ? path : name;
    }
}
