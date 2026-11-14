package com.ifuto.heartsmp.rtp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import com.ifuto.heartsmp.HeartSMP;
import com.ifuto.heartsmp.player.Rank;
import com.ifuto.heartsmp.util.Items;
import com.ifuto.heartsmp.util.Msg;

/** /rtp の GUI。 */
public final class RtpGui implements Listener {

    public record BiomeOption(String key, Material icon) {
    }

    /** 選択可能なバイオーム (きのこ島などは除外)。 */
    public static final List<BiomeOption> BIOMES = List.of(
            new BiomeOption("plains", Material.GRASS_BLOCK),
            new BiomeOption("sunflower_plains", Material.SUNFLOWER),
            new BiomeOption("snowy_plains", Material.SNOW_BLOCK),
            new BiomeOption("ice_spikes", Material.PACKED_ICE),
            new BiomeOption("desert", Material.SAND),
            new BiomeOption("savanna", Material.ACACIA_LOG),
            new BiomeOption("savanna_plateau", Material.ACACIA_LEAVES),
            new BiomeOption("windswept_savanna", Material.ACACIA_SAPLING),
            new BiomeOption("forest", Material.OAK_SAPLING),
            new BiomeOption("flower_forest", Material.POPPY),
            new BiomeOption("birch_forest", Material.BIRCH_SAPLING),
            new BiomeOption("dark_forest", Material.DARK_OAK_SAPLING),
            new BiomeOption("old_growth_birch_forest", Material.BIRCH_LOG),
            new BiomeOption("pale_garden", Material.OAK_SAPLING),
            new BiomeOption("jungle", Material.JUNGLE_SAPLING),
            new BiomeOption("sparse_jungle", Material.JUNGLE_LOG),
            new BiomeOption("bamboo_jungle", Material.BAMBOO),
            new BiomeOption("taiga", Material.SPRUCE_SAPLING),
            new BiomeOption("snowy_taiga", Material.SPRUCE_LOG),
            new BiomeOption("old_growth_pine_taiga", Material.PODZOL),
            new BiomeOption("old_growth_spruce_taiga", Material.SPRUCE_LEAVES),
            new BiomeOption("swamp", Material.LILY_PAD),
            new BiomeOption("mangrove_swamp", Material.MANGROVE_PROPAGULE),
            new BiomeOption("windswept_hills", Material.STONE),
            new BiomeOption("windswept_gravelly_hills", Material.GRAVEL),
            new BiomeOption("windswept_forest", Material.OAK_LOG),
            new BiomeOption("meadow", Material.ALLIUM),
            new BiomeOption("cherry_grove", Material.CHERRY_SAPLING),
            new BiomeOption("grove", Material.SNOW_BLOCK),
            new BiomeOption("snowy_slopes", Material.POWDER_SNOW_BUCKET),
            new BiomeOption("jagged_peaks", Material.PACKED_ICE),
            new BiomeOption("frozen_peaks", Material.ICE),
            new BiomeOption("stony_peaks", Material.ANDESITE),
            new BiomeOption("stony_shore", Material.COBBLESTONE),
            new BiomeOption("beach", Material.SAND),
            new BiomeOption("snowy_beach", Material.SNOWBALL),
            new BiomeOption("badlands", Material.TERRACOTTA),
            new BiomeOption("eroded_badlands", Material.ORANGE_TERRACOTTA),
            new BiomeOption("wooded_badlands", Material.RED_TERRACOTTA)
    );

    private static final int PAGE_SIZE = 45;

    private final HeartSMP plugin;

    public RtpGui(HeartSMP plugin) {
        this.plugin = plugin;
    }

    public void openMenu(Player viewer) {
        MenuHolder holder = new MenuHolder();
        Inventory inventory = Bukkit.createInventory(holder, 27,
                Msg.parse(plugin.messages().raw("rtp-title")));
        holder.inventory = inventory;

        ItemStack filler = Items.icon(Material.LIGHT_BLUE_STAINED_GLASS_PANE,
                Msg.parse(plugin.messages().raw("delivery-filler-name")), List.of());
        for (int slot = 0; slot < 27; slot++) {
            inventory.setItem(slot, filler);
        }
        inventory.setItem(11, button(viewer, RtpManager.Type.CAVE, Material.DEEPSLATE,
                "rtp-cave-name", "rtp-cave-lore"));
        inventory.setItem(13, button(viewer, RtpManager.Type.NORMAL, Material.GRASS_BLOCK,
                "rtp-normal-name", "rtp-normal-lore"));
        inventory.setItem(15, button(viewer, RtpManager.Type.BIOME, Material.COMPASS,
                "rtp-biome-name", "rtp-biome-lore"));
        viewer.openInventory(inventory);
    }

