package dev.ifuto.mcsa.client.net;

import dev.ifuto.mcsa.client.McsaClient;
import dev.ifuto.mcsa.client.McsaConfig;
import dev.ifuto.mcsa.client.collect.ReportBuilder;
import dev.ifuto.mcsa.client.consent.ConsentManager;
import dev.ifuto.mcsa.client.crypto.Signer;
import dev.ifuto.mcsa.client.integrity.SelfIntegrity;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;


/** チャレンジ受信 → HELLO → レポート断片 → SEAL の一連の流れ。 */
public final class Handshake {


    private Handshake() {
    }

    public static void onChallenge(ChallengePayload challenge) {
        ClientSession.onChallenge(challenge);
        MinecraftClient client = MinecraftClient.getInstance();
        String address = currentServerAddress(client);
        McsaConfig config = McsaConfig.get();

        if (!ConsentManager.accepted()) {
            // 告知に同意していない間は送らない（通常は同意画面で止まっている）
            McsaClient.LOGGER.info("[MCSA] プライバシィ告知に未同意のため自己申告を保留します");
            return;
        }

        if (!config.mayReport(address)) {
            // チャットには出さない（ログのみ）。送信状況を対象に見せない。
            McsaClient.LOGGER.info("[MCSA] {} への自己申告はクライアント設定で無効化されています", address);
            return;
        }

        if (challenge.protocol() != McsaClient.PROTOCOL) {
            McsaClient.LOGGER.warn("[MCSA] サーバのプロトコル版が違います (server={}, client={})",
                    challenge.protocol(), McsaClient.PROTOCOL);
        }

        int flags = 0;
        if (SelfIntegrity.isObfuscatedBuild()) {
            flags |= HelloPayload.FLAG_OBFUSCATED;
        }
        if (SelfIntegrity.lastSelfCheckOk()) {
            flags |= HelloPayload.FLAG_SELF_CHECK_OK;
        }
        ClientPlayNetworking.send(new HelloPayload(McsaClient.PROTOCOL, McsaClient.modVersion(),
                String.valueOf(SelfIntegrity.jarSha256()), Signer.keyId(), flags));
        // チャットには出さない（ログのみ）。プレイヤーには送信の事実を見せない。
        McsaClient.LOGGER.info("[MCSA] {} に自己申告（HELLO）を送信しました", address);

        // ファイルハッシュは重いので別スレッドで組み立て、送信だけクライアントスレッドに戻す
        ReportBuilder.executor().execute(() -> {
            byte[] gzip;
            try {
                gzip = ReportBuilder.build(challenge);
            } catch (Exception e) {
                McsaClient.LOGGER.error("[MCSA] レポートの作成に失敗しました", e);
                return;
            }
            client.execute(() -> send(challenge, gzip));
        });
    }

    private static void send(ChallengePayload challenge, byte[] gzip) {
        if (MinecraftClient.getInstance().getNetworkHandler() == null) {
            return;
        }
        if (!ClientPlayNetworking.canSend(ReportPayload.ID)) {
            McsaClient.LOGGER.warn("[MCSA] サーバが {} を受け付けないためレポートを送れません", ReportPayload.ID.id());
            return;
        }

        int total = (gzip.length + ReportPayload.CHUNK_SIZE - 1) / ReportPayload.CHUNK_SIZE;
        if (total == 0) {
            total = 1;
        }
        for (int seq = 0; seq < total; seq++) {
            int from = seq * ReportPayload.CHUNK_SIZE;
            int length = Math.min(ReportPayload.CHUNK_SIZE, gzip.length - from);
            byte[] chunk = new byte[Math.max(length, 0)];
            if (length > 0) {
                System.arraycopy(gzip, from, chunk, 0, length);
            }
            ClientPlayNetworking.send(new ReportPayload(challenge.sessionId(), seq, total, chunk));
        }
        ClientPlayNetworking.send(new SealPayload(challenge.sessionId(), total, gzip.length,
                Signer.hmacHex(challenge.nonce(), gzip)));
    }

    /** 接続先アドレス。シングルプレイ/ LAN では "singleplayer"。 */
    public static String currentServerAddress(MinecraftClient client) {
        try {
            if (client.getCurrentServerEntry() != null) {
                return client.getCurrentServerEntry().address;
            }
        } catch (Throwable ignored) {
            // 環境によっては取れない
        }
        if (client.isInSingleplayer()) {
            return "singleplayer";
        }
        return "unknown";
    }
}
