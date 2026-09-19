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
| 注入の痕跡     | Mixin 設定名の一覧と出所、ゲームプレイ関連クラスへの注入痕跡、怪しいスレッド名、クラスローダの連鎖 |
| ライブラリ名   | いま読み込んでいる jar のファイル名と件数（`KnotClassLoader#getURLs` + `java.class.path` + `jdk.module.path`） |
| jar の中身     | classpath 上の jar のエントリ名のうち、検知候補に一致したクラス名（**ロードしない**ので重い処理ではない） |
| 常時監視       | 起動時の基準値と現在の状態の差分（ダイジェスト 16 文字＋変化項目） |

**やらないこと**: プロセス一覧の読み取り、キー入力・クリップボードの監視、
`~/.minecraft` 以外のファイルアクセス。
**画面の取得は OP の指示があったときだけ**（既定は無効。対象には何も表示しない）。
詳細は [`PRIVACY.md`](PRIVACY.md)。

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
| 8b| Mixin 設定の出所照合                          | Mixin による介入（チートの主流）             | 正規 MOD の設定名を騙る（fabric.mod.json と突き合わせる） |
| 8c| ライブラリ名・jar の中身の走査                | MOD として登録されない注入 jar               | 名前と中身を作り替える（手間が跳ね上がる） |
| 8d| 常時監視（ウォッチドッグ）                    | **起動時だけ正常な顔をする**タイプ            | 常時きれいに保つ（＝常時チートを切る＝使えない） |
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

### 5.3 注入（Mixin / agent）をどう捕まえるか

**前提**: Fabric 自身が Mixin を使っている。だから「Mixin が入っている」は
何の証拠にもならない。証拠になるのは次の 2 つ。

1. **出所不明の Mixin 設定**
   Mixin には登録された設定ファイル名の一覧が取れる（`Mixins.getConfigurations()`）。
   これを **インストール済み MOD が `fabric.mod.json` で宣言している設定名** と突き合わせ、
   どちらにも属さないものを `MIXIN_UNKNOWN_CONFIG` として報告する。
   チートクライアントは `mixins.meteorclient.json` のような名前を使うので、
   MOD 一覧に現れなくてもここで引っかかる。
2. **ゲームプレイ関連クラスへの注入痕跡**
   `MinecraftClient` / プレイヤー / ワールド / インタラクションマネージャの
   クラス階層を辿り、合成メソッド（`$handler$...` など）を数える。
   Fabric API も注入するので **これ単体では判定に使わない**。
   サーバー側で「出所不明の設定 ≥1 かつ 注入痕跡 ≥ `injection.injected-member-threshold`」
   のときに `MIXIN_INJECTION_SUSPECTED`（critical）を立てる。

これに加えて、javaagent 系は次の 4 面で見る。

| 観測 | 何を見つけるか |
|------|----------------|
| JVM 引数（`-javaagent` / `-agentlib` / `-xbootclasspath`） | 起動時に注入するタイプ |
| `sun.instrument.InstrumentationImpl` のロード有無 | 実行中に attach された痕跡 |
| スレッド名（`retransform` / `agent` / `hook` 等） | 注入ツールのワーカースレッド |
| クラスローダの連鎖 | Fabric の Knot 以外が混ざっている／そもそも Fabric で動いていない |

### 5.4 検知候補は動的（クライアントの再配布が不要）

新しいチートが出るたびに MOD を配り直すのは現実的でない。そこで検知候補は
**サーバーが実行時に配る**。`/ac policy probe add <pattern>` 1 行で全クライアントに反映される。

| 書き方 | 何をするか |
|--------|-----------|
| `完全一致` | `Class.forName` で存在確認（ロード済みなら分かる） |
| `prefix:` / `contains:` / `regex:` | jar のエントリ名を走査して照合（**ロードされていなくても**見つかる） |
| `class:<上記>` | 同上（jar 走査向けの明示指定） |
| `lib:<上記>` | 読み込み中のライブラリ名（jar 名）を照合 |

「起動時だけ動いて、その後クラスを消す」タイプは `Class.forName` では見えないが、
jar の中身を見るので取りこぼさない。逆に「jar を消してメモリだけに載せる」タイプは
`Class.forName` と Mixin 設定の照合で捕まえる。**どちらか片方だけでは抜けるので両方やる。**

### 5.5 「起動時だけ綺麗」を潰す（2 段階認証的な常時監視）

1 回の検査を通るだけなら、検査の瞬間だけ真面目にしておけばいい。
そこで **通し続けることを条件にする**。

```
起動 ──▶ 基準値を記録
        │
        ├──(45±20% 秒ごと)──▶ 状態を再計算 ──▶ mcsa:digest（16 文字のハッシュ＋変化フラグ）
        │                                          │
        │                        サーバー ─────────┤ 変化フラグ ≠ 0 → アラート＋再チャレンジ
        │                                          └ 180 秒届かない → WATCHDOG_SILENT
        └── OP の /ac watch ──▶ 間隔を 20 秒に詰める＋定期的に画面取得
```

