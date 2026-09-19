package dev.ifuto.mcsa.admin;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MCSA の <b>OP 用</b>クライアント MOD。
 *
 * <p>プレイヤー向けの MCSA クライアント MOD（{@code mcsa}）とは別物で、
 * <b>運営側だけが入れる</b>。役割は 2 つ。
 * <ol>
 *   <li>コンソールを開かなくても、自分のクライアントから {@code /acadmin ...} で
 *       サーバーの {@code /ac} コマンドを実行できる
 *       （画面取得 {@code shot}、監視 {@code watch}、証拠一覧 {@code evidence} など）</li>
 *   <li>サーバーからの応答（実行結果、証拠の受信通知）をチャットに表示する</li>
 * </ol>
 *
 * <p>権限判定はサーバー側（{@code mcsa.admin}）で行う。この MOD を入れていても
 * 権限がなければ何もできない。
 *
 * <p><b>注意:</b> 画面取得（{@code /acadmin shot <player>}）は対象プレイヤーの画面を
 * サーバーに保存する。使う前にサーバールールでの告知が必要（{@code docs/PRIVACY.md}）。
 */
public final class McsaAdmin implements ClientModInitializer {

    public static final String MOD_ID = "mcsa-admin";
    public static final Logger LOGGER = LoggerFactory.getLogger("MCSA-Admin");

    @Override
    public void onInitializeClient() {
        PayloadTypeRegistry.playC2S().register(AdminCommandPayload.ID, AdminCommandPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(AdminMsgPayload.ID, AdminMsgPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ShotPayload.ID, ShotPayload.CODEC);

        ClientPlayNetworking.registerGlobalReceiver(AdminMsgPayload.ID, (payload, context) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null) {
                return;
            }
            client.execute(() -> print(client, payload.message()));
        });

        // サーバーが保存した証拠（画面）を OP のクライアントへ転送してもらうチャンネル
        ClientPlayNetworking.registerGlobalReceiver(ShotPayload.ID, (payload, context) -> {
            EvidenceReceiver.onChunk(payload);
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("acadmin")
                        .executes(context -> {
                            help(context.getSource().getClient());
                            return 1;
                        })
                        .then(ClientCommandManager.argument("args", StringArgumentType.greedyString())
                                .executes(context -> {
                                    String args = StringArgumentType.getString(context, "args").trim();
                                    return send(context.getSource().getClient(), args) ? 1 : 0;
                                }))));

        LOGGER.info("[MCSA-Admin] 初期化完了。/acadmin <コマンド> でサーバーの /ac を実行できます");
    }

    private static boolean send(MinecraftClient client, String args) {
        if (args.isEmpty()) {
            help(client);
            return false;
        }
        if (client.getNetworkHandler() == null) {
            print(client, "§cサーバーに接続していません");
            return false;
        }
        if (!ClientPlayNetworking.canSend(AdminCommandPayload.ID)) {
            print(client, "§cこのサーバーは Better NArena プラグイン（mcsa:admin）を受け付けていません");
            return false;
        }
        ClientPlayNetworking.send(new AdminCommandPayload(args));
        print(client, "§7/" + "ac " + args + " §8→ サーバーへ送信しました");
        return true;
    }

    private static void help(MinecraftClient client) {
        print(client, "§6[Better NArena] §7サーバーの /ac を実行します（権限 mcsa.admin が必要）");
        print(client, "§7 /acadmin status … 導入状況");
        print(client, "§7 /acadmin info <player> … 詳細（注入観測・常時監視を含む）");
        print(client, "§7 /acadmin shot <player> [reason] … 画面を取得（対象には表示されません）");
        print(client, "§7 /acadmin watch <player> <seconds|off> … 高頻度監視");
        print(client, "§7 /acadmin evidence <player> [n] … 保存済みの証拠（自分の画面に転送される）");
        print(client, "§7 /acadmin scan <player> … 新しい nonce で再申告させる");
        print(client, "§7 /acadmin mods|packs|shaders|flags <player> … 一覧・検知履歴");
    }

    private static void print(MinecraftClient client, String message) {
        try {
            if (client.inGameHud == null) {
                return;
            }
            MutableText text = Text.literal(message);
            text.setStyle(text.getStyle().withColor(Formatting.GRAY));
            client.inGameHud.getChatHud().addMessage(text);
        } catch (Throwable ignored) {
            // HUD が無い状況（ログイン直後など）
        }
    }

}
