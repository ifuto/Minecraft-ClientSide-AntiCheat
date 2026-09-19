package dev.ifuto.mcsa.client.integrity;

import com.google.gson.JsonObject;
import dev.ifuto.mcsa.client.util.Hashing;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * この MOD 自身の改ざん検知。
 *
 * <ul>
 *   <li>{@code jarSha256} … 自分自身の jar の SHA-256（サーバのピン留め値と照合する）</li>
 *   <li>{@code entryFingerprint} … jar 内の全エントリの「名前:サイズ:CRC」を並べ替えてハッシュ化したもの。
 *       クラスを 1 つ差し替えただけでも変わる</li>
 *   <li>{@code obfuscated} … クラス名がビルド時にリネームされているか（難読化ビルドの証明）</li>
 * </ul>
 *
 * <p>もちろん、パッチを当てた jar が「正しい値」を報告することもできる。
 * だからこれは単独では証拠にならない。サーバ側では
 * 「ピン留めしたハッシュと違う」「難読化されていない」「2 つの値がちぐはぐ」を
 * すべてフラグとして扱い、行動検知の結果と合わせて判断する。
 */
public final class SelfIntegrity {

    /** 難読化されていないときの自分のクラス名。リネームされていれば難読化ビルド。 */
    private static final String EXPECTED_CLASS_NAME = "dev.ifuto.mcsa.client.integrity.SelfIntegrity";
    private static final int MAX_ENTRIES = 8192;

    private static boolean computed;
    private static String jarName = "unknown";
    private static long jarSize = -1;
    private static String jarSha256 = "";
    private static String entryFingerprint = "";
    private static boolean selfCheckOk;
    private static boolean runtimeTampered;

    private SelfIntegrity() {
    }

    /** 起動直後に一度だけ呼ぶ（重いので非同期スレッドから）。 */
    public static synchronized void prefetch() {
        compute();
    }

    public static synchronized String jarSha256() {
        compute();
        return jarSha256;
    }

    /**
     * 実行時にもう一度計算し直す（ウォッチドッグ用）。
     *
     * <p>起動時に一度だけ見て「はい正常です」で終わらせると、
     * 起動後に jar を差し替える／クラスを注入する手口を取りこぼす。
     * 値が変わっていたら true を返し、以降のレポートに
     * {@code self.runtimeTampered=true} として載せる。
     *
     * @return 起動時の値と違ったか
     */
    public static synchronized boolean reverify() {
        if (!computed) {
            compute();
            return false;
        }
        String previousHash = jarSha256;
        String previousFingerprint = entryFingerprint;
        computed = false;
        compute();
        boolean changed = !previousHash.equals(jarSha256) || !previousFingerprint.equals(entryFingerprint);
        if (changed) {
            runtimeTampered = true;
        }
        return changed;
    }

    public static synchronized boolean runtimeTampered() {
        return runtimeTampered;
    }

    public static boolean isObfuscatedBuild() {
        return !EXPECTED_CLASS_NAME.equals(SelfIntegrity.class.getName());
    }

    public static synchronized boolean lastSelfCheckOk() {
        compute();
        return selfCheckOk;
    }

    public static synchronized void write(JsonObject root) {
        compute();
        JsonObject self = new JsonObject();
        self.addProperty("jarName", jarName);
        self.addProperty("jarSize", jarSize);
        self.addProperty("jarSha256", jarSha256);
        self.addProperty("entryFingerprint", entryFingerprint);
        self.addProperty("obfuscated", isObfuscatedBuild());
        self.addProperty("runtimeTampered", runtimeTampered);
        self.addProperty("className", SelfIntegrity.class.getName());
        root.add("self", self);
    }

    private static void compute() {
        if (computed) {
            return;
        }
        computed = true;
        try {
            Path jar = locateJar();
            if (jar == null || !Files.isRegularFile(jar)) {
                return;
            }
            jarName = jar.getFileName().toString();
            jarSize = Files.size(jar);
            String hash = Hashing.sha256Hex(jar, 0);
            String fingerprint = fingerprint(jar);
            if (hash != null && fingerprint != null) {
                jarSha256 = hash;
                entryFingerprint = fingerprint;
                selfCheckOk = true;
            }
        } catch (Throwable t) {
            selfCheckOk = false;
        }
    }

    private static Path locateJar() {
        try {
            CodeSource source = SelfIntegrity.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }
            URI uri = source.getLocation().toURI();
            if (!"file".equals(uri.getScheme())) {
                return null;
            }
            return Paths.get(uri);
        } catch (Exception e) {
            return null;
        }
    }

    /** jar 内の全エントリを「名前:サイズ:CRC」で並べ替えて連結し、その SHA-256 を返す。 */
    private static String fingerprint(Path jar) {
        List<String> lines = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements() && lines.size() < MAX_ENTRIES) {
                ZipEntry entry = entries.nextElement();
                lines.add(entry.getName() + ":" + entry.getSize() + ":" + entry.getCrc());
            }
        } catch (IOException e) {
            return null;
        }
        Collections.sort(lines);
        return Hashing.sha256Hex(String.join("\n", lines));
    }
}
