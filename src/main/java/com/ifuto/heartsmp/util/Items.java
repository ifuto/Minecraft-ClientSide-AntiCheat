package com.ifuto.heartsmp.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.entity.OfflinePlayer;

/** GUI アイコンやアイテム名まわりのヘルパ。 */
public final class Items {

    private Items() {
    }

    public static ItemStack icon(Material material, Component name, List<Component> lore) {
        return icon(material, name, lore, null);
    }

    public static ItemStack icon(Material material, Component name, List<Component> lore, String tag) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (name != null) {
                meta.displayName(name);
            }
            if (lore != null && !lore.isEmpty()) {
                meta.lore(lore);
            }
            if (tag != null && PdcKeys.GUI_TAG != null) {
                meta.getPersistentDataContainer().set(PdcKeys.GUI_TAG, PersistentDataType.STRING, tag);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    public static ItemStack head(OfflinePlayer owner, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof SkullMeta skull) {
            skull.setOwningPlayer(owner);
            if (name != null) {
                skull.displayName(name);
            }
            if (lore != null && !lore.isEmpty()) {
                skull.lore(lore);
            }
            item.setItemMeta(skull);
        }
        return item;
    }

    public static String tagOf(ItemStack item) {
        if (item == null || PdcKeys.GUI_TAG == null) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        return meta.getPersistentDataContainer().get(PdcKeys.GUI_TAG, PersistentDataType.STRING);
    }

    public static ItemStack named(Material material, Component name) {
        return icon(material, name, new ArrayList<>());
    }

    public static boolean isAir(ItemStack item) {
        return item == null || item.getType() == Material.AIR;
    }

    /** バニラの言語キー (item.minecraft.xxx)。 */
    public static String translationKey(Material material) {
        String key = material.getKey().getKey().toLowerCase(Locale.ROOT);
        return "item.minecraft." + key;
    }

    /** デフォルト名 (クライアント側で各言語に翻訳される)。 */
    public static Component defaultName(Material material) {
        return Component.translatable(translationKey(material));
    }

    /** サーバ側で使う素のデフォルト名 (ログや文字列メッセージ用)。 */
    public static String plainName(Material material) {
        String key = material.getKey().getKey().replace('_', ' ');
        if (key.isEmpty()) {
            return material.name();
        }
        return key.substring(0, 1).toUpperCase(Locale.ROOT) + key.substring(1);
    }

    /**
     * キルログ用のアイテム名。
     * カスタム名なら {@code "名前"} で囲み、デフォルト名なら各言語のデフォルト名を返します。
     */
    public static Component killLogName(ItemStack item, String quotedTemplate) {
        if (isAir(item)) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        Component custom = meta == null ? null : meta.displayName();
        if (custom != null) {
            return Msg.build(quotedTemplate, Map.of("Item_Name", custom));
        }
        return defaultName(item.getType());
    }
}
