package dev.ifuto.mcsa.client.collect;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.ifuto.mcsa.client.McsaClient;
import dev.ifuto.mcsa.client.McsaConfig;
import dev.ifuto.mcsa.client.integrity.Findings;
import dev.ifuto.mcsa.client.integrity.RuntimeProbes;
import dev.ifuto.mcsa.client.integrity.SelfIntegrity;
import dev.ifuto.mcsa.client.integrity.Watchdog;
import dev.ifuto.mcsa.client.net.ChallengePayload;
import dev.ifuto.mcsa.client.net.Handshake;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPOutputStream;

/**
 * レポート JSON を組み立てて gzip する。
 *
 * <p>フィールドの並びは {@code docs/PROTOCOL.md} が定義する順序どおり。
 * サーバ側（Paper プラグイン）はこの JSON を読むだけで、Minecraft のクラスには依存しない。
 */
public final class ReportBuilder {

    private static final Gson GSON = new Gson();

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "MCSA-Report");
        thread.setDaemon(true);
        return thread;
    });

    private ReportBuilder() {
    }

    public static ExecutorService executor() {
        return EXECUTOR;
    }

    public static byte[] build(ChallengePayload challenge) throws IOException {
        McsaConfig config = McsaConfig.get();
        int requested = challenge.collectFlags();

        JsonObject root = new JsonObject();
        root.addProperty("proto", McsaClient.PROTOCOL);
        root.addProperty("nonce", challenge.nonce());
        root.addProperty("sessionId", challenge.sessionId());
        root.addProperty("ts", System.currentTimeMillis());
        root.addProperty("modVersion", McsaClient.modVersion());

        JsonObject client = new JsonObject();
        client.addProperty("mc", gameVersion());
        client.addProperty("os", System.getProperty("os.name", "unknown"));
        client.addProperty("arch", System.getProperty("os.arch", "unknown"));
        client.addProperty("jvm", System.getProperty("java.version", "unknown"));
        client.addProperty("server", Handshake.currentServerAddress(MinecraftClient.getInstance()));
        root.add("client", client);

        // プレイヤーが送信を拒否した項目（サーバは「隠された」と分かる）
        JsonArray redacted = new JsonArray();
        if (!config.collectMods) {
            redacted.add("mods");
        }
        if (!config.collectResourcePacks) {
            redacted.add("resourcePacks");
        }
        if (!config.collectShaderPacks) {
            redacted.add("shaderPacks");
        }
        if (!config.collectJvmArgs) {
            redacted.add("jvmArgs");
        }
        if (!config.collectInjection) {
            redacted.add("injection");
        }
        root.add("redacted", redacted);

        if (config.collectMods && wants(requested, ChallengePayload.FLAG_MODS)) {
            ModScanner.collect(root, config);
        }
        if (config.collectResourcePacks && wants(requested, ChallengePayload.FLAG_RESOURCE_PACKS)) {
            PackScanner.collect(root, config);
        }
        if (config.collectShaderPacks && wants(requested, ChallengePayload.FLAG_SHADER_PACKS)) {
            ShaderScanner.collect(root, config);
        }
        Findings findings = new Findings();
        if (config.collectInjection) {
            RuntimeProbes.collect(root, config, challenge.probeClasses(),
                    wants(requested, ChallengePayload.FLAG_JVM_ARGS), findings);
        }

        // ウォッチドッグが起動時から溜めこんでいる変化も同じ findings に混ぜる
        for (String change : Watchdog.changes().list()) {
            findings.add(change);
        }
        JsonObject probes = root.getAsJsonObject("probes");
        if (probes == null) {
            probes = new JsonObject();
            root.add("probes", probes);
        }
        probes.add("findings", findings.toJson());
        probes.addProperty("findingCount", findings.size());
        probes.addProperty("findingDropped", findings.dropped());

        // 自己整合性は必ず送る（サーバが「この jar は改変されている」と照合するため）
        SelfIntegrity.write(root);

        return gzip(GSON.toJson(root).getBytes(StandardCharsets.UTF_8));
    }

    private static boolean wants(int flags, int bit) {
        return (flags & bit) != 0;
    }

    private static String gameVersion() {
        return FabricLoader.getInstance()
                .getModContainer("minecraft")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    private static byte[] gzip(byte[] raw) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, raw.length / 4));
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(raw);
        }
        return out.toByteArray();
    }
}
