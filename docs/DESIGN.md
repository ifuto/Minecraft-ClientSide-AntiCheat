# MCSA 設計書

クライアント MOD（Fabric）とサーバープラグイン（Paper）の両方を導入する形のアンチチート。

- クライアント: `client/` … Fabric MOD `mcsa`（MC 1.21.11 / Yarn / Java 21）
- サーバー: `server/` … Paper プラグイン `MCSA`（paper-api 1.21.11 / Java 21）

---

## 1. 前提と割り切り

クライアントとサーバーが **同じ権限**（どちらも自分の環境を自由にいじれる）である以上、
「クライアントが申告した情報」を数学的に信じる方法はない。チート使用者本人が
MOD をパッチして、きれいな一覧を返す偽レポートを送るのは原理的に可能。

なので MCSA の設計思想は次の 2 本立て。

1. **申告させる（状況証拠）**
   導入 MOD / リソースパック / シェーダー / 実行時環境を収集し、
   OP がコマンドで確認できるようにする。「怪しい」「この MOD は許可していない」を
   人間が判断するための材料。
2. **サーバー権限で測る（動かぬ証拠）**
   リーチ、攻撃角度、クリック間隔、破壊速度、浮遊、移動速度。
   これらはサーバーに届いたパケットそのものなので、クライアント側で何を偽装しても
   ごまかせない（ごまかそうとすると「物理的にありえない値」を送るしかない）。

**判定の重みは常に 2 > 1。** 1 は「足切り」と「捜査のきっかけ」、2 が「処分」の根拠。

対象とするのは「高度なカスタムチート」ではなく、
配布されているチートクライアントや MOD をそのまま使う層。
この層に対しては、下記の改ざん対策は十分すぎるほど効く（後述）。

---

## 2. 導入必須ポリシー

`config.yml` の `enforce.mode`。

| mode       | 挙動                                                       |
|------------|------------------------------------------------------------|
| `OFF`      | 導入を求めない。レポートは受け取る（情報収集だけ）          |
| `LISTED`   | `required-players` に載っている人だけ導入必須（**既定**）    |
| `EVERYONE` | 全員導入必須                                                |

- 対象者かどうかは `/ac policy require add <name>` で実行時に追加できる（UUID でも名前でも可）。
- 入室後 `enforce.grace-ticks`（既定 100 tick = 5 秒）待って HELLO が届いていなければ
  `enforce.action`（`KICK` / `WARN` / `LOG`）を実行する。
- `mcsa.bypass` 権限を持つプレイヤーは導入必須と行動検知の両方の対象外。

「MOD 導入済み」の判定は **HELLO かレポートのどちらかを受け取ったか** で行う。
Bukkit 側の `Player#getListeningPluginChannels()`（＝クライアントが受信登録した
チャンネル一覧に `mcsa:challenge` があるか）も補助的に使えるが、こちらは
プロキシ構成で欠けることがあるので一次判定には使っていない。

---

## 3. ハンドシェイクの流れ

```
   クライアント                                     サーバー
        │                                              │
        │                                   入室 (PlayerJoinEvent)
        │                                              │  セッション発行（sessionId + nonce）
        │            mcsa:challenge (S2C)              │
        │ ◀──────────────────────────────────────────  │  delay-ticks (20t) 後
        │                                              │
        │  mcsa:hello (C2S)                            │   → 導入済み判定が確定
        │ ───────────────────────────────────────────▶ │
        │                                              │
        │  （非同期で収集: mods/ resourcepacks/ …）    │
        │                                              │
        │  mcsa:report ×N (gzip 断片)                  │
        │ ───────────────────────────────────────────▶ │  再構成
        │  mcsa:seal (HMAC-SHA256)                     │
        │ ───────────────────────────────────────────▶ │  検証 → 判定 → 保存 → アラート
        │                                              │
        │                                 入室 + grace-ticks で導入必須を適用
```

- チャレンジの `nonce` は毎回ランダム。HMAC に混ぜるのでリプレイ（録画したレポートの使い回し）が無効。
- レポートは gzip した JSON を 16 KiB 断片に分割して送る（`mcsa:report`）。
  Bukkit のプラグインメッセージには歴史的に 32767 バイトの上限があるので、余裕を持たせている。
- 全断片が揃ったあと `mcsa:seal` で HMAC を送り、サーバーは再構成したバイト列を検証してから受理する。

詳細なバイト配置は [`PROTOCOL.md`](PROTOCOL.md)。

---

## 4. 収集する情報

| 区分           | 内容                                                                 |
|----------------|----------------------------------------------------------------------|
| MOD            | ロード済み MOD の id / 名前 / バージョン、`mods/` 内の jar 名・サイズ・SHA-256、`fabric.mod.json` の有無、同梱 MOD |
| リソースパック | 有効なパックの id 一覧、`resourcepacks/` の中身（名前・サイズ・SHA-256） |
| シェーダー     | Iris / OptiFine の種別・パック名・適用中か、`shaderpacks/` の中身     |
| 自己整合性     | 自分自身の jar の SHA-256、jar 内エントリの指紋、難読化ビルドか       |
| 実行時プローブ | `-javaagent` 等の JVM 引数、デバッガ、クラスローダ名、指定クラスの存在 |

