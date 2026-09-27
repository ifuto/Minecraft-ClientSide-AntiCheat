package dev.ifuto.lessping.tunnel.lpx;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.SecureRandom;

/**
 * 最小限の STUN クライアント（RFC 5389 Binding Request / XOR-MAPPED-ADDRESS）。
 *
 * <p>NAT 越しに「自分の UDP ソケットが外からどう見えるか (ip:port)」を調べるために使う。
 * トンネルのすべての通信（STUN・パンチ・データ）は<b>同じ UDP ソケット</b>を通すので、
 * STUN で判明した ip:port がそのままパンチに使える。
 *
 * <p>リクエストの送信は TunnelManager の共有ソケットから行い、応答は
 * {@link #parseResponse} で受け取る（LPX フレームの先頭バイト 0xC7 と区別できる:
 * STUN 応答の先頭バイトは 0x00/0x01）。
 */
public final class Stun {

    public static final int MAGIC = 0x2112A442;
    public static final int BINDING_REQUEST = 0x0001;
    public static final int BINDING_SUCCESS = 0x0101;
    public static final int ATTR_XOR_MAPPED = 0x0020;
    public static final int ATTR_MAPPED = 0x0001;

    private static final SecureRandom RANDOM = new SecureRandom();

    private Stun() {
    }

    /** 12 バイトのトランザクション ID を新しく作る */
    public static byte[] newTransactionId() {
        byte[] txid = new byte[12];
        RANDOM.nextBytes(txid);
        return txid;
    }

    /** Binding Request のバイト列（20 バイト） */
    public static byte[] bindingRequest(byte[] txid) {
        byte[] out = new byte[20];
        out[0] = 0x00;
        out[1] = 0x01; // Binding Request
        out[2] = 0x00;
        out[3] = 0x00; // 長さ 0
        out[4] = 0x21;
        out[5] = 0x12;
        out[6] = (byte) 0xA4;
        out[7] = 0x42; // マジック
        System.arraycopy(txid, 0, out, 8, 12);
        return out;
    }

    /**
     * Binding Success を解析して XOR-MAPPED-ADDRESS（なければ MAPPED-ADDRESS）を返す。
     * トランザクション ID が一致しないものは null。
     */
    public static InetSocketAddress parseResponse(byte[] buf, int len, byte[] txid) {
        if (len < 20) {
            return null;
        }
        int type = ((buf[0] & 0xFF) << 8) | (buf[1] & 0xFF);
        if (type != BINDING_SUCCESS) {
            return null;
        }
        int magic = ((buf[4] & 0xFF) << 24) | ((buf[5] & 0xFF) << 16) | ((buf[6] & 0xFF) << 8) | (buf[7] & 0xFF);
        if (magic != MAGIC) {
            return null;
        }
        for (int i = 0; i < 12; i++) {
            if (buf[8 + i] != txid[i]) {
                return null;
            }
        }
        int attrLen = ((buf[2] & 0xFF) << 8) | (buf[3] & 0xFF);
        int end = Math.min(len, 20 + attrLen);
        int p = 20;
        while (p + 4 <= end) {
            int at = ((buf[p] & 0xFF) << 8) | (buf[p + 1] & 0xFF);
            int al = ((buf[p + 2] & 0xFF) << 8) | (buf[p + 3] & 0xFF);
            int value = p + 4;
            if (at == ATTR_XOR_MAPPED && al >= 8 && value + 8 <= end) {
                return xorMapped(buf, value, magic);
            }
            if (at == ATTR_MAPPED && al >= 8 && value + 8 <= end) {
                int port = ((buf[value + 2] & 0xFF) << 8) | (buf[value + 3] & 0xFF);
                byte[] ip = new byte[]{buf[value + 4], buf[value + 5], buf[value + 6], buf[value + 7]};
                return address(ip, port);
            }
            p = value + ((al + 3) & ~3); // 4 バイト境界にパディング
        }
        return null;
    }

    private static InetSocketAddress xorMapped(byte[] buf, int p, int magic) {
        if ((buf[p] & 0xFF) != 0x01) {
            return null; // IPv4 のみ対応
        }
        int xport = ((buf[p + 2] & 0xFF) << 8) | (buf[p + 3] & 0xFF);
        int port = xport ^ (MAGIC >>> 16);
        byte[] ip = new byte[4];
        for (int i = 0; i < 4; i++) {
            ip[i] = (byte) ((buf[p + 4 + i] & 0xFF) ^ ((MAGIC >>> (24 - 8 * i)) & 0xFF));
        }
        return address(ip, port);
    }

    private static InetSocketAddress address(byte[] ip, int port) {
        try {
            return new InetSocketAddress(InetAddress.getByAddress(ip), port);
        } catch (Exception e) {
            return null;
        }
    }
}
