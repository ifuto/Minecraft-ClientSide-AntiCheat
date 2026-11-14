package com.ifuto.heartsmp.admin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
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
import com.ifuto.heartsmp.util.Items;
import com.ifuto.heartsmp.util.Msg;

/** /combat : Combat 中のプレイヤー一覧 (スペクテイター移動付き) */
public final class CombatCommand implements CommandExecutor, TabCompleter, Listener {

    private final HeartSMP plugin;
    private final Map<UUID, SpectateSession> spectating = new HashMap<>();

    public CombatCommand(HeartSMP plugin) {
        this.plugin = plugin;
    }

    private record SpectateSession(Location returnLocation, GameMode previous, UUID target) {
    }

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
        if (args.length > 0 && args[0].equalsIgnoreCase("back")) {
            back(player);
            return true;
        }
        openGui(player);
        return true;
    }

    private void openGui(Player viewer) {
        List<UUID> combatants = new ArrayList<>(plugin.combat().combatants());
        int rows = Math.max(1, Math.min(6, (combatants.size() + 8) / 9));
        Holder holder = new Holder(combatants);
        Inventory inventory = Bukkit.createInventory(holder, rows * 9,
                Msg.parse(plugin.messages().raw("combat-gui-title")));
        holder.inventory = inventory;

        if (combatants.isEmpty()) {
            inventory.setItem(0, Items.icon(Material.BARRIER,
                    Msg.parse(plugin.messages().raw("combat-gui-empty")), List.of()));
        } else {
            int slot = 0;
            for (UUID uuid : combatants) {
                Player target = Bukkit.getPlayer(uuid);
                if (target == null) {
                    continue;
                }
                List<Component> lore = plugin.messages().getList("combat-gui-head-lore",
                        "seconds", String.valueOf(plugin.combat().remainingSeconds(uuid)));
                inventory.setItem(slot++, Items.head(target,
                        Msg.build(plugin.messages().raw("combat-gui-head-name"), "player", target.getName()), lore));
            }
        }
        viewer.openInventory(inventory);
    }

    private void spectate(Player admin, Player target) {
        spectating.put(admin.getUniqueId(), new SpectateSession(admin.getLocation().clone(), admin.getGameMode(),
                target.getUniqueId()));
        admin.closeInventory();
        admin.setGameMode(GameMode.SPECTATOR);
        admin.teleport(target.getLocation());
        plugin.messages().send(admin, "combat-spectate", "player", target.getName());
        Component back = Msg.parse(plugin.messages().raw("combat-spectate-back-button"))
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/combat back"));
        admin.sendMessage(back);
    }

    private void back(Player admin) {
        SpectateSession session = spectating.remove(admin.getUniqueId());
        if (session == null) {
            plugin.messages().error(admin, "combat-spectate-no-session");
            return;
        }
        admin.setGameMode(session.previous());
        Location location = session.returnLocation();
        if (location != null) {
            admin.teleport(location);
        }
        plugin.messages().send(admin, "combat-spectate-back-done");
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (!(holder instanceof Holder combatHolder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= combatHolder.targets.size()) {
            return;
        }
        Player target = Bukkit.getPlayer(combatHolder.targets.get(slot));
        if (target == null) {
            return;
        }
        spectate(player, target);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && "back".startsWith(args[0].toLowerCase(Locale.ROOT))) {
            return List.of("back");
        }
        return List.of();
    }

    private static final class Holder implements InventoryHolder {
        private final List<UUID> targets;
        private Inventory inventory;

        private Holder(List<UUID> targets) {
            this.targets = targets;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    /** 管理者のスペクテイト状態を解除します (退出時など)。 */
    public void clear(Player admin) {
        spectating.remove(admin.getUniqueId());
    }
}
