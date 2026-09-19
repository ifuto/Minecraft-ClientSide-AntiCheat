# プライバシーと取得情報の範囲

この MOD はプレイヤーの PC から情報をサーバーへ送る。
配布する前に、このページの内容をそのまま案内に載せること。

## 送られるもの

サーバーが `mcsa:challenge` を送ってきたときだけ、以下を送る。

| 項目 | 内容 | 既定 |
|------|------|------|
| MOD | ロード済み MOD の id / 表示名 / バージョン | 送る |
| MOD（ファイル） | `mods/` 内の jar のファイル名・サイズ・SHA-256、`fabric.mod.json` の有無 | 送る |
| リソースパック | 有効なパックの id、`resourcepacks/` 内の名前・サイズ・SHA-256 | 送る |
| シェーダー | Iris / OptiFine の種別とパック名、`shaderpacks/` 内の名前・サイズ・SHA-256 | 送る |
| 自己整合性 | この MOD 自身の jar 名・サイズ・SHA-256・エントリ指紋 | 送る |
| 実行時環境 | JVM 引数のうち `-javaagent` 等の疑わしいもの、デバッガの有無、クラスローダ名 | 送る |
| クラス探索 | サーバーが指定したクラス名が存在するかどうか（クラス名のみ。中身は送らない） | 送る |
| 環境情報 | OS 名・アーキテクチャ・Java バージョン・Minecraft バージョン・接続先アドレス | 送る |

## 送られないもの（設計としてやっていない）

- プロセス一覧、ウィンドウ一覧
- スクリーンショット、画面の録画
- キーボード入力、マウス入力、クリップボード
- ユーザー名、Microsoft アカウント情報、認証トークン
- `~/.minecraft` の外にあるファイル
- `options.txt`、ワールドデータ、チャット履歴、スクリーンショットフォルダ

ファイルのハッシュは SHA-256 の値（64 文字の 16 進文字列）だけで、ファイルの中身は送らない。
既定では 64 MB を超えるファイルのハッシュは計算しない（`hashMaxBytes`）。

## プレイヤー側のコントロール

`config/mcsa/client.json`

```jsonc
{
  "reportPolicy": "ALL",          // ALL / ALLOWLIST / NONE
  "deniedServers": ["play.example.com"],
  "allowedServers": [],
  "collectMods": true,
  "collectResourcePacks": true,
  "collectShaderPacks": true,
  "collectJvmArgs": true,
  "hashFiles": true,
  "hashMaxBytes": 67108864,
  "chatNotice": true
}
```

- `reportPolicy: "NONE"` … どのサーバーにも送らない
- `reportPolicy: "ALLOWLIST"` … `allowedServers` に書いたサーバーだけに送る
- `deniedServers` … `ALL` のとき、このサーバーには送らない
- `collect*` を false にすると、その項目は送らず「`redacted` に項目名が入る」形で通知する
  （＝サーバー側には「隠した」ことが分かる。黙って欠落させることはできない）

送信を拒否しても、導入必須のサーバー（`enforce.mode: LISTED / EVERYONE`）では入室を断られる。
それがこの仕組みの設計なので、拒否するかどうかはプレイヤー自身が決める。

## サーバー管理者の責任

- **告知する**: MOTD・Discord・参加案内に「このサーバーは導入 MOD 一覧を収集する」と書く。
  クライアント MOD も初回接続時にチャットで 1 回告知する。
- **目的外に使わない**: 収集した情報は不正利用の調査に限る。
- **公開しない**: `/ac` の結果は OP だけに見える権限（`mcsa.admin`）にしてある。
  レポートの JSON は `plugins/MCSA/reports/<uuid>.json` に平文で保存されるので、
  サーバーのバックアップと一緒に管理すること。不要なら `storage.save-reports: false`。
- **開示できるようにする**: プレイヤーから「何が送られているか」を聞かれたら、
  このページと `config/mcsa/client.json` を案内する。

## 法的な位置づけ

- Mojang / Microsoft の EULA は、サーバーが独自のルール（MOD 導入の必須化を含む）を
  定めることを妨げない。ただし **有料での販売や、Mojang の資産の再配布** は禁止されている。
  この MOD 自体を有料で配布しないこと。
- 収集する情報は「プレイヤーの PC にあるファイルの一覧」であり、
  個人情報（氏名・住所・連絡先）ではない。ただし UUID とプレイ内容は
  地域によっては個人関連情報になり得るので、保存期間とアクセス権を決めておくこと。
