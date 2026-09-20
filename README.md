# Minecraft Client-Side AntiCheat (MCSA)

クライアント MOD（Fabric）とサーバープラグイン（Paper）を **両方入れて** 使うアンチチート。

- 導入者の **MOD / リソースパック / シェーダーの一覧** を OP がコマンドで確認できる
- MOD の改ざん・**MOD 名の偽装**・jar 差し替えの検知
- **注入の検知**（Mixin / javaagent / 改造クラスローダ / 怪しいライブラリ名 / jar の中身）
- **常時監視**（ウォッチドッグ）で「起動時だけ正常な顔をする」タイプも取りこぼさない
- **証拠の取得**: OP のコマンド 1 発で対象の画面を取得（**対象の画面には何も表示しない**）
- サーバー権限で測る行動検知（リーチ、キルオーラ、自動クリック、高速破壊、浮遊、速度）
- 導入必須は「全員」か「指定した人だけ」を選べる（既定は **指定した人だけ**）
- 起動時に**プライバシィ告知（英語）を出して同意を取る**。拒否したら Minecraft を終了させる
- 検知候補（クラス名・ライブラリ名）は**サーバーから動的に追加**できる（MOD の再配布不要）

> **前提**: クライアントとサーバーは同じ権限なので、「クライアントの自己申告」を
> 数学的に信じる方法はありません。MCSA は ①申告させて状況証拠にする、
> ②サーバーに届いたパケットそのものを測る、の 2 段構えです。判定の重みは常に ② > ①。
> 詳細は [`docs/DESIGN.md`](docs/DESIGN.md)。

---

## 構成

| 成果物 | 場所 | 説明 |
|--------|------|------|
| `mcsa-client-<ver>.jar`  | `client/build/libs/` | Fabric MOD（**プレイヤー全員**に入れる） |
| `mcsa-server-<ver>.jar`  | `server/build/libs/` | Paper プラグイン（サーバー） |
| `mcsa-admin-<ver>.jar`   | `admin/build/libs/`  | Fabric MOD（**運営だけ**に入れる。`/acadmin`） |

対応: Minecraft **1.21.11** / Fabric Loader 0.19.5 / Fabric API 0.141.6 / Paper 1.21.11。
実行には Java 21（**ビルド**には JDK 25 + Gradle 9.7.1 が必要。下記参照）。

---

## 1. ビルド

### GitHub Actions で（推奨）

このリポジトリの `Multi Build` / `Multi Build (Paper)` ワークフローが
[`trigger.md`](trigger.md) の値を読んでビルドする。

```markdown
# trigger.md
workdir: client                      # client か server
artifact_path: client/build/libs/*.jar
java: 25                             # Loom 1.18 は JDK 25 が必要
```

`trigger.md` を編集して push すると両ワークフローが走る（成果物は Actions の Artifacts へ）。

| 対象 | workdir | artifact_path |
|------|---------|---------------|
| クライアント MOD | `client` | `client/build/libs/*.jar` |
| サーバープラグイン | `server` | `server/build/libs/*.jar` |
| OP 用 MOD | `admin` | `admin/build/libs/*.jar` |

クライアント MOD は **「Better NArena」** という名前・アイコンで配布する
（MOD 一覧を見た程度では対チェート用と分からない。内部 ID・チャンネル・設定パスは
`mcsa` のままなので既存の仕組みに影響しない）。未導入で入ろうとしたプレイヤーには
「参加するには Better NArena の導入が必要です。」とキックされる（`enforce.kick-message`）。

3 つを一度に見たい場合は [`ci/build-check.yml`](ci/build-check.yml) を
`.github/workflows/` にコピーする（client / admin / server を並列ビルドする）。

```bash
cp ci/build-check.yml .github/workflows/build-check-2.yml   # 中身を差し替える
```

GitHub App の権限制約でこちらは `.github/workflows/` を push できないため、
**コピーは手動で**お願いします。古い中身のままだと次の 2 つで落ちます
（どちらも `ci/build-check.yml` では修正済み）。

