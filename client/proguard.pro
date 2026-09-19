# MCSA クライアント MOD の ProGuard 設定
# remapJar 出力（Minecraft 参照が intermediary 名になった jar）に適用する。
#
# 方針:
#   - クラス/メソッド/フィールドのリネームのみ行う（難読化）。
#   - 縮約(shrink)と最適化(optimize)は、エントリーポイントや Mixin を
#     誤って消すリスクが高いため無効化する。
#   - JSON から名前で参照されるクラスは必ず -keep する。

-dontoptimize
-dontshrink
-dontpreverify

# 例外スタックトレースからソース情報を消す
-renamesourcefileattribute ''
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# --- fabric.mod.json の entrypoints から名前で呼ばれる ---
-keep class * implements net.fabricmc.api.ClientModInitializer { *; }
-keep class * implements net.fabricmc.api.ModInitializer { *; }
-keep class * implements net.fabricmc.api.DedicatedServerModInitializer { *; }

# --- CustomPayload 実装（intermediary 名 net.minecraft.class_8710）---
# Id/Type/Codec がジェネリクス経由で参照されるため丸ごと残す
-keep class * implements net.minecraft.class_8710 { *; }

# --- 生成された鍵素材（呼び出しは直接なので消えても困らないが念のため）---
-keep class dev.ifuto.mcsa.client.gen.KeyMaterial { *; }

# 任意依存（Iris / OptiFine はリフレクションのみ）や未解決参照の警告を潰す
-dontwarn **
