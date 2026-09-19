package dev.ifuto.mcsa.client;

import dev.ifuto.mcsa.client.collect.ReportBuilder;
import dev.ifuto.mcsa.client.consent.ConsentScreen;
import dev.ifuto.mcsa.client.integrity.SelfIntegrity;
import dev.ifuto.mcsa.client.integrity.Watchdog;
import dev.ifuto.mcsa.client.net.ChallengePayload;
import dev.ifuto.mcsa.client.net.Handshake;
import dev.ifuto.mcsa.client.net.Payloads;
import dev.ifuto.mcsa.client.net.TaskPayload;
import dev.ifuto.mcsa.client.net.Tasks;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MCSA クライアント MOD のエントリーポイント。
 *
 * <p>この MOD は「サーバが求めたときに、自分の環境（MOD / リソースパック / シェーダー）を
 * 自己申告する」ことしかしない。判定と処分はすべてサーバ側プラグインが行う。
 * 自己申告は原理的に偽装可能なので、サーバ側では「証拠の一つ」として扱うこと。
 */
public final class McsaClient implements ClientModInitializer {

    public static final String MOD_ID = "mcsa";
    /** サーバプラグインとの通信プロトコル版。両側で一致させること。 */
    public static final int PROTOCOL = 1;
    public static final Logger LOGGER = LoggerFactory.getLogger("MCSA");

    private static McsaClient instance;
    /** 起動直後の 1 tick だけ同意画面を確認する */
    private static volatile boolean consentChecked;

    public static McsaClient get() {
        return instance;
    }

    public static String modVersion() {
        return FabricLoader.getInstance()
                .getModContainer(MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    @Override
    public void onInitializeClient() {
        instance = this;
        McsaConfig.load();
        Payloads.register();

        // S2C: サーバからのチャレンジを受け取る（この受信が「MOD 導入済み」の一次判定になる）
        ClientPlayNetworking.registerGlobalReceiver(ChallengePayload.ID,
                (payload, context) -> Handshake.onChallenge(payload));

        // S2C: OP からの指示（再申告 / 画面取得 / 監視間隔の変更）
        ClientPlayNetworking.registerGlobalReceiver(TaskPayload.ID,
                (payload, context) -> Tasks.onTask(payload));

        // 起動直後にプライバシィ告知を出す（同意するまでプレイできない）
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (consentChecked) {
                return;
            }
            consentChecked = true;
            ConsentScreen.openIfRequired(client);
        });

        // 起動時に一度だけ自己整合性を計算しておく（重いので非同期）
        ReportBuilder.executor().execute(SelfIntegrity::prefetch);

        // 起動時だけ正常な顔をするタイプへの対策（常時監視）
        ReportBuilder.executor().execute(Watchdog::start);

        LOGGER.info("[MCSA] 初期化完了 (version={}, protocol={}, reportPolicy={}, watchdog={}s, capture={})",
                modVersion(), PROTOCOL, McsaConfig.get().reportPolicy,
                McsaConfig.get().watchdogIntervalSeconds,
                McsaConfig.get().allowCapture ? "allowed" : "denied");
    }
}
