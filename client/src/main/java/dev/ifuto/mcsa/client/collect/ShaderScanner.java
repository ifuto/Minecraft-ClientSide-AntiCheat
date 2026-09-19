package dev.ifuto.mcsa.client.collect;

import com.google.gson.JsonObject;
import dev.ifuto.mcsa.client.McsaConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Field;

/**
 * シェーダーパックの収集（Iris / OptiFine）。
 *
 * <p>どちらの MOD も必須依存にはしたくないのでリフレクションで触る。
 * 入っていなければ {@code loader=none} になる。
 */
public final class ShaderScanner {

    private static final String IRIS_API = "net.irisshaders.iris.api.v0.IrisApi";
    private static final String OPTIFINE_SHADERS = "net.optifine.shaders.Shaders";
    private static final String[] OPTIFINE_NAME_FIELDS = {
            "shaderPackName", "currentShaderName", "shaderPackNameString"
    };

    private ShaderScanner() {
    }

    public static void collect(JsonObject root, McsaConfig config) {
        JsonObject shaders = new JsonObject();
        String loader = "none";
        Boolean enabled = null;
        String pack = null;

        try {
            Class<?> api = Class.forName(IRIS_API);
            Object instance = api.getMethod("getInstance").invoke(null);
            loader = "iris";
            Object inUse = api.getMethod("isShaderPackInUse").invoke(instance);
            if (inUse instanceof Boolean b) {
                enabled = b;
            }
            Object name = api.getMethod("getShaderPackName").invoke(instance);
            if (name != null) {
                pack = name.toString();
            }
        } catch (Throwable ignored) {
            // Iris が入っていない
        }

        if ("none".equals(loader)) {
            try {
                Class<?> shadersClass = Class.forName(OPTIFINE_SHADERS);
                loader = "optifine";
                for (String fieldName : OPTIFINE_NAME_FIELDS) {
                    try {
                        Field field = shadersClass.getDeclaredField(fieldName);
                        field.setAccessible(true);
                        Object value = field.get(null);
                        if (value != null && !value.toString().isEmpty()) {
                            pack = value.toString();
                            break;
                        }
                    } catch (Throwable ignored) {
                        // 次の候補へ
                    }
                }
            } catch (Throwable ignored) {
                // OptiFine が入っていない
            }
        }

        shaders.addProperty("loader", loader);
        if (enabled != null) {
            shaders.addProperty("enabled", enabled);
        }
        if (pack != null && !pack.isEmpty()) {
            shaders.addProperty("pack", pack);
        }
        root.add("shaders", shaders);

        if (config.collectShaderPacks) {
            root.add("shaderPackFiles",
                    FileScanner.listDirectory(FabricLoader.getInstance().getGameDir().resolve("shaderpacks"), config));
        }
    }
}
