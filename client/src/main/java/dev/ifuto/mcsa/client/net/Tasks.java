package dev.ifuto.mcsa.client.net;

import com.google.gson.JsonObject;
import dev.ifuto.mcsa.client.McsaClient;
import dev.ifuto.mcsa.client.McsaConfig;
import dev.ifuto.mcsa.client.capture.SilentCapture;
import dev.ifuto.mcsa.client.integrity.SelfIntegrity;
import dev.ifuto.mcsa.client.integrity.Watchdog;
import net.minecraft.client.MinecraftClient;

/**
 * サーバーからの指示（{@link TaskPayload}）の処理。
 *
 * <p>OP がコマンドを打つたびにここに来る。
 * どれも<b>対象プレイヤーには何も表示しない</b>（逃走されると証拠が消えるため）。
 */
public final class Tasks {

    private Tasks() {
    }

    public static void onTask(TaskPayload task) {
        ClientSession.onTask(task.sessionId(), task.nonce());
        MinecraftClient client = MinecraftClient.getInstance();
        switch (task.kind()) {
            case TaskPayload.KIND_RESCAN -> Handshake.onChallenge(ClientSession.rescanChallenge());
            case TaskPayload.KIND_CAPTURE -> {
                if (client != null) {
                    client.execute(() -> SilentCapture.capture(task.sessionId(), task.nonce(), task.reason()));
                }
            }
            case TaskPayload.KIND_NOTE -> SilentCapture.sendNote(task.sessionId(), task.nonce(), note());
            case TaskPayload.KIND_WATCH_ON -> {
                Watchdog.setInterval(task.intervalSeconds());
                SilentCapture.sendNote(task.sessionId(), task.nonce(),
                        "watch-on interval=" + Watchdog.intervalSeconds());
            }
            case TaskPayload.KIND_WATCH_OFF -> {
                Watchdog.setInterval(McsaConfig.get().watchdogIntervalSeconds);
                SilentCapture.sendNote(task.sessionId(), task.nonce(),
                        "watch-off interval=" + Watchdog.intervalSeconds());
            }
            default -> McsaClient.LOGGER.debug("[MCSA] 未知の指示: kind={}", task.kind());
        }
    }

    /** 疎通確認用の短いテキスト（証拠チャンネルで返す） */
    private static String note() {
        JsonObject json = new JsonObject();
        json.addProperty("modVersion", McsaClient.modVersion());
        json.addProperty("uptimeSeconds", Watchdog.uptimeSeconds());
        json.addProperty("capture", SilentCapture.supported());
        json.addProperty("obfuscated", SelfIntegrity.isObfuscatedBuild());
        json.addProperty("runtimeTampered", SelfIntegrity.runtimeTampered());
        return json.toString();
    }
}
