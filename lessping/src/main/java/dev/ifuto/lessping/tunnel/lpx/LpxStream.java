package dev.ifuto.lessping.tunnel.lpx;

import java.net.SocketAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * LPX ストリーム 1 本（= Minecraft の TCP 接続 1 本を運ぶ）。
 *
 * <p>Minecraft の接続は TCP の信頼性（欠落なし・順序どおり・EOF）に依存するため、
 * このクラスは UDP の上に「順序付きの信頼できるバイトストリーム」を実装する。
 *
 * <ul>
 *   <li>送信: 書き込みを MSS 以下のセグメントに切り、連番 (sn) を振って送る。
 *       未 ACK のセグメント数は WINDOW 件まで（TCP のスライディングウィンドウと同じ）。
 *       書き込み側が多すぎるときはブロックして背圧をかける。</li>
 *   <li>受信: sn 順に並べ替えてからアプリへ渡す。穴の位置 (una) と受信済みの
 *       飛び飛び sn (SACK) を ACK で返す。<b>重複 DATA が来たときも ACK を返す</b>
 *       （ACK 自体がロスすると再送が止まらなくなるため）。</li>
 *   <li>再送: RTO（RTT から推定）によるタイムアウト再送と、ACK が 3 回同じ una を
 *       指したときの高速再送。</li>
 *   <li>終了: {@link #close()} は TCP の shutdownOutput と同じ<b>半閉鎖</b>。
 *       残りデータの ACK 待ちをしてから CLOSE を送る。相手からの CLOSE は
 *       「もう相手からは来ない」印で、outbox はその時点までの全データを届けてから
 *       EOF を流す（close で受信側まで殺さない）。</li>
 * </ul>
 *
 * <p>スレッド構成: {@link #write} はスプライススレッドから、{@link #onFrame} は
 * UDP 受信スレッドから、{@link #tick} は共有タイマーから呼ばれる。すべて
 * このオブジェクトのモニターで直列化する（コネクション数は少ないので十分）。
 */
public final class LpxStream {

    /** 1 セグメントの最大ペイロード（バイト）。パス MTU を割らない大きさに。 */
    public static final int MSS = 1000;
    /** 未 ACK で送れる最大セグメント数（≈ MSS×WINDOW バイトのウィンドウ） */
    private static final int WINDOW = 128;
    /** 送信キューの上限（セグメント）。超えたら書き込みをブロックする */
    private static final int QUEUE_CAP = 1024;
    /** 受信並べ替えバッファの上限（セグメント）。超えた分は捨てて RTO 再送に任せる */
    private static final int RECV_CAP = 600;

    private static final long MIN_RTO = 80;
    private static final long MAX_RTO = 3000;
    private static final long INITIAL_RTO = 400;

    /** フレームを実際に送り出す口（UDP ソケット or テストの仮想リンク） */
    public interface Sender {
        void send(byte[] frame, SocketAddress to);
    }

    /** outbox の終端マーカー（長さ 0 のバイト配列） */
    public static final byte[] EOF = new byte[0];

    static final class Segment {
        final int sn;
        final byte[] data;
        int sendCount;
        long sentAt;      // 最後に送った時刻（0 = 未送信）
        long firstSentAt; // 初回送信時刻（RTT 測定用）
        long rto = INITIAL_RTO;
        boolean acked;

        Segment(int sn, byte[] data) {
            this.sn = sn;
            this.data = data;
        }
    }

    private final int conv;
    private final SocketAddress remote;
    private final Sender sender;
    private final long keepaliveMs;

    private final ArrayDeque<Segment> queue = new ArrayDeque<>();
    private int nextSn = 0;

    private final TreeMap<Integer, byte[]> recvBuf = new TreeMap<>();
    private int nextExpected = 0;
    private boolean ackDirty = false;

    private final LinkedBlockingQueue<byte[]> outbox = new LinkedBlockingQueue<>();

    /** 自分の送信側を閉じた（FIN 送信済み）。それでも受信は続く */
    private boolean sendClosed;
    /** 相手から CLOSE が来た（もう相手からデータは来ない） */
    private boolean remoteClosed;
    /** エラー等で完全に切断 */
    private boolean dead;

    private long lastActivity = System.currentTimeMillis();
    private long lastSentAt = System.currentTimeMillis();
    /** CLOSE を最後に送った時刻（ロス対策で相手に届くまで再送する） */
    private long closeSentAt;

    private long srtt = -1;
    private long rttvar;
    private long rto = INITIAL_RTO;
    private int lastFastUna = -1;
    private int fastCount = 0;

    public LpxStream(int conv, SocketAddress remote, Sender sender, long keepaliveMs) {
        this.conv = conv;
        this.remote = remote;
        this.sender = sender;
        this.keepaliveMs = keepaliveMs;
    }

    public int conv() {
        return conv;
    }

    public SocketAddress remote() {
        return remote;
    }

    /** 完全に終了（送受信両方向クローズ） */
    public boolean finished() {
        return dead || (sendClosed && remoteClosed);
    }

    /** 送信だけ閉じた状態か（受信はまだ続く） */
    public boolean isSendClosed() {
        return sendClosed;
    }

    /** 相手が送信を閉じたか（outbox が EOF に到達する） */
    public boolean isRemoteClosed() {
        return remoteClosed;
    }

    /** 順序どおりに届いた受信データの取り出し口。{@link #EOF} で終端。 */
    public LinkedBlockingQueue<byte[]> outbox() {
        return outbox;
    }

    /** 最後に何か動きがあった時刻（アイドル切断の判定に使う） */
    public long lastActivity() {
        return lastActivity;
    }

    // ------------------------------------------------------------------ 送信

    /**
     * ストリームへ書き込む。キューがいっぱいならブロックする（TCP の背圧と同じ）。
     * ここで切れた分は MSS に分割され、次の {@link #tick} で送信に回る。
     */
    public void write(byte[] data, int off, int len) throws InterruptedException {
        if (len <= 0) {
            return;
        }
        int pos = off;
        int end = off + len;
        while (pos < end) {
            int chunk = Math.min(MSS, end - pos);
            byte[] seg = new byte[chunk];
            System.arraycopy(data, pos, seg, 0, chunk);
            synchronized (this) {
                while (!sendClosed && !dead && queue.size() >= QUEUE_CAP) {
                    wait(1000);
                }
                if (sendClosed || dead) {
                    return;
                }
                queue.addLast(new Segment(nextSn++, seg));
            }
            pos += chunk;
        }
    }

    /**
     * 送信側を閉じる（TCP の shutdownOutput に相当）。
     * 残りデータの ACK 待ちをしてから CLOSE を送る。受信は継続する。
     */
    public void close() {
        long deadline = System.currentTimeMillis() + 10_000;
        boolean doSend;
        synchronized (this) {
            // 送り残しが無くなるまで待つ（最大 10 秒）。
            // 注意: remoteClosed（相手の送信終了）で待ってはいけない。相手はこちらの
            // データを ACK し続けるので、フラッシュ完了まで送り切るべき。
            while (!queue.isEmpty() && !sendClosed && !dead
                    && System.currentTimeMillis() < deadline) {
                try {
                    wait(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            doSend = !sendClosed && !dead;
            sendClosed = true;
            notifyAll();
        }
        if (doSend) {
            synchronized (this) {
                closeSentAt = System.currentTimeMillis();
            }
            try {
                sender.send(LpxFrame.simple(LpxFrame.T_CLOSE, conv), remote);
            } catch (Throwable ignored) {
                // ベストエフォート。tick で再送する
            }
        }
    }

    /** エラー・タイムアウト等で完全に切る（両方向。outbox にも即 EOF を流す） */
    public void hardClose() {
        synchronized (this) {
            dead = true;
            sendClosed = true;
            remoteClosed = true;
            notifyAll();
        }
        try {
            sender.send(LpxFrame.simple(LpxFrame.T_CLOSE, conv), remote);
        } catch (Throwable ignored) {
            // ベストエフォート
        }
        outbox.offer(EOF);
    }

    // ------------------------------------------------------------------ 受信

    /** UDP 受信スレッドから。DATA / ACK / CLOSE を処理する。 */
    public void onFrame(LpxFrame.Decoded frame) {
        switch (frame.type()) {
            case LpxFrame.T_DATA -> onData(frame);
            case LpxFrame.T_ACK -> onAck(frame);
            case LpxFrame.T_CLOSE -> {
                boolean firstTime;
                synchronized (this) {
                    firstTime = !remoteClosed;
                    remoteClosed = true;
                    lastActivity = System.currentTimeMillis();
                    notifyAll();
                }
                // CLOSE は相手が全データの ACK を確認してから送られるので、
                // この時点で受信データは揃っている。EOF を流してよい（1 回だけ）。
                if (firstTime) {
                    outbox.offer(EOF);
                }
            }
            default -> {
                // KEEP などは TunnelManager 側で処理される
            }
        }
    }

    private void onData(LpxFrame.Decoded frame) {
        byte[] payload = frame.payload();
        synchronized (this) {
            lastActivity = System.currentTimeMillis();
            int sn = frame.sn();
            if (sn < nextExpected) {
                // 重複（相手の ACK がロスした可能性）→ 必ず再 ACK する
                ackDirty = true;
                return;
            }
            if (recvBuf.size() >= RECV_CAP) {
                // 並べ替えバッファいっぱい → 捨てて RTO 再送に任せる
                return;
            }
            recvBuf.putIfAbsent(sn, payload);
            // 順番が揃った分から順にアプリへ流す
            while (!recvBuf.isEmpty() && recvBuf.firstKey() == nextExpected) {
                outbox.offer(recvBuf.pollFirstEntry().getValue());
                nextExpected++;
            }
            ackDirty = true;
        }
    }

    private void onAck(LpxFrame.Decoded frame) {
        int una = frame.una();
        int[] sns = frame.sns();
        long now = System.currentTimeMillis();
        synchronized (this) {
            lastActivity = now;
            for (Segment seg : queue) {
                if (seg.acked) {
                    continue;
                }
                boolean acked = seg.sn < una;
                if (!acked && sns != null) {
                    for (int sn : sns) {
                        if (sn == seg.sn) {
                            acked = true;
                            break;
                        }
                    }
                }
                if (acked) {
                    seg.acked = true;
                    if (seg.sendCount == 1 && seg.firstSentAt > 0) {
                        sampleRtt(now - seg.firstSentAt);
                    }
                }
            }
            // ACK 済みの先頭を捨ててウィンドウを進める
            while (!queue.isEmpty() && queue.peekFirst().acked) {
                queue.pollFirst();
                notifyAll(); // close() のフラッシュ待ちを起こす
            }
            // 高速再送: 同じ una が 3 回続いた = 先頭が落ちている
            if (una == lastFastUna) {
                fastCount++;
            } else {
                lastFastUna = una;
                fastCount = 1;
            }
            if (fastCount >= 3) {
                for (Segment seg : queue) {
                    if (!seg.acked && seg.sentAt > 0) {
                        resend(seg, now);
                        break;
                    }
                }
                fastCount = 0;
            }
        }
    }

    // ------------------------------------------------------------------ 定期処理

    /** 共有タイマーから 5〜10ms ごとに。ウィンドウ内の送信・再送・ACK・keepalive。 */
    public void tick(long now) {
        byte[] ackFrame = null;
        byte[] keepFrame = null;
        List<byte[]> toSend = null;
        synchronized (this) {
            if (dead) {
                return;
            }
            // 1) 未送信セグメントをウィンドウの空きぶんだけ送る
            int inflight = 0;
            for (Segment seg : queue) {
                if (seg.acked) {
                    continue;
                }
                if (seg.sentAt > 0) {
                    inflight++;
                    // 2) RTO 超過の再送
                    if (now - seg.sentAt > seg.rto) {
                        resend(seg, now);
                    }
                } else if (inflight < WINDOW) {
                    seg.sendCount = 1;
                    seg.sentAt = now;
                    seg.firstSentAt = now;
                    if (toSend == null) {
                        toSend = new ArrayList<>();
                    }
                    toSend.add(LpxFrame.data(conv, seg.sn, seg.data, 0, seg.data.length));
                    inflight++;
                }
            }
            // 3) 受信したら ACK を返す（重複受信でも）
            if (ackDirty) {
                ackDirty = false;
                ackFrame = buildAck();
            }
            // 4) アイドルなら keepalive。送信を閉じたら CLOSE を受け取ってもらえるまで
            //    再送し続ける（conn 解体で dead になったら止まる。6 バイト/keepalive 間隔）
            if (sendClosed && queue.isEmpty()) {
                if (closeSentAt > 0 && now - closeSentAt > keepaliveMs) {
                    keepFrame = LpxFrame.simple(LpxFrame.T_CLOSE, conv);
                    closeSentAt = now;
                }
            } else if (now - lastSentAt > keepaliveMs) {
                keepFrame = LpxFrame.simple(LpxFrame.T_KEEP, conv);
            }
        }
        if (toSend != null) {
            for (byte[] frame : toSend) {
                sender.send(frame, remote);
            }
            synchronized (this) {
                lastSentAt = now;
            }
        }
        if (ackFrame != null) {
            sender.send(ackFrame, remote);
        }
        if (keepFrame != null) {
            sender.send(keepFrame, remote);
            synchronized (this) {
                lastSentAt = now;
            }
        }
    }

    private void resend(Segment seg, long now) {
        seg.sendCount++;
        seg.sentAt = now;
        seg.rto = Math.min(seg.rto * 2, MAX_RTO);
        sender.send(LpxFrame.data(conv, seg.sn, seg.data, 0, seg.data.length), remote);
    }

    private byte[] buildAck() {
        int una = nextExpected;
        List<Integer> sns = new ArrayList<>();
        for (int sn : recvBuf.keySet()) {
            if (sns.size() >= LpxFrame.MAX_SNS) {
                break;
            }
            sns.add(sn);
        }
        int[] arr = new int[sns.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = sns.get(i);
        }
        return LpxFrame.ack(conv, una, arr);
    }

    /** RTT 標本から RTO を更新（RFC 6298 の方式。再送済みセグメントは除外済み） */
    private void sampleRtt(long sample) {
        if (sample <= 0) {
            return;
        }
        if (srtt < 0) {
            srtt = sample;
            rttvar = sample / 2;
        } else {
            rttvar = (rttvar * 3 + Math.abs(srtt - sample)) / 4;
            srtt = (srtt * 7 + sample) / 8;
        }
        rto = Math.max(MIN_RTO, Math.min(MAX_RTO, srtt + Math.max(50, rttvar * 4)));
    }

    /** デバッグ用の統計 */
    public synchronized String stats() {
        int inflight = 0;
        int retrans = 0;
        for (Segment seg : queue) {
            if (!seg.acked && seg.sentAt > 0) {
                inflight++;
            }
            retrans += Math.max(0, seg.sendCount - 1);
        }
        return "conv=" + conv + " inflight=" + inflight + " queued=" + queue.size()
                + " retransmits=" + retrans + " nextExpected=" + nextExpected
                + " srtt=" + (srtt < 0 ? "-" : srtt + "ms") + " rto=" + rto + "ms"
                + (sendClosed ? " FIN" : "") + (remoteClosed ? " REMOTE-FIN" : "")
                + (dead ? " DEAD" : "");
    }

    /** テスト用: 相手からの CLOSE が来るまで待つ */
    public void awaitRemoteClose(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (remoteClosed || dead) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(5);
        }
    }
}
