package dev.ifuto.mcsa.server.net;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * MCSA の通信フォーマット。
 *
 * <p>クライアント MOD 側の {@code dev.ifuto.mcsa.client.net.*} と
 * <b>1 バイト単位で同じ並び</b>にすること（{@code docs/PROTOCOL.md} が定義）。
 * すべて Minecraft のプラグインメッセージ（カスタムペイロード）に乗るため、
 * Bukkit 側ではチャンネル名 + 生バイト列として届く。
 *
 * <p>エンコーディングは Minecraft の慣例どおり。
 * <ul>
 *   <li>varint … 7bit ずつ、下位から。最長 5 バイト</li>
 *   <li>string … varint でバイト長 + UTF-8</li>
 * </ul>
 */
public final class Wire {

    public static final String CH_CHALLENGE = "mcsa:challenge";
    public static final String CH_HELLO = "mcsa:hello";
    public static final String CH_REPORT = "mcsa:report";
    public static final String CH_SEAL = "mcsa:seal";

    /** チャレンジの collectFlags ビット */
    public static final int FLAG_MODS = 1;
    public static final int FLAG_RESOURCE_PACKS = 1 << 1;
    public static final int FLAG_SHADER_PACKS = 1 << 2;
    public static final int FLAG_JVM_ARGS = 1 << 3;

    public record Hello(int protocol, String modVersion, String selfJarSha256, String keyId, int flags) {
    }

    public record Chunk(int sessionId, int seq, int total, byte[] data) {
    }

    public record Seal(int sessionId, int total, int dataLength, String hmac) {
    }

    private Wire() {
    }

    // ---------------------------------------------------------------- S2C

    public static byte[] encodeChallenge(int protocol, int sessionId, String nonce, String serverId,
                                         List<String> probeClasses, int collectFlags) {
        Writer writer = new Writer();
        writer.varInt(protocol);
        writer.varInt(sessionId);
        writer.string(nonce == null ? "" : nonce);
        writer.string(serverId == null ? "" : serverId);
        List<String> probes = probeClasses == null ? List.of() : probeClasses;
        writer.varInt(probes.size());
        for (String probe : probes) {
            writer.string(probe == null ? "" : probe);
        }
        writer.varInt(collectFlags);
        return writer.toByteArray();
    }

    // ---------------------------------------------------------------- C2S

    public static Hello decodeHello(byte[] raw) {
        Reader reader = new Reader(raw);
        return new Hello(reader.varInt(), reader.string(64), reader.string(128), reader.string(64), reader.varInt());
    }

    public static Chunk decodeChunk(byte[] raw) {
        Reader reader = new Reader(raw);
        int sessionId = reader.varInt();
        int seq = reader.varInt();
        int total = reader.varInt();
        return new Chunk(sessionId, seq, total, reader.bytes(1 << 20));
    }

    public static Seal decodeSeal(byte[] raw) {
        Reader reader = new Reader(raw);
        return new Seal(reader.varInt(), reader.varInt(), reader.varInt(), reader.string(128));
    }

    // ---------------------------------------------------------------- 入出力

    private static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream(128);

        void varInt(int value) {
            int v = value;
            while ((v & 0xFFFFFF80) != 0) {
                out.write((v & 0x7F) | 0x80);
                v >>>= 7;
            }
            out.write(v & 0x7F);
        }

        void string(String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            varInt(bytes.length);
            out.write(bytes, 0, bytes.length);
        }

        byte[] toByteArray() {
            return out.toByteArray();
        }
    }

    private static final class Reader {
        private final byte[] buf;
        private int pos;

        Reader(byte[] buf) {
            this.buf = buf == null ? new byte[0] : buf;
        }

        int varInt() {
            int result = 0;
            int shift = 0;
            while (true) {
                if (pos >= buf.length) {
                    throw new IllegalArgumentException("varint が途中で終わりました");
                }
                byte b = buf[pos++];
                result |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return result;
                }
                shift += 7;
                if (shift > 28) {
                    throw new IllegalArgumentException("varint が長すぎます");
                }
            }
        }

        String string(int maxLength) {
            byte[] bytes = bytes(maxLength);
            return new String(bytes, StandardCharsets.UTF_8);
        }

        byte[] bytes(int maxLength) {
            int length = varInt();
            if (length < 0 || length > maxLength) {
                throw new IllegalArgumentException("長さが不正です: " + length);
            }
            if (pos + length > buf.length) {
                throw new IllegalArgumentException("データが足りません");
            }
            byte[] out = new byte[length];
            System.arraycopy(buf, pos, out, 0, length);
            pos += length;
            return out;
        }
    }
}
