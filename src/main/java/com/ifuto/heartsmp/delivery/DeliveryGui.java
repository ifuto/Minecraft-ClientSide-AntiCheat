package com.ifuto.heartsmp.delivery;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import com.ifuto.heartsmp.HeartSMP;
import com.ifuto.heartsmp.util.InvUtil;
import com.ifuto.heartsmp.util.Items;
import com.ifuto.heartsmp.util.Msg;

/** /send と /bagages の Chest GUI。 */
public final class DeliveryGui implements Listener {

    public static final int SEND_SIZE = 45;
    public static final int SLOT_ITEM = 22;
    public static final int SLOT_DIAMOND = 25;
    public static final int SLOT_FAST = 19;
    public static final int SLOT_CONFIRM = 31;
    public static final int SLOT_SHULKER = 13;
    public static final int SLOT_DIAMOND_DECO = 16;
    public static final int SLOT_FAST_DECO = 10;

    public static final int BAGAGES_SIZE = 27;
    public static final int BAGAGES_SLOTS = 21;

    private final HeartSMP plugin;

    public DeliveryGui(HeartSMP plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    // /send
    // ------------------------------------------------------------------

    public void openSend(Player viewer, Player target) {
        SendHolder holder = new SendHolder(target.getUniqueId(), target.getName());
        Inventory inventory = Bukkit.createInventory(holder, SEND_SIZE,
                Msg.parse(plugin.messages().raw("delivery-title")));
        holder.inventory = inventory;

        ItemStack filler = Items.icon(Material.LIGHT_BLUE_STAINED_GLASS_PANE,
                Msg.parse(plugin.messages().raw("delivery-filler-name")), List.of());
        for (int slot = 0; slot < SEND_SIZE; slot++) {
            inventory.setItem(slot, filler);
        }
        inventory.setItem(SLOT_ITEM, null);
        inventory.setItem(SLOT_DIAMOND, null);
        renderDecorations(holder);
        viewer.openInventory(inventory);
    }

    private int cost(boolean fast) {
        return fast ? plugin.settings().deliveryFastCost : plugin.settings().deliveryBaseCost;
    }

    private void renderDecorations(SendHolder holder) {
        Inventory inventory = holder.inventory;
        String stateKey = holder.fast ? "delivery-fast-on" : "delivery-fast-off";
        Component state = Msg.parse(plugin.messages().raw(stateKey));

        inventory.setItem(SLOT_SHULKER, Items.icon(Material.SHULKER_BOX,
                Msg.parse(plugin.messages().raw("delivery-shulker-name")),
                plugin.messages().getList("delivery-shulker-lore")));

        inventory.setItem(SLOT_DIAMOND_DECO, Items.icon(Material.DIAMOND,
                plugin.messages().get("delivery-diamond-name",
                        "cost", String.valueOf(cost(holder.fast))),
                plugin.messages().getList("delivery-diamond-lore",
                        "cost", String.valueOf(cost(holder.fast)),
                        "base", String.valueOf(plugin.settings().deliveryBaseCost),
                        "fast", String.valueOf(plugin.settings().deliveryFastCost))));

        inventory.setItem(SLOT_FAST_DECO, Items.icon(Material.FEATHER,
                plugin.messages().get("delivery-fast-name", Map.of("state", state)),
                plugin.messages().getList("delivery-fast-lore",
                        "state", holder.fast ? plugin.messages().plain("delivery-fast-on")
                                : plugin.messages().plain("delivery-fast-off"),
                        "cost", String.valueOf(cost(holder.fast)),
                        "fast", String.valueOf(plugin.settings().deliveryFastCost))));

        inventory.setItem(SLOT_FAST, Items.icon(
                holder.fast ? Material.LIME_DYE : Material.GRAY_DYE,
                plugin.messages().get("delivery-fast-toggle-name", Map.of("state", state)),
                plugin.messages().getList("delivery-fast-toggle-lore",
                        "state", holder.fast ? plugin.messages().plain("delivery-fast-on")
                                : plugin.messages().plain("delivery-fast-off"))));

        inventory.setItem(SLOT_CONFIRM, Items.icon(Material.EMERALD_BLOCK,
                Msg.parse(plugin.messages().raw("delivery-confirm-name")),
                plugin.messages().getList("delivery-confirm-lore",
                        "target", holder.targetName,
                        "cost", String.valueOf(cost(holder.fast)))));
    }

    private void handleSendClick(InventoryClickEvent event, SendHolder holder) {
        Player player = (Player) event.getWhoClicked();
        int raw = event.getRawSlot();
        boolean top = raw >= 0 && raw < SEND_SIZE;

        if (!top) {
            if (event.isShiftClick()) {
                event.setCancelled(true);
            }
            return;
        }
        if (raw == SLOT_FAST) {
            event.setCancelled(true);
            holder.fast = !holder.fast;
            renderDecorations(holder);
            player.playSound(player.getLocation(), "ui.button.click", 0.8F, holder.fast ? 1.4F : 0.9F);
            return;
        }
        if (raw == SLOT_CONFIRM) {
            event.setCancelled(true);
            attemptSend(player, holder);
            return;
        }
        if (raw == SLOT_ITEM || raw == SLOT_DIAMOND) {
            Bukkit.getScheduler().runTask(plugin, () -> validate(player, holder));
            return;
        }
        event.setCancelled(true);
    }

    private void validate(Player player, SendHolder holder) {
        if (!(player.getOpenInventory().getTopInventory().getHolder() instanceof SendHolder current)
                || current != holder) {
            return;
        }
        ItemStack item = holder.inventory.getItem(SLOT_ITEM);
        if (!Items.isAir(item)) {
            String banned = bannedName(item);
            if (banned != null) {
                holder.inventory.setItem(SLOT_ITEM, null);
                InvUtil.giveOrDrop(player, item);
                plugin.messages().error(player, "delivery-banned", "item", banned);
            }
        }
        ItemStack diamonds = holder.inventory.getItem(SLOT_DIAMOND);
        if (!Items.isAir(diamonds) && diamonds.getType() != Material.DIAMOND) {
            holder.inventory.setItem(SLOT_DIAMOND, null);
            InvUtil.giveOrDrop(player, diamonds);
            plugin.messages().error(player, "delivery-not-enough-diamonds",
                    "cost", String.valueOf(cost(holder.fast)));
        }
        renderDecorations(holder);
    }

    /** 配送規約違反のアイテムなら表示名を返します。 */
    private String bannedName(ItemStack item) {
        if (Items.isAir(item)) {
            return null;
        }
        if (item.getType() == Material.SHULKER_BOX
                || item.getType() == Material.WHITE_SHULKER_BOX
                || item.getType() == Material.ORANGE_SHULKER_BOX
                || item.getType() == Material.MAGENTA_SHULKER_BOX
                || item.getType() == Material.LIGHT_BLUE_SHULKER_BOX
                || item.getType() == Material.YELLOW_SHULKER_BOX
                || item.getType() == Material.LIME_SHULKER_BOX
                || item.getType() == Material.PINK_SHULKER_BOX
                || item.getType() == Material.GRAY_SHULKER_BOX
                || item.getType() == Material.LIGHT_GRAY_SHULKER_BOX
                || item.getType() == Material.CYAN_SHULKER_BOX
                || item.getType() == Material.PURPLE_SHULKER_BOX
                || item.getType() == Material.BLUE_SHULKER_BOX
                || item.getType() == Material.BROWN_SHULKER_BOX
                || item.getType() == Material.GREEN_SHULKER_BOX
                || item.getType() == Material.RED_SHULKER_BOX
                || item.getType() == Material.BLACK_SHULKER_BOX) {
            return Items.plainName(item.getType());
        }
        if (plugin.settings().deliveryBanned.contains(item.getType())) {
            return Items.plainName(item.getType());
        }
        if (DeliveryRules.isDebuffPotion(item)) {
            return Items.plainName(item.getType());
        }
        return null;
    }

    private int countDiamonds(ItemStack item) {
        if (Items.isAir(item) || item.getType() != Material.DIAMOND) {
            return 0;
        }
        return item.getAmount();
    }

    private void attemptSend(Player player, SendHolder holder) {
        ItemStack item = holder.inventory.getItem(SLOT_ITEM);
        if (Items.isAir(item)) {
            plugin.messages().error(player, "delivery-no-item");
            return;
        }
        String banned = bannedName(item);
        if (banned != null) {
            plugin.messages().error(player, "delivery-banned", "item", banned);
            return;
        }
        int cost = cost(holder.fast);
        int diamonds = countDiamonds(holder.inventory.getItem(SLOT_DIAMOND));
        if (diamonds < cost) {
            plugin.messages().error(player, "delivery-not-enough-diamonds", "cost", String.valueOf(cost));
            return;
        }
        if (plugin.delivery().pendingCount(player.getUniqueId()) >= plugin.settings().deliveryMaxPending) {
            plugin.messages().error(player, "delivery-too-many-packages",
                    "max", String.valueOf(plugin.settings().deliveryMaxPending));
            return;
        }
        if (plugin.delivery().isFull(holder.target)) {
            plugin.messages().error(player, "delivery-target-full", "player", holder.targetName);
            return;
        }

        int leftover = diamonds - cost;
        holder.inventory.setItem(SLOT_ITEM, null);
        holder.inventory.setItem(SLOT_DIAMOND, null);
        if (leftover > 0) {
            InvUtil.giveOrDrop(player, new ItemStack(Material.DIAMOND, leftover));
        }

        holder.closed = true;
        DeliveryPackage pkg = plugin.delivery().send(player, holder.target, item, holder.fast);
        player.closeInventory();

        plugin.messages().send(player, "delivery-sent");
        plugin.messages().send(player, "delivery-arrive-line",
                "arrivetime", formatDuration(pkg.arriveAt() - System.currentTimeMillis()));
        player.playSound(player.getLocation(), "entity.player.levelup", 0.8F, 1.6F);
    }

    private String formatDuration(long millis) {
        long totalSeconds = Math.max(0L, (millis + 999L) / 1000L);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        String template = plugin.messages().raw("delivery-arrive-time-format");
        return template.replace("%minutes%", String.valueOf(minutes)).replace("%seconds%", String.valueOf(seconds));
    }

    // ------------------------------------------------------------------
    // /bagages
    // ------------------------------------------------------------------

    public void openBagages(Player viewer) {
        List<DeliveryPackage> packages = new ArrayList<>(plugin.delivery().bagages(viewer.getUniqueId()));
        BagagesHolder holder = new BagagesHolder(viewer.getUniqueId(), packages);
        Inventory inventory = Bukkit.createInventory(holder, BAGAGES_SIZE,
                Msg.parse(plugin.messages().raw("delivery-arrived-title")));
        holder.inventory = inventory;

        ItemStack filler = Items.icon(Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                Msg.parse(plugin.messages().raw("delivery-filler-name")), List.of());
        for (int slot = BAGAGES_SLOTS; slot < BAGAGES_SIZE; slot++) {
            inventory.setItem(slot, filler);
        }

        int slot = 0;
        for (DeliveryPackage pkg : packages) {
            if (slot >= BAGAGES_SLOTS) {
                break;
            }
            ItemStack display = pkg.item();
            if (display == null) {
                continue;
            }
            ItemMeta meta = display.getItemMeta();
            if (meta != null) {
                List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
                lore.add(Msg.build(plugin.messages().raw("delivery-bagages-lore"),
                        "player", pkg.senderName()));
                meta.lore(lore);
                display.setItemMeta(meta);
            }
            inventory.setItem(slot++, display);
        }
        viewer.openInventory(inventory);
        if (packages.isEmpty()) {
            plugin.messages().send(viewer, "delivery-empty-bagages");
        }
    }

    private void handleBagagesClick(InventoryClickEvent event, BagagesHolder holder) {
        int raw = event.getRawSlot();
        boolean top = raw >= 0 && raw < BAGAGES_SIZE;
        if (!top && !event.isShiftClick()) {
            return;
        }
        event.setCancelled(true);
        if (!top) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (raw >= BAGAGES_SLOTS) {
            return;
        }
        DeliveryPackage pkg = raw < holder.packages.size() ? holder.packages.get(raw) : null;
        if (pkg == null) {
            return;
        }
        ItemStack item = pkg.item();
        if (item == null) {
            return;
        }
        if (!InvUtil.hasRoomFor(player.getInventory(), item)) {
            plugin.messages().error(player, "delivery-inventory-full");
            return;
        }
        plugin.delivery().take(player.getUniqueId(), pkg.id());
        holder.packages.remove(raw);
        holder.inventory.setItem(raw, null);
        InvUtil.giveOrDrop(player, item);
        player.playSound(player.getLocation(), "entity.item.pickup", 1.0F, 1.0F);
        // ずれを詰める
        rerenderBagages(holder);
    }

    private void rerenderBagages(BagagesHolder holder) {
        for (int slot = 0; slot < BAGAGES_SLOTS; slot++) {
            holder.inventory.setItem(slot, null);
        }
        int slot = 0;
        for (DeliveryPackage pkg : holder.packages) {
            if (slot >= BAGAGES_SLOTS) {
                break;
            }
            ItemStack display = pkg.item();
            if (display == null) {
                continue;
            }
            ItemMeta meta = display.getItemMeta();
            if (meta != null) {
                List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
                lore.add(Msg.build(plugin.messages().raw("delivery-bagages-lore"), "player", pkg.senderName()));
                meta.lore(lore);
                display.setItemMeta(meta);
            }
            holder.inventory.setItem(slot++, display);
        }
    }

    // ------------------------------------------------------------------
    // イベント
    // ------------------------------------------------------------------

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof SendHolder sendHolder) {
            handleSendClick(event, sendHolder);
        } else if (holder instanceof BagagesHolder bagagesHolder) {
            handleBagagesClick(event, bagagesHolder);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof BagagesHolder) {
            event.setCancelled(true);
            return;
        }
        if (holder instanceof SendHolder) {
            for (int slot : event.getRawSlots()) {
                if (slot < SEND_SIZE && slot != SLOT_ITEM && slot != SLOT_DIAMOND) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof SendHolder holder) || holder.closed) {
            return;
        }
        holder.closed = true;
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        ItemStack item = holder.inventory.getItem(SLOT_ITEM);
        ItemStack diamonds = holder.inventory.getItem(SLOT_DIAMOND);
        holder.inventory.setItem(SLOT_ITEM, null);
        holder.inventory.setItem(SLOT_DIAMOND, null);
        boolean returned = false;
        if (!Items.isAir(item)) {
            InvUtil.giveOrDrop(player, item);
            returned = true;
        }
        if (!Items.isAir(diamonds)) {
            InvUtil.giveOrDrop(player, diamonds);
            returned = true;
        }
        if (returned) {
            plugin.messages().send(player, "delivery-returned");
        }
    }

    // ------------------------------------------------------------------
    // Holder
    // ------------------------------------------------------------------

    private static final class SendHolder implements InventoryHolder {
        private final java.util.UUID target;
        private final String targetName;
        private Inventory inventory;
        private boolean fast;
        private boolean closed;

        private SendHolder(java.util.UUID target, String targetName) {
            this.target = target;
            this.targetName = targetName;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class BagagesHolder implements InventoryHolder {
        private final java.util.UUID owner;
        private final List<DeliveryPackage> packages;
        private Inventory inventory;

        private BagagesHolder(java.util.UUID owner, List<DeliveryPackage> packages) {
            this.owner = owner;
            this.packages = packages;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
