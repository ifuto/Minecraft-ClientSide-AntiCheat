package com.ifuto.heartsmp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import com.ifuto.heartsmp.util.Msg;

/** config.yml の messages セクションを扱います。 */
public final class Messages {

    private final HeartSMP plugin;

    public Messages(HeartSMP plugin) {
        this.plugin = plugin;
    }

    private FileConfiguration config() {
        return plugin.getConfig();
    }

    public String raw(String key) {
        String value = config().getString("messages." + key);
        if (value == null) {
            return "<red>[missing message: " + key + "]";
        }
        return value;
    }

    public List<String> rawList(String key) {
        List<String> list = config().getStringList("messages." + key);
        if (list.isEmpty()) {
            String single = config().getString("messages." + key);
            if (single != null) {
                List<String> result = new ArrayList<>();
                result.add(single);
                return result;
            }
        }
        return list;
    }

    /** {@code %key%} を文字列で置換した素の文字列を返します (内部処理用)。 */
    public String plain(String key, String... pairs) {
        String value = raw(key);
        if (pairs != null) {
            for (int i = 0; i + 1 < pairs.length; i += 2) {
                value = value.replace("%" + pairs[i] + "%", pairs[i + 1] == null ? "" : pairs[i + 1]);
            }
        }
        return value;
    }

    public Component get(String key, String... pairs) {
        return Msg.build(raw(key), pairs);
    }

    public Component get(String key, Map<String, Component> components, String... pairs) {
        return Msg.build(raw(key), components, pairs);
    }

    public List<Component> getList(String key, String... pairs) {
        List<Component> result = new ArrayList<>();
        for (String line : rawList(key)) {
            result.add(Msg.build(line, pairs));
        }
        return result;
    }

    public Component prefix() {
        return Msg.parse(raw("prefix"));
    }

    /** プレフィックス無しで送信します。 */
    public void send(CommandSender sender, String key, String... pairs) {
        if (sender == null) {
            return;
        }
        sender.sendMessage(get(key, pairs));
    }

    public void send(CommandSender sender, Component component) {
        if (sender == null || component == null) {
            return;
        }
        sender.sendMessage(component);
    }

    /** プレフィックス付きで送信します。 */
    public void error(CommandSender sender, String key, String... pairs) {
        if (sender == null) {
            return;
        }
        sender.sendMessage(prefix().append(get(key, pairs)));
    }

    public void actionBar(Player player, String key, String... pairs) {
        if (player == null) {
            return;
        }
        player.sendActionBar(get(key, pairs));
    }

    public void broadcast(Component component) {
        plugin.getServer().sendMessage(component);
    }
}
