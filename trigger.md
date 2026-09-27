# Multi Build / Multi Build (Paper) への引数（push トリガー用）
# このファイルを変更して push すると、両ワークフローがここで指定した値で走る。
#
# 注意: Fabric Loom 1.18 は Gradle の実行に JDK 25 を必要とします（MC 26.x 世代の要件）。
#       成果物のバイトコードは Java 21 向け（lessping/build.gradle の options.release=21）。
#
# 今の設定: LessPing-NArena 0.2.0 をビルド（lessping/ = MOD + Paper 中継プラグイン）。
#           「narena」と打つと P2P トンネルへ差し替えて ping を下げるやつ。
#           ビルド時に LPX（reliable-UDP）のループバックテスト（12% ロスで双方向 2MB）
#           が走る。失敗したらビルドが落ちる。
#
#           ★ これは MCSA（Better NArena）とは別物。client/ の mcsa_version は
#             今回は触っていない（1.0.32 のまま）。MCSA をビルドするときは
#             workdir を client に戻し、client/gradle.properties の mcsa_version を
#             trigger の build 番号に合わせること。
#
# 前回: build 33 = mcsa-server 1.0.33（UNVERIFIED 設定起因キックの修正）。
#           build 32 = mcsa-client 1.0.32（同意画面の新 ctor 修正）。

workdir: lessping
artifact_path: lessping/**/build/libs/*.jar
java: 25
server_test: false

# ビルド番号（この行を変えると push トリガーが走る）。
# lessping をビルドするときは lessping/gradle.properties の lessping_version を
# バージョンアップすること（mod / relay 共通）。
build: 38
