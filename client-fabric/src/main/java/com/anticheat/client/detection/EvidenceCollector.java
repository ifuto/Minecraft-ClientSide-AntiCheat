package com.anticheat.client.detection;

import com.anticheat.client.util.CryptoUtil;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Evolution: Evidence collection with hash chain for forensic integrity.
 * When violation occurs, collect surrounding context (rotation history, movement, packets) and create tamper-evident log.
 *
 * Hash chain: each evidence entry hash = SHA256(prevHash + data)
 * This makes it hard for attacker to modify evidence log without breaking chain (like blockchain).
 *
 * Performance: Ring buffer of 1000 entries, O(1) per tick.
 */
public final class EvidenceCollector {

    public static class Evidence {
        public final long ts;
        public final String type;
        public final String data;
        public final String hash;
        public final String prevHash;

        public Evidence(long ts, String type, String data, String prevHash) {
            this.ts = ts;
            this.type = type;
            this.data = data;
            this.prevHash = prevHash;
            this.hash = CryptoUtil.chainHash(prevHash, type + "|" + data + "|" + ts);
        }

        @Override
        public String toString() {
            return "[" + ts + "] " + type + " data=" + data + " hash=" + hash.substring(0,8) + "...";
        }
    }

    private final Deque<Evidence> evidenceRing = new ArrayDeque<>();
    private final int maxSize;
    private String lastHash = "GENESIS";
    private final ReentrantLock lock = new ReentrantLock();

    public EvidenceCollector(int maxSize) {
        this.maxSize = maxSize;
    }

    public EvidenceCollector() {
        this(1000);
    }

    public void addEvidence(String type, String data) {
        lock.lock();
        try {
            long ts = System.currentTimeMillis();
            Evidence ev = new Evidence(ts, type, data, lastHash);
            lastHash = ev.hash;
            evidenceRing.addLast(ev);
            if (evidenceRing.size() > maxSize) {
                evidenceRing.pollFirst();
            }
        } finally {
            lock.unlock();
        }
    }

    public java.util.List<Evidence> getRecent(int n) {
        lock.lock();
        try {
            java.util.ArrayList<Evidence> list = new java.util.ArrayList<>(evidenceRing);
            int from = Math.max(0, list.size() - n);
            return list.subList(from, list.size());
        } finally {
            lock.unlock();
        }
    }

    public String getLastHash() {
        return lastHash;
    }

    public boolean verifyChain() {
        lock.lock();
        try {
            String prev = "GENESIS";
            for (Evidence ev : evidenceRing) {
                String expected = CryptoUtil.chainHash(prev, ev.type + "|" + ev.data + "|" + ev.ts);
                if (!expected.equals(ev.hash)) return false;
                prev = ev.hash;
            }
            return true;
        } finally {
            lock.unlock();
        }
    }
}
