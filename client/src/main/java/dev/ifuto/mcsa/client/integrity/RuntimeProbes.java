package dev.ifuto.mcsa.client.integrity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.ifuto.mcsa.client.McsaConfig;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Locale;

/**
 * 実行時環境のプローブ。
 *
 * <p>「MOD 一覧に現れない注入」を捕まえるのが目的。
 * 有名チートクライアントの多くは MOD としてロードされず、
 * javaagent や改造ローダ経由で入ってくるため、MOD 一覧だけでは見えない。
 *
 * <p>プロセス一覧を読む・画面を撮る・キー入力を監視する、といったことはしない
 * （{@code docs/PRIVACY.md} 参照）。
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

    public static void collect(JsonObject root, McsaConfig config, List<String> serverProbes, boolean wantJvmArgs) {
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
                    if (config.collectJvmArgs && wantJvmArgs) {
                        suspicious.add(arg);
                    }
                }
            }
            if (lower.contains("jdwp") || lower.contains("dt_socket")) {
                debugger = true;
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
            if (present(name)) {
                found.add(name);
            }
        }
        probes.add("defaultProbesFound", found);

        JsonArray serverFound = new JsonArray();
        if (serverProbes != null) {
            for (String name : serverProbes) {
                if (name != null && !name.isBlank() && present(name)) {
                    serverFound.add(name);
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
            }
        }
        probes.add("oddClasspath", oddClasspath);

        root.add("probes", probes);
    }

    private static String classLoaderName() {
        ClassLoader loader = RuntimeProbes.class.getClassLoader();
        return loader == null ? "bootstrap" : loader.getClass().getName();
    }

    /** クラスを初期化せずに存在だけを確認する。 */
    private static boolean present(String className) {
        try {
            Class.forName(className, false, RuntimeProbes.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
