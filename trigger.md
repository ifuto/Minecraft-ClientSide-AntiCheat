# Multi Build / Multi Build (Paper) への引数（push トリガー用）
# このファイルを変更して push すると、両ワークフローがここで指定した値で走る。
#
# 注意: Fabric Loom 1.18 は Gradle の実行に JDK 25 が必要（MC 26.x 世代の要件）。
#       成果物のバイトコードは Java 21 向け（client/build.gradle の options.release=21）なので、
#       プレイヤー側は Java 21 のままで動く。
#
# 今の設定: クライアント MOD（client/）をビルド（同意画面が「背景だけで何も出ない」
#           のを根本修正、1.0.32）。
#           本体原因: 1.21.11 で Screen の client/textRenderer/executor が final になり、
#           旧 Screen(Text) コンストラクタでは null が残る。renderBackground が
#           this.client 参照で NPE → 描画が毎フレーム中断されていた（26〜31 の実態）。
#           新 ctor Screen(MinecraftClient, TextRenderer, Text) に変更
#           （バニラ・移行済み OSS と同じ形）。背景/文字/ボタンを別々に try/catch して
#           どれか失敗しても残りは描くようにした。admin の EvidenceScreen も同じ修正。
#           アルファ付き色 (0xFF…) とピクセル幅折り返しは 31 から継続。
#           HMAC 鍵の成果物同梠と MCSA_HMAC_SEED 対応は build 30 で入済み。
#           3 つを一度に見たい場合は ci/build-check.yml を .github/workflows/ に置く。

workdir: client
artifact_path: client/build/libs/*
java: 25
server_test: false

# ビルド番号（この行を変えると push トリガーが走る）。
# ★ 同時に client/gradle.properties の mcsa_version を「1.0.<同じ番号>」に上げること。
#   （成果物のファイル名と MOD 一覧のバージョンがビルド毎に変わるので、
#    古い jar の混同が起きなくなる）
build: 32
