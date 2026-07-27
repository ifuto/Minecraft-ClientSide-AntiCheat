# 誤検知分析と除外リスト

## なぜ誤検知が起きるか

ブラックリスト方式では、パッケージ名が類似している正当Modを誤ってチートと判定するリスクがあります。

### 例

- `me.jellysquid.mods.sodium` (Sodium) と `me.jellysquid.mods.lithium` のような命名規則で、 `melon`? ... 実際には `meteordevelopment.meteorclient` は `me.*` ではないため衝突しないが、ライブラリレベルで衝突する例:

- `com.google.common.eventbus` (Guava) は多くのModとチートクライアント両方が使用する。ブラックリストに入れるとほぼ全てのModで誤検知。

- `net.minecraftforge.eventbus` はForge自体。

- `mixinextras` はFabricのMixin拡張ライブラリで、SodiumやModMenuなど多数の正当Modが使用。

- `com.moonsworth.lunar` (Lunar Client) は正当ランチャー。チートではないが、名前が `moonclient` (`net.moonclient` というチートクライアント) と類似。

- `net.badlion` (Badlion Client) も同様に正当。

- `net.optifine` と `net.anoptik` はOptiFine。

## 除外戦略

本プロジェクトでは `tools/generate_hashes.py` にて以下の除外リスト `false_positive_packages` を定義し、CRITICALリストから除去しています。

```python
false_positive_packages = [
    "me.jellysquid.mods.sodium",
    "net.caffeinemc.mods.sodium",
    "net.optifine",
    "net.anoptik",
    "com.moonsworth.lunar",
    "net.badlion",
    "xaero.map",
    "com.mamiyaotaru.voxelmap",
    "fi.dy.masa.tweakeroo",
    "fi.dy.masa.malilib",
    "fi.dy.masa.litematica",
    "fi.dy.masa.minihud",
    "de.jcm.discordgamesdk",
    "net.earthcomputer.clientcommands",
    "dev.isxander.zoomify",
    "mixinextras",
    "com.google.common.eventbus",
    "net.minecraftforge.eventbus",
    "net.bytebuddy",
    "javassist",
    "com.github.retrooper.packetevents",
    "io.github.retrooper.packetevents",
    "com.viaversion.viaversion",
    "de.florianmichael.viafabricplus",
    "com.aayushatharva.brotli",
    "me.zeroeightsix.antichatreport",
    "dev.isxander.nochatreports",
    "com.aizistral.nochatreports",
    "org.lwjgl.nanovg",
    "me.shedaniel.rei",
    "me.shedaniel.clothconfig",
    "net.fabricmc.fabric",
]
```

## SUSPICIOUSレベル

以下のModは完全に正当ではないが、サーバポリシーによって許可される場合があるため、**即BANではなく警告ログ**に留めています：

- `tweakeroo` : フリーカム的機能を含むが、建築補助として広く使われる
- `litematica` : 簡易建築 (EasyPlace) がチートと見なされる場合あり
- `xaero.map`, `journeymap`, `voxelmap` : エンティティレーダーがチートだが、ミニマップ自体は許可されるサーバが多い
- `freecam` 系: 明らかにチートだが、スペクテイター的用途で許可される場合もある → 本実装ではCRITICALとSUSPICIOUSの両方に一部含め、設定で切り替え可能

## 推奨設定

- **厳格サーバ (競技, Anarchy除外)**: CRITICAL + SUSPICIOUS両方でBAN
- **緩めサーバ (建築, 生活)**: CRITICALのみBAN, SUSPICIOUSはログのみ
- **Moddedサーバ**: Tweakeroo, Litematica, Xaeroを許可リストに追加し、SUSPICIOUSからも除外

## 今後の誤検知対策

- パッケージだけでなく、**クラスバイトコードの特徴量** (例: KillAuraモジュールは特定のメソッドシグネチャを持つ) を解析
- **ホワイトリストハッシュ**: 正当Modのjar SHA-256を登録し、ホワイトリストにあればブラックリストに該当しても無視
- **署名検証**: Fabric Modの署名 (Jar Signing) を検証し、信頼された作者のModは除外

