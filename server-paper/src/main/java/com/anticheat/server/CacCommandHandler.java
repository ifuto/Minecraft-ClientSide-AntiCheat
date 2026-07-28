package com.anticheat.server;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import java.util.*;
public class CacCommandHandler implements CommandExecutor, TabCompleter {
    private final AntiCheatServerPlugin plugin;
    public CacCommandHandler(AntiCheatServerPlugin plugin){this.plugin=plugin;}
    @Override public boolean onCommand(CommandSender sender,Command cmd,String label,String[] args){
        if(!sender.hasPermission("anticheat.admin")){sender.sendMessage("§cNo permission");return true;}
        if(args.length==0){
            sender.sendMessage("§a/CAC - Client Anticheat Admin"); sender.sendMessage("§7/cac profile <player> [page]"); sender.sendMessage("§7/cac bprofile <player> [page]"); sender.sendMessage("§7/cac list"); return true;
        }
        String sub=args[0].toLowerCase();
        switch(sub){
            case "profile"->handleProfile(sender,args);
            case "bprofile","bgprofile","appprofile"->handleBProfile(sender,args);
            case "list"->handleList(sender);
            case "status"->handleStatus(sender,args);
            case "evidence"->handleEvidence(sender,args);
            default->sender.sendMessage("§cUnknown. Use /cac");
        }
        return true;
    }
    private void handleProfile(CommandSender sender,String[] args){
        if(args.length<2){sender.sendMessage("§cUsage: /cac profile <player> [page]");return;}
        String target=args[1]; int page=1; if(args.length>=3) try{page=Integer.parseInt(args[2]);}catch(Exception ignored){}
        ProfileManager.PlayerProfile profile=plugin.getProfileManager().getProfileByName(target);
        if(profile==null){ Player online=Bukkit.getPlayer(target); if(online!=null) profile=plugin.getProfileManager().getProfile(online.getUniqueId()); }
        if(profile==null){sender.sendMessage("§cNo mod profile for "+target);return;}
        int perPage=15, total=profile.mods.size(), pages=(int)Math.ceil((double)total/perPage); if(page<1)page=1; if(page>pages)page=pages;
        sender.sendMessage("§a=== Mod Profile: "+profile.playerName+" Mods: "+total+" Page "+page+"/"+pages+" ==="); sender.sendMessage("§7OS: "+profile.os+" HWID: "+profile.hwid);
        int from=(page-1)*perPage, to=Math.min(from+perPage,total);
        for(int i=from;i<to;i++){ var mod=profile.mods.get(i); boolean sus=isSuspiciousMod(mod.modId); String pref=sus?"§c[!] ":"§7"; sender.sendMessage(pref+mod.modId+" v"+mod.version+" ("+mod.file+")"); }
        if(pages>1) sender.sendMessage("§7/cac profile "+target+" "+(page+1));
    }
    private void handleBProfile(CommandSender sender,String[] args){
        if(args.length<2){sender.sendMessage("§cUsage: /cac bprofile <player> [page]");return;}
        String target=args[1]; int page=1; if(args.length>=3) try{page=Integer.parseInt(args[2]);}catch(Exception ignored){}
        BackgroundAppProfileManager.BgProfile profile=plugin.getBgProfileManager().getProfileByName(target);
        if(profile==null){ Player online=Bukkit.getPlayer(target); if(online!=null) profile=plugin.getBgProfileManager().getProfile(online.getUniqueId()); }
        if(profile==null){sender.sendMessage("§cNo bprofile for "+target+" - DLL not collected yet (10s after join)");return;}
        int perPage=20, total=profile.apps.size(), pages=(int)Math.ceil((double)total/perPage); if(page<1)page=1; if(page>pages)page=pages;
        sender.sendMessage("§a=== Background App Profile: "+profile.playerName+" Apps: "+total+" Page "+page+"/"+pages+" (DLL 10s after join) ===");
        int from=(page-1)*perPage, to=Math.min(from+perPage,total);
        for(int i=from;i<to;i++){ var app=profile.apps.get(i); boolean sus=isSuspiciousApp(app.name); String pref=sus?"§c[!] ":"§7"; sender.sendMessage(pref+app.name+(app.pid>0?" (PID "+app.pid+")":"")); }
        if(pages>1) sender.sendMessage("§7/cac bprofile "+target+" "+(page+1));
    }
    private void handleList(CommandSender sender){ sender.sendMessage("§a=== Mod Profiles ==="); for(var p:plugin.getProfileManager().getAll()) sender.sendMessage("§7- "+p.playerName+" mods="+p.mods.size()); sender.sendMessage("§a=== App Profiles ==="); for(var p:plugin.getBgProfileManager().getAll()) sender.sendMessage("§7- "+p.playerName+" apps="+p.apps.size()); }
    private void handleStatus(CommandSender sender,String[] args){ if(args.length<2){sender.sendMessage("§cUsage: /cac status <player>");return;} Player t=Bukkit.getPlayer(args[1]); if(t==null){sender.sendMessage("§cNot online");return;} sender.sendMessage("§7Score: "+String.format("%.1f",plugin.getViolationScore().getScore(t))); }
    private void handleEvidence(CommandSender sender,String[] args){ if(args.length<2){sender.sendMessage("§cUsage: /cac evidence <player> [n]");return;} Player t=Bukkit.getPlayer(args[1]); if(t==null){sender.sendMessage("§cNot online");return;} int n=10; if(args.length>=3) try{n=Integer.parseInt(args[2]);}catch(Exception ignored){} var recent=plugin.getEvidenceManager().getRecent(t,n); sender.sendMessage("§a=== Evidence "+t.getName()+" last "+n+" ==="); for(String ev:recent) sender.sendMessage("§7"+ev); }
    private boolean isSuspiciousMod(String modId){ String l=modId.toLowerCase(); return l.contains("meteor")||l.contains("wurst")||l.contains("baritone")||l.contains("liquidbounce")||l.contains("aristois")||l.contains("future")||l.contains("rusher")||l.contains("vape")||l.contains("cheat")||l.contains("killaura"); }
    private boolean isSuspiciousApp(String appName){ String l=appName.toLowerCase(); return l.contains("cheatengine")||l.contains("x64dbg")||l.contains("processhacker")||l.contains("artmoney")||l.contains("wireshark")||l.contains("fiddler"); }
    @Override public List<String> onTabComplete(CommandSender sender,Command cmd,String alias,String[] args){ List<String> sug=new ArrayList<>(); if(args.length==1){ sug.addAll(Arrays.asList("profile","bprofile","list","status","evidence")); return filter(sug,args[0]); } if(args.length==2 && (args[0].equalsIgnoreCase("profile")||args[0].equalsIgnoreCase("bprofile"))){ for(Player p:Bukkit.getOnlinePlayers()) sug.add(p.getName()); return filter(sug,args[1]); } return sug; }
    private List<String> filter(List<String> list,String prefix){ if(prefix==null||prefix.isEmpty()) return list; List<String> f=new ArrayList<>(); for(String s:list) if(s.toLowerCase().startsWith(prefix.toLowerCase())) f.add(s); return f; }
}
