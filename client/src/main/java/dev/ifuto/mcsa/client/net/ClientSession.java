package dev.ifuto.mcsa.client.net;

import java.util.Collections;
import java.util.List;

/**
 * いま接続中のサーバーとの「会話の状態」。
 *
 * <p>チャレンジ（{@link ChallengePayload}）と指示（{@link TaskPayload}）の
 * {@code sessionId} / {@code nonce} を覚えておく場所。
 * ウォッチドッグや証拠送信が「どのセッションに対して送るか」を知るために使う。
 */
public final class ClientSession {

    private static volatile int sessionId;
    private static volatile String nonce = "";
    private static volatile ChallengePayload lastChallenge;
    private static volatile List<String> probeClasses = List.of();

    private ClientSession() {
    }

    public static void onChallenge(ChallengePayload challenge) {
        if (challenge == null) {
            return;
        }
        sessionId = challenge.sessionId();
        nonce = challenge.nonce() == null ? "" : challenge.nonce();
        lastChallenge = challenge;
        probeClasses = challenge.probeClasses() == null
                ? List.of() : Collections.unmodifiableList(challenge.probeClasses());
    }

    /** 指示（task）は新しい nonce を持ってくる。以降の証拠はこの nonce で署名する。 */
    public static void onTask(int newSessionId, String newNonce) {
        sessionId = newSessionId;
        nonce = newNonce == null ? "" : newNonce;
    }

    /** サーバーが最後に指定した検知パターン（クラス名 / {@code prefix:} 等） */
    public static List<String> probeClasses() {
        return probeClasses;
    }

    public static int sessionId() {
        return sessionId;
    }

    public static String nonce() {
        return nonce;
    }

    /** 直近のチャレンジ。未取得なら null */
    public static ChallengePayload lastChallenge() {
        return lastChallenge;
    }

    /** 新しい nonce でチャレンジを作り直す（再申告に使う） */
    public static ChallengePayload rescanChallenge() {
        ChallengePayload last = lastChallenge;
        int flags = last == null ? ChallengePayload.FLAG_MODS | ChallengePayload.FLAG_RESOURCE_PACKS
                | ChallengePayload.FLAG_SHADER_PACKS | ChallengePayload.FLAG_JVM_ARGS : last.collectFlags();
        return new ChallengePayload(dev.ifuto.mcsa.client.McsaClient.PROTOCOL, sessionId, nonce,
                last == null ? "" : last.serverId(), probeClasses, flags);
    }
}
