package dev.ifuto.lessping.tunnel.lpx;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LPX のループバックテスト（CI の build で必ず走る。失敗したらビルドを落とす）。
 *
 * <p>2 つのエンドポイントを「12% ロス + 15〜35ms ジッタ」の仮想リンクで繋ぎ、
 * 同じ conv の LPX ストリームを両端に作って双方向 2MB ずつのランダムデータが
 * 欠落・破損・順序崩壊なしに届くことを確認する（実運用と同じ構成:
 * 1 conv = 1 TCP 接続、両方向のデータと ACK が同じ conv に乗る）。
 *
 * <pre>
 *   java dev.ifuto.lessping.tunnel.lpx.LpxLoopbackTest   (exit 0 = 成功)
 * </pre>
 */
public final class LpxLoopbackTest {

    /** 送るデータ量（片方向） */
    private static final int BYTES = 2 * 1024 * 1024;
    /** ロス率 */
    private static final double LOSS = 0.12;
    /** ベース遅延（ms） */
    private static final int BASE_DELAY = 15;
    /** ジッタ（ms） */
    private static final int JITTER = 20;
    /** テスト全体のタイムアウト（秒） */
    private static final int TIMEOUT_SECONDS = Integer.getInteger("lpx.test.timeoutSeconds", 120);

    /** 仮想リンクの片側（受信コールバックを持つ） */
    private interface Receiver {
        void onPacket(byte[] data, int len, SocketAddress from);
    }

    /** ロスとジッタを載せる仮想ネットワーク */
    private static final class LossyNetwork {
        private final ConcurrentMap<SocketAddress, Receiver> nodes = new ConcurrentHashMap<>();
        private final ScheduledExecutorService scheduler = new ScheduledThreadPoolExecutor(2);
        private final Random random = new Random(12345L);
        private final AtomicLong dropped = new AtomicLong();
        private final AtomicLong delivered = new AtomicLong();

        void attach(SocketAddress address, Receiver receiver) {
            nodes.put(address, receiver);
        }

        LpxStream.Sender senderOf(SocketAddress self) {
            return (frame, to) -> {
                if (random.nextDouble() < LOSS) {
                    dropped.incrementAndGet();
                    return; // パケットロス
                }
                delivered.incrementAndGet();
                long delay = BASE_DELAY + random.nextInt(JITTER + 1);
                byte[] copy = new byte[frame.length];
                System.arraycopy(frame, 0, copy, 0, frame.length);
                scheduler.schedule(() -> {
                    Receiver receiver = nodes.get(to);
                    if (receiver != null) {
                        receiver.onPacket(copy, copy.length, self);
                    }
                }, delay, TimeUnit.MILLISECONDS);
            };
        }

        long dropped() {
            return dropped.get();
        }

        long delivered() {
            return delivered.get();
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("[LPX-TEST] 開始: " + BYTES + " bytes/方向, " + (int) (LOSS * 100)
                + "% ロス, " + BASE_DELAY + "+" + JITTER + "ms ジッタ");

        InetAddress aIp = InetAddress.getByAddress(new byte[]{10, 0, 0, 1});
        InetAddress bIp = InetAddress.getByAddress(new byte[]{10, 0, 0, 2});
        SocketAddress addrA = new InetSocketAddress(aIp, 5001);
        SocketAddress addrB = new InetSocketAddress(bIp, 5002);

        LossyNetwork network = new LossyNetwork();

        // 同じ conv のストリームを両端に作る（実運用と同じ）
        final int conv = 1001;
        LpxStream streamA = new LpxStream(conv, addrB, network.senderOf(addrA), 5000);
        LpxStream streamB = new LpxStream(conv, addrA, network.senderOf(addrB), 5000);

        network.attach(addrA, (data, len, from) -> {
            LpxFrame.Decoded frame = LpxFrame.decode(data, len, from);
            if (frame != null) {
                streamA.onFrame(frame);
            }
        });
        network.attach(addrB, (data, len, from) -> {
            LpxFrame.Decoded frame = LpxFrame.decode(data, len, from);
            if (frame != null) {
                streamB.onFrame(frame);
            }
        });

        // 共有タイカー（送信・再送・ACK を回す。実運用では TunnelManager が持つ）
        ScheduledExecutorService ticker = new ScheduledThreadPoolExecutor(1);
        ticker.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            streamA.tick(now);
            streamB.tick(now);
        }, 5, 5, TimeUnit.MILLISECONDS);

