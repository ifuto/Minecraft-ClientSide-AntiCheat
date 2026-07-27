# 入力真正性検証の詳細

## 要件

「その操作がしっかりデバイスによって行われたものか(Modにより生成されたものなのか判別)」

## 1. Windows低レベルフック

### API

```cpp
HHOOK mouseHook = SetWindowsHookEx(WH_MOUSE_LL, LowLevelMouseProc, hInst, 0);
HHOOK kbHook = SetWindowsHookEx(WH_KEYBOARD_LL, LowLevelKeyboardProc, hInst, 0);
```

`WH_MOUSE_LL`, `WH_KEYBOARD_LL` はグローバルな低レベルフックで、他プロセスの入力も含めてキャプチャ可能 (同一デスクトップ)。

コールバックでは `MSLLHOOKSTRUCT.flags` が重要：

- `LLMHF_INJECTED` (0x00000001): `SendInput` や `mouse_event`, `keybd_event` による合成入力
- `LLMHF_LOWER_IL_INJECTED` (0x00000002): Lower integrity level からの注入

同様にキーボードは `LLKHF_INJECTED`.

### 判定ロジック

```java
long now = System.currentTimeMillis();
long lastHwMouse = NativeBridge.getLastHardwareMouseClickTime();
boolean injected = NativeBridge.wasLastInputInjected();

if (injected) flag("MOUSE_INJECTED");
else if (now - lastHwMouse > 150ms) flag("SYNTHETIC_CLICK");
```

### バイパス

- **カーネルドライバ**: ドライバレベルで入力を偽装すると、OSはハードウェア入力として扱い、INJECTEDフラグが立たない
- **HIDデバイス偽装**: Arduino Leonardoをマウスとして認識させると完全にハードウェア扱い

対策として統計的解析を併用。

## 2. 統計的解析

### 2.1 回転解析

- **Snap検出**: 1tick (50ms) で yawDelta > 60度は人間ではほぼ不可能
- **GCDチェック**: Minecraftの感度は `gcd = (sensitivity*0.6+0.2)^3 * 8` で量子化される。AimBotが直接回転を設定するとgcdの倍数にならない

### 2.2 CPS解析

クリック間隔 `I_i` の標本から：

```
mean = avg(I_i)
var = avg((I_i-mean)^2)
cv = sqrt(var)/mean
```

人間: cv > 0.15
Autoclicker: cv < 0.05

### 2.3 マウス移動の線形性

人間のマウス移動はFittsの法則に従い、加速・減速があり、軌跡にノイズが含まれる。

理想的な直線移動 (R^2 ≈ 1.0) はAimBotの特徴。

本実装では簡易的に `deltaYaw` の分散が0に近い場合を線形と判定。より高度には：

- 移動軌跡のDFT (離散フーリエ変換) で高周波成分の有無を解析
- エントロピー計算

## 3. 追加アイデア

- **Raw Input API**: `RegisterRawInputDevices` でRAW入力を取得し、HIDレベルでの真正性を検証
- **Touch / Pen**: タッチ入力は別経路
- **Biometric**: キーストロークのバイオメトリクス (打鍵間隔の個人特徴) を学習し、Botと区別
