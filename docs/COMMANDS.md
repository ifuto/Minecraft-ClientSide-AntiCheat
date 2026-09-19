# コマンド一覧

すべて `/ac`（エイリアス `/anticheat`, `/mcsa`）。権限は `mcsa.admin`（既定: OP）。

## 導入状況の確認

| コマンド | 説明 |
|----------|------|
| `/ac status` | オンライン全員の導入有無・MOD バージョン・フラグ件数・違反レベルの一覧 |
| `/ac info <player>` | そのプレイヤーの詳細（HMAC 検証、難読化、jar ハッシュ、フラグ、送信拒否項目、VL） |
| `/ac refresh <player>` | レポートを再要求する（チャレンジを再送） |
| `/ac scan <player>` | **新しい nonce で**環境を申告し直させる（起動時だけ正常な顔をする対策） |

```
> /ac status
── 導入状況 (mode=LISTED, action=KICK) ──
 Steve    導入=YES  ver=1.0.0  flags=clean  VL=0  [詳細]
 Alex     導入=YES  ver=1.0.0  flags=2件    VL=3 [詳細]
```

## MOD / リソースパック / シェーダーの確認

| コマンド | 説明 |
|----------|------|
| `/ac mods <player> [page]` | 導入 MOD 一覧（10 件/ページ）。判定ラベル付き |
| `/ac packs <player>` | 有効なリソースパックと `resourcepacks/` の中身（名前・サイズ・SHA-256） |
| `/ac shaders <player>` | Iris / OptiFine の種別・パック名と `shaderpacks/` の中身 |

判定ラベル（`/ac mods`）:

| ラベル | 意味 |
|--------|------|
| `PINNED`  | `pins.yml` に固定したハッシュと一致（安全） |
| `ALLOWED` | `policy.allowed-mods` に一致 |
| `UNKNOWN` | どのリストにも無い（未確認） |
| `NO_HASH` | ピン留め済み MOD だがハッシュが送られてこなかった |
| `SUSPICIOUS` | `policy.suspicious-mods` に一致 |
| `BANNED`  | `policy.banned-mods` に一致 |
| `ID_SPOOF`| ピン留め済み MOD の id を名乗っているが中身が違う（＝偽装） |

さらに `mods/` に `fabric.mod.json` を持たない jar や、読み込まれていない jar があれば
一覧の下に赤字で出す（MOD ローダに姿を見せない注入物の兆候）。

```
> /ac mods Alex
── Alex の MOD (87 件) 1/9 ──
 fabric-api   0.141.6+1.21.11  [PINNED]  3f9c2a1b7e04
 sodium       0.6.0            [PINNED]  a81d55c0e9b2
 meteor-client 0.5.9           [BANNED]  77b1c0e2d93a
 baritone     1.11.0           [SUSPICIOUS]  91e0aa77c3d2
 mods/ の気になるファイル:
  helper.jar (fabric.mod.json なし＝MODとして読み込まれない注入物)
```

## 注入検知と証拠

| コマンド | 説明 |
|----------|------|
| `/ac shot <player> [reason]` | **画面を取得**してサーバーに保存する（対象の画面には何も表示されない） |
| `/ac watch <player> <seconds\|off>` | 高頻度監視。ダイジェスト間隔を詰め、`evidence.capture.enabled=true` なら定期的に画面も取得 |
| `/ac evidence <player> [n]` | 保存済みの証拠の一覧と保存先。**OP 用 MOD を入れていれば n 番目（既定は最新）を自分のクライアントへ転送**してその場で見られる |

```
> /ac shot Alex 通報対応
 要求しました。届くと .../plugins/MCSA/evidence に保存されます
> /ac evidence Alex
── Alex の証拠 (2 件) ──
 .../evidence/Alex-9b1f.../2026-09-19T09-12-03_shot-1758....png
 保存先: plugins/MCSA/evidence
 監査ログ: plugins/MCSA/evidence/evidence-log.txt
```

**画面取得を使う前に**（`docs/PRIVACY.md`）:

1. `config.yml` の `evidence.capture.enabled` を `true` にする（**既定は false**）
2. サーバールール／利用規約に「不正調査のために画面を取得・保存する場合がある」ことを書く
3. 取得したら必ず `/ac evidence <player>` で保存先を確認し、監査ログ（`evidence-log.txt`）を残す

対象プレイヤーの画面には**何も表示されない**（チャット・トースト・撮影音なし）。
これは「撮られた」と分かって抜けるのを防ぐためで、倫理的な根拠は
**事前にルールで告知していること**に置く。告知なしの運用はしないこと。

## 検知履歴と処分

