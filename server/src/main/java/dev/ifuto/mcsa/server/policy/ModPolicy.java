package dev.ifuto.mcsa.server.policy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.ifuto.mcsa.server.McsaConfig;
import dev.ifuto.mcsa.server.McsaPlugin;
import dev.ifuto.mcsa.server.report.ClientReport;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * レポートの中身を照合してフラグを立てる。
 *
 * <p>「MOD 名の偽装」を捕まえる仕組みがここ。
 * <ul>
 *   <li>{@code banned-mods} … id が一致したら即 critical</li>
 *   <li>{@code pins.yml} … id ごとの SHA-256 をピン留め。
 *       既知 MOD の id を名乗っているのにハッシュが一致しなければ
 *       {@code MOD_ID_SPOOF}（＝中身を差し替えた偽物）</li>
 *   <li>{@code fabric.mod.json} を持たない jar … {@code JAR_WITHOUT_MANIFEST}
 *       （MOD ローダに姿を見せない注入物）</li>
 * </ul>
 */
public final class ModPolicy {

    private final McsaPlugin plugin;

    public ModPolicy(McsaPlugin plugin) {
        this.plugin = plugin;
    }

    public void classify(ClientReport report) {
        McsaConfig config = plugin.config();

        classifyConsent(report, config);
        classifyTrust(report, config);
        classifyMods(report, config);
        classifyModJars(report);
        classifyPacks(report, config);
        classifyShaders(report, config);
        classifyProbes(report);
        classifyRedacted(report);
    }

    /**
     * プライバシィ告知への同意を確認する。
     *
     * <p>クライアントは起動時に告知を出し、同意するまでプレイできない（拒否すると
     * Minecraft が終了する）。同意の事実（文面の指紋と時刻）はレポートに乗ってくるので、
     * ここで「同意していない」「運営が配っている文面と違う版に同意している」を拾う。
     */
    private void classifyConsent(ClientReport report, McsaConfig config) {
        if (!config.consentRequired) {
            return;
        }
        JsonObject consent = report.consent();
        boolean accepted = consent.has("accepted") && consent.get("accepted").getAsBoolean();
        if (!accepted) {
            report.flag("CONSENT_MISSING", true);
            return;
        }
        String hash = consent.has("hash") ? consent.get("hash").getAsString() : "";
        if (!config.consentNoticeHash.isEmpty() && !config.consentNoticeHash.equalsIgnoreCase(hash)) {
            report.flag("CONSENT_NOTICE_MISMATCH:" + hash, false);
        }
    }

    /**
     * MOD 1 つの判定ラベル（{@code /ac mods} の表示用）。
     *
     * @return BANNED / SUSPICIOUS / ALLOWED / PINNED / ID_SPOOF / UNKNOWN
     */
    public String verdict(String id, String sha256) {
        McsaConfig config = plugin.config();
        if (McsaConfig.matchesAny(config.bannedMods, id)) {
            return "BANNED";
        }
        if (McsaConfig.matchesAny(config.suspiciousMods, id)) {
            return "SUSPICIOUS";
        }
        if (McsaConfig.matchesAny(config.allowedMods, id)) {
            return "ALLOWED";
        }
        Set<String> pinned = config.pins.get(id == null ? "" : id.toLowerCase(Locale.ROOT));
        if (pinned != null && !pinned.isEmpty()) {
            if (sha256 == null || sha256.isEmpty()) {
                return "NO_HASH";
            }
            return pinned.contains(sha256.toLowerCase(Locale.ROOT)) ? "PINNED" : "ID_SPOOF";
        }
        return "UNKNOWN";
    }

    private void classifyTrust(ClientReport report, McsaConfig config) {
        if (config.hmacEnabled) {
            if (config.hmacKey.length == 0) {
                report.flag("HMAC_KEY_NOT_SET", false);
            } else if (!report.hmacValid()) {
                report.flag(ClientReport.FLAG_UNVERIFIED, true);
            }
        }
        if (config.requireObfuscated && !report.obfuscated()) {
            report.flag(ClientReport.FLAG_NOT_OBFUSCATED, true);
        }
        String jarSha = report.selfJarSha256();
        if (!config.pinnedClientJar.isEmpty()) {
            if (!config.pinnedClientJar.equalsIgnoreCase(jarSha)) {
                report.flag(ClientReport.FLAG_CLIENT_TAMPERED, true);
            }
        } else if (!config.pinnedClientJars.isEmpty()
                && !config.pinnedClientJars.contains(jarSha.toLowerCase(Locale.ROOT))) {
            report.flag("CLIENT_UNKNOWN_BUILD", false);
        }
        if (jarSha.isEmpty()) {
            report.flag("CLIENT_HASH_MISSING", false);
        }
    }

