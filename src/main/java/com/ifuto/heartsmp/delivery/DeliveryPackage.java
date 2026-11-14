package com.ifuto.heartsmp.delivery;

import java.util.UUID;

import org.bukkit.inventory.ItemStack;

/** いふと配送の 1 荷物。 */
public final class DeliveryPackage {

    private final String id;
    private final UUID sender;
    private final String senderName;
    private final UUID target;
    private final ItemStack item;
    private final boolean fast;
    private long arriveAt;

    public DeliveryPackage(String id, UUID sender, String senderName, UUID target, ItemStack item,
                           boolean fast, long arriveAt) {
        this.id = id;
        this.sender = sender;
        this.senderName = senderName;
        this.target = target;
        this.item = item;
        this.fast = fast;
        this.arriveAt = arriveAt;
    }

    public String id() {
        return id;
    }

    public UUID sender() {
        return sender;
    }

    public String senderName() {
        return senderName == null ? "unknown" : senderName;
    }

    public UUID target() {
        return target;
    }

    public ItemStack item() {
        return item == null ? null : item.clone();
    }

    public boolean fast() {
        return fast;
    }

    public long arriveAt() {
        return arriveAt;
    }

    public void arriveAt(long arriveAt) {
        this.arriveAt = arriveAt;
    }
}
