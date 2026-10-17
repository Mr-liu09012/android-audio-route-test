# TRTC Native Call（Android 原生双人通话）

目标：两个人异地，用 Android 原生 TRTC 通话，并保持：

- 输入：手机内置麦克风；
- 输出：蓝牙 A2DP；
- 不经过 TRTC 内部播放器，避免被通信模式带回手机扬声器。

## 核心设计

普通 TRTC 通话会让 SDK 自己打开音频采集和播放，Android 可能把播放切到通信路由（扬声器/HFP）。

这个 App 改成：

```text
AudioRecord（内置麦克风，setPreferredDevice）
  → TRTCCloud.sendCustomAudioData()
  → TRTC 云端
  → TRTCCloud.getCustomAudioRenderingFrame()
  → AudioTrack（USAGE_MEDIA，setPreferredDevice = 蓝牙 A2DP）
```

关键 API：

- `enableCustomAudioCapture(true)`
- `enableCustomAudioRendering(true)`（必须在 `enterRoom` 前）
- `sendCustomAudioData(TRTCAudioFrame)`
- `getCustomAudioRenderingFrame(TRTCAudioFrame)`
- `setPreferredDevice()` + `getRoutedDevice()`

这样 TRTC 只负责房间/信令/传输，音频设备由原生层控制。

## 与 Web 方案的区别

Web/TRTC Web 失败的原因：

- 浏览器没有 `HTMLMediaElement.setSinkId`；
- `AudioContext.setSinkId` 只能 `default`；
- TRTC Web 不能按 deviceId 选 A2DP。

这个 App 直接在 Android 原生层创建 `AudioTrack`，所以能沿用你已验证成功的路由：

```text
内置麦克风 + 蓝牙 A2DP
```

## 文件

```text
android-trtc-call/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── java/com/example/trtcnativecall/MainActivity.java
├── .github/workflows/build-apk.yml
└── README.md
```

依赖：

```gradle
implementation("com.tencent.liteav:LiteAVSDK_TRTC:latest.release")
```

## 构建 APK

### Android Studio

1. 安装 Android Studio。
2. `File -> Open` 选择 `android-trtc-call`。
3. Gradle Sync。
4. `Build -> Build Bundle(s) / APK(s) -> Build APK(s)`。
5. APK：
   ```text
   app/build/outputs/apk/debug/app-debug.apk
   ```

### GitHub Actions

1. 把整个文件夹推到 GitHub 仓库。
2. Actions → `Build Android APK` → Run workflow。
3. 下载 artifact `trtc-native-call-debug-apk`。
4. 解压得到 `app-debug.apk`。

## 测试：手机 + 电脑（推荐）

手机装原生 App，电脑用现有的 [TRTC Web 测试页](../trtc-meeting-test/index.html) 作为另一端。

1. 电脑打开 TRTC Web 测试页，填入：
   - `SDKAppID`
   - `RoomId`
   - `UserId = pc`
   - 电脑用户的 `UserSig`
2. 电脑端选择「测试音 440Hz」或电脑麦克风，加入房间。
3. 手机打开原生 App，填入：
   - 同一个 `SDKAppID`
   - 同一个 `RoomId`
   - `UserId = phone_native`
   - 手机用户的 `UserSig`
4. 手机选择：
   - 输入：`内置麦克风`
   - 输出：`蓝牙 A2DP`
5. 点「加入房间」。
6. 看「实际路由」：
   - 输入实际 = 内置麦克风；
   - 输出实际 = 蓝牙 A2DP。
7. 手机应听到电脑测试音，且只有蓝牙耳机出声。
8. 对手机麦克风说话/刮麦克风，电脑端应看到远端音量变化。
9. 用「复制 JSON」或「分享 JSON」回传报告。

## 测试：两部手机

两个手机都装 App，使用同一个 `SDKAppID` / `RoomId`，不同 `UserId` / `UserSig`；各自选内置麦克风 + A2DP，加入同一房间即可。

## 预期结果

- 如果「实际输入 = 内置麦克风」「实际输出 = 蓝牙 A2DP」且耳机只有蓝牙声音：
  → 原生双人通话方案成立。
- 如果实际输出变回内置扬声器：
  → TRTC 仍可能改了音频模式/路由；看报告 `routeChanges` 和 `actual`。
- 如果实际输入变成蓝牙 SCO：
  → 输入被 HFP 接管，需要进一步调整。
- 如果 App 崩溃/进房失败：
  → 看日志中的 `TRTC 错误`、`onEnterRoom result`、`startLocalAudio`。

## 注意事项

- 输出 `AudioTrack` 固定 `USAGE_MEDIA`；不要改成 `USAGE_VOICE_COMMUNICATION`。
- 必须先连接蓝牙 A2DP，再打开 App/刷新设备。
- 测试时关闭小米“音频分享/双设备音频”等功能。
- `UserSig` 只在本页内存，不写入报告；不要把 `SecretKey` 放进 APK。
- 测试完成后点「离开房间」，避免持续占用麦克风/蓝牙。
- 当前工程没有在本环境编译；需要 Android Studio 或 GitHub Actions 构建 APK。
