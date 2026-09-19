package dev.ifuto.mcsa.server.report;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.ifuto.mcsa.server.McsaPlugin;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 受信したレポートの保持と {@code plugins/MCSA/reports/<uuid>.json} への保存。 */
public final class ReportStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final McsaPlugin plugin;
    private final Map<UUID, ClientReport> reports = new ConcurrentHashMap<>();

    public ReportStore(McsaPlugin plugin) {
        this.plugin = plugin;
    }

    public void put(Player player, ClientReport report) {
        reports.put(player.getUniqueId(), report);
        if (plugin.config().saveReports) {
            save(report);
        }
    }

    public ClientReport get(UUID playerId) {
        return reports.get(playerId);
    }

    public ClientReport get(Player player) {
        return player == null ? null : reports.get(player.getUniqueId());
    }

    public void remove(UUID playerId) {
        reports.remove(playerId);
    }

    public Collection<ClientReport> all() {
        return reports.values();
    }

    public void clearOffline() {
        reports.keySet().removeIf(id -> plugin.getServer().getPlayer(id) == null);
    }

    private void save(ClientReport report) {
        try {
            File dir = new File(plugin.getDataFolder(), "reports");
            if (!dir.exists() && !dir.mkdirs()) {
                plugin.getLogger().warning("レポート保存先を作れませんでした: " + dir);
                return;
            }
            JsonObject wrapper = new JsonObject();
            wrapper.addProperty("player", report.playerName());
            wrapper.addProperty("uuid", report.playerId().toString());
            wrapper.addProperty("receivedAt", report.receivedAt());
            wrapper.addProperty("hmacValid", report.hmacValid());
            wrapper.addProperty("keyId", report.keyId());
            JsonArray flags = new JsonArray();
            report.flags().forEach(flags::add);
            wrapper.add("flags", flags);
            wrapper.addProperty("critical", report.hasCritical());
            wrapper.add("report", report.json());

            File file = new File(dir, report.playerId() + ".json");
            Files.writeString(file.toPath(), GSON.toJson(wrapper), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().warning("レポートを保存できませんでした: " + e);
        }
    }
}