| コマンド | 説明 |
|----------|------|
| `/ac flags <player>` | レポートのフラグと、行動検知の違反レベル（VL）一覧 |
| `/ac kick <player> [reason]` | 理由付きでキック |

## ポリシー

| コマンド | 説明 |
|----------|------|
| `/ac policy` | ポリシーのサブコマンド一覧 |
| `/ac policy mode <OFF\|LISTED\|EVERYONE>` | 導入必須の範囲を変更（既定 `LISTED`） |
| `/ac policy require add <player>` | 導入必須リストに追加 |
| `/ac policy require remove <player>` | 導入必須リストから削除 |
| `/ac policy require list` | 導入必須リストを表示 |
| `/ac policy pin <player> <modId>` | そのプレイヤーのレポートから MOD の SHA-256 を `pins.yml` に固定 |
| `/ac policy pin-client <player>` | そのプレイヤーのクライアント jar の SHA-256 を固定 |
| `/ac policy probe list` | 検知候補（クラス名 / パターン）の一覧 |
| `/ac policy probe add <pattern>` | 検知候補を追加。**クライアント MOD を配り直さずに検知対象を増やせる** |
| `/ac policy probe remove <pattern>` | 検知候補を削除 |

### 検知候補の書き方（動的に増やせる）

| 書き方 | 意味 | 例 |
|--------|------|----|
| 完全一致 | そのクラスがロードされているか | `meteordevelopment.meteorclient.MeteorClient` |
| `prefix:` | 前方一致（ロード判定はせず jar 走査で使う） | `prefix:me.rhys` |
| `contains:` | 部分一致 | `contains:killaura` |
| `regex:` | 正規表現（部分一致） | `regex:^net\.wurst.*Client$` |
| `class:` + 上記 | jar の**中身**（エントリ名）を走査。ロードされていないものも見つかる | `class:prefix:me.rhys` |
| `lib:` + 上記 | 読み込み中のライブラリ（jar 名）を照合 | `lib:contains:byte-buddy` |

新しいチートが出たら、その MOD のパッケージ名を 1 行足すだけで全クライアントに配布される。
`config.yml` の `challenge.probe-classes` に書くのでも同じ（`/ac policy probe add` は
`config.yml` にも書き戻す）。
| `/ac reload` | `config.yml` / `pins.yml` を読み直す |

### ピン留めの運用

```
# 1) クリーンだと分かっているプレイヤー（例: 自分）に入ってもらう
# 2) その人のレポートからハッシュを固定する
/ac policy pin Notch sodium
>  固定: sodium = 3f9c2a1b7e04

# 3) 配布しているクライアント jar 自体も固定する
/ac policy pin-client Notch
>  クライアント jar を固定: 8d0a41f2c93b
```

以降、`sodium` を名乗る MOD でハッシュが違うクライアントは `MOD_ID_SPOOF:sodium`（critical）になる。

## OP 用クライアント MOD（`admin/`）

コンソールを開かなくても、運営が自分のクライアントから調査できるようにする MOD。
**運営だけに入れる**もの（プレイヤーには配布しない）。

| コマンド（クライアント側） | 説明 |
|------|------|
| `/acadmin` | ヘルプ |
| `/acadmin <args...>` | サーバーの `/ac <args...>` を実行する（権限 `mcsa.admin` が必要） |

```
> /acadmin shot Alex 通報対応
 §7/ac shot Alex 通報対応 §8→ サーバーへ送信しました
 §6[MCSA] §7画面を受信しました: evidence/Alex-9b1f.../2026-09-19T09-12-03_shot.png (284112 bytes, hmac=ok)
```


- サーバー側の権限判定を通るので、MOD だけ入れても権限がなければ何もできない。
- 実行結果と証拠の受信通知はチャットに出る（`mcsa:adminmsg`）。
- 画面はサーバーの `plugins/MCSA/evidence/` に保存される（原本＋監査ログ）。
- さらに、**画面取得を指示した OP が OP 用 MOD を入れていれば、その OP のクライアントにも
  自動で転送される**（`mcsa:shot`）。受け取った側は `.minecraft/mcsa-evidence/` に保存し、
  **ゲーム内のビューアに画像そのものが表示される**（画面サイズに合わせて縮小）。
  `Open image` で OS のビューア、`Open folder` で保存先を開く。
  `/acadmin evidence <player>` でも取り寄せられる。
- 転送先の OP が OP 用 MOD を入れていない場合は転送されず、サーバー上のファイルを開くことになる。

## 権限

| 権限 | 既定 | 説明 |
|------|------|------|
| `mcsa.admin`  | op    | `/ac` のすべて |
| `mcsa.alerts` | op    | 検知アラートを受け取る |
| `mcsa.bypass` | false | 導入必須と行動検知の対象外（スタッフ・Bot 用） |
