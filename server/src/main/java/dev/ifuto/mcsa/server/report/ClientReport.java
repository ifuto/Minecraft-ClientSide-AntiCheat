package dev.ifuto.mcsa.server.report;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * クライアントから届いたレポート 1 件分。
 *
 * <p>中身は Gson の {@link JsonObject} をそのまま保持し、よく使う値だけ抽出している。
 * フォーマットの定義は {@code docs/PROTOCOL.md}。
 */
public final class ClientReport {

    public static final String FLAG_UNVERIFIED = "UNVERIFIED";
    public static final String FLAG_CLIENT_TAMPERED = "CLIENT_TAMPERED";
    public static final String FLAG_NOT_OBFUSCATED = "NOT_OBFUSCATED";

    private static final JsonArray EMPTY_ARRAY = new JsonArray();
    private static final JsonObject EMPTY_OBJECT = new JsonObject();

    private final UUID playerId;
    private final String playerName;
    private final long receivedAt;
    private final JsonObject json;
    private final boolean hmacValid;
    private final String keyId;

    private final List<String> flags = new ArrayList<>();
    private final List<String> criticalFlags = new ArrayList<>();
    private boolean critical;

    public ClientReport(Player player, JsonObject json, boolean hmacValid, String keyId) {
        this.playerId = player.getUniqueId();
        this.playerName = player.getName();
        this.receivedAt = System.currentTimeMillis();
        this.json = json;
        this.hmacValid = hmacValid;
        this.keyId = keyId == null ? "" : keyId;
    }

    public static ClientReport parse(Player player, String json, boolean hmacValid, String keyId) {
        JsonElement parsed = JsonParser.parseString(json);
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("レポートが JSON オブジェクトではありません");
        }
        return new ClientReport(player, parsed.getAsJsonObject(), hmacValid, keyId);
    }

    public UUID playerId() {
        return playerId;
    }

    public String playerName() {
        return playerName;
    }

    public long receivedAt() {
        return receivedAt;
    }

    public boolean hmacValid() {
        return hmacValid;
    }

    public String keyId() {
        return keyId;
    }

    public JsonObject json() {
        return json;
    }

    // ------------------------------------------------------------------ 抽出

    public String modVersion() {
        return string("modVersion", "unknown");
    }

    public int protocol() {
        return json.has("proto") && json.get("proto").isJsonPrimitive() ? json.get("proto").getAsInt() : -1;
    }

    public String selfJarSha256() {
        JsonObject self = self();
        return self.has("jarSha256") ? self.get("jarSha256").getAsString() : "";
    }

    public String selfJarName() {
        JsonObject self = self();
        return self.has("jarName") ? self.get("jarName").getAsString() : "";
    }

    public boolean obfuscated() {
        JsonObject self = self();
        return self.has("obfuscated") && self.get("obfuscated").getAsBoolean();
    }

    public JsonObject self() {
        return object("self");
    }

    public JsonObject probes() {
        return object("probes");
    }

    public JsonObject shaders() {
        return object("shaders");
    }

    public JsonArray mods() {
        return array("mods");
    }

    public JsonArray modJars() {
        return array("modJars");
    }

    public JsonArray resourcePacks() {
        return array("resourcePacks");
    }

    public JsonArray resourcePackFiles() {
        return array("resourcePackFiles");
    }

    public JsonArray shaderPackFiles() {
        return array("shaderPackFiles");
    }

    /** プライバシィ告知への同意（{@code accepted} / 文面の指紋 / 同意時刻） */
    public JsonObject consent() {
        return object("consent");
    }

    /** クライアントが観測した「引っかかったもの」の一覧（{@code CODE:詳細}） */
    public List<String> findings() {
        List<String> out = new ArrayList<>();
        JsonElement element = probes().get("findings");
        if (element != null && element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                if (item != null && item.isJsonPrimitive()) {
                    out.add(item.getAsString());
                }
            }
        }
        return out;
    }

    public List<String> redacted() {
        List<String> out = new ArrayList<>();
        for (JsonElement element : array("redacted")) {
            out.add(element.getAsString());
        }
        return out;
    }

    public int modCount() {
        return mods().size();
    }

    // ------------------------------------------------------------------ 判定

    public void flag(String code, boolean isCritical) {
        String normalized = code.toUpperCase(Locale.ROOT);
        if (!flags.contains(normalized)) {
            flags.add(normalized);
        }
        if (isCritical) {
            critical = true;
            if (!criticalFlags.contains(normalized)) {
                criticalFlags.add(normalized);
            }
        }
    }

    public List<String> flags() {
        return flags;
    }

    /**
     * critical と判定されたフラグだけを返す。
     *
     * <p>ポリシー違反でのキック対象を決めるときに使う。
     * {@code UNVERIFIED}（HMAC 鍵の設定不一致）や {@code CLIENT_TAMPERED}
     * （ピン留めハッシュの更新忘れ）のような<b>運用側の設定起因</b>フラグは
     * 「禁止 MOD を入れていた」証拠ではないため、キック判断からは除外したい。
     * その判別に個別の重要度が必要になる。
     */
    public List<String> criticalFlags() {
        return new ArrayList<>(criticalFlags);
    }

    public boolean hasCritical() {
        return critical;
    }

    public String flagSummary() {
        return flags.isEmpty() ? "なし" : String.join(", ", flags);
    }

    // ------------------------------------------------------------------ 内部

    private JsonArray array(String key) {
        JsonElement element = json.get(key);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : EMPTY_ARRAY;
    }

    private JsonObject object(String key) {
        JsonElement element = json.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : EMPTY_OBJECT;
    }

    private String string(String key, String fallback) {
        JsonElement element = json.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : fallback;
    }
}
