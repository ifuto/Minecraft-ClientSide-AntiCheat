package dev.ifuto.lessping;

import dev.ifuto.lessping.signal.EventsPayload;
import dev.ifuto.lessping.signal.SignalPayload;
import dev.ifuto.lessping.tunnel.TunnelManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LessPing-NArena（クライアント MOD）。
 *
 * <p>「narena」への接続を P2P トンネルへ差し替えて遅延を減らす。
 * 詳細な仕組みは {@code lessping/README.md}。
 *
 * <ul>
 *   <li>サーバー参加時に HELLO（自分のエンドポイント）を中継プラグインへ送る</li>
 *   <li>INTRO が届いた相手と UDP ホールパンチで直結する</li>
 *   <li>「narena」への接続（とサーバーリストの ping）をトンネルへ差し替える</li>
 * </ul>
 */
public final class LessPing implements ClientModInitializer {

    /** gradle.properties の lessping_version と合わせる */
    public static final String VERSION = "0.1.2";

    public static final Logger LOGGER = LoggerFactory.getLogger("lessping");

    @Override
    public void onInitializeClient() {
        LpConfig config = LpConfig.load();
        TunnelManager.get().init(config);
        if (!config.enabled) {
            LOGGER.info("[LessPing] 無効です（config/lessping/client.json の enabled）");
            return;
        }

        PayloadTypeRegistry.playC2S().register(SignalPayload.ID, SignalPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(EventsPayload.ID, EventsPayload.CODEC);

        ClientPlayNetworking.registerGlobalReceiver(EventsPayload.ID, (payload, context) ->
                context.client().execute(() ->
                        TunnelManager.get().onIntro(payload.player(), payload.data())));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                client.execute(() -> TunnelManager.get().onServerJoin(client.getSession().getUsername())));

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> TunnelManager.get().shutdown());
    }
}
