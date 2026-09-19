# コマンド一覧

すべて `/ac`（エイリアス `/anticheat`, `/mcsa`）。権限は `mcsa.admin`（既定: OP）。

## 導入状況の確認

| コマンド | 説明 |
|----------|------|
| `/ac status` | オンライン全員の導入有無・MOD バージョン・フラグ件数・違反レベルの一覧 |
| `/ac info <player>` | そのプレイヤーの詳細（HMAC 検証、難読化、jar ハッシュ、フラグ、送信拒否項目、VL） |
| `/ac refresh <player>` | レポートを再要求する（チャレンジを再送） |

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
| `/ac policy probe list` | クライアントに探索させるクラス名の一覧 |
| `/ac policy probe add <class>` | 探索クラスを追加（クライアント MOD の更新なしに検知対象を増やせる） |
| `/ac policy probe remove <class>` | 探索クラスを削除 |
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

## 権限

| 権限 | 既定 | 説明 |
|------|------|------|
| `mcsa.admin`  | op    | `/ac` のすべて |
| `mcsa.alerts` | op    | 検知アラートを受け取る |
| `mcsa.bypass` | false | 導入必須と行動検知の対象外（スタッフ・Bot 用） |
