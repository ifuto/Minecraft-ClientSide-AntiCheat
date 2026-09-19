# Multi Build / Multi Build (Paper) への引数（push トリガー用）
# このファイルを変更して push すると、両ワークフローがここで指定した値で走る。
#
# 注意: Fabric Loom 1.18 は Gradle の実行に JDK 25 が必要（MC 26.x 世代の要件）。
#       成果物のバイトコードは Java 21 向け（client/build.gradle の options.release=21）なので、
#       プレイヤー側は Java 21 のままで動く。
#
# 今の設定: OP 用 MOD（admin/）をビルド。
#           client / server は build: 9 / 10 で検証済み
#           （server は Paper 1.21.11 の起動＋Enable まで確認済み）。
#           3 つを一度に見たい場合は ci/build-check.yml を .github/workflows/ に置く。

workdir: admin
artifact_path: admin/build/libs/*.jar
java: 25
server_test: false

# ビルド番号（この行を変えると push トリガーが走る）
build: 11
