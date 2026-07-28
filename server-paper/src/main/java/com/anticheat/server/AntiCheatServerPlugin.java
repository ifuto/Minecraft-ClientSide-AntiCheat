package com.anticheat.server;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Safe Server v3 - with mod list and background app list
 * /cac profile <player> -> mod list, /cac bprofile <player> -> app list collected 10s after join via DLL
 * Removed false-ban prone auto-bans; only critical package/file/cheatengine ban
 */
public class AntiCheatServerPlugin extends JavaPlugin implements PluginMessageListener {

    public static final String VIOLATION_CHANNEL = "anticheat:violation";
    public static final String HEARTBEAT_CHANNEL = "anticheat:heartbeat";
    public static final String HANDSHAKE_CHANNEL = "anticheat:handshake";
    public static final String HWID_CHANNEL = "anticheat:hwid";
    public static final String CHALLENGE_CHANNEL = "anticheat:challenge";
    public static final String MODLIST_CHANNEL = "anticheat:modlist";
    public static final String APPLIST_CHANNEL = "anticheat:applist";

    private final Map<UUID, Long> lastHeartbeat = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastHandshake = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerNames = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerHwids = new ConcurrentHashMap<>();
    private final Map<UUID, String> pendingChallenges = new ConcurrentHashMap<>();

    private String hmacSecret;
    private long heartbeatTimeoutMs;
    private boolean requireAnticheat;
    private boolean enableBan;
    private String banCommand;
    private String discordWebhookUrl;

    private ViolationScore violationScore;
    private EvidenceManager evidenceManager;
    private HardwareBanManager hwidBanManager;
    private DiscordWebhook discordWebhook;
    private ProfileManager profileManager;
    private BackgroundAppProfileManager bgProfileManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();
        hmacSecret = getConfig().getString("hmacSecret", "change-me-in-production-256-bit-secret");
        heartbeatTimeoutMs = getConfig().getLong("heartbeatTimeoutMs", 60000);
        requireAnticheat = getConfig().getBoolean("requireAnticheat", false);
        enableBan = getConfig().getBoolean("enableBan", true);
        banCommand = getConfig().getString("banCommand", "ban %player% %reason%");
        discordWebhookUrl = getConfig().getString("discordWebhookUrl", "");

        violationScore = new ViolationScore();
        evidenceManager = new EvidenceManager(new java.io.File(getDataFolder(), "evidence"));
        hwidBanManager = new HardwareBanManager(getDataFolder());
        discordWebhook = new DiscordWebhook(discordWebhookUrl);
        profileManager = new ProfileManager();
        bgProfileManager = new BackgroundAppProfileManager();

        getServer().getMessenger().registerIncomingPluginChannel(this, VIOLATION_CHANNEL, this);
        getServer().getMessenger().registerIncomingPluginChannel(this, HEARTBEAT_CHANNEL, this);
        getServer().getMessenger().registerIncomingPluginChannel(this, HANDSHAKE_CHANNEL, this);
        getServer().getMessenger().registerIncomingPluginChannel(this, HWID_CHANNEL, this);
        getServer().getMessenger().registerIncomingPluginChannel(this, CHALLENGE_CHANNEL, this);
        getServer().getMessenger().registerIncomingPluginChannel(this, MODLIST_CHANNEL, this);
        getServer().getMessenger().registerIncomingPluginChannel(this, APPLIST_CHANNEL, this);
        getServer().getMessenger().registerOutgoingPluginChannel(this, VIOLATION_CHANNEL);
        getServer().getMessenger().registerOutgoingPluginChannel(this, CHALLENGE_CHANNEL);

        getServer().getScheduler().runTaskTimer(this, this::checkHeartbeats, 20L * 5, 20L * 5);

        try {
            if (getCommand("anticheat") != null) getCommand("anticheat").setExecutor(new CommandHandler(this));
            if (getCommand("cac") != null) getCommand("cac").setExecutor(new CacCommandHandler(this));
        } catch (Exception e) { getLogger().warning("Cmd register fail: " + e.getMessage()); }

