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

# ※ -dontpreverify は絶対に付けないこと。
#   Java 6+ のクラスファイルは分岐を持つメソッドに StackMapTable が必須で、
#   これを無効化すると jar はビルドできるが、起動直後に
#   「java.lang.VerifyError: Expecting a stackmap frame」で Minecraft が落ちる
#   （実際に build 16 以前の -obf.jar で発生した）。
#   検証は build.gradle の verifyStackMaps がビルド時に機械的に行う。

# 例外スタックトレースからソース情報を消す
-renamesourcefileattribute ''
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# --- fabric.mod.json の entrypoints から名前で呼ばれる ---
-keep class * implements net.fabricmc.api.ClientModInitializer { *; }
-keep class * implements net.fabricmc.api.ModInitializer { *; }
-keep class * implements net.fabricmc.api.DedicatedServerModInitializer { *; }

# --- Screen のサブクラス（同意画面・証拠ビューア）。Override がリネームされると
#     開いた瞬間に AbstractMethodError になる（class_437 = Screen）---
-keep class * extends net.minecraft.class_437 { *; }

# --- CustomPayload 実装（intermediary 名 net.minecraft.class_8710）---
# Id/Type/Codec がジェネリクス経由で参照されるため丸ごと残す
-keep class * implements net.minecraft.class_8710 { *; }

# --- 生成された鍵素材（呼び出しは直接なので消えても困らないが念のため）---
-keep class dev.ifuto.mcsa.client.gen.KeyMaterial { *; }

# java.base をライブラリに渡していない（JDK 25 のクラスファイルを ProGuard が読めない）ため、
# 未解決参照の警告はすべて抑止する。リネームのみなので成果物には影響しない。
-dontwarn **
-ignorewarnings