| 症状 | 原因 |
|------|------|
| `Client MOD` の「成果物の中身を確認」が失敗 | `mcsa-client-*.jar` が難読化版 (`-obf.jar`) にも一致し、`unzip` に jar を 2 本渡して exit 11 |
| `OP 用 MOD` のビルドが失敗しているのに success 扱い | `gradle … \| tee` は `tee` の終了コードを返す（`set -o pipefail` を追加済み） |

サーバープラグインは `server_test: true` を追加すると、CI 上で実際に Paper を起動して
プラグインが Enable されるかまで確認する。

### ローカルで

```bash
./gradlew -p client build     # → client/build/libs/mcsa-client-1.0.0.jar（+ -obf.jar）
./gradlew -p server build     # → server/build/libs/mcsa-server-1.0.0.jar
./gradlew -p admin  build     # → admin/build/libs/mcsa-admin-1.0.0.jar（OP 用）
```

- `./gradlew` は Gradle 9.7.1 を自動ダウンロードする軽量ブートストラップ
  （`gradle/wrapper/gradle-wrapper.jar` はバイナリなので Git に入れていない。
  正式な Wrapper が欲しければ `gradle wrapper --gradle-version 9.7.1` を実行）。
- クライアントのビルドには **JDK 25** が必要（Fabric Loom 1.18.2 の要件）。
  生成される jar のバイトコードは Java 21 向けなので、プレイヤー側は Java 21 のままで動く。

### 難読化

クライアントは既定で **ProGuard による難読化**（クラス／メソッド／フィールドのリネーム）を行う。

```bash
./gradlew -p client build                     # mcsa.obfuscate=true（既定）
./gradlew -p client build -Pmcsa.obfuscate=false   # 無効化
```

- 通常版: `mcsa-client-<バージョン>.jar`
- 難読化版: `mcsa-client-<バージョン>-obf.jar` ← **配布するのはこちら**
- **バージョンはビルド毎に上げる**（`trigger.md` の `build:` と同じ番号 → `1.0.25` 等）。
  jar のファイル名・MOD 一覧のバージョン・`/ac info` の modVersion で
  「どのビルドか」が必ず分かるようにするため（古い jar の混同防止）。
- 設定は [`client/proguard.pro`](client/proguard.pro)。エントリーポイントと
  `CustomPayload` 実装は `-keep` してあり、ビルド時に `verifyJar` タスクが
  「jar にエントリーポイントのクラスが入っているか」を検証する。
- さらに `verifyStackMaps` が「分岐を持つ全メソッドに StackMapTable があるか」を
  検証する（かつて `-dontpreverify` 付きでビルドした -obf.jar が起動直後に
  `VerifyError: Expecting a stackmap frame` で落ちた事故の再発防止）。
  **build 22 以前の `-obf.jar` は使わないこと**（build ≤16 は起動時 VerifyError、
  build ≤22 は接続直後に `AbstractMethodError`（PacketCodec.decode がリネームされる）で切断）。

### HMAC 鍵

ビルドのたびに 32 バイトの鍵を生成（`client/hmac-key.txt` があれば再利用）し、
XOR マスクした int 配列としてソースを生成する（平文は jar に残らない）。

鍵はビルドログと `client/build/libs/mcsa-client-<ver>-hmac-key.txt` に出るので、
その値をサーバーの `plugins/MCSA/config.yml` → `hmac.key` に貼る。

```yaml
hmac:
  enabled: true
  key: "0f3a…（64 文字の 16 進）"
```

鍵が未設定だとレポートの改ざんを検知できず、全レポートが `UNVERIFIED` 扱いになる
（起動時に WARNING が出る）。

---

## 2. インストール

**サーバー**: `mcsa-server-<ver>.jar` を `plugins/` へ。起動すると `plugins/MCSA/config.yml` が出る。

**クライアント**: `mcsa-client-<ver>-obf.jar` を `mods/` へ（Fabric Loader + Fabric API 必須）。
初回起動で `config/mcsa/client.json` が作られる。

