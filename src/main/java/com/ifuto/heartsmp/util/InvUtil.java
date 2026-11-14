package com.ifuto.heartsmp.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** インベントリ操作の小さなヘルパ群。 */
public final class InvUtil {

    private InvUtil() {
    }

    public static List<ItemStack> list(Inventory inventory) {
        List<ItemStack> result = new ArrayList<>();
        if (inventory == null) {
            return result;
        }
        for (ItemStack item : inventory.getContents()) {
            result.add(item);
        }
        return result;
    }

    public static int countSimilar(Inventory inventory, ItemStack sample) {
        if (inventory == null || sample == null) {
            return 0;
        }
        int total = 0;
        for (ItemStack item : inventory.getContents()) {
            if (item != null && item.isSimilar(sample)) {
                total += item.getAmount();
            }
        }
        return total;
    }

    public static boolean hasAtLeast(Inventory inventory, ItemStack sample, int amount) {
        return countSimilar(inventory, sample) >= amount;
    }

    /** sample と同種のアイテムを amount 個だけ削除し、削除できた個数を返します。 */
    public static int removeAmount(Inventory inventory, ItemStack sample, int amount) {
        if (inventory == null || sample == null || amount <= 0) {
            return 0;
        }
        int remaining = amount;
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack item = contents[slot];
            if (item == null || !item.isSimilar(sample)) {
                continue;
            }
            int take = Math.min(item.getAmount(), remaining);
            remaining -= take;
            int left = item.getAmount() - take;
            if (left <= 0) {
                inventory.setItem(slot, null);
            } else {
                ItemStack copy = item.clone();
                copy.setAmount(left);
                inventory.setItem(slot, copy);
            }
        }
        return amount - remaining;
    }

    public static int freeSlots(Inventory inventory) {
        int free = 0;
        for (ItemStack item : inventory.getContents()) {
            if (item == null || item.getType() == Material.AIR) {
                free++;
            }
        }
        return free;
    }

    public static boolean hasRoomFor(Inventory inventory, ItemStack item) {
        if (item == null) {
            return true;
        }
        if (freeSlots(inventory) > 0) {
            return true;
        }
        for (ItemStack existing : inventory.getContents()) {
            if (existing != null && existing.isSimilar(item)
                    && existing.getAmount() + item.getAmount() <= existing.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    /** インベントリに入るだけ入れ、入りきらない分は足元にドロップします。 */
    public static void giveOrDrop(Player player, ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return;
        }
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(item.clone());
        for (ItemStack remain : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), remain);
        }
    }

    public static void dropAt(Player player, ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return;
        }
        player.getWorld().dropItemNaturally(player.getLocation(), item.clone());
    }
}
