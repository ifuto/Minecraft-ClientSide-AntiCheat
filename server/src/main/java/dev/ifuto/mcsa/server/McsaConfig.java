package dev.ifuto.mcsa.server;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import dev.ifuto.mcsa.server.util.Hex;

/** config.yml / pins.yml の型付きビュー。{@link #reload()} で読み直す。 */
public final class McsaConfig {

    private final McsaPlugin plugin;

    public int protocol = 1;

    public boolean hmacEnabled = true;
    public byte[] hmacKey = new byte[0];
    public String hmacKeyHex = "";
    public String hmacOnInvalid = "FLAG";

    public String enforceMode = "LISTED";
    public Set<String> requiredPlayers = new HashSet<>();
    public int graceTicks = 100;
    public String enforceAction = "KICK";
    public String kickMessage = "";
    public String warnMessage = "";

    public int challengeDelayTicks = 20;
    public int challengeIntervalTicks = 6000;
    public int collectFlags = 15;
    public List<String> probeClasses = new ArrayList<>();

    public String pinnedClientJar = "";
    public boolean requireObfuscated = false;

    public List<String> bannedMods = new ArrayList<>();
    public List<String> suspiciousMods = new ArrayList<>();
    public List<String> allowedMods = new ArrayList<>();
    public boolean flagUnknownMods = false;
    public List<String> bannedShaders = new ArrayList<>();
    public List<String> bannedResourcePacks = new ArrayList<>();
    public String onBanned = "KICK";
    public String bannedKickMessage = "";

    public boolean alertConsole = true;
    public boolean alertBroadcast = true;
    public String alertPermission = "mcsa.alerts";
    public String webhook = "";
    public boolean saveReports = true;

    public boolean checksEnabled = true;
    public boolean checksCancel = false;
    public int alertVl = 5;
    public int kickVl = 0;
    public String checkKickMessage = "";

    public boolean reachEnabled = true;
    public double maxReach = 3.1;
    public boolean pingCompensation = true;

    public boolean killAuraEnabled = true;
    public double killAuraMaxAngle = 75.0;
    public int killAuraMaxHitsPerSecond = 16;

    public boolean autoClickerEnabled = true;
    public int autoClickerMaxCps = 20;
    public double autoClickerMinDeviation = 8.0;
    public int autoClickerMinSamples = 12;

    public boolean fastBreakEnabled = true;
    public int fastBreakMaxPerSecond = 20;

    public boolean flyEnabled = true;
    public int flyMinAirTicks = 30;

    public boolean speedEnabled = true;
    public double speedMaxPerTick = 0.6;
    public int speedMaxTicks = 10;

    /** pins.yml: MOD id -> 許可する SHA-256 の集合 */
    public final Map<String, Set<String>> pins = new LinkedHashMap<>();
    /** pins.yml: 配布クライアント jar の SHA-256 */
    public final Set<String> pinnedClientJars = new HashSet<>();

    public McsaConfig(McsaPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        plugin.reloadConfig();
        var config = plugin.getConfig();

        protocol = config.getInt("protocol", 1);

        hmacEnabled = config.getBoolean("hmac.enabled", true);
        hmacKeyHex = config.getString("hmac.key", "").trim();
        hmacKey = Hex.decode(hmacKeyHex);
        hmacOnInvalid = upper(config.getString("hmac.on-invalid", "FLAG"));

        enforceMode = upper(config.getString("enforce.mode", "LISTED"));
        requiredPlayers = new HashSet<>();
        for (String entry : config.getStringList("enforce.required-players")) {
            if (entry != null && !entry.isBlank()) {
                requiredPlayers.add(entry.trim().toLowerCase(Locale.ROOT));
            }
        }
        graceTicks = config.getInt("enforce.grace-ticks", 100);
        enforceAction = upper(config.getString("enforce.action", "KICK"));
        kickMessage = config.getString("enforce.kick-message", "");
        warnMessage = config.getString("enforce.warn-message", "");

        challengeDelayTicks = config.getInt("challenge.delay-ticks", 20);
        challengeIntervalTicks = config.getInt("challenge.interval-ticks", 6000);
        collectFlags = config.getInt("challenge.collect-flags", 15);
        probeClasses = new ArrayList<>(config.getStringList("challenge.probe-classes"));

        pinnedClientJar = config.getString("client.pinned-jar-sha256", "").trim().toLowerCase(Locale.ROOT);
        requireObfuscated = config.getBoolean("client.require-obfuscated", false);

        bannedMods = lower(config.getStringList("policy.banned-mods"));
        suspiciousMods = lower(config.getStringList("policy.suspicious-mods"));
        allowedMods = lower(config.getStringList("policy.allowed-mods"));
        flagUnknownMods = config.getBoolean("policy.flag-unknown-mods", false);
        bannedShaders = lower(config.getStringList("policy.banned-shaders"));
        bannedResourcePacks = lower(config.getStringList("policy.banned-resource-packs"));
        onBanned = upper(config.getString("policy.on-banned", "KICK"));
        bannedKickMessage = config.getString("policy.banned-kick-message", "");

        alertConsole = config.getBoolean("alerts.console", true);
        alertBroadcast = config.getBoolean("alerts.broadcast", true);
        alertPermission = config.getString("alerts.broadcast-permission", "mcsa.alerts");
        webhook = config.getString("alerts.webhook", "").trim();
        saveReports = config.getBoolean("storage.save-reports", true);

        checksEnabled = config.getBoolean("checks.enabled", true);
        checksCancel = config.getBoolean("checks.cancel", false);
        alertVl = config.getInt("checks.alert-vl", 5);
        kickVl = config.getInt("checks.kick-vl", 0);
        checkKickMessage = config.getString("checks.kick-message", "");

        reachEnabled = config.getBoolean("checks.reach.enabled", true);
        maxReach = config.getDouble("checks.reach.max-reach", 3.1);
        pingCompensation = config.getBoolean("checks.reach.ping-compensation", true);

        killAuraEnabled = config.getBoolean("checks.killaura.enabled", true);
        killAuraMaxAngle = config.getDouble("checks.killaura.max-angle", 75.0);
        killAuraMaxHitsPerSecond = config.getInt("checks.killaura.max-hits-per-second", 16);

        autoClickerEnabled = config.getBoolean("checks.autoclicker.enabled", true);
        autoClickerMaxCps = config.getInt("checks.autoclicker.max-cps", 20);
        autoClickerMinDeviation = config.getDouble("checks.autoclicker.min-interval-deviation-ms", 8.0);
        autoClickerMinSamples = config.getInt("checks.autoclicker.min-samples", 12);

        fastBreakEnabled = config.getBoolean("checks.fastbreak.enabled", true);
        fastBreakMaxPerSecond = config.getInt("checks.fastbreak.max-breaks-per-second", 20);

        flyEnabled = config.getBoolean("checks.fly.enabled", true);
        flyMinAirTicks = config.getInt("checks.fly.min-air-ticks", 30);

        speedEnabled = config.getBoolean("checks.speed.enabled", true);
        speedMaxPerTick = config.getDouble("checks.speed.max-per-tick", 0.6);
        speedMaxTicks = config.getInt("checks.speed.max-ticks", 10);

        loadPins();
    }