    private void classifyMods(ClientReport report, McsaConfig config) {
        Set<String> seen = new HashSet<>();
        for (JsonElement element : report.mods()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject mod = element.getAsJsonObject();
            String id = text(mod, "id");
            if (id.isEmpty()) {
                continue;
            }
            String sha = text(mod, "sha256");

            if (!seen.add(id.toLowerCase(Locale.ROOT))) {
                report.flag("DUPLICATE_MOD:" + id, true);
            }

            if (McsaConfig.matchesAny(config.bannedMods, id)) {
                report.flag("BANNED_MOD:" + id, true);
            } else if (McsaConfig.matchesAny(config.suspiciousMods, id)) {
                report.flag("SUSPICIOUS_MOD:" + id, false);
            } else if (!McsaConfig.matchesAny(config.allowedMods, id)) {
                Set<String> pinned = config.pins.get(id.toLowerCase(Locale.ROOT));
                if (pinned != null && !pinned.isEmpty()) {
                    if (sha.isEmpty()) {
                        report.flag("MOD_HASH_MISSING:" + id, false);
                    } else if (!pinned.contains(sha.toLowerCase(Locale.ROOT))) {
                        // 既知 MOD の id を名乗っているのに中身が違う
                        report.flag("MOD_ID_SPOOF:" + id, true);
                    }
                } else if (config.flagUnknownMods) {
                    report.flag("UNKNOWN_MOD:" + id, false);
                }
            }
        }
    }

    private void classifyModJars(ClientReport report) {
        for (JsonElement element : report.modJars()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject jar = element.getAsJsonObject();
            String file = text(jar, "file");
            if (jar.has("manifest") && !jar.get("manifest").getAsBoolean()) {
                report.flag("JAR_WITHOUT_MANIFEST:" + file, true);
            }
            if (jar.has("loaded") && !jar.get("loaded").getAsBoolean() && jar.has("manifest")
                    && jar.get("manifest").getAsBoolean()) {
                report.flag("JAR_NOT_LOADED:" + file, false);
            }
            if (jar.has("ids") && jar.get("ids").isJsonArray() && jar.getAsJsonArray("ids").size() > 1) {
                report.flag("JAR_MULTI_ID:" + file, false);
            }
        }
    }

    private void classifyPacks(ClientReport report, McsaConfig config) {
        if (config.bannedResourcePacks.isEmpty()) {
            return;
        }
        for (JsonElement element : report.resourcePacks()) {
            String id = element.getAsString();
            if (McsaConfig.matchesAny(config.bannedResourcePacks, id)) {
                report.flag("BANNED_PACK:" + id, true);
            }
        }
        for (JsonElement element : report.resourcePackFiles()) {
            if (!element.isJsonObject()) {
                continue;
            }
            String name = text(element.getAsJsonObject(), "name");
            if (McsaConfig.matchesAny(config.bannedResourcePacks, name)) {
                report.flag("BANNED_PACK:" + name, true);
            }
        }
    }

    private void classifyShaders(ClientReport report, McsaConfig config) {
        JsonObject shaders = report.shaders();
        String loader = text(shaders, "loader");
        String pack = text(shaders, "pack");
        if (!loader.isEmpty() && !"none".equalsIgnoreCase(loader)) {
            if (!pack.isEmpty() && McsaConfig.matchesAny(config.bannedShaders, pack)) {
                report.flag("BANNED_SHADER:" + pack, true);
            }
        }
        for (JsonElement element : report.shaderPackFiles()) {
            if (!element.isJsonObject()) {
                continue;
            }
            String name = text(element.getAsJsonObject(), "name");
            if (McsaConfig.matchesAny(config.bannedShaders, name)) {
                report.flag("BANNED_SHADER:" + name, true);
            }
        }
    }

    private void classifyProbes(ClientReport report) {
        JsonObject probes = report.probes();
        if (bool(probes, "agent")) {
            report.flag("JAVA_AGENT", true);
        }
        if (bool(probes, "debugger")) {
            report.flag("DEBUGGER", true);
        }
        for (JsonElement element : array(probes, "defaultProbesFound")) {
            report.flag("CHEAT_CLASS:" + element.getAsString(), true);
        }
        for (JsonElement element : array(probes, "probesFound")) {
            report.flag("CHEAT_CLASS:" + element.getAsString(), true);
        }
        JsonArray odd = array(probes, "oddClasspath");
        if (odd.size() > 0) {
            report.flag("ODD_CLASSPATH:" + odd.get(0).getAsString(), false);
        }
    }

    private void classifyRedacted(ClientReport report) {
        for (String key : report.redacted()) {
            report.flag("REDACTED:" + key, true);
        }
    }

    private static String text(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : "";
    }

    private static boolean bool(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() && element.getAsBoolean();
    }

    private static JsonArray array(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
    }
}
