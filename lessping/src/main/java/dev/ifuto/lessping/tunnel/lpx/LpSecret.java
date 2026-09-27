package dev.ifuto.lessping.tunnel.lpx;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * LessPing の共通鍵処理（MOD と中継プラグインで同じ挙動になるよう、lpx パッケージに置く）。
 *
 * <p>やることは 2 つ:
 * <ul>
 *   <li>{@link #punchToken(String)}: PUNCH フレームに載せるトークン。
 *       鍵が空なら誰でも穴あけできる（オープン）。鍵を設定すると同じ鍵を持つ
 *       相手だけがトンネルを張れる（HMAC-SHA256 の先頭 8 バイト）。</li>
 *   <li>{@link #encode(String, String)} / {@link #decode(String, String)}:
 *       サーバーリスト ping の version 名に載せるエンドポイント情報のエンコード。
 *       鍵が空なら {@code LP1:} + 平文 base64url（誰でも読める＝認証なし運用）。
 *       鍵を設定すると {@code LP2:} + <b>AES-256-GCM</b>（ランダム nonce + 認証タグ付き）。
 *       鍵を持たない人は構造既知でも解読できず、改竈も検知される</li>
 * </ul>
 *
 * <p>鍵導出: HMAC-SHA256(secret, 用途文字列)。AES 鍵は "lessping-aes"、
 * PUNCH トークンは "lessping-punch"。
 */
public final class LpSecret {

    /** 平文（認証なし）モードの接頭辞 */
    public static final String PREFIX_PLAIN = "LP1:";
    /** AES-GCM 暗号化モードの接頭辞 */
    public static final String PREFIX_AES = "LP2:";

    /** 鍵なしモードの PUNCH トークン（"LPX1PUNC" 相当の定数。誰でも穴あけ可） */
    public static final long OPEN_TOKEN = 0x4C50583150554E43L;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int NONCE_LEN = 12;
    private static final int TAG_BITS = 128;

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

    /**
     * 平文字列を ping の version 名に載せられる形へエンコード。
     * 鍵が空なら {@code LP1:}+base64url（平文）、あれば {@code LP2:}+AES-GCM。
     */
    public static String encode(String plain, String secret) {
        byte[] data = plain.getBytes(StandardCharsets.UTF_8);
        if (secret == null || secret.isEmpty()) {
            return PREFIX_PLAIN + Base64.getUrlEncoder().withoutPadding().encodeToString(data);
        }
        try {
            byte[] nonce = new byte[NONCE_LEN];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, aesKey(secret),
                    new GCMParameterSpec(TAG_BITS, nonce));
            byte[] encrypted = cipher.doFinal(data); // 平文 + 認証タグ
            byte[] out = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, out, 0, nonce.length);
            System.arraycopy(encrypted, 0, out, nonce.length, encrypted.length);
            return PREFIX_AES + Base64.getUrlEncoder().withoutPadding().encodeToString(out);
        } catch (Exception e) {
            // AES/GCM は JVM 標準なので通常起こらない。失敗時は読み取れない文字列を返す
            return PREFIX_AES + "unavailable";
        }
    }

    /** encode の逆。鍵不一致・破損・接頭辞不一致はすべて null（=候補なし扱い） */
    public static String decode(String encoded, String secret) {
        if (encoded == null) {
            return null;
        }
        if (encoded.startsWith(PREFIX_PLAIN)) {
            try {
                return new String(Base64.getUrlDecoder()
                        .decode(encoded.substring(PREFIX_PLAIN.length())), StandardCharsets.UTF_8);
            } catch (Exception e) {
                return null;
            }
        }
        if (encoded.startsWith(PREFIX_AES)) {
            try {
                byte[] all = Base64.getUrlDecoder().decode(encoded.substring(PREFIX_AES.length()));
                if (all.length < NONCE_LEN + TAG_BITS / 8) {
                    return null;
                }
                byte[] nonce = Arrays.copyOfRange(all, 0, NONCE_LEN);
                byte[] encrypted = Arrays.copyOfRange(all, NONCE_LEN, all.length);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, aesKey(secret == null ? "" : secret),
                        new GCMParameterSpec(TAG_BITS, nonce));
                return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
            } catch (Exception e) {
                // 鍵不一致（タグ検証失敗）またはデータ破損
                return null;
            }
        }
        return null;
    }

    private static SecretKey aesKey(String secret) {
        byte[] key = hmac(secret, "lessping-aes");
        return new SecretKeySpec(key, "AES");
    }

    private static byte[] hmac(String secret, String purpose) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(purpose.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            // HmacSHA256 は JVM 標準で必ず実装されている。万一の際は決定的な値で代用
            try {
                return java.security.MessageDigest.getInstance("SHA-256")
                        .digest(purpose.getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) {
                return new byte[32];
            }
        }
    }
}
