package dev.ifuto.mcsa.client.crypto;

import dev.ifuto.mcsa.client.gen.KeyMaterial;
import dev.ifuto.mcsa.client.util.Hashing;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * レポートの改ざん検知に使う HMAC-SHA256。
 *
 * <p><b>注意:</b> 鍵はクライアント jar の中にある。同一権限の相手（＝チート使用者本人）は
 * リバースエンジニアリングで鍵を取り出せるため、これは「セキュリティ境界」ではなく
 * <b>「そのへんの MOD で偽装レポートを投げるのを手間にする」ための抑止</b>である。
 * ビルド時の難読化（ProGuard）と XOR マスクされた鍵素材と合わせて使うこと。
 */
public final class Signer {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String KEY_ID_LABEL = "mcsa/keyid/v1";

    private Signer() {
    }

    /** {@code nonce ‖ data} の HMAC-SHA256 を 16 進文字列で返す。 */
    public static String hmacHex(String nonceHex, byte[] data) {
        byte[] nonce = nonceHex == null ? new byte[0] : nonceHex.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[nonce.length + data.length];
        System.arraycopy(nonce, 0, payload, 0, nonce.length);
        System.arraycopy(data, 0, payload, nonce.length, data.length);
        return Hashing.hex(mac().doFinal(payload));
    }

    /**
     * 鍵の指紋（先頭 12 文字）。サーバ側が「自分の鍵とクライアントの鍵が一致しているか」を
     * 検証するために HELLO で送る。鍵そのものは送らない。
     */
    public static String keyId() {
        return Hashing.hex(mac().doFinal(KEY_ID_LABEL.getBytes(StandardCharsets.UTF_8))).substring(0, 12);
    }

    private static Mac mac() {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(KeyMaterial.key(), ALGORITHM));
            return mac;
        } catch (Exception e) {
            throw new IllegalStateException("HMAC を初期化できません", e);
        }
    }
}