        // 方向ごとに別の乱数データ
        byte[] payloadA = new byte[BYTES];
        new Random(777L).nextBytes(payloadA);
        byte[] payloadB = new byte[BYTES];
        new Random(888L).nextBytes(payloadB);

        // 送信側（write は背圧でブロックする。送り終えたら flush 付き close）
        Thread senderA = new Thread(() -> sendAll(streamA, payloadA), "lp-test-send-a");
        Thread senderB = new Thread(() -> sendAll(streamB, payloadB), "lp-test-send-b");

        // 受信側（outbox から順に取り出す）
        ByteArrayOutputStream receivedAtA = new ByteArrayOutputStream();
        ByteArrayOutputStream receivedAtB = new ByteArrayOutputStream();
        Thread receiverA = new Thread(() -> drain(streamA, receivedAtA), "lp-test-recv-a");
        Thread receiverB = new Thread(() -> drain(streamB, receivedAtB), "lp-test-recv-b");

        senderA.start();
        senderB.start();
        receiverA.start();
        receiverB.start();

        long deadline = System.currentTimeMillis() + TIMEOUT_SECONDS * 1000L;
        senderA.join(Math.max(1, deadline - System.currentTimeMillis()));
        senderB.join(Math.max(1, deadline - System.currentTimeMillis()));
        receiverA.join(Math.max(1, deadline - System.currentTimeMillis()));
        receiverB.join(Math.max(1, deadline - System.currentTimeMillis()));

        ticker.shutdownNow();
        network.scheduler.shutdownNow();

        boolean ok = true;
        ok &= check("A→B", payloadA, receivedAtB.toByteArray());
        ok &= check("B→A", payloadB, receivedAtA.toByteArray());

        System.out.println("[LPX-TEST] フレーム到達 " + network.delivered() + " / ロス演出 "
                + network.dropped() + " (" + String.format("%.1f", 100.0 * network.dropped()
                / Math.max(1, network.dropped() + network.delivered())) + "%)");
        System.out.println("[LPX-TEST] A " + streamA.stats());
        System.out.println("[LPX-TEST] B " + streamB.stats());

        if (!ok) {
            System.out.println("error: [LPX-TEST] 失敗: データが完全には届かなかった");
            System.exit(1);
        }
        System.out.println("[LPX-TEST] 成功: 双方向 " + BYTES + " bytes が完全に届いた");
        System.exit(0);
    }

    private static void sendAll(LpxStream stream, byte[] payload) {
        try {
            stream.write(payload, 0, payload.length);
            stream.close();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void drain(LpxStream stream, ByteArrayOutputStream out) {
        try {
            while (true) {
                byte[] chunk = stream.outbox().poll(1, TimeUnit.SECONDS);
                if (chunk == null) {
                    continue;
                }
                if (chunk.length == 0) {
                    return; // EOF
                }
                out.write(chunk, 0, chunk.length);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean check(String label, byte[] expected, byte[] actual) {
        if (expected.length != actual.length) {
            System.out.println("error: [LPX-TEST] " + label + ": 長さ不一致 expected="
                    + expected.length + " actual=" + actual.length);
            return false;
        }
        String expectedHash = sha256(expected);
        String actualHash = sha256(actual);
        if (!expectedHash.equals(actualHash)) {
            System.out.println("error: [LPX-TEST] " + label + ": ハッシュ不一致 "
                    + expectedHash + " != " + actualHash);
            return false;
        }
        System.out.println("[LPX-TEST] " + label + ": " + expected.length
                + " bytes OK (sha256=" + expectedHash.substring(0, 16) + "...)");
        return true;
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private LpxLoopbackTest() {
    }
}
