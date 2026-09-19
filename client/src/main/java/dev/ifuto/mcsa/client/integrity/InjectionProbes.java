package dev.ifuto.mcsa.client.integrity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.ifuto.mcsa.client.McsaClient;
import dev.ifuto.mcsa.client.McsaConfig;
import dev.ifuto.mcsa.client.util.Patterns;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.MinecraftClient;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 注入（インジェクション）系のプローブ。
 *
 * <p>対象は「MOD 一覧に出てこない介入」。具体的には
 * <ul>
 *   <li>Mixin によるメソッド差し替え（チートクライアントの大半がこれ）</li>
 *   <li>javaagent / attach による実行時バイトコード改変</li>
 *   <li>クラスローダの差し替え（Fabric の Knot 以外が混ざる）</li>
 * </ul>
 *
 * <p>すべてリフレクションで観測する。検知対象のクラスを直接 import すると、
 * そのクラスが無い環境では MOD ごと起動しなくなるため。
 *
 * <p>注意: <b>Fabric 自体が Mixin を使っている</b>ので「Mixin が入っている」こと自体は
 * 何の証拠にもならない。証拠になるのは
 * 「インストール済み MOD のどれにも属さない Mixin 設定が存在する」ことと、
 * 「ゲームプレイに直結するクラスへ注入された痕跡がある」ことの組み合わせ。
 */
public final class InjectionProbes {

    /** 注入の痕跡を探すゲームプレイ関連クラス（実行時オブジェクトから辿る） */
    private static final int MAX_SAMPLE = 24;

    /** Mixin 設定名から mod id を切り出すときに無視するトークン（実在の mod id を入れると誤判定になる） */
    private static final Set<String> MIXIN_STOPWORDS = Set.of(
            "mixins", "mixin", "json", "client", "common", "main");

    /** 正規のクラスローダ（Fabric 起動時の想定される並び） */
    private static final Set<String> KNOWN_LOADERS = Set.of(
            "net.fabricmc.loader.impl.launch.knot.KnotClassLoader",
            "net.fabricmc.loader.impl.launch.knot.KnotClassLoaderInterface",
            "jdk.internal.loader.ClassLoaders$PlatformClassLoader",
            "jdk.internal.loader.ClassLoaders$AppClassLoader",
            "java.net.URLClassLoader",
            "sun.misc.Launcher$AppClassLoader",
            "sun.misc.Launcher$ExtClassLoader");

    /** 怪しいスレッド名（agent / attach / retransform 系） */
    private static final String[] SUSPICIOUS_THREAD_HINTS = {
            "retransform", "redefine", "instrument", "agentmain", "arthas", "frida",
            "bytebuddy", "byte-buddy", "hook", "dumper", "cheat", "hack", "wurst", "meteor",
            "liquidbounce", "impact", "baritone"
    };

    private InjectionProbes() {
    }

    /** {@code probes} オブジェクトに注入系の観測結果を追記する。 */
    public static void collect(JsonObject probes, Findings findings, List<String> serverPatterns) {
        collectMixin(probes, findings);
        collectInjectedMembers(probes, findings);
        collectAgent(probes, findings);
        collectClassLoader(probes, findings);
        collectDynamicClasses(probes, findings, serverPatterns);
    }

    // ---------------------------------------------------------------- Mixin

    private static void collectMixin(JsonObject probes, Findings findings) {
        List<String> names = mixinConfigNames();
        Set<String> declared = declaredMixinConfigs();
        Set<String> modIds = installedModIds();
        List<String> allow = McsaConfig.get().knownMixinConfigs;

        JsonArray all = new JsonArray();
        JsonArray unknown = new JsonArray();
        for (String name : names) {
            all.add(name);
            if (!isKnownMixinConfig(name, modIds, declared, allow)) {
                unknown.add(name);
                findings.add("MIXIN_UNKNOWN_CONFIG", name);
            }
        }
        probes.addProperty("mixinPresent", !names.isEmpty() || mixinClassPresent());
        probes.addProperty("mixinConfigCount", names.size());
        probes.add("mixinConfigs", all);
        probes.add("mixinUnknownConfigs", unknown);
        findings.add("MIXIN_CONFIG_COUNT", String.valueOf(names.size()));
    }

