package dev.ifuto.lessping.tunnel.lpx;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * LessPing の共通鍵処理（MOD と中継プラグインで同じ挙動になるよう、lpx パッケージに置く）。
 *
 * <p>やることは 2 つ:
 * <ul>
 *   <li>{@link #punchToken(String)}: PUNCH フレームに載せるトークン。
 *       鍵が空なら誰でも穴あけできる（オープン）。鍵を設定すると同じ鍵を持つ
 *       相手だけがトンネルを張れる。</li>
 *   <li>{@link #encode(String, String)} / {@link #decode(String, String)}:
 *       サーバーリスト ping の version 名に載せるエンドポイント情報の
 *       エンコード（鍵があればマスク、無ければ平文 base64url）。</li>
 * </ul>
 */
public final class LpSecret {

    /** 鍵なしモードの PUNCH トークン（"LP1PUNCH" の ASCII） */
    public static final long OPEN_TOKEN = 0x4C50583150554E43L;

    /** エンコード済みデータの接頭辞 */
    public static final String PREFIX = "LP1:";

    private LpSecret() {
    }

    /** PUNCH トークン。鍵が空なら定数、なければ HMAC-SHA256 の先頭 8 バイト */
    public static long punchToken(String secret) {
        if (secret == null || secret.isEmpty()) {
            return OPEN_TOKEN;
        }
        byte[] mac = hmac(secret, "lessping-punch");
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (mac[i] & 0xFFL);
        }
        return v;
    }

    /** 平文文字列 → "LP1:" + base64url（鍵があれば HMAC キーストリームでマスク） */
    public static String encode(String plain, String secret) {
        byte[] data = plain.getBytes(StandardCharsets.UTF_8);
        byte[] masked = mask(data, secret);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(masked);
    }

    /** encode の逆。失敗したら null */
    public static String decode(String encoded, String secret) {
        if (encoded == null || !encoded.startsWith(PREFIX)) {
            return null;
        }
        try {
            byte[] masked = Base64.getUrlDecoder().decode(encoded.substring(PREFIX.length()));
            byte[] data = mask(masked, secret);
            return new String(data, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** XOR マスク（鍵が空ならそのまま）。encode/decode で同じ呼び出しなので可逆 */
    private static byte[] mask(byte[] data, String secret) {
        if (secret == null || secret.isEmpty()) {
            return data;
        }
        byte[] key = hmac(secret, "lessping-sig");
        byte[] out = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            out[i] = (byte) (data[i] ^ key[i % key.length]);
        }
        return out;
    }

    private static byte[] hmac(String secret, String purpose) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(purpose.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            // HmacSHA256 は JVM 標準で必ず実装されている。万一の際は決定的な値で代用
            try {
                return MessageDigest.getInstance("SHA-256")
                        .digest(purpose.getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) {
                return new byte[32];
            }
        }
    }
}
