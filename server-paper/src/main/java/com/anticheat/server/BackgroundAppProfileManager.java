package com.anticheat.server;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.entity.Player;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
public final class BackgroundAppProfileManager {
    public static class AppEntry { public final String name; public final long pid; public AppEntry(String name,long pid){this.name=name;this.pid=pid;} }
    public static class BgProfile { public final UUID uuid; public final String playerName; public final List<AppEntry> apps; public final Instant timestamp; public final String os;
        public BgProfile(UUID uuid,String playerName,List<AppEntry> apps,String os){this.uuid=uuid;this.playerName=playerName;this.apps=apps;this.timestamp=Instant.now();this.os=os;}
    }
    private final Map<UUID, BgProfile> profiles = new ConcurrentHashMap<>();
    public void updateProfile(Player player, JsonObject json){
        try{
            UUID uuid=player.getUniqueId(); String playerName=player.getName(); String os=json.has("os")?json.get("os").getAsString():System.getProperty("os.name");
            List<AppEntry> apps=new ArrayList<>();
            if(json.has("apps") && json.get("apps").isJsonArray()){
                JsonArray arr=json.getAsJsonArray("apps");
                for(JsonElement el:arr){ if(!el.isJsonObject()) continue; JsonObject obj=el.getAsJsonObject();
                    String name=obj.has("name")?obj.get("name").getAsString():"unknown"; long pid=obj.has("pid")?obj.get("pid").getAsLong():0;
                    apps.add(new AppEntry(name,pid));
                }
            }
            profiles.put(uuid,new BgProfile(uuid,playerName,apps,os));
        }catch(Exception e){e.printStackTrace();}
    }
    public BgProfile getProfile(UUID uuid){return profiles.get(uuid);}
    public BgProfile getProfileByName(String name){for(BgProfile p:profiles.values()) if(p.playerName.equalsIgnoreCase(name)) return p; return null;}
    public Collection<BgProfile> getAll(){return profiles.values();}
}
