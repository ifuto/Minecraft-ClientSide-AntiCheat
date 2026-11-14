package com.ifuto.heartsmp.death;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 1 回の死亡 (=ドロップ) の記録。 */
public final class DeathRecord {

    public enum Type {
        PLAYER_KILL,
        COMBAT_LOGOUT
    }

    private final String id;
    private final UUID victim;
    private final String victimName;
    private final UUID killer;
    private final String killerName;
    private final Type type;
    private final long time;
    private final String cause;
    private final String world;
    private final double x;
    private final double y;
    private final double z;
    private final List<String> participants;
    private final Map<Integer, String> items;
    private final int experience;

    public DeathRecord(String id, UUID victim, String victimName, UUID killer, String killerName, Type type,
                       long time, String cause, String world, double x, double y, double z,
                       List<String> participants, Map<Integer, String> items, int experience) {
        this.id = id;
        this.victim = victim;
        this.victimName = victimName;
        this.killer = killer;
        this.killerName = killerName;
        this.type = type;
        this.time = time;
        this.cause = cause;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.participants = participants == null ? new ArrayList<>() : new ArrayList<>(participants);
        this.items = items == null ? new LinkedHashMap<>() : new LinkedHashMap<>(items);
        this.experience = experience;
    }

    public String id() {
        return id;
    }

    public UUID victim() {
        return victim;
    }

    public String victimName() {
        return victimName == null ? "unknown" : victimName;
    }

    public UUID killer() {
        return killer;
    }

    public String killerName() {
        return killerName;
    }

    public Type type() {
        return type;
    }

    public long time() {
        return time;
    }

    public String cause() {
        return cause == null ? "unknown" : cause;
    }

    public String world() {
        return world;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    public List<String> participants() {
        return new ArrayList<>(participants);
    }

    public Map<Integer, String> items() {
        return items;
    }

    public int experience() {
        return experience;
    }

    public String locationString() {
        return world + " " + Math.round(x) + ", " + Math.round(y) + ", " + Math.round(z);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("victim", victim.toString());
        map.put("victim-name", victimName);
        map.put("killer", killer == null ? "" : killer.toString());
        map.put("killer-name", killerName == null ? "" : killerName);
        map.put("type", type.name());
        map.put("time", time);
        map.put("cause", cause);
        map.put("world", world);
        map.put("x", x);
        map.put("y", y);
        map.put("z", z);
        map.put("participants", new ArrayList<>(participants));
        map.put("exp", experience);
        List<Map<String, Object>> itemList = new ArrayList<>();
        for (Map.Entry<Integer, String> entry : items.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("slot", entry.getKey());
            item.put("data", entry.getValue());
            itemList.add(item);
        }
        map.put("items", itemList);
        return map;
    }

    @SuppressWarnings("unchecked")
    public static DeathRecord fromMap(Map<?, ?> map) {
        try {
            String id = String.valueOf(map.get("id"));
            UUID victim = UUID.fromString(String.valueOf(map.get("victim")));
            String victimName = map.get("victim-name") == null ? "unknown" : String.valueOf(map.get("victim-name"));
            String killerRaw = String.valueOf(map.get("killer"));
            UUID killer = killerRaw == null || killerRaw.isEmpty() || "null".equals(killerRaw) ? null : UUID.fromString(killerRaw);
            String killerName = map.get("killer-name") == null || String.valueOf(map.get("killer-name")).isEmpty()
                    ? null : String.valueOf(map.get("killer-name"));
            Object rawType = map.get("type");
            Type type = rawType == null ? Type.PLAYER_KILL : Type.valueOf(String.valueOf(rawType));
            long time = toLong(map.get("time"));
            String cause = String.valueOf(map.get("cause"));
            String world = String.valueOf(map.get("world"));
            double x = toDouble(map.get("x"));
            double y = toDouble(map.get("y"));
            double z = toDouble(map.get("z"));
            List<String> participants = new ArrayList<>();
            Object rawParticipants = map.get("participants");
            if (rawParticipants instanceof List<?> listRaw) {
                for (Object entry : listRaw) {
                    participants.add(String.valueOf(entry));
                }
            }
            int exp = (int) toLong(map.get("exp"));
            Map<Integer, String> items = new LinkedHashMap<>();
            Object rawItems = map.get("items");
            if (rawItems instanceof List<?> listRaw) {
                for (Object entry : listRaw) {
                    if (entry instanceof Map<?, ?> itemMap) {
                        int slot = (int) toLong(itemMap.get("slot"));
                        String data = String.valueOf(itemMap.get("data"));
                        items.put(slot, data);
                    }
                }
            }
            return new DeathRecord(id, victim, victimName, killer, killerName, type, time, cause,
                    world, x, y, z, participants, items, exp);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private static double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0.0D;
        }
    }
}