    /** SpongePowered Mixin に登録されている設定ファイル名の一覧（取れなければ空） */
    public static List<String> mixinConfigNames() {
        List<String> names = new ArrayList<>();
        try {
            ClassLoader loader = InjectionProbes.class.getClassLoader();
            Class<?> mixins = Class.forName("org.spongepowered.asm.mixin.Mixins", false, loader);
            Object configurations = mixins.getMethod("getConfigurations").invoke(null);
            if (configurations instanceof Iterable<?> iterable) {
                for (Object config : iterable) {
                    String name = invokeString(config, "getName");
                    if (name == null) {
                        name = String.valueOf(config);
                    }
                    names.add(name);
                }
            }
        } catch (Throwable ignored) {
            // Mixin が入っていない／内部実装が変わっている
        }
        return names;
    }

    /** インストール済み MOD が fabric.mod.json で宣言している Mixin 設定名（＝正規のもの） */
    private static Set<String> declaredMixinConfigs() {
        Set<String> declared = new HashSet<>();
        for (ModContainer container : FabricLoader.getInstance().getAllMods()) {
            try {
                Object metadata = container.getMetadata();
                for (Method method : metadata.getClass().getMethods()) {
                    if (!"getMixinConfigs".equals(method.getName()) || method.getParameterCount() != 1) {
                        continue;
                    }
                    Object env = enumValue(method.getParameterTypes()[0], "CLIENT", "client");
                    if (env == null) {
                        continue;
                    }
                    Object result = method.invoke(metadata, env);
                    if (result instanceof Iterable<?> iterable) {
                        for (Object item : iterable) {
                            declared.add(String.valueOf(item));
                        }
                    }
                }
            } catch (Throwable ignored) {
                // ローダの内部 API に依存しているので失敗しても続行する
            }
        }
        return declared;
    }

    private static Set<String> installedModIds() {
        Set<String> ids = new HashSet<>();
        for (ModContainer container : FabricLoader.getInstance().getAllMods()) {
            ids.add(container.getMetadata().getId().toLowerCase(Locale.ROOT));
        }
        return ids;
    }

