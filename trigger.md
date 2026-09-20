# Multi Build / Multi Build (Paper) への引数（push トリガー用）
# このファイルを変更して push すると、両ワークフローがここで指定した値で走る。
#
# 注意: Fabric Loom 1.18 は Gradle の実行に JDK 25 を必要とします（MC 26.x 世代の要件）。
#       成果物のバイトコードは Java 21 向け（client/build.gradle の options.release=21）なので、
#       プレイヤー側は Java 21 のままで動く。
#
# 今の設定: サーバー（Paper プラグイン）をビルド（1.0.33）。
#           「入れてもいない禁止MODがあると言われてキックされる」の修正:
#           UNVERIFIED（HMAC 鍵の設定不一致）と CLIENT_TAMPERED（ピン留め hash の
#           更新忘れ）は設定起因なのでポリシー違反キックの対象から除外。
#           キック時にはフラグ名を併記し、鍵不一致は keyId つきでコンソールに案内。
#           ★クライアントは 1.0.32 のまま（変更なし。client/gradle.properties 参照）。
#           クライアントをビルドするときは workdir を client に戻すこと。
#
# 前回: build 32 = client 1.0.32（同意画面の新 ctor 修正）。
#           HMAC 鍵の成果物同梠と MCSA_HMAC_SEED 対応は build 30 で入済み。

workdir: server
artifact_path: server/build/libs/*
java: 25
server_test: true

# ビルド番号（この行を変えると push トリガーが走る）。
# ★ client をビルドするときは client/gradle.properties の mcsa_version を
#   「1.0.<同じ番号>」に上げること（成果物のファイル名と MOD 一覧のバージョンが
#   ビルド毎に変わるので、古い jar の混同が起きなくなる）。
#   server のみのビルドでは server/gradle.properties の mcsa_version を合わせる。
build: 33
