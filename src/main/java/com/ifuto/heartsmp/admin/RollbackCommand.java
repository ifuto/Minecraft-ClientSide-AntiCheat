package com.ifuto.heartsmp.admin;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import com.ifuto.heartsmp.HeartSMP;
import com.ifuto.heartsmp.death.DeathRecord;
import com.ifuto.heartsmp.death.RollbackService;
import com.ifuto.heartsmp.util.ItemCodec;
import com.ifuto.heartsmp.util.Items;
import com.ifuto.heartsmp.util.Msg;

/** /inventoryrollback &lt;player&gt; */
public final class RollbackCommand implements CommandExecutor, TabCompleter, Listener {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final HeartSMP plugin;

    public RollbackCommand(HeartSMP plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    // コマンド
    // ------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().error(sender, "player-only");
            return true;
        }
        if (!player.hasPermission("heartsmp.admin")) {
            plugin.messages().error(player, "no-permission");
            return true;
        }
        if (args.length < 1) {
            player.sendMessage(Msg.parse("<#eb4034>/inventoryrollback <player>"));
            return true;
        }
        OfflinePlayer target = findPlayer(args[0]);
        if (target == null) {
            plugin.messages().error(player, "player-not-found", "player", args[0]);
            return true;
        }
        openList(player, target);
        return true;
    }

    private OfflinePlayer findPlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        for (OfflinePlayer offline : Bukkit.getOfflinePlayers()) {
            if (offline.getName() != null && offline.getName().equalsIgnoreCase(name)) {
                return offline;
            }
        }
        return null;
    }

    private void openList(Player viewer, OfflinePlayer target) {
        List<DeathRecord> records = plugin.deaths().byVictim(target.getUniqueId());
        ListHolder holder = new ListHolder(target.getUniqueId());
        Inventory inventory = Bukkit.createInventory(holder, 54,
                Msg.build(plugin.messages().raw("rollback-title"), "player", target.getName() == null ? "?" : target.getName()));
        holder.inventory = inventory;

        if (records.isEmpty()) {
            inventory.setItem(22, Items.icon(Material.BARRIER,
                    Msg.parse(plugin.messages().raw("rollback-empty")), List.of()));
        } else {
            int slot = 0;
            for (DeathRecord record : records) {
                if (slot >= 54) {
                    break;
                }
                inventory.setItem(slot++, recordItem(record));
            }
        }
        viewer.openInventory(inventory);
    }

    private ItemStack recordItem(DeathRecord record) {
        String nameKey = record.type() == DeathRecord.Type.COMBAT_LOGOUT
                ? "rollback-record-name-logout" : "rollback-record-name";
        Component name = Msg.build(plugin.messages().raw(nameKey),
                "killer", record.killerName() == null ? "Unknown" : record.killerName());

        List<Component> lore = new ArrayList<>();
        Map<String, Component> placeholders = new LinkedHashMap<>();
        placeholders.put("time", Component.text(TIME_FORMAT.format(Instant.ofEpochMilli(record.time()))));
        placeholders.put("cause", Component.text(record.cause()));
        placeholders.put("location", Component.text(record.locationString()));
        placeholders.put("players", Component.text(String.join(", ", record.participants())));
        placeholders.put("items", Component.text(String.valueOf(record.items().size())));
        for (String line : plugin.messages().rawList("rollback-record-lore")) {
            lore.add(Msg.build(line, placeholders));
        }
        return Items.icon(Material.PAPER, name, lore);
    }

    // ------------------------------------------------------------------
    // GUI
    // ------------------------------------------------------------------

    private void openDetail(Player viewer, DeathRecord record) {
        DetailHolder holder = new DetailHolder(record);
        String killer = record.killerName() == null ? "Combat Logout" : record.killerName();
        Inventory inventory = Bukkit.createInventory(holder, 54, Msg.build(
                plugin.messages().raw("rollback-detail-title"),
                "player", record.victimName(), "killer", killer));
        holder.inventory = inventory;

        for (Map.Entry<Integer, String> entry : record.items().entrySet()) {
            int slot = entry.getKey();
            if (slot < 0 || slot >= 45) {
                continue;
            }
            ItemStack item = ItemCodec.decode(entry.getValue());
            if (item != null) {
                inventory.setItem(slot, item);
            }
        }

        inventory.setItem(45, Items.icon(Material.LIME_DYE,
                Msg.parse(plugin.messages().raw("rollback-button")),
                plugin.messages().getList("rollback-button-lore"), "rollback"));
        inventory.setItem(46, Items.icon(Material.EXPERIENCE_BOTTLE,
                Msg.parse(plugin.messages().raw("rollback-button-xp")),
                plugin.messages().getList("rollback-button-xp-lore", "exp", String.valueOf(record.experience())),
                "rollback-xp"));
        if (plugin.rollback().hasUndo(record.id())) {
            inventory.setItem(47, Items.icon(Material.RED_DYE,
                    Msg.parse(plugin.messages().raw("rollback-undo-button")),
                    plugin.messages().getList("rollback-undo-button-lore"), "undo"));
        }
        inventory.setItem(49, Items.icon(Material.BOOK,
                Msg.parse(plugin.messages().raw("rollback-info-button")),
                plugin.messages().getList("rollback-info-lore",
                        "player", record.victimName(),
                        "killer", killer,
                        "time", TIME_FORMAT.format(Instant.ofEpochMilli(record.time())),
                        "items", String.valueOf(record.items().size()),
                        "exp", String.valueOf(record.experience())),
                "info"));
        inventory.setItem(53, Items.icon(Material.BARRIER,
                Msg.parse(plugin.messages().raw("rollback-close-button")), List.of(), "close"));

        viewer.openInventory(inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof ListHolder listHolder) {
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) {
                return;
            }
            ItemStack clicked = event.getCurrentItem();
            if (Items.isAir(clicked)) {
                return;
            }
            List<DeathRecord> records = plugin.deaths().byVictim(listHolder.victim);
            int index = event.getRawSlot();
            if (index < 0 || index >= records.size()) {
                return;
            }
            openDetail(player, records.get(index));
            return;
        }
        if (holder instanceof DetailHolder detailHolder) {
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) {
                return;
            }
            int slot = event.getRawSlot();
            DeathRecord record = detailHolder.record;
            if (slot == 53) {
                player.closeInventory();
                return;
            }
            if (slot == 45) {
                applyRollback(player, record, false);
                return;
            }
            if (slot == 46) {
                applyRollback(player, record, true);
                return;
            }
            if (slot == 47) {
                undoRollback(player, record);
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof ListHolder || holder instanceof DetailHolder) {
            event.setCancelled(true);
        }
    }

    private void applyRollback(Player executor, DeathRecord record, boolean restoreExp) {
        Player victim = Bukkit.getPlayer(record.victim());
        if (victim == null) {
            plugin.messages().error(executor, "unknown-player-online", "player", record.victimName());
            return;
        }
        RollbackService.Result result = plugin.rollback().rollback(record, restoreExp);
        if (result == null) {
            plugin.messages().error(executor, "rollback-nothing");
            return;
        }
        plugin.messages().send(executor, "rollback-applied",
                "found", String.valueOf(result.found()),
                "restored", String.valueOf(result.restored()),
                "dropped", String.valueOf(result.dropped()));
        plugin.messages().send(victim, "rollback-received");
        executor.closeInventory();
    }

    private void undoRollback(Player executor, DeathRecord record) {
        RollbackService.Result result = plugin.rollback().undo(record);
        if (result == null) {
            plugin.messages().error(executor, "rollback-no-undo");
            return;
        }
        plugin.messages().send(executor, "rollback-undone",
                "restored", String.valueOf(result.restored()),
                "dropped", String.valueOf(result.dropped()));
        Player victim = Bukkit.getPlayer(record.victim());
        if (victim != null) {
            plugin.messages().send(victim, "rollback-removed-notice");
        }
        executor.closeInventory();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> names = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                names.add(online.getName());
            }
        }
        return names;
    }

    // ------------------------------------------------------------------
    // Holder
    // ------------------------------------------------------------------

    private static final class ListHolder implements InventoryHolder {
        private final UUID victim;
        private Inventory inventory;

        private ListHolder(UUID victim) {
            this.victim = victim;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class DetailHolder implements InventoryHolder {
        private final DeathRecord record;
        private Inventory inventory;

        private DetailHolder(DeathRecord record) {
            this.record = record;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
