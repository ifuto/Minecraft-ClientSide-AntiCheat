package com.anticheat.client.detection;

import com.anticheat.client.nativebridge.EnhancedNativeBridge;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects background running apps via DLL (native) 10s after join.
 */
public final class BackgroundAppCollector {

    public static class AppInfo {
        public final String name;
        public final long pid;

        public AppInfo(String name, long pid) {
            this.name = name;
            this.pid = pid;
        }
    }

    public List<AppInfo> collect() {
        List<AppInfo> result = new ArrayList<>();
        if (EnhancedNativeBridge.isAvailable()) {
            try {
                String[] nativeApps = EnhancedNativeBridge.safeGetRunningApps();
                for (String app : nativeApps) {
                    if (app == null || app.isBlank()) continue;
                    result.add(new AppInfo(app, 0));
                }
                if (!result.isEmpty()) {
                    return result;
                }
            } catch (Throwable t) { t.printStackTrace(); }
            try {
                String[] detailed = EnhancedNativeBridge.safeGetRunningAppsDetailed();
                for (String line : detailed) {
                    if (line.contains(":")) {
                        String[] parts = line.split(":", 2);
                        long pid = Long.parseLong(parts[0]);
                        String exe = parts[1];
                        result.add(new AppInfo(exe, pid));
                    } else {
                        result.add(new AppInfo(line, 0));
                    }
                }
                if (!result.isEmpty()) return result;
            } catch (Throwable ignored) {}
        }
        try {
            ProcessHandle.allProcesses().forEach(ph -> {
                try {
                    String cmd = ph.info().command().orElse("unknown");
                    String exeName = cmd;
                    int lastSlash = Math.max(cmd.lastIndexOf('/'), cmd.lastIndexOf('\\'));
                    if (lastSlash >= 0 && lastSlash + 1 < cmd.length()) exeName = cmd.substring(lastSlash + 1);
                    if (result.size() < 300) result.add(new AppInfo(exeName, ph.pid()));
                } catch (Exception ignored) {}
            });
        } catch (Exception e) { e.printStackTrace(); }
        return result;
    }

    public JsonObject toJson() {
        List<AppInfo> apps = collect();
        JsonArray arr = new JsonArray();
        for (AppInfo app : apps) {
            JsonObject obj = new JsonObject();
            obj.addProperty("name", app.name);
            obj.addProperty("pid", app.pid);
            arr.add(obj);
        }
        JsonObject root = new JsonObject();
        root.addProperty("count", apps.size());
        root.add("apps", arr);
        root.addProperty("os", System.getProperty("os.name"));
        return root;
    }
}