**やらないこと**: プロセス一覧の読み取り、スクリーンショット、キー入力・クリップボードの監視、
`~/.minecraft` 以外のファイルアクセス。詳細は [`PRIVACY.md`](PRIVACY.md)。

---

## 5. 改ざん対策（多層）と、それぞれの破られ方

「基礎的なチートを全部潰す」ために、成本を段階的に上げる構成にしてある。
各層に **必ず抜け道がある** ので、破られ方もセットで書いておく。

| # | 対策                                        | 何を防ぐか                                   | 破られ方（残る穴）                         |
|---|---------------------------------------------|----------------------------------------------|--------------------------------------------|
| 1 | ビルド時の難読化（ProGuard、リネーム）      | 静的解析のしやすさ。どこを直せばいいか分からない | jar をデコンパイルして頑張る             |
| 2 | 鍵を平文で置かない（XOR マスク + 生成ソース）| `strings` で鍵が抜かれる                      | デバッガで `Mac.init` をフックして鍵を得る |
| 3 | HMAC-SHA256（nonce 込み）                   | そのへんの MOD で偽レポートを投げる／録画の使い回し | 鍵を抜けば自分で署名できる           |
| 4 | 自分自身の jar の SHA-256 + エントリ指紋     | アンチチート jar 自体の改変                  | 改変版が「正しい値」を報告する             |
| 5 | サーバー側のピン留め照合（`pins.yml`）       | 配布 jar と違うビルド、既知 MOD の中身差し替え | 本物の jar をそのまま使う（＝改変できない） |
| 6 | id と中身の突き合わせ                        | **MOD 名の偽装**（`fabric.mod.json` の id 詐称） | 本物の MOD に細工を混ぜる             |
| 7 | `mods/` の実ファイル走査                     | MOD ローダに姿を見せない jar（manifest なし） | `mods/` 以外から注入する               |
| 8 | 実行時プローブ（サーバー指定クラス探索）      | javaagent / 注入系のチートクライアント       | クラス名を変える（サーバー側で追随できる） |
| 9 | 行動検知（サーバー権限）                      | 上記すべてを突破した相手                     | **原理的にごまかせない**（値を偽装すればするほど検知される） |

### 5.1 MOD 名の偽装をどう捕まえるか

「MOD 名を偽装する」には 3 通りあって、それぞれ別の穴に落ちる。

1. **jar のファイル名を変える**（`meteor-client.jar` → `sodium.jar`）
   → 判定は id（`fabric.mod.json` の中）とハッシュで行うので無意味。
2. **`fabric.mod.json` の id を書き換える**（`meteor-client` → `sodium`）
   → `pins.yml` に「sodium = この SHA-256」と固定してあれば、ハッシュが一致せず
   `MOD_ID_SPOOF:sodium`（critical）になる。ピン留めしていない MOD でも、
   `banned-mods` / `suspicious-mods` の判定は id ではなく **実ファイルのハッシュ照合** に
   切り替えられるので、運用を続けた分だけ強くなる。
3. **`fabric.mod.json` を持たない jar を `mods/` に置く**
   → `JAR_WITHOUT_MANIFEST:<file>`（critical）。そもそも MOD として読み込まれないので、
   注入ローダ経由の注入物である可能性が高い。

ピン留めは `/ac policy pin <信頼できるプレイヤー> <modId>` でその場で記録できる。
「クリーンだと分かっているプレイヤーのクライアント」からハッシュを吸い上げて固定する運用。

### 5.2 なぜ HMAC 鍵をクライアントに持つのか

サーバー秘密鍵で署名する方式（＝クライアントが鍵を持たない）にすると、
クライアントのレスポンスに署名できず、結局誰でも偽レポートを送れる。
かといって秘密鍵をクライアントに配布すると、鍵は「公開されているが探しにくい」状態になる。
これは DRM と同じ構造で、**完全には守れない**。だから MCSA では

- 鍵を抜く手間（＝難読化 + マスク + リネーム）を「そのへんのスクリプトキディには無理」な水準に置く
- 鍵の指紋（`keyId`）を HELLO で送らせ、サーバーの鍵と違うビルドが来たら即アラート

という割り切りにしている。

---

## 6. 行動検知（サーバー側）

`CheckManager` に実装してあるもの。すべて `config.yml` の `checks.*` で ON/OFF と閾値を変えられる。

