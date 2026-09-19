package dev.ifuto.mcsa.client.integrity;

import dev.ifuto.mcsa.client.McsaClient;
import dev.ifuto.mcsa.client.McsaConfig;
import dev.ifuto.mcsa.client.capture.SilentCapture;
import dev.ifuto.mcsa.client.collect.LibraryScanner;
import dev.ifuto.mcsa.client.net.ClientSession;
import dev.ifuto.mcsa.client.net.DigestPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * ウォッチドッグ（常時監視）。
 *
 * <p>「起動時にチェックされるなら、そのときだけ真面目にしておく」という回避策を
 * 潰すための仕組み。2 段階認証と同じ発想で、
 * <b>1 回通ることではなく「通し続けること」を条件にする</b>。
 *
 * <ul>
 *   <li>一定間隔（既定 {@code watchdogIntervalSeconds} 秒、±20% のジッタ付き）で
 *       状態ダイジェストを計算し、サーバーへ {@code mcsa:digest} を送る</li>
 *   <li>起動時の基準値と違っていたら、どの項目が変わったかを finding に積む</li>
 *   <li>サーバー側は「ダイジェストが変わった」「送ってこなくなった」の両方を証拠にする</li>
 * </ul>
 *
 * <p>間隔はサーバーが {@code mcsa:task (WATCH_ON)} で都度詰められる。
 * 疑わしい相手だけ高頻度にして、ふつうのプレイヤーの負荷を上げないようにするため。
 */
public final class Watchdog {

    private static final Findings CHANGES = new Findings();

    private static volatile String baseline;
    private static volatile int intervalSeconds;
    private static volatile int seq;
    private static volatile boolean started;
    private static volatile long startedAt = System.currentTimeMillis();

    private record State(String selfHash, String mixin, String libraries, String loader, String probes) {
    }

    private Watchdog() {
    }

    /** 起動時に 1 回だけ呼ぶ。 */
    public static synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        intervalSeconds = normalize(McsaConfig.get().watchdogIntervalSeconds);
        startedAt = System.currentTimeMillis();
        Thread thread = new Thread(Watchdog::loop, "MCSA-Watchdog");
        thread.setDaemon(true);
        thread.start();
    }

    public static void setInterval(int seconds) {
        intervalSeconds = normalize(seconds);
    }

    public static int intervalSeconds() {
        return intervalSeconds;
    }

    public static long uptimeSeconds() {
        return (System.currentTimeMillis() - startedAt) / 1000L;
    }

    /** 起動時との差分（次のレポートに混ぜて送る） */
    public static Findings changes() {
        return CHANGES;
    }

    private static int normalize(int seconds) {
        if (seconds <= 0) {
            return 0;
        }
        return Math.max(5, Math.min(3600, seconds));
    }

    private static void loop() {
        while (true) {
            int interval = intervalSeconds;
            if (interval <= 0) {
                sleep(5000);
                continue;
            }
            // 間隔をぴったり読まれると「その瞬間だけ綺麗にする」ことができるので揺らす
            int jitter = (int) (interval * 1000L * (0.8 + ThreadLocalRandom.current().nextDouble() * 0.4));
            sleep(Math.max(3000, jitter));
            try {
                tick();
            } catch (Throwable t) {
                McsaClient.LOGGER.debug("[MCSA] ウォッチドッグの処理に失敗: {}", t.toString());
            }
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 状態を計算し、基準値と比較し、ダイジェストを送る。 */
    public static void tick() {
        State current = sample();
        int flags = 0;

        if (baseline == null) {
            baseline = digest(current);
        } else {
            if (SelfIntegrity.reverify()) {
                flags |= DigestPayload.FLAG_SELF_JAR_CHANGED;
                CHANGES.add("STATE_CHANGED", "self-jar");
            }
            if (!current.mixin().equals(baselineMixin)) {
                flags |= DigestPayload.FLAG_MIXIN_CHANGED;
                CHANGES.add("STATE_CHANGED", "mixin-configs");
            }
            if (!current.libraries().equals(baselineLibraries)) {
                flags |= DigestPayload.FLAG_LIBRARY_CHANGED;
                CHANGES.add("STATE_CHANGED", "libraries");
            }
            if (!current.loader().equals(baselineLoader)) {
                flags |= DigestPayload.FLAG_CLASSLOADER_CHANGED;
                CHANGES.add("STATE_CHANGED", "classloader");
            }
            if (!current.probes().equals(baselineProbes)) {
                flags |= DigestPayload.FLAG_PROBE_APPEARED;
                CHANGES.add("STATE_CHANGED", "probes");
            }
        }
        if (SilentCapture.supported()) {
            flags |= DigestPayload.FLAG_CAPTURE_SUPPORTED;
        }

        if (SelfIntegrity.runtimeTampered()) {
            CHANGES.add("STATE_CHANGED", "self-jar-runtime");
        }

        send(digest(current), flags);
    }

    private static volatile String baselineMixin = "";
    private static volatile String baselineLibraries = "";
    private static volatile String baselineLoader = "";
    private static volatile String baselineProbes = "";

    private static State sample() {
        State state = new State(SelfIntegrity.jarSha256(),
                fnv(String.join(",", InjectionProbes.mixinConfigNames())),
                fnv(String.join(",", LibraryScanner.libraryNames())),
                fnv(InjectionProbes.environmentDigest()),
                fnv(String.join(",", InjectionProbes.foundProbeNames(ClientSession.probeClasses()))));
        if (baselineMixin.isEmpty()) {
            baselineMixin = state.mixin();
            baselineLibraries = state.libraries();
            baselineLoader = state.loader();
            baselineProbes = state.probes();
        }
        return state;
    }

    private static String digest(State state) {
        return fnv(state.selfHash() + "|" + state.mixin() + "|" + state.libraries()
                + "|" + state.loader() + "|" + state.probes());
    }

    private static void send(String stateHex, int flags) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getNetworkHandler() == null) {
            return;
        }
        int currentSession = ClientSession.sessionId();
        if (currentSession == 0) {
            return;
        }
        seq++;
        DigestPayload payload = new DigestPayload(currentSession, seq, stateHex, flags, intervalSeconds);
        client.execute(() -> {
            if (!ClientPlayNetworking.canSend(DigestPayload.ID)) {
                return;
            }
            ClientPlayNetworking.send(payload);
        });
    }

    /** FNV-1a（64bit）の 16 進表記。内容を送らずに「変わったか」だけ伝えるための短い指紋。 */
    static String fnv(String value) {
        long hash = 0xcbf29ce484222325L;
        String lower = value == null ? "" : value.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lower.length(); i++) {
            hash ^= lower.charAt(i);
            hash *= 0x100000001b3L;
        }
        return String.format("%016x", hash);
    }

    /** レポートに載せる要約 */
    public static void write(com.google.gson.JsonObject probes) {
        com.google.gson.JsonObject watchdog = new com.google.gson.JsonObject();
        watchdog.addProperty("intervalSeconds", intervalSeconds);
        watchdog.addProperty("uptimeSeconds", uptimeSeconds());
        watchdog.addProperty("seq", seq);
        watchdog.addProperty("state", baseline == null ? "" : baseline);
        watchdog.addProperty("changed", CHANGES.size() > 0);
        List<String> changes = CHANGES.list();
        com.google.gson.JsonArray array = new com.google.gson.JsonArray();
        for (String change : changes) {
            array.add(change);
        }
        watchdog.add("changes", array);
        probes.add("watchdog", watchdog);
    }
}
