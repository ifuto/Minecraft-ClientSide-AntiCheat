package dev.ifuto.lessping.tunnel.lpx;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * LPX v1 のワイヤフォーマット（LPX = LessPing eXchange、自作の reliable-UDP）。
 *
 * <pre>
 *   u8  magic = 0xC7
 *   u8  type
 *   i32 conv（コネクション ID。クライアント側の TCP accept ごとにランダムに決める）
 *
 *   DATA  (1): i32 sn, u16 len, bytes[len]          … ストリーム断片
 *   ACK   (2): i32 una, u16 count, i32[count] sns   … una=未受信の最小 sn、sns=受信済みの穴埋めリスト
 *   PUNCH (3): i64 token                            … NAT 穴あけ
 *   PONG  (4): i64 token                            … 穴あけ応答（同じ token を返す）
 *   KEEP  (5):                                      … keepalive（NAT マップの維持）
 *   CLOSE (6):                                      … コネクション終了
 * </pre>
 *
 * <p>すべてビッグエンディアン。暗号化は v1 ではしない（Minecraft のプロトコル暗号が
 * エンドツーエンドで掛かるため。詳細は lessping/README.md）。
 */
public final class LpxFrame {

    public static final byte MAGIC = (byte) 0xC7;
    public static final int T_DATA = 1;
    public static final int T_ACK = 2;
    public static final int T_PUNCH = 3;
    public static final int T_PONG = 4;
    public static final int T_KEEP = 5;
    public static final int T_CLOSE = 6;

    public static final int MAX_SNS = 64;

    private LpxFrame() {
    }

    /** 受信したフレームの解析結果。タイプに応じたフィールドのみ有効。 */
    public record Decoded(int type, int conv, int sn, int una, int[] sns, long token,
                          byte[] payload, SocketAddress from) {
    }

    public static byte[] data(int conv, int sn, byte[] payload, int off, int len) {
        byte[] out = new byte[2 + 4 + 4 + 2 + len];
        int p = 0;
        out[p++] = MAGIC;
        out[p++] = T_DATA;
        p = i32(out, p, conv);
        p = i32(out, p, sn);
        out[p++] = (byte) (len >>> 8);
        out[p++] = (byte) len;
        System.arraycopy(payload, off, out, p, len);
        return out;
    }

    public static byte[] ack(int conv, int una, int[] sns) {
        int n = Math.min(sns.length, MAX_SNS);
        byte[] out = new byte[2 + 4 + 4 + 2 + n * 4];
        int p = 0;
        out[p++] = MAGIC;
        out[p++] = T_ACK;
        p = i32(out, p, conv);
        p = i32(out, p, una);
        out[p++] = (byte) (n >>> 8);
        out[p++] = (byte) n;
        for (int i = 0; i < n; i++) {
            p = i32(out, p, sns[i]);
        }
        return out;
    }

    public static byte[] token(int type, int conv, long token) {
        byte[] out = new byte[2 + 4 + 8];
        int p = 0;
        out[p++] = MAGIC;
        out[p++] = (byte) type;
        p = i32(out, p, conv);
        p = i64(out, p, token);
        return out;
    }

    public static byte[] simple(int type, int conv) {
        byte[] out = new byte[2 + 4];
        out[0] = MAGIC;
        out[1] = (byte) type;
        i32(out, 2, conv);
        return out;
    }

    /** @return 解析できなければ null（知らないプロトコルのパケット） */
    public static Decoded decode(byte[] buf, int len, SocketAddress from) {
        if (len < 6 || buf[0] != MAGIC) {
            return null;
        }
        int type = buf[1] & 0xFF;
        int conv = r32(buf, 2);
        switch (type) {
            case T_DATA: {
                if (len < 12) {
                    return null;
                }
                int sn = r32(buf, 6);
                int plen = ((buf[10] & 0xFF) << 8) | (buf[11] & 0xFF);
                if (len < 12 + plen) {
                    return null;
                }
                byte[] payload = new byte[plen];
                System.arraycopy(buf, 12, payload, 0, plen);
                return new Decoded(T_DATA, conv, sn, 0, null, 0, payload, from);
            }
            case T_ACK: {
                if (len < 12) {
                    return null;
                }
                int una = r32(buf, 6);
                int count = ((buf[10] & 0xFF) << 8) | (buf[11] & 0xFF);
                if (len < 12 + count * 4 || count > 1024) {
                    return null;
                }
                int[] sns = new int[count];
                for (int i = 0; i < count; i++) {
                    sns[i] = r32(buf, 12 + i * 4);
                }
                return new Decoded(T_ACK, conv, 0, una, sns, 0, null, from);
            }
            case T_PUNCH, T_PONG: {
                if (len < 14) {
                    return null;
                }
                long token = r64(buf, 6);
                return new Decoded(type, conv, 0, 0, null, token, null, from);
            }
            case T_KEEP, T_CLOSE: {
                return new Decoded(type, conv, 0, 0, null, 0, null, from);
            }
            default:
                return null;
        }
    }

    /** デバッグ表示用のアドレス短縮 */
    public static String addr(SocketAddress address) {
        if (address instanceof InetSocketAddress inet) {
            return inet.getHostString() + ":" + inet.getPort();
        }
        return String.valueOf(address);
    }

    public static String text(byte[] payload) {
        return new String(payload, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ 内部

    private static int i32(byte[] out, int p, int v) {
        out[p] = (byte) (v >>> 24);
        out[p + 1] = (byte) (v >>> 16);
        out[p + 2] = (byte) (v >>> 8);
        out[p + 3] = (byte) v;
        return p + 4;
    }

    private static int i64(byte[] out, int p, long v) {
        p = i32(out, p, (int) (v >>> 32));
        return i32(out, p, (int) v);
    }

    private static int r32(byte[] buf, int p) {
        return ((buf[p] & 0xFF) << 24) | ((buf[p + 1] & 0xFF) << 16)
                | ((buf[p + 2] & 0xFF) << 8) | (buf[p + 3] & 0xFF);
    }

    private static long r64(byte[] buf, int p) {
        return ((long) r32(buf, p) << 32) | (r32(buf, p + 4) & 0xFFFFFFFFL);
    }
}
