package com.anticheat.server;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.entity.Player;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
public final class ProfileManager {
    public static class ModEntry {
        public final String modId, version, name, file; public final long size;
        public ModEntry(String modId,String version,String name,String file,long size){this.modId=modId;this.version=version;this.name=name;this.file=file;this.size=size;}
    }
    public static class PlayerProfile {
        public final UUID uuid; public final String playerName; public final List<ModEntry> mods; public final Instant timestamp; public final String hwid, os;
        public PlayerProfile(UUID uuid,String playerName,List<ModEntry> mods,String hwid,String os){this.uuid=uuid;this.playerName=playerName;this.mods=mods;this.timestamp=Instant.now();this.hwid=hwid;this.os=os;}
    }
    private final Map<UUID, PlayerProfile> profiles = new ConcurrentHashMap<>();
    public void updateProfile(Player player, JsonObject json){
        try{
            UUID uuid=player.getUniqueId(); String playerName=player.getName();
            String hwid=json.has("hwid")?json.get("hwid").getAsString():"unknown"; String os=json.has("os")?json.get("os").getAsString():System.getProperty("os.name");
            List<ModEntry> mods=new ArrayList<>();
            if(json.has("mods") && json.get("mods").isJsonArray()){
                JsonArray arr=json.getAsJsonArray("mods");
                for(JsonElement el:arr){ if(!el.isJsonObject()) continue; JsonObject obj=el.getAsJsonObject();
                    String modId=obj.has("modId")?obj.get("modId").getAsString():"unknown"; String version=obj.has("version")?obj.get("version").getAsString():"unknown";
                    String name=obj.has("name")?obj.get("name").getAsString():modId; String file=obj.has("file")?obj.get("file").getAsString():"unknown"; long size=obj.has("size")?obj.get("size").getAsLong():0;
                    mods.add(new ModEntry(modId,version,name,file,size));
                }
            }
            profiles.put(uuid,new PlayerProfile(uuid,playerName,mods,hwid,os));
        }catch(Exception e){e.printStackTrace();}
    }
    public PlayerProfile getProfile(UUID uuid){return profiles.get(uuid);}
    public PlayerProfile getProfileByName(String name){for(PlayerProfile p:profiles.values()) if(p.playerName.equalsIgnoreCase(name)) return p; return null;}
    public Collection<PlayerProfile> getAll(){return profiles.values();}
}
