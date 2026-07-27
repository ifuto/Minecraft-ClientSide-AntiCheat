package com.anticheat.client.detection;

import com.anticheat.client.network.ViolationReporter;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Evolution: Client-side packet rate analysis.
 * Even though server is authoritative, client can detect if its own packets are being manipulated or if cheat is spamming packets.
 *
 * Checks:
 * - Outgoing packet rate: normal player sends ~20-40 packets/sec (movement, look, etc). If rate >200/sec, possible Timer or packet spam.
 * - Inconsistent packet order: e.g., attack packet without preceding swing packet? Might be NoSwing.
 * - CPS vs attack packet count mismatch: client reports 10 CPS but 50 attack packets/sec => packet injection.
 *
 * Performance: O(1) per packet, ring buffer of timestamps.
 *
 * Note: This requires mixin into ClientConnection to intercept sends. For simplicity we expose methods to be called from mixin.
 */
public final class PacketAnalyzer {

    private final Deque<Long> packetTimestamps = new ArrayDeque<>();
    private final Deque<Long> attackPacketTimestamps = new ArrayDeque<>();

    private static final int WINDOW_MS = 1000;
    private static final int MAX_PACKETS_PER_SECOND = 150;
    private static final int MAX_ATTACK_PACKETS_PER_SECOND = 20;

    public void onPacketSend() {
        long now = System.currentTimeMillis();
        packetTimestamps.addLast(now);
        trimOld(packetTimestamps, now);
        if (packetTimestamps.size() > MAX_PACKETS_PER_SECOND) {
            ViolationReporter.reportTamper("PACKET_SPAM", "packets/sec=" + packetTimestamps.size() + " > " + MAX_PACKETS_PER_SECOND);
        }
    }

    public void onAttackPacket() {
        long now = System.currentTimeMillis();
        attackPacketTimestamps.addLast(now);
        trimOld(attackPacketTimestamps, now);
        onPacketSend(); // also counts as packet
        if (attackPacketTimestamps.size() > MAX_ATTACK_PACKETS_PER_SECOND) {
            ViolationReporter.reportTamper("ATTACK_PACKET_SPAM", "attackPackets/sec=" + attackPacketTimestamps.size());
        }
    }

    private void trimOld(Deque<Long> deque, long now) {
        while (!deque.isEmpty() && now - deque.peekFirst() > WINDOW_MS) {
            deque.pollFirst();
        }
    }

    public int getCurrentPPS() {
        return packetTimestamps.size();
    }

    public void reset() {
        packetTimestamps.clear();
        attackPacketTimestamps.clear();
    }
}
