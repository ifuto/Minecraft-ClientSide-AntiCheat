package dev.ifuto.mcsa.server.check;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/** プレイヤー 1 人ぶんの行動検知の状態。メインスレッドからのみ触る。 */
public final class PlayerState {

    /** テレポートやノックバック直後の誤検知を避ける猶予（System.currentTimeMillis） */
    public long exemptUntil;

    public int airTicks;
    public int speedTicks;

    public final Deque<Long> clicks = new ArrayDeque<>();
    public final Deque<Long> breaks = new ArrayDeque<>();
    public final Deque<Long> attacks = new ArrayDeque<>();

    public long lastAttackAt;
    public long lastAlertAt;

    private final Map<String, Integer> violations = new HashMap<>();

    public boolean exempt() {
        return System.currentTimeMillis() < exemptUntil;
    }

    public void exempt(long millis) {
        exemptUntil = System.currentTimeMillis() + millis;
    }

    public int addViolation(String checkId) {
        int next = violations.getOrDefault(checkId, 0) + 1;
        violations.put(checkId, next);
        return next;
    }

    public int violations(String checkId) {
        return violations.getOrDefault(checkId, 0);
    }

    public Map<String, Integer> allViolations() {
        return violations;
    }

    public void reset() {
        violations.clear();
        airTicks = 0;
        speedTicks = 0;
        clicks.clear();
        breaks.clear();
        attacks.clear();
    }

    /** 直近 windowMillis のイベントだけをデックに残す。 */
    public static void trim(Deque<Long> deque, long now, long windowMillis) {
        while (!deque.isEmpty() && now - deque.peekFirst() > windowMillis) {
            deque.pollFirst();
        }
    }
}
