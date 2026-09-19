package dev.ifuto.mcsa.server.policy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.ifuto.mcsa.server.McsaConfig;
import dev.ifuto.mcsa.server.McsaPlugin;
import dev.ifuto.mcsa.server.report.ClientReport;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 注入（インジェクション）系の観測結果をフラグに変換する。
 *
 * <p>クライアントは「何が引っかかったか」だけを送ってくる
 * （{@code probes.findings[]} の {@code CODE:詳細} 形式）。
 * 黒白の判定はここでやる。理由は 2 つ。
 * <ol>
 *   <li>クライアントで判定すると、MOD をいじった相手に「検知しなかった」と偽装される</li>
 *   <li>閾値や処分をサーバー側でいつでも変えられる（クライアントの再配布が不要）</li>
 * </ol>
 *
 * <p>単独では誤検知しやすい観測（Mixin の注入痕跡、怪しいライブラリ名）は
 * <b>組み合わせ</b>で判定する。特に
 * 「インストール済み MOD のどれにも属さない Mixin 設定」＋「ゲームプレイ関連クラスへの注入痕跡」
 * は、ふつうの MOD 環境では出ない組み合わせ。
 */
public final class InjectionPolicy {

    private final McsaPlugin plugin;

    public InjectionPolicy(McsaPlugin plugin) {
        this.plugin = plugin;
    }

    public void classify(ClientReport report) {
        McsaConfig config = plugin.config();
        if (!config.injectionEnabled) {
            return;
        }
        List<String> findings = report.findings();
        JsonObject probes = report.probes();

        int unknownMixins = 0;
        int injected = probeInt(probes, "mixinInjectedCount");
        int mixinConfigs = probeInt(probes, "mixinConfigCount");
        List<String> libraryHits = new ArrayList<>();

        for (String finding : findings) {
            String code = code(finding);
            String detail = detail(finding);
            switch (code) {
                case "CHEAT_CLASS", "CHEAT_CLASS_LOADED", "CHEAT_CLASS_IN_JAR" ->
                        report.flag(code + ":" + detail, true);
                case "JVM_ARG_SUSPICIOUS" -> report.flag("JAVA_AGENT:" + detail, true);
                case "DEBUGGER_ARG" -> report.flag("DEBUGGER:" + detail, true);
                case "AGENT_CLASS" -> report.flag("JAVA_AGENT:" + detail, true);
                case "THREAD_SUSPICIOUS" -> report.flag("SUSPICIOUS_THREAD:" + detail, config.threadCritical);
                case "CLASSLOADER_ODD" -> report.flag("CLASSLOADER_ODD:" + detail, config.classloaderCritical);
                case "CLASSLOADER_NOT_FABRIC" -> report.flag("CLASSLOADER_ODD:" + detail, config.classloaderCritical);
                case "LIBRARY_SUSPICIOUS" -> {
                    libraryHits.add(detail);
                    report.flag("LIBRARY_SUSPICIOUS:" + detail, config.libraryCritical);
                }
                case "MIXIN_UNKNOWN_CONFIG" -> {
                    unknownMixins++;
                    report.flag("MIXIN_UNKNOWN_CONFIG:" + detail, config.unknownMixinCritical);
                }
                case "STATE_CHANGED" -> report.flag("STATE_CHANGED:" + detail, true);
                case "ODD_CLASSPATH" -> report.flag("ODD_CLASSPATH:" + detail, false);
                default -> {
                    // MIXIN_INJECTED / MIXIN_INJECT_SAMPLE / LIBRARY_COUNT / MIXIN_CONFIG_COUNT などは
                    // 参考情報。フラグにはしない（Fabric API も Mixin を使うため）
                }
            }
        }

        // --- 組み合わせ判定 -------------------------------------------------
        if (unknownMixins > 0 && injected >= config.injectedMemberThreshold) {
            report.flag("MIXIN_INJECTION_SUSPECTED:unknown=" + unknownMixins + ",injected=" + injected, true);
        }
        if (unknownMixins > 0 && !libraryHits.isEmpty()) {
            report.flag("INJECTION_CORROBORATED:mixin=" + unknownMixins + ",lib=" + libraryHits.size(), true);
        }
        if (probeBoolean(probes, "runtimeTampered")) {
            report.flag("CLIENT_TAMPERED_RUNTIME", true);
        }
        JsonObject watchdog = probeObject(probes, "watchdog");
        if (watchdog.has("changed") && watchdog.get("changed").getAsBoolean()) {
            report.flag("WATCHDOG_STATE_CHANGED", true);
        }
        if (mixinConfigs == 0 && report.modCount() > 0 && config.requireMixinConfigs) {
            // MOD が入っているのに Mixin 設定が 1 つも無い = 観測を潰されている可能性
            report.flag("MIXIN_CONFIG_MISSING", false);
        }
    }

    /** ダッシュボード用の要約（{@code /ac info} に出す） */
    public String summarize(ClientReport report) {
        JsonObject probes = report.probes();
        JsonObject jarScan = probeObject(probes, "jarScan");
        return String.format("mixin=%d(unknown=%d) injected=%d libraries=%d(suspicious=%d) "
                        + "scannedJars=%d matched=%d findings=%d watchdog=%s",
                probeInt(probes, "mixinConfigCount"),
                probeArray(probes, "mixinUnknownConfigs").size(),
                probeInt(probes, "mixinInjectedCount"),
                probeInt(probes, "libraryCount"),
                probeArray(probes, "suspiciousLibraries").size(),
                probeInt(jarScan, "scannedJars"),
                probeArray(jarScan, "matchedClasses").size(),
                report.findings().size(),
                probeBoolean(probes, "captureSupported") ? "capture-ok" : "capture-ng");
    }

    private static int probeInt(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsInt() : 0;
    }

    private static boolean probeBoolean(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() && element.getAsBoolean();
    }

    private static JsonArray probeArray(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
    }

    private static JsonObject probeObject(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    static String code(String finding) {
        if (finding == null) {
            return "";
        }
        int at = finding.indexOf(':');
        return (at < 0 ? finding : finding.substring(0, at)).toUpperCase(Locale.ROOT);
    }

    static String detail(String finding) {
        if (finding == null) {
            return "";
        }
        int at = finding.indexOf(':');
        return at < 0 ? "" : finding.substring(at + 1);
    }
}
