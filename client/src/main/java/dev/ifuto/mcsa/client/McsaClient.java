package dev.ifuto.mcsa.client;

import dev.ifuto.mcsa.client.collect.ReportBuilder;
import dev.ifuto.mcsa.client.consent.ConsentManager;
import dev.ifuto.mcsa.client.consent.ConsentScreen;
import dev.ifuto.mcsa.client.integrity.SelfIntegrity;
import dev.ifuto.mcsa.client.integrity.Watchdog;
import dev.ifuto.mcsa.client.net.ChallengePayload;
import dev.ifuto.mcsa.client.net.Handshake;
import dev.ifuto.mcsa.client.net.Payloads;
import dev.ifuto.mcsa.client.net.TaskPayload;
import dev.ifuto.mcsa.client.net.Tasks;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
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

    public static McsaClient get() {
        return instance;
    }

    /**
     * ローディングオーバーレイ（初期ロード・リソース再読み込み中の例の画面）が
     * 表示中か。この間に独自画面を描くとフォントの初期化と競合して
     * ゲーム全体の文字が消える事故になるので、ここを見て待つ。
     *
     * <p>取得方法は版で変わるので、public メソッド → named フィールド →
     * intermediary({@code field_18175}) → official({@code aW}) の順に試す。
     * どれも取れない場合は「オーバーレイ無し」とみなす（タイトル画面の
     * 表示確認だけでかなり絞れるため）。
     */
    private static boolean overlayActive(MinecraftClient client) {
        if (client == null) {
            return false;
        }
        try {
            Object overlay = MinecraftClient.class.getMethod("getOverlay").invoke(client);
            return overlay != null;
        } catch (Throwable ignored) {
            // public な getter は無い
        }
        for (String name : new String[]{"overlay", "field_18175", "aW"}) {
            try {
                java.lang.reflect.Field field = MinecraftClient.class.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(client) != null;
            } catch (Throwable ignored) {
                // 次の候補へ
            }
        }
        return false;
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

        // 起動後にプライバシィ告知を出す（同意するまでプレイできない）。
        //
        // 実装は Fabric の標準 API（ScreenEvents.AFTER_INIT）+ 遅延:
        //   - 最初の tick やロード中に setScreen すると、後から表示されるタイトル画面に
        //     上書きされて消える（build ≤25 で同意画面が一度も出なかった原因）
        //   - かといって「毎 tick setScreen で差し戻す」と、ロードオーバーレイ中に
        //     currentScreen==null が続いて毎 tick 画面を作り直す暴走になり、
        //     ボタンが消えるなどの壊れ方をする（build 26 で実際に起きた）
        //   - AFTER_INIT は「画面が init() を終えた直後」に一度だけ呼ばれるので、
        //     これで受け取って、client.execute(...) で次の tick へ遅延してから
        //     差し替える（init の途中で setScreen しないための定石）
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (ConsentManager.accepted() || screen instanceof ConsentScreen) {
                return;
            }
            client.execute(() -> client.setScreen(new ConsentScreen()));
        });

        // 起動時に一度だけ自己整合性を計算しておく（重いので非同期）
        ReportBuilder.executor().execute(SelfIntegrity::prefetch);

        // 起動時だけ正常な顔をするタイプへの対策（常時監視）
        ReportBuilder.executor().execute(Watchdog::start);

        LOGGER.info("[MCSA] 初期化完了 (version={}, protocol={}, reportPolicy={}, watchdog={}s, "
                        + "capture=on (起動時同意済み, format={}))",
                modVersion(), PROTOCOL, McsaConfig.get().reportPolicy,
                McsaConfig.get().watchdogIntervalSeconds,
                McsaConfig.get().captureFormat);
    }
}