    private ItemStack button(Player viewer, RtpManager.Type type, Material icon, String nameKey, String loreKey) {
        boolean unlocked = plugin.rtp().unlocked(viewer, type);
        Material material = unlocked ? icon : Material.BARRIER;
        List<Component> lore = new ArrayList<>(plugin.messages().getList(loreKey,
                "cooldown", formatCooldown(plugin.rtp().cooldownMillis(type))));
        if (!unlocked) {
            lore.add(Msg.build(plugin.messages().raw("rtp-locked"),
                    "rank", plugin.rtp().requiredRank(type).display(plugin)));
        }
        return Items.icon(material, Msg.parse(plugin.messages().raw(nameKey)), lore,
                "rtp:" + type.name());
    }

    private String formatCooldown(long millis) {
        long seconds = millis / 1000L;
        if (seconds % 60L == 0L) {
            return (seconds / 60L) + "min";
        }
        return seconds + "s";
    }

    public void openBiomes(Player viewer, int page) {
        List<BiomeOption> options = BIOMES;
        int maxPage = Math.max(0, (options.size() - 1) / PAGE_SIZE);
        int safePage = Math.max(0, Math.min(maxPage, page));

        BiomeHolder holder = new BiomeHolder(safePage);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                Msg.parse(plugin.messages().raw("rtp-biome-title")));
        holder.inventory = inventory;

        ItemStack filler = Items.icon(Material.LIGHT_BLUE_STAINED_GLASS_PANE,
                Msg.parse(plugin.messages().raw("delivery-filler-name")), List.of());
        for (int slot = 45; slot < 54; slot++) {
            inventory.setItem(slot, filler);
        }

        int start = safePage * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = start + i;
            if (index >= options.size()) {
                break;
            }
            BiomeOption option = options.get(index);
            String pretty = prettify(option.key());
            List<Component> lore = new ArrayList<>(plugin.messages().getList("rtp-biome-button-lore",
                    "cooldown", formatCooldown(plugin.rtp().cooldownMillis(RtpManager.Type.BIOME))));
            inventory.setItem(i, Items.icon(option.icon(),
                    Msg.parse("<#9BEFFF>" + Msg.escape(pretty)), lore, "biome:" + option.key()));
        }

        if (safePage > 0) {
            inventory.setItem(45, Items.icon(Material.ARROW,
                    Msg.parse(plugin.messages().raw("rtp-biome-prev-page")), List.of(), "page:" + (safePage - 1)));
        }
        inventory.setItem(49, Items.icon(Material.BARRIER,
                Msg.parse(plugin.messages().raw("rtp-biome-back")), List.of(), "back"));
        if (safePage < maxPage) {
            inventory.setItem(53, Items.icon(Material.ARROW,
                    Msg.parse(plugin.messages().raw("rtp-biome-next-page")), List.of(), "page:" + (safePage + 1)));
        }
        viewer.openInventory(inventory);
    }

    private String prettify(String key) {
        String spaced = key.replace('_', ' ');
        if (spaced.isEmpty()) {
            return key;
        }
        return spaced.substring(0, 1).toUpperCase(Locale.ROOT) + spaced.substring(1);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (!(holder instanceof MenuHolder) && !(holder instanceof BiomeHolder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (Items.isAir(clicked)) {
            return;
        }
        String tag = Items.tagOf(clicked);
        if (tag == null) {
            return;
        }
        if (tag.startsWith("rtp:")) {
            RtpManager.Type type = RtpManager.Type.valueOf(tag.substring(4));
            if (type == RtpManager.Type.BIOME) {
                if (!plugin.rtp().unlocked(player, RtpManager.Type.BIOME)) {
                    plugin.messages().error(player, "rtp-locked",
                            "rank", Rank.VIP_PLUS.display(plugin));
                    return;
                }
                openBiomes(player, 0);
                return;
            }
            player.closeInventory();
            plugin.rtp().attempt(player, type, null);
            return;
        }
        if (tag.startsWith("biome:")) {
            String key = tag.substring(6);
            player.closeInventory();
            plugin.rtp().attempt(player, RtpManager.Type.BIOME, key);
            return;
        }
        if (tag.equals("back")) {
            openMenu(player);
            return;
        }
        if (tag.startsWith("page:")) {
            int page = Integer.parseInt(tag.substring(5));
            openBiomes(player, page);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof MenuHolder || holder instanceof BiomeHolder) {
            event.setCancelled(true);
        }
    }

    private static final class MenuHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class BiomeHolder implements InventoryHolder {
        private final int page;
        private Inventory inventory;

        private BiomeHolder(int page) {
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
