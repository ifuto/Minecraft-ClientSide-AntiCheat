package dev.ifuto.mcsa.client.net;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

/**
 * ペイロードの登録。送受信の両側で同じチャンネル名・同じ並び順のフィールドを使うこと。
 * （対応するサーバ実装: {@code dev.ifuto.mcsa.server.net.Wire} / {@code ReportListener}）
 */
public final class Payloads {

    private Payloads() {
    }

    public static void register() {
        // S2C（サーバ → クライアント）
        PayloadTypeRegistry.playS2C().register(ChallengePayload.ID, ChallengePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(TaskPayload.ID, TaskPayload.CODEC);
        // C2S（クライアント → サーバ）
        PayloadTypeRegistry.playC2S().register(HelloPayload.ID, HelloPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(ReportPayload.ID, ReportPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(SealPayload.ID, SealPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(EvidencePayload.ID, EvidencePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(DigestPayload.ID, DigestPayload.CODEC);
    }
}
