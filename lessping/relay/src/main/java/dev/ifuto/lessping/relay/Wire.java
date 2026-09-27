package dev.ifuto.lessping.relay;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * クライアント MOD（{@code dev.ifuto.lessping.signal}）のペイロードと同じワイヤ形式。
 *
 * <pre>
 *   varint type / varint len + utf8(player) / varint len + utf8(data)
 * </pre>
 *
 * <p>{@code PacketByteBuf#writeString} と同じ並び（Minecraft 側のバッファは
 * 可変長整数 + バイト長 + UTF-8 で文字列を書くため）。
 */
final class Wire {

    record Msg(int type, String player, String data) {
    }

    private Wire() {
    }

    static byte[] build(int type, String player, String data) {
        byte[] p = utf8(player);
        byte[] d = utf8(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream(8 + p.length + d.length);
        writeVarInt(out, type);
        writeVarInt(out, p.length);
        out.writeBytes(p);
        writeVarInt(out, d.length);
        out.writeBytes(d);
        return out.toByteArray();
    }

    /** @return 解析できなければ null */
    static Msg parse(byte[] bytes) {
        try {
            int[] pos = {0};
            int type = readVarInt(bytes, pos);
            String player = readString(bytes, pos, 256);
            String data = readString(bytes, pos, 4096);
            return new Msg(type, player, data);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] utf8(String value) {
        return (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        int v = value;
        while (true) {
            if ((v & ~0x7F) == 0) {
                out.write(v);
                return;
            }
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
    }

    private static int readVarInt(byte[] b, int[] pos) {
        int value = 0;
        int shift = 0;
        while (shift < 28) {
            if (pos[0] >= b.length) {
                throw new IllegalArgumentException("varint が途中で切れている");
            }
            byte byteValue = b[pos[0]++];
            value |= (byteValue & 0x7F) << shift;
            if ((byteValue & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
        throw new IllegalArgumentException("varint が長すぎる");
    }

    private static String readString(byte[] b, int[] pos, int maxBytes) {
        int len = readVarInt(b, pos);
        if (len < 0 || len > maxBytes || pos[0] + len > b.length) {
            throw new IllegalArgumentException("文字列の長さが不正: " + len);
        }
        String out = new String(b, pos[0], len, StandardCharsets.UTF_8);
        pos[0] += len;
        return out;
    }
}
