package com.ifuto.heartsmp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import com.ifuto.heartsmp.death.DeathRecord;
import com.ifuto.heartsmp.death.RollbackService;
import com.ifuto.heartsmp.player.Rank;
import com.ifuto.heartsmp.util.Msg;

/**
 * サーバを起動せずに検証できる範囲のテスト。
 * 主に config.yml のメッセージが MiniMessage として壊れていないことを確認します。
 */
class ConfigAndLogicTest {

    @Test
    void allMessagesParseAsMiniMessage() {
        File file = new File("src/main/resources/config.yml");
        assertTrue(file.isFile(), "config.yml が見つかりません: " + file.getAbsolutePath());
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("messages");
        assertNotNull(section, "messages セクションがありません");

        List<String> errors = new ArrayList<>();
        int checked = 0;
        for (String key : section.getKeys(true)) {
            Object raw = section.get(key);
            List<String> lines = new ArrayList<>();
            if (raw instanceof List<?> list) {
                for (Object entry : list) {
                    lines.add(String.valueOf(entry));
                }
            } else if (raw instanceof String string) {
                lines.add(string);
            } else {
                continue;
            }
            for (String line : lines) {
                checked++;
                try {
                    Msg.parse(line);
                } catch (RuntimeException ex) {
                    errors.add("[" + key + "] " + line + " -> " + ex.getMessage());
                    continue;
                }
                try {
                    Msg.build(line, "player", "Steve", "hearts", "10", "seconds", "3");
                } catch (RuntimeException ex) {
                    errors.add("[" + key + " (build)] " + line + " -> " + ex.getMessage());
                }
            }
        }
        assertTrue(checked > 40, "メッセージ数が少なすぎます: " + checked);
        if (!errors.isEmpty()) {
            throw new IllegalStateException("MiniMessage の解析に失敗しました:\n" + String.join("\n", errors));
        }
    }

    @Test
    void messagePlaceholdersAreResolved() {
        Map<String, net.kyori.adventure.text.Component> components = new LinkedHashMap<>();
        components.put("Item_Name", net.kyori.adventure.text.Component.text("Diamond Sword"));
        net.kyori.adventure.text.Component built = Msg.build(
                "<#ff6f4d>⚔️ <bold>%Killer_Name%</bold> <#ffffff><bold>→</bold> <#00D6FF>💀  "
                        + "<#C6C6C6>%Death_Name% <#C6C6C6>for <#ffffff>%Item_Name%",
                components, "Killer_Name", "Killer", "Death_Name", "Victim");
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(built);
        assertTrue(plain.contains("Killer"), plain);
        assertTrue(plain.contains("Victim"), plain);
        assertTrue(plain.contains("Diamond Sword"), plain);
        assertTrue(!plain.contains("%"), "未解決のプレースホルダが残っています: " + plain);
    }

    @Test
    void rankParsing() {
        assertEquals(Rank.DEFAULT, Rank.parse("default"));
        assertEquals(Rank.VIP, Rank.parse("VIP"));
        assertEquals(Rank.VIP_PLUS, Rank.parse("VIP+"));
        assertEquals(Rank.VIP_PLUS, Rank.parse("vip_plus"));
        assertEquals(null, Rank.parse("owner"));
    }

    @Test
    void originRoundTrip() {
        RollbackService.Origin origin = new RollbackService.Origin("CONTAINER", "-", "world", 12, 64, -30, 5);
        RollbackService.Origin parsed = RollbackService.Origin.parse(origin.encode());
        assertNotNull(parsed);
        assertEquals(origin, parsed);
        assertEquals(5, parsed.slot());
        assertEquals("world", parsed.world());
    }

    @Test
    void deathRecordRoundTrip() {
        UUID victim = UUID.randomUUID();
        UUID killer = UUID.randomUUID();
        Map<Integer, String> items = new LinkedHashMap<>();
        items.put(0, "gz:AAAA");
        items.put(36, "gz:BBBB");
        DeathRecord record = new DeathRecord("id-1", victim, "Victim", killer, "Killer",
                DeathRecord.Type.PLAYER_KILL, 1700000000000L, "Killer により殺害",
                "world", 1.5D, 64.0D, -2.5D, List.of("Killer", "Other"), items, 123);
        DeathRecord copy = DeathRecord.fromMap(record.toMap());
        assertNotNull(copy);
        assertEquals(record.id(), copy.id());
        assertEquals(victim, copy.victim());
        assertEquals(killer, copy.killer());
        assertEquals(DeathRecord.Type.PLAYER_KILL, copy.type());
        assertEquals(123, copy.experience());
        assertEquals(2, copy.items().size());
        assertEquals("gz:BBBB", copy.items().get(36));
        assertEquals(List.of("Killer", "Other"), copy.participants());
    }

    @Test
    void combatLogoutRecordHasNoKiller() {
        DeathRecord record = new DeathRecord("id-2", UUID.randomUUID(), "Victim", null, null,
                DeathRecord.Type.COMBAT_LOGOUT, System.currentTimeMillis(), "Combat 中のログアウト",
                "world", 0, 64, 0, List.of(), Map.of(), 0);
        DeathRecord copy = DeathRecord.fromMap(record.toMap());
        assertNotNull(copy);
        assertEquals(null, copy.killer());
        assertEquals(DeathRecord.Type.COMBAT_LOGOUT, copy.type());
    }

    @Test
    void harnessProbe() {
        throw new IllegalStateException("HARNESS_PROBE: テストが実行されているかの確認用 (この行は次のコミットで削除)");
    }

    @Test
    void obfuscatedNameIsObfuscated() {
        net.kyori.adventure.text.Component hidden = Msg.parse("<obfuscated>aaaaaa");
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(hidden);
        assertEquals("aaaaaa", plain);
        assertTrue(hidden.decoration(net.kyori.adventure.text.format.TextDecoration.OBFUSCATED),
                "難読化が付いていません");
    }

    @Test
    void clickableButtonsWork() {
        net.kyori.adventure.text.Component cancel = Msg.parse("<#ff6f4d>[CANCEL]")
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/tpacancel Steve"));
        assertEquals("[CANCEL]", net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(cancel));
        assertNotNull(cancel.clickEvent());
        assertEquals(net.kyori.adventure.text.event.ClickEvent.Action.RUN_COMMAND, cancel.clickEvent().action());
    }

    @Test
    void hiddenNamePlaceholderKeepsObfuscation() {
        java.util.Map<String, net.kyori.adventure.text.Component> placeholders = new java.util.LinkedHashMap<>();
        placeholders.put("JoinPlayer_Name", Msg.parse("<obfuscated>aaaaaa"));
        net.kyori.adventure.text.Component message = Msg.build(
                "<#C6C6C6>[<#7aff59>+<#C6C6C6>] <#ffffff>%JoinPlayer_Name%", placeholders);
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(message);
        assertTrue(plain.contains("aaaaaa"), plain);
        assertTrue(!plain.contains("%"), plain);
        assertTrue(plain.contains("+"), plain);
    }
}
