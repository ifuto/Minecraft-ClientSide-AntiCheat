# Multi Build / Multi Build (Paper) への引数（push トリガー用）
# このファイルを変更して push すると、両ワークフローがここで指定した値で走る。
#
# 注意: Fabric Loom 1.18 は Gradle の実行に JDK 25 が必要（MC 26.x 世代の要件）。
#       成果物のバイトコードは Java 21 向け（client/build.gradle の options.release=21）なので、
#       プレイヤー側は Java 21 のままで動く。
#
# 今の設定: クライアント MOD（client/）をビルド（同意画面・証拠ビューアの文字が
#           表示されないのを修正、1.0.31）。原因は 1.21.2+ のテキスト描画が色を
#           完全 ARGB として解釈するようになったため（アルファ無しの 0xC8C8C8 等は
#           完全透明）。すべて 0xFF…… 付きに修正し、折り返しもピクセル幅ベースに変更。
#           HMAC 鍵の成果物同梠と MCSA_HMAC_SEED 対応は build 30 で入済み。
#           admin の EvidenceScreen も同じ修正済み（admin 再ビルドは次回 admin 回すとき）。
#           3 つを一度に見たい場合は ci/build-check.yml を .github/workflows/ に置く。

workdir: client
artifact_path: client/build/libs/*
java: 25
server_test: false

# ビルド番号（この行を変えると push トリガーが走る）。
# ★ 同時に client/gradle.properties の mcsa_version を「1.0.<同じ番号>」に上げること。
#   （成果物のファイル名と MOD 一覧のバージョンがビルド毎に変わるので、
#    古い jar の混同が起きなくなる）
build: 31
