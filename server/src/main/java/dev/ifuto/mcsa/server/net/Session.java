package dev.ifuto.mcsa.server.net;

import dev.ifuto.mcsa.server.report.ClientReport;

import java.util.UUID;

/**
 * プレイヤー 1 人のハンドシェイク状態。
 *
 * <p>チャレンジごとに {@code sessionId} と {@code nonce} を発行し、
 * レポートの断片を {@code sessionId} で束ねる。断片の受け取りはネットワークスレッドから
 * 呼ばれるため、可変部分の操作は {@code synchronized}。
 */
public final class Session {

    private final UUID playerId;
    private final int sessionId;
    private final String nonce;
    private final long createdAt = System.currentTimeMillis();

    private final Object lock = new Object();
    private byte[][] parts;
    private int expectedTotal = -1;
    private byte[] assembled;
    private int challengeCount;

    private volatile Wire.Hello hello;
    private volatile ClientReport report;
    private volatile String lastError;
    private volatile long lastChallengeAt;

    public Session(UUID playerId, int sessionId, String nonce) {
        this.playerId = playerId;
        this.sessionId = sessionId;
        this.nonce = nonce;
    }

    public UUID playerId() {
        return playerId;
    }

    public int sessionId() {
        return sessionId;
    }

    public String nonce() {
        return nonce;
    }

    public long createdAt() {
        return createdAt;
    }

    public Wire.Hello hello() {
        return hello;
    }

    public void hello(Wire.Hello hello) {
        this.hello = hello;
    }

    public ClientReport report() {
        return report;
    }

    public void report(ClientReport report) {
        this.report = report;
    }

    public String lastError() {
        return lastError;
    }

    public void lastError(String error) {
        this.lastError = error;
    }

    public int challengeCount() {
        return challengeCount;
    }

    public long lastChallengeAt() {
        return lastChallengeAt;
    }

    public void markChallenged() {
        this.challengeCount++;
        this.lastChallengeAt = System.currentTimeMillis();
    }

    /** HELLO かレポートのどちらかが届いていれば「MOD 導入済み」とみなす。 */
    public boolean hasMod() {
        return hello != null || report != null;
    }

    /**
     * レポート断片を受け取る。全断片が揃ったら再構成したバイト列を返す。
     *
     * @return 揃ったときの gzip バイト列、まだなら null
     */
    public byte[] accept(Wire.Chunk chunk) {
        synchronized (lock) {
            if (chunk.sessionId() != sessionId) {
                lastError = "セッション不一致 (expected=" + sessionId + ", got=" + chunk.sessionId() + ")";
                return null;
            }
            if (chunk.total() <= 0 || chunk.total() > 4096) {
                lastError = "断片数が不正: " + chunk.total();
                return null;
            }
            if (chunk.seq() < 0 || chunk.seq() >= chunk.total()) {
                lastError = "断片番号が不正: " + chunk.seq();
                return null;
            }
            if (expectedTotal < 0) {
                expectedTotal = chunk.total();
                parts = new byte[chunk.total()][];
            } else if (expectedTotal != chunk.total()) {
                lastError = "断片数が途中で変わった: " + expectedTotal + " -> " + chunk.total();
                return null;
            }
            parts[chunk.seq()] = chunk.data();
            int received = 0;
            int length = 0;
            for (byte[] part : parts) {
                if (part != null) {
                    received++;
                    length += part.length;
                }
            }
            if (received < expectedTotal) {
                return null;
            }
            assembled = new byte[length];
            int offset = 0;
            for (byte[] part : parts) {
                System.arraycopy(part, 0, assembled, offset, part.length);
                offset += part.length;
            }
            parts = null;
            return assembled;
        }
    }

    /** 再構成済みで、まだ消費していないレポート本体を取り出す。 */
    public byte[] takeAssembled() {
        synchronized (lock) {
            byte[] data = assembled;
            assembled = null;
            return data;
        }
    }
}