---

## 3. 使い方

### 起動時の同意（プレイヤー側）

Minecraft を起動するとプライバシィ告知が出る。**I Agree** で続行、
**Decline and Quit** で Minecraft が終了する（＝MOD を抜かない限りプレイできない）。

- 文面: `config/mcsa/privacy-notice.txt`（初回起動時に jar から書き出される。**編集可**）
- 文面を変えると指紋が変わるので、次回の起動で **全員に同意を取り直す**
- 同意の記録（指紋＋時刻）はレポートに乗ってサーバーにも残る
  （未同意は `CONSENT_MISSING`、文面違いは `CONSENT_NOTICE_MISMATCH`）

サーバー側で「運営が配っている文面に同意しているか」を強制するなら
`consent.notice-hash` に指紋を入れる（クライアントの `config/mcsa/client.json` の
`consentHash` で確認できる）。

### 導入必須の対象を決める

`enforce.mode` は既定で `LISTED`（＝指定した人だけ必須）。

```
/ac policy mode LISTED          # OFF / LISTED / EVERYONE
/ac policy require add Steve    # Steve だけ必須にする
/ac policy require list
```

入室後 5 秒（`enforce.grace-ticks`）で HELLO が届いていなければ
`enforce.action`（既定 `KICK`）を実行する。

### 一覧を見る

```
/ac status                 # オンライン全員の導入状況
/ac mods Steve             # MOD 一覧（判定ラベル付き）
/ac packs Steve            # リソースパック
/ac shaders Steve          # シェーダー
/ac info Steve             # 整合性・注入観測・常時監視・違反レベル
/ac flags Steve            # 検知履歴
/ac refresh Steve          # レポート再要求
/ac scan Steve             # 新しい nonce で申告し直させる（録画レポートの使い回し防止）
```

すべて [`docs/COMMANDS.md`](docs/COMMANDS.md)。権限は `mcsa.admin`（既定 OP）。

### 注入を疑ったら

```
/ac info Steve                       # 注入観測の要約（mixin / injected / libraries / jarScan）
/ac policy probe add prefix:me.rhys  # 検知候補を追加（全クライアントに即反映）
/ac policy probe add contains:xray
/ac policy probe list
```

検知候補はサーバーが配るので、**新しいチートが出ても MOD を配り直す必要はない**。

### 「起動時だけ綺麗」な相手

クライアントは 45 秒（±20%）ごとに状態ダイジェストを送り続ける。
サーバーは「内容が変わった」「送ってこなくなった」の両方を拾う。

```
/ac watch Steve 300        # 5 分間、高頻度監視（＋設定次第で定期画面取得）
/ac watch Steve off
```

### 証拠を取る（画面の取得）

```
/ac shot Steve 通報対応     # 対象の画面には何も表示されない
/ac evidence Steve         # 保存先の一覧（plugins/MCSA/evidence/）
```

- **既定は無効**。`evidence.capture.enabled: true` にすると使える。
- 有効化の前に **サーバールール／利用規約で告知** すること（[`docs/PRIVACY.md`](docs/PRIVACY.md)）。
  画面に通知を出さない設計なので、告知が唯一の歯止めになる。
- 保存先には「誰が・いつ・なぜ」の sidecar JSON と、追記専用の監査ログ
  `evidence-log.txt` が残る（OP 権限の悪用も追える）。

### 運営は OP 用 MOD で

`mcsa-admin-<ver>.jar` を運営のクライアントに入れると、コンソールを開かなくても
自分の画面から `/acadmin <コマンド>` でサーバーの `/ac` を実行できる。
権限判定はサーバー側なので、MOD だけ入れても権限がなければ何もできない。

### MOD 名の偽装を捕まえる（ピン留め）

```
/ac policy pin Steve sodium        # 信頼できるプレイヤーの sodium のハッシュを固定
/ac policy pin-client Steve        # 配布しているクライアント jar のハッシュを固定
```

