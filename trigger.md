# Multi Build / Multi Build (Paper) への引数（push トリガー用）
# このファイルを変更して push すると、両ワークフローがここで指定した値で走る。
#
# 注意: Fabric Loom 1.18 は Gradle の実行に JDK 25 が必要（MC 26.x 世代の要件）。
#       成果物のバイトコードは Java 21 向け（client/build.gradle の options.release=21）なので、
#       プレイヤー側は Java 21 のままで動く。
#
# 今の設定: クライアント MOD（client/）をビルド（同意画面の表示タイミング修正、1.0.26）。
#           admin は build: 24、server は build: 24（Paper 起動確認込み）で検証済み。
#           3 つを一度に見たい場合は ci/build-check.yml を .github/workflows/ に置く。

workdir: client
artifact_path: client/build/libs/*.jar
java: 25
server_test: false

# ビルド番号（この行を変えると push トリガーが走る）。
# ★ 同時に client/gradle.properties の mcsa_version を「1.0.<同じ番号>」に上げること。
#   （成果物のファイル名と MOD 一覧のバージョンがビルド毎に変わるので、
#    古い jar の混同が起きなくなる）
build: 26
