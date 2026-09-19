package dev.ifuto.mcsa.client.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** SHA-256 のヘルパー。 */
public final class Hashing {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Hashing() {
    }

    /**
     * ファイルの SHA-256 を 16 進文字列で返す。
     *
     * @param maxBytes このバイト数を超えるファイルは {@code null}（ハッシュしない）
     */
    public static String sha256Hex(Path file, long maxBytes) {
        MessageDigest md = newDigest();
        long total = 0;
        byte[] buf = new byte[65536];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buf)) > 0) {
                total += read;
                if (maxBytes > 0 && total > maxBytes) {
                    return null;
                }
                md.update(buf, 0, read);
            }
        } catch (IOException e) {
            return null;
        }
        return hex(md.digest());
    }

    public static String sha256Hex(byte[] data) {
        return hex(newDigest().digest(data));
    }

    public static String sha256Hex(String data) {
        return hex(newDigest().digest(data.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    public static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 が使えない JVM です", e);
        }
    }

    public static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }
}
