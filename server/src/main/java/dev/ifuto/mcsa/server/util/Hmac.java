package dev.ifuto.mcsa.server.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * レポートの HMAC-SHA256 検証。
 *
 * <p>鍵はクライアント jar の中にある（{@code client/build.gradle} が生成する
 * {@code hmac-key.txt}）。同一権限の相手はリバースエンジニアリングで鍵を取り出せるため、
 * これは「セキュリティ境界」ではなく、そのへんの MOD で偽レポートを投げるのを
 * 手間にするための抑止である。
 */
public final class Hmac {

    private static final String ALGORITHM = "HmacSHA256";
    /** クライアント側 {@code dev.ifuto.mcsa.client.crypto.Signer} と一致させること */
    private static final String KEY_ID_LABEL = "mcsa/keyid/v1";

    private Hmac() {
    }

    public static String sign(byte[] key, String nonce, byte[] data) {
        byte[] nonceBytes = nonce == null ? new byte[0] : nonce.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[nonceBytes.length + data.length];
        System.arraycopy(nonceBytes, 0, payload, 0, nonceBytes.length);
        System.arraycopy(data, 0, payload, nonceBytes.length, data.length);
        return Hex.encode(mac(key).doFinal(payload));
    }

    /** 定時間比較で検証する。 */
    public static boolean verify(byte[] key, String nonce, byte[] data, String expectedHex) {
        if (key == null || key.length == 0 || expectedHex == null) {
            return false;
        }
        String actual = sign(key, nonce, data);
        return MessageDigest.isEqual(actual.getBytes(StandardCharsets.UTF_8),
                expectedHex.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
    }

    /** クライアントの鍵の指紋（先頭 12 文字）。HELLO の keyId と照合する。 */
    public static String keyId(byte[] key) {
        if (key == null || key.length == 0) {
            return "";
        }
        return Hex.encode(mac(key).doFinal(KEY_ID_LABEL.getBytes(StandardCharsets.UTF_8))).substring(0, 12);
    }

    private static Mac mac(byte[] key) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return mac;
        } catch (Exception e) {
            throw new IllegalStateException("HMAC を初期化できません", e);
        }
    }
}