    private void loadPins() {
        pins.clear();
        pinnedClientJars.clear();
        File file = new File(plugin.getDataFolder(), "pins.yml");
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection mods = yaml.getConfigurationSection("mods");
        if (mods != null) {
            for (String id : mods.getKeys(false)) {
                Set<String> hashes = new HashSet<>();
                for (String hash : mods.getStringList(id)) {
                    hashes.add(hash.trim().toLowerCase(Locale.ROOT));
                }
                pins.put(id.toLowerCase(Locale.ROOT), hashes);
            }
        }
        for (String hash : yaml.getStringList("client-jar")) {
            pinnedClientJars.add(hash.trim().toLowerCase(Locale.ROOT));
        }
    }

    /** pins.yml を保存する（/ac policy pin 系から呼ばれる）。 */
    public void savePins() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("mods", null);
        for (Map.Entry<String, Set<String>> entry : pins.entrySet()) {
            yaml.set("mods." + entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        yaml.set("client-jar", new ArrayList<>(pinnedClientJars));
        try {
            File file = new File(plugin.getDataFolder(), "pins.yml");
            if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
                plugin.getLogger().warning("プラグインフォルダを作れませんでした");
            }
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("pins.yml を保存できませんでした: " + e);
        }
    }

    /** 導入必須の対象かどうか。 */
    public boolean isRequired(Player player) {
        if ("EVERYONE".equals(enforceMode)) {
            return true;
        }
        if (!"LISTED".equals(enforceMode)) {
            return false;
        }
        return requiredPlayers.contains(player.getName().toLowerCase(Locale.ROOT))
                || requiredPlayers.contains(player.getUniqueId().toString().toLowerCase(Locale.ROOT));
    }

    public void addRequired(String nameOrUuid) {
        requiredPlayers.add(nameOrUuid.toLowerCase(Locale.ROOT));
        List<String> list = new ArrayList<>(plugin.getConfig().getStringList("enforce.required-players"));
        list.add(nameOrUuid);
        plugin.getConfig().set("enforce.required-players", list);
        plugin.saveConfig();
    }

    public boolean removeRequired(String nameOrUuid) {
        boolean removed = requiredPlayers.remove(nameOrUuid.toLowerCase(Locale.ROOT));
        List<String> list = new ArrayList<>(plugin.getConfig().getStringList("enforce.required-players"));
        list.removeIf(entry -> entry.equalsIgnoreCase(nameOrUuid));
        plugin.getConfig().set("enforce.required-players", list);
        plugin.saveConfig();
        return removed;
    }

    public static boolean matchesAny(List<String> patterns, String value) {
        if (value == null) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        for (String pattern : patterns) {
            if (pattern == null || pattern.isBlank()) {
                continue;
            }
            String p = pattern.trim().toLowerCase(Locale.ROOT);
            if (p.equals(lower) || wildcard(p, lower)) {
                return true;
            }
        }
        return false;
    }

    /** 単純な {@code *} ワイルドカード照合。 */
    private static boolean wildcard(String pattern, String value) {
        if (pattern.indexOf('*') < 0) {
            return false;
        }
        String[] parts = pattern.split("\\*", -1);
        int index = 0;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty()) {
                continue;
            }
            int found = value.indexOf(part, index);
            if (found < 0) {
                return false;
            }
            if (i == 0 && found != 0) {
                return false;
            }
            index = found + part.length();
        }
        return !pattern.endsWith("*") || index <= value.length();
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static List<String> lower(List<String> values) {
        List<String> out = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    out.add(value.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return out;
    }
}
