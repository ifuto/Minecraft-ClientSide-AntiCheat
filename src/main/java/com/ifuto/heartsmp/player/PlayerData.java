package com.ifuto.heartsmp.player;

import java.util.UUID;

/** 1 プレイヤー分の永続データ。 */
public final class PlayerData {

    private final UUID uuid;
    private String name;
    private int hearts;
    private Rank rank;

    public PlayerData(UUID uuid, String name, int hearts, Rank rank) {
        this.uuid = uuid;
        this.name = name;
        this.hearts = hearts;
        this.rank = rank;
    }

    public UUID uuid() {
        return uuid;
    }

    public String name() {
        return name == null ? "unknown" : name;
    }

    public void name(String name) {
        this.name = name;
    }

    public int hearts() {
        return hearts;
    }

    public void hearts(int hearts) {
        this.hearts = hearts;
    }

    public Rank rank() {
        return rank == null ? Rank.DEFAULT : rank;
    }

    public void rank(Rank rank) {
        this.rank = rank == null ? Rank.DEFAULT : rank;
    }
}
