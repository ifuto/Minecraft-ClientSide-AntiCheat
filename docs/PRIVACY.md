# プライバシーと取得情報の範囲

この MOD はプレイヤーの PC から情報をサーバーへ送る。
配布する前に、このページの内容をそのまま案内に載せること。

## 起動時の同意（consent）

Minecraft を起動すると、**プライバシィ告知の画面が出る**。選べるのは 2 つだけ。

| 操作 | 何が起きるか |
|------|--------------|
| **I Agree** | 同意を記録して普通にプレイできる。以降は出ない |
| **Decline and Quit** | 拒否を記録して **Minecraft を終了する** |

- ESC では閉じられない。同意するまでプレイは始まらない。
- 拒否したあとプレイする唯一の方法は **この MOD を抜くこと**。
  抜けばサーバー側の導入必須チェック（`enforce.mode`）で入室を断られる。
- 同意は「文面の SHA-256（先頭 12 文字）」として `config/mcsa/client.json` に残る。
  **文面が変われば再度同意を取り直す**（MOD の更新でも、運営が文面を編集しても）。
- 同意の事実（`consent.accepted` / `hash` / `at`）はレポートに乗せてサーバーにも残る。
  同意していないクライアントは `CONSENT_MISSING`（critical）になる。
- 告知文面は初回起動時に `config/mcsa/privacy-notice.txt` へ書き出される。
  **サーバー運営が自分の言葉に書き換えてよい**（書き換えると指紋が変わり、
  全プレイヤーに再同意が求められる。サーバー側 `consent.notice-hash` も合わせること）。

告知文面（英語・既定）は [`client/src/main/resources/privacy-notice.txt`](../client/src/main/resources/privacy-notice.txt)。
要点は 3 つ。

1. **Definition of Collected Data and Purpose of Use** … 収集するのは MCID、MOD 構成、
   リソースパック／シェーダー構成、クライアント側で生成したゲーム内スクリーンショットなどで、
   目的はチート検知と不正アクセス／ToS 違反の抑止・特定に限る。
2. **Non-Applicability of PII** … 収集データはゲームプレイとクライアント環境に依存する動的データであり、
   単独では実世界の個人を特定できる PII にならない。運営は MCID と X（旧 Twitter）・YouTube などの
   外部プラットフォームの登録情報を突き合わせないし、プロファイリングもしない。
3. **Data Management, Security, and Prohibition of Third-Party Disclosure** … ログは
   アンチチートとセキュリティ監査に必要な最小期間だけ管理し、期間満了後は速やかに消去する。
   法令等による開示要求を除き、第三者に開示・提供しない。

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
| 注入の痕跡 | Mixin 設定名、注入痕跡の数とサンプル、クラスローダの連鎖、怪しいスレッド名 | 送る |
| ライブラリ名 | いま読み込んでいる jar のファイル名（パスではなく名前）と件数 | 送る |
| jar の中身 | classpath 上の jar のエントリ名（クラス名）のうち、検知候補に一致したもの | 送る |
| 常時監視 | 一定間隔の状態ダイジェスト（16 文字のハッシュ値。中身は送らない） | 送る |
| 環境情報 | OS 名・アーキテクチャ・Java バージョン・Minecraft バージョン・接続先アドレス | 送る |

## 画面の取得（OP の指示があったときだけ）

**この機能は既定で無効。** サーバー側 `evidence.capture.enabled: true` のときだけ、
OP が `/ac shot` を打つと画面を送る。クライアント側の拒否ゲートは無い
（起動時のプライバシィ告知への同意が画面取得も含む同意になる。拒否した場合、
Minecraft は起動しない）。

| 項目 | 内容 |
|------|------|
| いつ | OP が明示的にコマンドを打ったとき（自動では撮らない。`/ac watch` の定期取得は `watchdog.watch.capture-interval-seconds` が 0 なら無効） |
| 何を | その瞬間のゲーム画面 1 枚（PNG/JPEG）。音声・他のウィンドウ・ファイルは含まない |
| どこへ | サーバーの `plugins/MCSA/evidence/<player>-<uuid8>/`。監査ログは `evidence-log.txt` |
| 誰が分かる | 取得の事実は監査ログに「誰が・いつ・なぜ」が残る（OP 権限の悪用も追える） |

- **対象プレイヤーの画面には何も表示しない。** チャット・トースト・撮影音・
  バニラのスクリーンショット通知、いずれも出さない。理由は「撮られた」と分かると
  証拠が残る前に抜けるから。
- その代わり、**使う前にルールで告知しておくことが前提**。
  隠し撮りを正当化するのは「事前に知らせてある」ことだけなので、
  告知なしで `evidence.capture.enabled` を true にしてはいけない。
- 保存した画像は調査目的に限り、`evidence.retention-days` で自動削除できる。

## 送られないもの（設計としてやっていない）

- プロセス一覧、ウィンドウ一覧
- 画面の録画、連続キャプチャ（取得は OP の 1 回のコマンドにつき 1 枚）
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
  "chatNotice": true,

  "collectInjection": true,        // 注入（Mixin / agent / ライブラリ名）の観測
  "knownMixinConfigs": [],         // 出所が分かっている Mixin 設定名（誤検知対策）
  "suspiciousLibraryPatterns": [], // 追加で怪しいとみなすライブラリ名
  "extraClassPatterns": [],        // 追加で探すクラス名
  "watchdogIntervalSeconds": 45,   // 常時監視の間隔（0 で停止）

  "captureFormat": "PNG",          // PNG / JPEG
  "captureMaxWidth": 1600,         // 横幅の上限（超えたら縮小）
  "captureMaxBytes": 6291456,      // これを超えたら縮小して送り直す
  "captureLog": false              // 送信を自分のログに残すか
}
```

- `reportPolicy: "NONE"` … どのサーバーにも送らない
- `reportPolicy: "ALLOWLIST"` … `allowedServers` に書いたサーバーだけに送る
- `deniedServers` … `ALL` のとき、このサーバーには送らない
- `collect*` を false にすると、その項目は送らず「`redacted` に項目名が入る」形で通知する
  （＝サーバー側には「隠した」ことが分かる。黙って欠落させることはできない）
- `collectInjection: false` … Mixin / ライブラリ / jar の中身の観測を送らない（`redacted` に `injection`）
- `watchdogIntervalSeconds: 0` … 常時監視を止める（サーバーには「送ってこない」と見える）

送信を拒否しても、導入必須のサーバー（`enforce.mode: LISTED / EVERYONE`）では入室を断られる。
それがこの仕組みの設計なので、拒否するかどうかはプレイヤー自身が決める。

## サーバー管理者の責任

- **告知する**: MOTD・Discord・参加案内に「このサーバーは導入 MOD 一覧を収集する」と書く。
  クライアント MOD も初回接続時にチャットで 1 回告知する。
- **画面取得を使うなら必ず告知する**: 利用規約に「不正調査のためにゲーム画面を取得・保存する
  場合がある」ことを明記してから `evidence.capture.enabled: true` にする。
  画面に通知を出さない設計なので、告知が唯一の歯止めになる。
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
- **画面の取得は性質が違う。** ゲーム画面にはチャット、他のプレイヤーの名前、
  場合によっては個人情報が映り込む。日本では「不正調査のための取得」でも
  あらかじめ規約で知らせ、目的外に使わないことが求められる（個人情報保護法 18 条の
  利用目的の通知・公表）。`evidence.retention-days` で保持期間を決め、
  監査ログ（`evidence-log.txt`）を消さないこと。
