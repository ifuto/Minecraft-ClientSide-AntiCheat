package com.anticheat.client.detection;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Safe feature: collects mod list to send to server.
 * No ban logic, only informational.
 * Runs on join.
 */
public final class ModListCollector {

    public static class ModInfo {
        public final String modId;
        public final String version;
        public final String name;
        public final String originPath;
        public final long fileSize;

        public ModInfo(String modId, String version, String name, String originPath, long fileSize) {
            this.modId = modId;
            this.version = version;
            this.name = name;
            this.originPath = originPath;
            this.fileSize = fileSize;
        }
    }

    public List<ModInfo> collect() {
        List<ModInfo> result = new ArrayList<>();
        try {
            Collection<ModContainer> mods = FabricLoader.getInstance().getAllMods();
            for (ModContainer container : mods) {
                ModMetadata meta = container.getMetadata();
                String modId = meta.getId();
                String version = meta.getVersion().getFriendlyString();
                String name = meta.getName();
                String originPath = "unknown";
                long size = 0;
                try {
                    List<Path> paths = container.getOrigin().getPaths();
                    if (!paths.isEmpty()) {
                        Path p = paths.get(0);
                        originPath = p.getFileName() != null ? p.getFileName().toString() : p.toString();
                        try {
                            if (java.nio.file.Files.isRegularFile(p)) {
                                size = java.nio.file.Files.size(p);
                            }
                        } catch (Exception ignored) {}
                    }
                } catch (Exception ignored) {}
                result.add(new ModInfo(modId, version, name, originPath, size));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return result;
    }

    public JsonObject toJson() {
        List<ModInfo> mods = collect();
        JsonArray arr = new JsonArray();
        for (ModInfo info : mods) {
            JsonObject obj = new JsonObject();
            obj.addProperty("modId", info.modId);
            obj.addProperty("version", info.version);
            obj.addProperty("name", info.name);
            obj.addProperty("file", info.originPath);
            obj.addProperty("size", info.fileSize);
            arr.add(obj);
        }
        JsonObject root = new JsonObject();
        root.addProperty("count", mods.size());
        root.add("mods", arr);
        return root;
    }
}
