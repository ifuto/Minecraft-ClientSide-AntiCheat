# Multi Build / Multi Build (Paper) への引数（push トリガー用）
# このファイルを変更して push すると、両ワークフローがここで指定した値で走る。
#
# 注意: Fabric Loom 1.18 は Gradle の実行に JDK 25 が必要（MC 26.x 世代の要件）。
#       成果物のバイトコードは Java 21 向け（client/build.gradle の options.release=21）なので、
#       プレイヤー側は Java 21 のままで動く。
#
# 今の設定: クライアント MOD（client/）をビルド（StackMapTable 修正を難読化込みで検証）。
#           admin は build: 19、server は build: 19（Paper 起動確認込み）で検証済み。
#           3 つを一度に見たい場合は ci/build-check.yml を .github/workflows/ に置く。

workdir: client
artifact_path: client/build/libs/*.jar
java: 25
server_test: false

# ビルド番号（この行を変えると push トリガーが走る）
build: 19