    /**
     * この Mixin 設定が「インストール済み MOD のもの」と言えるか。
     *
     * <p>判定は 3 段。fabric.mod.json の宣言と一致 → 既知。ファイル名トークンが mod id と一致 → 既知。
     * 設定で許可済み → 既知。それ以外は「出所不明」として報告する。
     */
    static boolean isKnownMixinConfig(String name, Set<String> modIds, Set<String> declared, List<String> allow) {
        if (name == null || name.isBlank()) {
            return true;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (declared.contains(name) || declared.contains(lower)) {
            return true;
        }
        if (allow != null) {
            for (String entry : allow) {
                if (Patterns.matches(entry, name)) {
                    return true;
                }
            }
        }
        for (String token : lower.split("[._/\\\\]")) {
            if (token.length() < 3 || MIXIN_STOPWORDS.contains(token)) {
                continue;
            }
            if (modIds.contains(token)) {
                return true;
            }
        }
        return lower.startsWith(McsaClient.MOD_ID);
    }

    // ------------------------------------------------------- 注入された痕跡

    /**
     * ゲームプレイに直結するクラスの「合成メンバ」を数える。
     *
     * <p>Mixin の注入は合成メソッド（{@code $handler$...} 等）として痕跡が残る。
     * ただし Fabric API も legitimately 注入するので、これ単体では判定に使わない。
     * サーバー側で「出所不明の Mixin 設定」と組み合わせて判定する。
     */
    private static void collectInjectedMembers(JsonObject probes, Findings findings) {
        List<Class<?>> targets = gameplayClasses();
        JsonArray sample = new JsonArray();
        int injected = 0;
        Set<String> seen = new HashSet<>();
        for (Class<?> target : targets) {
            for (Class<?> current = target; current != null && current != Object.class;
                 current = current.getSuperclass()) {
                String className = current.getName();
                if (!seen.add(className)) {
                    continue;
                }
                for (Method method : safeMethods(current)) {
                    if (!isInjected(method)) {
                        continue;
                    }
                    injected++;
                    if (sample.size() < MAX_SAMPLE) {
                        String entry = className + "#" + method.getName();
                        sample.add(entry);
                        findings.add("MIXIN_INJECT_SAMPLE", entry);
                    }
                }
            }
        }
        probes.addProperty("mixinInjectedCount", injected);
        probes.add("mixinInjectedSample", sample);
        if (injected > 0) {
            findings.add("MIXIN_INJECTED", String.valueOf(injected));
        }
    }

    /** 実行時オブジェクトから辿れるゲームプレイ関連クラス */
    public static List<Class<?>> gameplayClasses() {
        List<Class<?>> list = new ArrayList<>();
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null) {
                return list;
            }
            add(list, client.getClass());
            // フィールド名はバージョンで変わり得るので、リフレクションで辿る
            for (String field : new String[]{"player", "world", "interactionManager", "options"}) {
                Object value = fieldValue(client, field);
                if (value != null) {
                    add(list, value.getClass());
                }
            }
            if (client.getNetworkHandler() != null) {
                add(list, client.getNetworkHandler().getClass());
            }
        } catch (Throwable ignored) {
            // クライアント起動前など
        }
        return list;
    }

    private static Object fieldValue(Object target, String name) {
        try {
            return target.getClass().getField(name).get(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void add(List<Class<?>> list, Class<?> type) {
        if (type != null && !list.contains(type)) {
            list.add(type);
        }
    }

    private static Method[] safeMethods(Class<?> type) {
        try {
            return type.getDeclaredMethods();
        } catch (Throwable t) {
            return new Method[0];
        }
    }

    /** Mixin の注入が作り出すメソッド名の特徴（$handler$ / $redirect$ / $wrap 等） */
    static boolean isInjected(Method method) {
        String name = method.getName();
        if (name.indexOf('$') >= 0) {
            return true;
        }
        if (!method.isSynthetic()) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.startsWith("handler") || lower.startsWith("redirect") || lower.startsWith("modify")
                || lower.startsWith("wrap") || lower.startsWith("inject") || lower.startsWith("overwrite");
    }

    // ------------------------------------------------------ agent / thread

    private static void collectAgent(JsonObject probes, Findings findings) {
        boolean agentClass = present("sun.instrument.InstrumentationImpl");
        boolean attachApi = present("com.sun.tools.attach.VirtualMachine")
                || present("com.sun.jna.Platform");
        probes.addProperty("agentClassPresent", agentClass);
        probes.addProperty("attachApiPresent", attachApi);
        if (agentClass) {
            findings.add("AGENT_CLASS", "sun.instrument.InstrumentationImpl");
        }

        JsonArray threads = new JsonArray();
        try {
            for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
                String name = entry.getKey().getName();
                if (name == null) {
                    continue;
                }
                String lower = name.toLowerCase(Locale.ROOT);
                for (String hint : SUSPICIOUS_THREAD_HINTS) {
                    if (lower.contains(hint)) {
                        threads.add(name);
                        findings.add("THREAD_SUSPICIOUS", name);
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
            // セキュリティマネージャ等に阻まれる環境
        }
        probes.add("suspiciousThreads", threads);
        probes.addProperty("threadCount", Thread.activeCount());
    }

    // ---------------------------------------------------------- クラスローダ

    private static void collectClassLoader(JsonObject probes, Findings findings) {
        JsonArray chain = new JsonArray();
        boolean knot = false;
        for (ClassLoader loader = InjectionProbes.class.getClassLoader(); loader != null;
             loader = loader.getParent()) {
            String name = loader.getClass().getName();
            chain.add(name);
            if (name.startsWith("net.fabricmc.loader")) {
                knot = true;
            }
            if (!KNOWN_LOADERS.contains(name)) {
                findings.add("CLASSLOADER_ODD", name);
            }
        }
        String systemLoader = ClassLoader.getSystemClassLoader() == null
                ? "none" : ClassLoader.getSystemClassLoader().getClass().getName();
        chain.add("system:" + systemLoader);
        if (!knot) {
            findings.add("CLASSLOADER_NOT_FABRIC", systemLoader);
        }
        probes.add("classloaderChain", chain);
    }

    // --------------------------------------------------------- 動的なパターン

    /**
     * サーバーが指定したパターンでクラスを探す。
     *
     * <p>MOD を配り直さずに検知対象を増やすための経路。
     * 見つかった場合は「どの jar から来たのか」も一緒に報告する（証拠になる）。
     */
    private static void collectDynamicClasses(JsonObject probes, Findings findings, List<String> patterns) {
        JsonArray loaded = new JsonArray();
        if (patterns == null || patterns.isEmpty()) {
            probes.add("dynamicClassesFound", loaded);
            return;
        }
        ClassLoader loader = InjectionProbes.class.getClassLoader();
        for (String pattern : patterns) {
            if (pattern == null || pattern.isBlank()) {
                continue;
            }
            String candidate = pattern.trim();
            if (candidate.startsWith(Patterns.PREFIX) || candidate.startsWith(Patterns.CONTAINS)
                    || candidate.startsWith(Patterns.REGEX)) {
                continue; // パターン指定は jar 走査側（JarClassScanner）で扱う
            }
            try {
                Class<?> type = Class.forName(candidate, false, loader);
                loaded.add(candidate);
                CodeSource source = type.getProtectionDomain() == null
                        ? null : type.getProtectionDomain().getCodeSource();
                String from = source == null || source.getLocation() == null
                        ? "in-memory" : String.valueOf(source.getLocation());
                findings.add("CHEAT_CLASS_LOADED", candidate + "@" + from);
            } catch (Throwable ignored) {
                // 無い（＝正常）
            }
        }
        probes.add("dynamicClassesFound", loaded);
    }

    // ------------------------------------------------------------------ 共通

    private static boolean mixinClassPresent() {
        return present("org.spongepowered.asm.mixin.Mixins");
    }

    static boolean present(String className) {
        try {
            Class.forName(className, false, InjectionProbes.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String invokeString(Object target, String method) {
        try {
            Object value = target.getClass().getMethod(method).invoke(target);
            return value == null ? null : String.valueOf(value);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object enumValue(Class<?> type, String... candidates) {
        for (String candidate : candidates) {
            try {
                return type.getMethod("valueOf", String.class).invoke(null, candidate);
            } catch (Throwable ignored) {
                // 次の候補を試す
            }
        }
        return null;
    }

    /**
     * 指定されたパターンのうち、いま実際にロードされている完全クラス名の一覧。
     *
     * <p>ウォッチドッグが「前回は無かったクラスが現れた」を検出するのに使う。
     * {@code prefix:} 等のパターンはロード可否を判定できないので対象外
     * （そちらは jar 走査側で拾う）。
     */
    public static List<String> foundProbeNames(List<String> patterns) {
        List<String> found = new ArrayList<>();
        if (patterns == null) {
            return found;
        }
        for (String pattern : patterns) {
            if (pattern == null) {
                continue;
            }
            String candidate = pattern.trim();
            if (candidate.isEmpty() || candidate.indexOf(':') >= 0) {
                continue;
            }
            if (present(candidate)) {
                found.add(candidate);
            }
        }
        return found;
    }

    /** 起動環境の識別子（ウォッチドッグのダイジェストに混ぜる） */
    public static String environmentDigest() {
        StringBuilder builder = new StringBuilder();
        builder.append(ManagementFactory.getRuntimeMXBean().getName()).append('|');
        for (String name : mixinConfigNames()) {
            builder.append(name).append(',');
        }
        builder.append('|');
        for (ClassLoader loader = InjectionProbes.class.getClassLoader(); loader != null;
             loader = loader.getParent()) {
            builder.append(loader.getClass().getName()).append(',');
        }
        return builder.toString();
    }
}