以降、`sodium` を名乗るのにハッシュが違う MOD は `MOD_ID_SPOOF:sodium`（critical）。
`fabric.mod.json` を持たない jar は `JAR_WITHOUT_MANIFEST:<file>`（critical）。

---

## 4. 注意

- **告知する**: この MOD はプレイヤーの PC から MOD / リソースパック / シェーダーの一覧を
  サーバーへ送る。MOTD や Discord で必ず案内すること。
  送る範囲はプレイヤー側で絞れる（`docs/PRIVACY.md`）。
- **いきなりキックしない**: `checks.cancel` と `checks.kick-vl` は既定で無効。
  まずアラートだけ出して誤検知を確認すること。
- **自己申告は証拠ではない**: `docs/DESIGN.md` §5 に「各対策の破られ方」を書いてある。
- **画面取得は告知が前提**: 対象に通知を出さない設計なので、規約に書いてから使うこと。
  既定は無効（`evidence.capture.enabled: false`）。
  ただし **起動時の同意画面で告知は行われる**（`privacy-notice.txt`。拒否すると
  Minecraft が終了するので、同意したプレイヤーだけが入れる）。

---

### 動かないとき（トラブルシューティング）

| 症状 | 原因と対処 |
|------|-----------|
| MOD を入れているのに「参加するには Better NArena の導入が必要です。」でキックされる | **プライバシー系 MOD（OpSec / PrivacyFix 等）がチャンネル隠蔽（Channel Spoofing）をしている**。Paper はクライアントが `minecraft:register` で登録宣言してきたチャンネルにしか plugin message を送らないため、`mcsa:challenge` が届かず「未導入」と同じ扱いになる。該当 MOD の設定で `mcsa` をホワイトリストに入れるか、チャンネル隠蔽をオフにする。**サーバー側からは「隠している」と「入れていない」の区別はつかない（隠蔽の目的どおり）**ので、導入必須サーバーでは OpSec 等は実質使えない |
| レポートは届くが `UNVERIFIED` ★ が付く | `hmac.key` がクライアントのビルドと不一致。**CI はビルドごとに新しい鍵を生成する**ので、jar を差し替えるたびに同梱の `mcsa-client-<ver>-hmac-key.txt` の値を `config.yml` に反映して `/ac reload` |
| 起動直後に `VerifyError` / 接続直後に `AbstractMethodError` で落ちる | 古い `-obf.jar`（build ≤22）。必ず最新ビルドを使う（バージョン番号で確認） |

## 5. リポジトリ

```
client/                    Fabric MOD（MC 1.21.11 / Yarn / Java 21）
admin/                     OP 用 Fabric MOD（/acadmin）
server/                    Paper プラグイン（paper-api 1.21.11）
docs/DESIGN.md             設計・脅威モデル・改ざん対策の各層と、その破られ方
docs/PROTOCOL.md           通信プロトコル（チャンネル、バイト配置、JSON、HMAC）
docs/COMMANDS.md           /ac コマンド一覧
docs/PRIVACY.md            収集する情報・しない情報・プレイヤー側の設定
tools/verify_protocol.py   クライアント／サーバーの契約の整合チェック
ci/build-check.yml         追加 CI（.github/workflows/ にコピーして使う）
trigger.md                 Multi Build ワークフローへの引数
```

### 開発時に確認していること

```bash
python3 tools/verify_protocol.py    # チャンネル名・レポート JSON キー・config キーの整合
python3 tools/check_java_syntax.py  # 括弧・プロジェクト内 import・plugin.xxx() の実在
./gradlew -p client build           # コンパイル + verifyJar（成果物の中身）+ 難読化
./gradlew -p admin  build           # コンパイル + verifyJar
./gradlew -p server build           # コンパイル
```

`ci/build-check.yml` はこれを 1 本にまとめたもの（失敗時は javac のエラーを
チェックのアノテーションに出すので、Actions のログを見られなくても原因が分かる）。
GitHub App の権限制約で `.github/workflows/` へは push できないため、
使う場合は手動でコピーしてほしい。

## ライセンス

MIT