| チェック      | 観測                                        | 既定の閾値                 | 誤検知の主な原因と除外              |
|---------------|---------------------------------------------|-----------------------------|--------------------------------------|
| `REACH`       | 目線から当たり判定までの距離                | 3.1 + ping 補正（上限 +1.5）| クリエイティブは +2.0               |
| `KILLAURA`    | 視線と対象の角度 / 1 秒間の攻撃回数         | 75° / 16 回                 | 広い角度は許容                       |
| `AUTOCLICKER` | CPS とクリック間隔の標準偏差                | 20 CPS / ばらつき 8 ms      | 連打は許容、機械的な等間隔だけ拾う   |
| `FASTBREAK`   | 1 秒間の破壊数                              | 20 個                       | 効率 V + ヘイストの instamine は 20/s 未満 |
| `FLY`         | 接地せずに停滞した tick 数                  | 30 tick                     | 跳躍・落下・はしご・つる・水・溶岩・エリトラ・トライデント・浮遊/低速落下・船やトロッコの上に立つ |
| `SPEED`       | 1 tick あたりの水平移動量                   | 0.6 blocks/tick × 10 tick   | 移動速度上昇ポーション、氷、テレポート直後、ノックバック直後 |

違反レベル（VL）を溜めて、`checks.alert-vl` でアラート、`checks.kick-vl`（既定 0 = 無効）でキック。
`checks.cancel`（既定 false）を true にすると REACH のダメージをキャンセルする。

**導入直後はアラートだけにして様子を見ること。** 誤検知でプレイヤーを追い出すほうが損害が大きい。

---

## 7. 判定フラグ

`/ac flags <player>` とレポート保存先（`plugins/MCSA/reports/<uuid>.json`）に出るコード。

| フラグ                    | 重大 | 意味                                             |
|---------------------------|------|--------------------------------------------------|
| `UNVERIFIED`              | ★    | HMAC 検証に失敗（改変 jar か鍵違い）              |
| `CLIENT_TAMPERED`         | ★    | ピン留めしたクライアント jar とハッシュが違う     |
| `CLIENT_UNKNOWN_BUILD`    |      | ピン留めリストにないビルド                        |
| `NOT_OBFUSCATED`          | ★    | 難読化されていないビルド                          |
| `BANNED_MOD:<id>`         | ★    | `policy.banned-mods` に一致                       |
| `MOD_ID_SPOOF:<id>`       | ★    | 既知 MOD の id を名乗っているが中身が違う         |
| `JAR_WITHOUT_MANIFEST:<f>`| ★    | `fabric.mod.json` のない jar                      |
| `CHEAT_CLASS:<class>`     | ★    | プローブで指定したクラスが存在                    |
| `JAVA_AGENT` / `DEBUGGER` | ★    | `-javaagent` / jdwp                               |
| `REDACTED:<項目>`         | ★    | プレイヤーが送信を拒否した項目                    |
| `SUSPICIOUS_MOD:<id>`     |      | `policy.suspicious-mods` に一致                   |
| `UNKNOWN_MOD:<id>`        |      | どのリストにもない（`flag-unknown-mods` 有効時）  |
| `JAR_NOT_LOADED:<f>`      |      | `mods/` にあるのに読み込まれていない              |
| `ODD_CLASSPATH:<jar>`     |      | クラスパスに注入系の名前の jar                    |

---

## 8. ディレクトリ構成

```
client/                        Fabric MOD
  src/main/java/dev/ifuto/mcsa/client/
    McsaClient.java            エントリーポイント
    McsaConfig.java            config/mcsa/client.json
    net/                       ペイロード定義とハンドシェイク
    collect/                   MOD / パック / シェーダーの収集
    integrity/                 自己整合性と実行時プローブ
    crypto/Signer.java         HMAC
    gen/KeyMaterial.java       ビルド時に生成（Git に入れない）
  build.gradle                 鍵生成タスク + ProGuard タスク
  proguard.pro

server/                        Paper プラグイン
  src/main/java/dev/ifuto/mcsa/server/
    McsaPlugin.java            初期化
    McsaConfig.java            config.yml / pins.yml
    net/                       Wire（通信）/ Session / SessionManager / ReportListener
    report/                    ClientReport / ReportStore
    policy/ModPolicy.java      照合と判定
    check/                     行動検知
    alert/AlertService.java    コンソール / OP / Webhook
    command/AcCommand.java     /ac

tools/verify_protocol.py       両側の契約（チャンネル名・JSON キー・config キー）の整合チェック
ci/build-check.yml             追加の CI（.github/workflows/ にコピーして使う）
docs/                          このドキュメント群
```

---

## 9. これからやること（ロードマップ）

- [ ] リソースパックの中身の検査（X-ray テクスチャの検出）
- [ ] `PacketOrder` / `BadPackets` 系の検知（Paper API では限界があるので ProtocolLib 連携）
- [ ] クライアントの設定 GUI（Mod Menu 連携）
- [ ] Web UI（レポートの履歴と差分）
- [ ] ベッドロック／プロキシ（Velocity）対応
- [ ] MC 26.x（Mojang マッピング）への移行 … Yarn が 1.21.x で止まるため