        getLogger().info("Safe Anticheat v3 enabled - modlist + bprofile");
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterIncomingPluginChannel(this, VIOLATION_CHANNEL, this);
        getServer().getMessenger().unregisterIncomingPluginChannel(this, HEARTBEAT_CHANNEL, this);
        getServer().getMessenger().unregisterIncomingPluginChannel(this, HANDSHAKE_CHANNEL, this);
        getServer().getMessenger().unregisterIncomingPluginChannel(this, HWID_CHANNEL, this);
        getServer().getMessenger().unregisterIncomingPluginChannel(this, CHALLENGE_CHANNEL, this);
        getServer().getMessenger().unregisterIncomingPluginChannel(this, MODLIST_CHANNEL, this);
        getServer().getMessenger().unregisterIncomingPluginChannel(this, APPLIST_CHANNEL, this);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        try {
            String payload = tryDecodeFabricString(message);
            com.google.gson.JsonObject json;
            try { json = new com.google.gson.Gson().fromJson(payload, com.google.gson.JsonObject.class); } catch (Exception e) { getLogger().warning("Invalid JSON from " + player.getName() + " on " + channel); return; }
            if (json == null) return;
            if (json.has("sig")) {
                String sig = json.get("sig").getAsString();
                if (!validateHmac(json, sig)) { getLogger().warning("Invalid HMAC from " + player.getName() + " ch " + channel); return; }
            }
            switch (channel.toLowerCase()) {
                case "anticheat:handshake" -> handleHandshake(player, json);
                case "anticheat:heartbeat" -> handleHeartbeat(player, json);
                case "anticheat:violation" -> handleViolation(player, json);
                case "anticheat:hwid" -> handleHwid(player, json);
                case "anticheat:modlist" -> handleModList(player, json);
                case "anticheat:applist" -> handleAppList(player, json);
            }
            evidenceManager.addEvidence(player, channel + " " + payload);
        } catch (Exception e) { e.printStackTrace(); }
    }

    private String tryDecodeFabricString(byte[] message) {
        try {
            int idx=0,length=0,shift=0;
            while(true){ byte b=message[idx]; length|=(b&0x7F)<<shift; idx++; if((b&0x80)==0) break; shift+=7; if(shift>35) throw new IllegalArgumentException(); if(idx>=message.length) break; }
            if(length>0 && idx+length<=message.length) return new String(message,idx,length,StandardCharsets.UTF_8);
        } catch(Exception ignored){}
        return new String(message,StandardCharsets.UTF_8).trim();
    }

    private boolean validateHmac(com.google.gson.JsonObject json, String receivedSig) {
        try{
            String playerUuid=json.has("playerUuid")?json.get("playerUuid").getAsString():""; long ts=json.has("ts")?json.get("ts").getAsLong():0; String dataToSign;
            if(json.has("type") && json.has("subType") && json.has("detail")){
                String type=json.get("type").getAsString(); String sub=json.get("subType").getAsString(); String detail=json.get("detail").getAsString(); String nonce=json.has("nonce")?json.get("nonce").getAsString():"";
                dataToSign=playerUuid+"|"+type+"|"+sub+"|"+detail+"|"+ts+"|"+nonce;
            }else if(json.has("mods")){ dataToSign=playerUuid+"|MODLIST|"+ts; }
            else if(json.has("apps")){ dataToSign=playerUuid+"|APPLIST|"+ts; }
            else if(json.has("hwid") && !json.has("mods") && !json.has("apps")){ String hwid=json.get("hwid").getAsString(); dataToSign=playerUuid+"|HWID|"+hwid+"|"+ts; }
            else if(json.has("nonce")){ String nonce=json.get("nonce").getAsString(); dataToSign=playerUuid+"|HEARTBEAT|"+ts+"|"+nonce; }
            else{ dataToSign=playerUuid+"|HANDSHAKE|"+ts; }
            return hmacSha256(hmacSecret,dataToSign).equalsIgnoreCase(receivedSig);
        }catch(Exception e){e.printStackTrace(); return false;}
    }

    private void handleHandshake(Player player, com.google.gson.JsonObject json){ lastHandshake.put(player.getUniqueId(),System.currentTimeMillis()); lastHeartbeat.put(player.getUniqueId(),System.currentTimeMillis()); playerNames.put(player.getUniqueId(),player.getName()); }
    private void handleHeartbeat(Player player, com.google.gson.JsonObject json){ lastHeartbeat.put(player.getUniqueId(),System.currentTimeMillis()); }
    private void handleHwid(Player player, com.google.gson.JsonObject json){ if(json.has("hwid")) playerHwids.put(player.getUniqueId(),json.get("hwid").getAsString()); }
    private void handleModList(Player player, com.google.gson.JsonObject json){ profileManager.updateProfile(player,json); getLogger().info("Mod list from "+player.getName()+" count="+(json.has("count")?json.get("count").getAsInt():0)); }
    private void handleAppList(Player player, com.google.gson.JsonObject json){ bgProfileManager.updateProfile(player,json); getLogger().info("App list from "+player.getName()+" count="+(json.has("count")?json.get("count").getAsInt():0)+" via DLL 10s after join"); }
    private void handleViolation(Player player, com.google.gson.JsonObject json){
        String type=json.has("type")?json.get("type").getAsString():"UNKNOWN"; String sub=json.has("subType")?json.get("subType").getAsString():"UNKNOWN"; String detail=json.has("detail")?json.get("detail").getAsString():""; String reason="Anticheat violation: "+type+":"+sub+" "+detail;
        getLogger().warning("VIOLATION from "+player.getName()+" "+type+":"+sub+" "+detail);
        boolean isCritical=type.equals("PACKAGE")||type.equals("FILE")|| (type.equals("MEMORY") && detail.toLowerCase().contains("cheat"));
        if(isCritical && enableBan){ Bukkit.getScheduler().runTask(this,()->{ BanHandler.banPlayer(player,reason,banCommand); }); }
        else{ getLogger().info("Non-critical logged only (safe mode) for "+player.getName()); }
    }
    private void checkHeartbeats(){ if(!requireAnticheat) return; long now=System.currentTimeMillis(); for(Player p:Bukkit.getOnlinePlayers()){ UUID uuid=p.getUniqueId(); Long lastHs=lastHandshake.get(uuid); Long lastHb=lastHeartbeat.get(uuid); if(lastHs==null && p.getTicksLived()>20*30){ p.kick(net.kyori.adventure.text.Component.text("Anticheat mod required")); continue; } if(lastHb!=null && now-lastHb>heartbeatTimeoutMs){ if(getConfig().getBoolean("kickOnHeartbeatTimeout",true)) p.kick(net.kyori.adventure.text.Component.text("Heartbeat timeout")); } } }
    private static String hmacSha256(String secret,String data){ try{ javax.crypto.Mac mac=javax.crypto.Mac.getInstance("HmacSHA256"); javax.crypto.spec.SecretKeySpec k=new javax.crypto.spec.SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"); mac.init(k); byte[] raw=mac.doFinal(data.getBytes(StandardCharsets.UTF_8)); StringBuilder sb=new StringBuilder(); for(byte b:raw) sb.append(String.format("%02x",b)); return sb.toString(); }catch(Exception e){throw new RuntimeException(e);} }
    public ProfileManager getProfileManager(){return profileManager;}
    public BackgroundAppProfileManager getBgProfileManager(){return bgProfileManager;}
    public ViolationScore getViolationScore(){return violationScore;}
    public EvidenceManager getEvidenceManager(){return evidenceManager;}
    public HardwareBanManager getHwidBanManager(){return hwidBanManager;}
}
