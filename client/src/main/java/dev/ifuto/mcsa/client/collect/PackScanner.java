package dev.ifuto.mcsa.client.collect;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.ifuto.mcsa.client.McsaConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

/**
 * リソースパックの収集。
 *
 * <ul>
 *   <li>{@code resourcePacks} … 有効化されているパックの id 一覧（vanilla, file/xxx.zip, programer_art など）</li>
 *   <li>{@code resourcePackFiles} … {@code resourcepacks/} の中身（ファイル名・サイズ・SHA-256）</li>
 * </ul>
 *
 * <p>X-ray パックなどは「どのテクスチャが差し替わっているか」まで見ないと確実な判定はできない。
 * そこまではしない（プライバシーとコストの観点）。サーバ側では名前とハッシュで照合すること。
 */
public final class PackScanner {

    private PackScanner() {
    }

    public static void collect(JsonObject root, McsaConfig config) {
        JsonArray enabled = new JsonArray();
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            for (String id : client.getResourcePackManager().getEnabledIds()) {
                enabled.add(id);
            }
        } catch (Throwable t) {
            // バージョン差異などで取れない場合は空のまま
        }
        root.add("resourcePacks", enabled);

        if (config.collectResourcePacks) {
            root.add("resourcePackFiles",
                    FileScanner.listDirectory(FabricLoader.getInstance().getGameDir().resolve("resourcepacks"), config));
        }
    }
}
