package dev.ifuto.mcsa.client.integrity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.ifuto.mcsa.client.McsaConfig;
import dev.ifuto.mcsa.client.capture.SilentCapture;
import dev.ifuto.mcsa.client.collect.JarClassScanner;
import dev.ifuto.mcsa.client.collect.LibraryScanner;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Locale;

/**
 * 実行時環境のプローブ（まとめ役）。
 *
 * <p>「MOD 一覧に現れない介入」を捕まえるのが目的。
 * 有名チートクライアントの多くは MOD としてロードされず、
 * javaagent や改造ローダ経由で入ってくるため、MOD 一覧だけでは見えない。
 *
 * <p>ここで集めた観測結果は {@link Findings} に {@code CODE:詳細} の形で積まれ、
 * 判定はサーバー側（{@code InjectionPolicy}）が行う。
 *
 * <p>プロセス一覧を読む・キー入力を監視する、といったことはしない。
 * 画面の取得は OP の指示があったときだけ（{@link SilentCapture}）。
 * いずれも {@code docs/PRIVACY.md} に書いてある。
 */
public final class RuntimeProbes {

    /** サーバが指定しなくても常に探すクラス（サーバーは {@code probeClasses} で拡張できる） */
    private static final String[] DEFAULT_PROBES = {
            "meteordevelopment.meteorclient.MeteorClient",
            "net.wurstclient.WurstClient",
            "net.ccbluex.liquidbounce.LiquidBounce",
            "com.liquidbounce.LiquidBounce",
            "net.impactclient.Impact",
            "baritone.api.BaritoneAPI"
    };

    private static final String[] SUSPICIOUS_ARG_PREFIXES = {
            "-javaagent", "-agentlib", "-agentpath", "-xbootclasspath"
    };

    private RuntimeProbes() {
    }

    public static void collect(JsonObject root, McsaConfig config, List<String> serverProbes,
                               boolean wantJvmArgs, Findings findings) {
        JsonObject probes = new JsonObject();

        List<String> args = ManagementFactory.getRuntimeMXBean().getInputArguments();
        JsonArray suspicious = new JsonArray();
        boolean agent = false;
        boolean debugger = false;
        for (String arg : args) {
            String lower = arg.toLowerCase(Locale.ROOT);
            for (String prefix : SUSPICIOUS_ARG_PREFIXES) {
                if (lower.startsWith(prefix)) {
                    agent = true;
                    findings.add("JVM_ARG_SUSPICIOUS", arg);
                    if (config.collectJvmArgs && wantJvmArgs) {
                        suspicious.add(arg);
                    }
                }
            }
            if (lower.contains("jdwp") || lower.contains("dt_socket")) {
                debugger = true;
                findings.add("DEBUGGER_ARG", arg);
            }
        }
        probes.addProperty("jvmArgCount", args.size());
        if (config.collectJvmArgs && wantJvmArgs) {
            probes.add("suspiciousJvmArgs", suspicious);
        }
        probes.addProperty("agent", agent);
        probes.addProperty("debugger", debugger);
        probes.addProperty("classLoader", classLoaderName());

        JsonArray found = new JsonArray();
        for (String name : DEFAULT_PROBES) {
            if (InjectionProbes.present(name)) {
                found.add(name);
                findings.add("CHEAT_CLASS", name);
            }
        }
        probes.add("defaultProbesFound", found);

        JsonArray serverFound = new JsonArray();
        if (serverProbes != null) {
            for (String name : serverProbes) {
                if (name != null && !name.isBlank() && name.indexOf(':') < 0 && InjectionProbes.present(name)) {
                    serverFound.add(name);
                    findings.add("CHEAT_CLASS", name);
                }
            }
        }
        probes.add("probesFound", serverFound);

        // クラスパスに紛れ込んだ見慣れない jar（注入系の名前だけ拾う）
        JsonArray oddClasspath = new JsonArray();
        String classPath = System.getProperty("java.class.path", "");
        for (String entry : classPath.split(java.io.File.pathSeparator)) {
            String lower = entry.toLowerCase(Locale.ROOT);
            if (lower.contains("agent") || lower.contains("inject") || lower.contains("hook")
                    || lower.contains("cheat") || lower.contains("hack")) {
                oddClasspath.add(entry);
                findings.add("ODD_CLASSPATH", entry);
            }
        }
        probes.add("oddClasspath", oddClasspath);

        // 注入（Mixin / agent / クラスローダ）、ライブラリ名、jar の中身
        InjectionProbes.collect(probes, findings, serverProbes);
        LibraryScanner.collect(probes, findings, serverProbes);
        JarClassScanner.collect(probes, findings, serverProbes);

        // 常時監視の状態（起動時からの変化）
        Watchdog.write(probes);
        probes.addProperty("captureSupported", SilentCapture.supported());
        probes.addProperty("runtimeTampered", SelfIntegrity.runtimeTampered());

        root.add("probes", probes);
    }

    private static String classLoaderName() {
        ClassLoader loader = RuntimeProbes.class.getClassLoader();
        return loader == null ? "bootstrap" : loader.getClass().getName();
    }
}
