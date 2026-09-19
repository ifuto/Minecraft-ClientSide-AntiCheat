package dev.ifuto.mcsa.server.net;

import dev.ifuto.mcsa.server.report.ClientReport;

import java.util.ArrayList;
import java.util.List;
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
    private final long createdAt = System.currentTimeMillis();

    private final Object lock = new Object();
    private byte[][] parts;
    private int expectedTotal = -1;
    private byte[] assembled;
    private int challengeCount;

    /** 指示（task）ごとに切り替わる nonce。前回の値も残して検証に使う */
    private volatile String nonce;
    private volatile String previousNonce = "";

    // --- 証拠（画面 / テキスト）の再構成
    private final Object evidenceLock = new Object();
    private byte[][] evidenceParts;
    private int evidenceTotal = -1;
    private int evidenceKind;
    private String evidenceName = "";
    private String evidenceHmac = "";

    // --- ウォッチドッグ（常時監視）
    private volatile long lastDigestAt;
    private volatile String lastStateHex = "";
    private volatile int digestSeq;
    private volatile int digestFlags;
    private volatile long watchUntil;
    private volatile long lastCaptureAt;
    private volatile String requestedBy = "";
    private volatile String requestReason = "";
    private volatile boolean silentAlerted;
    private final List<String> runtimeFlags = new ArrayList<>();

    private volatile Wire.Hello hello;
    private volatile ClientReport report;
    private volatile String lastError;
    private volatile long lastChallengeAt;

    public Session(UUID playerId, int sessionId, String nonce) {
        this.playerId = playerId;
        this.sessionId = sessionId;
        this.nonce = nonce == null ? "" : nonce;
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

    public String previousNonce() {
        return previousNonce;
    }

    /** 指示を出すたびに nonce を切り替える（リプレイ防止） */
    public String rotateNonce(String newNonce) {
        previousNonce = nonce;
        nonce = newNonce == null ? "" : newNonce;
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

    // ------------------------------------------------------------------ 証拠

    /**
     * 証拠の断片を受け取る。全断片が揃ったら本体を返す。
     *
     * <p>kind（画面 / テキスト）が変わったら、それまでの断片は捨てる
     * （途中で別の指示に応答した場合の混線を防ぐ）。
     *
     * @return 揃ったときのバイト列、まだなら null
     */
    public byte[] acceptEvidence(Wire.Evidence evidence) {
        synchronized (evidenceLock) {
            if (evidence.total() <= 0 || evidence.total() > 1024) {
                lastError = "証拠の断片数が不正: " + evidence.total();
                return null;
            }
            if (evidence.seq() < 0 || evidence.seq() >= evidence.total()) {
                lastError = "証拠の断片番号が不正: " + evidence.seq();
                return null;
            }
            if (evidenceTotal < 0 || evidenceKind != evidence.kind() || evidenceTotal != evidence.total()) {
                evidenceTotal = evidence.total();
                evidenceKind = evidence.kind();
                evidenceName = evidence.name();
                evidenceHmac = evidence.hmac();
                evidenceParts = new byte[evidence.total()][];
            } else {
                evidenceName = evidence.name();
                evidenceHmac = evidence.hmac();
            }
            evidenceParts[evidence.seq()] = evidence.data();
            int received = 0;
            int length = 0;
            for (byte[] part : evidenceParts) {
                if (part != null) {
                    received++;
                    length += part.length;
                }
            }
            if (received < evidenceTotal) {
                return null;
            }
            byte[] out = new byte[length];
            int offset = 0;
            for (byte[] part : evidenceParts) {
                System.arraycopy(part, 0, out, offset, part.length);
                offset += part.length;
            }
            evidenceParts = null;
            evidenceTotal = -1;
            return out;
        }
    }

    public int evidenceKind() {
        synchronized (evidenceLock) {
            return evidenceKind;
        }
    }

    public String evidenceName() {
        synchronized (evidenceLock) {
            return evidenceName;
        }
    }

    public String evidenceHmac() {
        synchronized (evidenceLock) {
            return evidenceHmac;
        }
    }

    public void resetEvidence() {
        synchronized (evidenceLock) {
            evidenceParts = null;
            evidenceTotal = -1;
            evidenceName = "";
            evidenceHmac = "";
        }
    }

    // ------------------------------------------------------------ ウォッチドッグ

    public void onDigest(Wire.Digest digest) {
        lastDigestAt = System.currentTimeMillis();
        lastStateHex = digest.stateHex();
        digestSeq = digest.seq();
        digestFlags = digest.flags();
    }

    public long lastDigestAt() {
        return lastDigestAt;
    }

    public String lastStateHex() {
        return lastStateHex;
    }

    public int digestSeq() {
        return digestSeq;
    }

    public int digestFlags() {
        return digestFlags;
    }

    /** 画面取得が可能なクライアントか（ダイジェストのフラグから） */
    public boolean captureSupported() {
        return (digestFlags & Wire.DIGEST_CAPTURE_SUPPORTED) != 0;
    }

    public void watchUntil(long timestamp) {
        this.watchUntil = timestamp;
    }

    public boolean watching() {
        return watchUntil > System.currentTimeMillis();
    }

    public long watchUntil() {
        return watchUntil;
    }

    public long lastCaptureAt() {
        return lastCaptureAt;
    }

    public void markCapture() {
        lastCaptureAt = System.currentTimeMillis();
    }

    /** 誰が・何のために指示を出したか（証拠の sidecar に残す） */
    public void request(String by, String reason) {
        this.requestedBy = by == null ? "?" : by;
        this.requestReason = reason == null ? "" : reason;
    }

    public String requestedBy() {
        return requestedBy;
    }

    public String requestReason() {
        return requestReason;
    }

    public boolean silentAlerted() {
        return silentAlerted;
    }

    public void silentAlerted(boolean value) {
        this.silentAlerted = value;
    }

    /** ダイジェストで検出した実行時の変化（レポートとは別枠で保持する） */
    public List<String> runtimeFlags() {
        synchronized (runtimeFlags) {
            return new ArrayList<>(runtimeFlags);
        }
    }

    public void runtimeFlag(String code) {
        synchronized (runtimeFlags) {
            if (!runtimeFlags.contains(code)) {
                runtimeFlags.add(code);
            }
        }
    }
}