- 送るのは 16 文字のハッシュだけ（中身は送らない）。変わったときだけ次のレポートで詳細を送る。
- 比較するのは「自分の jar のハッシュ」「Mixin 設定の一覧」「読み込み中ライブラリ」
  「クラスローダの連鎖」「検知対象クラスの存在」。
- **送ってこなくなったこと自体が証拠**になる（MOD を殺した／フックして黙らせた）。
- `/ac scan <player>` は nonce を新しくして申告し直させるので、録画したレポートを使い回せない。

### 5.6 証拠（画面の取得）

`/ac shot <player> [reason]` → `mcsa:task (CAPTURE)` → クライアントが
フレームバッファを直接読んで PNG/JPEG を作り、`mcsa:evidence` で分割送信する。

- **対象プレイヤーの画面には何も表示しない。** バニラのスクリーンショット機能は
  必ずチャットに出るので使わず、`glReadPixels` で読んでメモリ上で符号化する。
  一時ファイルも作らない。理由は「撮られた」と分かって抜けるのを防ぐため。
- 送信前に HMAC-SHA256（`nonce ‖ data`）を付け、サーバーは一致したものだけ採用する
  （不一致でも保存はするが `hmacValid=false` を記録してアラートを出す）。
- 保存先は `plugins/MCSA/evidence/<player>-<uuid8>/`。同じ場所に `.json` の sidecar
  （誰が・いつ・なぜ・SHA-256・HMAC 検証結果）と、追記専用の監査ログ
  `evidence-log.txt` を置く。OP 権限の悪用もこれで追える。
- **既定は無効**（`evidence.capture.enabled: false`）。有効化するには
  サーバールールでの告知が前提（[`PRIVACY.md`](PRIVACY.md)）。

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
| `MIXIN_UNKNOWN_CONFIG:<n>`| ★    | インストール済み MOD に属さない Mixin 設定         |
| `MIXIN_INJECTION_SUSPECTED`| ★   | 出所不明の Mixin 設定＋注入痕跡の組み合わせ        |
| `CHEAT_CLASS_IN_JAR:<c>`  | ★    | jar の中に検知対象のクラスを見つけた              |
| `CHEAT_CLASS_LOADED:<c>`  | ★    | サーバー指定のクラスがロードされている            |
| `LIBRARY_SUSPICIOUS:<j>`  |      | 怪しい名前のライブラリを読み込んでいる（既定は非重大） |
| `SUSPICIOUS_THREAD:<n>`   | ★    | 怪しい名前のスレッドが動いている                  |
| `CLASSLOADER_ODD:<n>`     | ★    | Fabric 以外のクラスローダが混ざっている            |
| `STATE_CHANGED:<what>`    | ★    | ウォッチドッグが起動時との差分を検出              |
| `WATCHDOG_SILENT`         | ★    | 状態ダイジェストが途絶えた（MOD を殺された疑い）   |
| `CLIENT_TAMPERED_RUNTIME` | ★    | 実行中に自分の jar が変わった                     |
| `EVIDENCE_HMAC_INVALID`   | ★    | 証拠の HMAC が一致しなかった                      |

---

## 8. ディレクトリ構成

```
client/                        Fabric MOD
  src/main/java/dev/ifuto/mcsa/client/
    McsaClient.java            エントリーポイント
    McsaConfig.java            config/mcsa/client.json
    net/                       ペイロード定義とハンドシェイク
    collect/                   MOD / パック / シェーダー / ライブラリ / jar の中身の収集
    integrity/                 自己整合性・実行時プローブ・注入検知・常時監視
    capture/SilentCapture.java 画面の取得（対象には表示しない）
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
    policy/ModPolicy.java      MOD の照合と判定
    policy/InjectionPolicy.java 注入系の finding をフラグに変換
    evidence/EvidenceStore.java 証拠（画面）の保存と監査ログ
    check/                     行動検知
    alert/AlertService.java    コンソール / OP / Webhook
    command/AcCommand.java     /ac

admin/                         OP 用クライアント MOD（/acadmin でサーバーの /ac を実行）
tools/verify_protocol.py       両側の契約（チャンネル名・JSON キー・config キー）の整合チェック
ci/build-check.yml             追加の CI（.github/workflows/ にコピーして使う）
docs/                          このドキュメント群
```

---

## 9. これからやること（ロードマップ）

- [ ] リソースパックの中身の検査（X-ray テクスチャの検出）
- [ ] `PacketOrder` / `BadPackets` 系の検知（Paper API では限界があるので ProtocolLib 連携）
- [x] 注入（Mixin / javaagent / ライブラリ名 / jar の中身）の検知
- [x] 常時監視（ウォッチドッグ）で「起動時だけ綺麗」を潰す
- [x] 証拠の取得（画面）と監査ログ
- [x] OP 用クライアント MOD（`admin/`）
- [ ] クライアントの設定 GUI（Mod Menu 連携）
- [ ] Web UI（レポートの履歴と差分）
- [ ] ベッドロック／プロキシ（Velocity）対応
- [ ] MC 26.x（Mojang マッピング）への移行 … Yarn が 1.21.x で止まるため
