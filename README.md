# TRTC Native Call（Android 原生双人通话）

目标：两个人异地，用 Android 原生 TRTC 通话，并保持：

- 输入：手机内置麦克风；
- 输出：蓝牙 A2DP；
- 不经过 TRTC 内部播放器，避免被通信模式带回手机扬声器；
- 退到后台/锁屏时，通过前台服务和常驻通知保持通话。

## 界面

只保留：

- 输入麦克风选择；
- 输出设备选择；
- 开始通话 / 结束通话。

默认凭据写在 `CallService.java` 中，不在界面展示：

```text
SDKAppID = 1600167619
RoomId   = 123456
UserId   = And
UserSig  = 内置
```

> UserSig 会过期。它被硬编码在 APK 里，只适合个人测试；过期后需要换新的 UserSig 重新打包。不要把 SecretKey 放进 APK。

## 核心设计

普通 TRTC 通话会让 SDK 自己打开音频采集和播放，Android 可能把播放切到通信路由（扬声器/HFP）。

这个 App 改成：

```text
AudioRecord（内置麦克风，setPreferredDevice）
  → TRTCCloud.sendCustomAudioData()
  → TRTC 云端
  → TRTCCloud.getCustomAudioRenderingFrame()
  → AudioTrack（USAGE_MEDIA，48k 立体声，setPreferredDevice = 蓝牙 A2DP）
```

音质相关：

- TRTC 本地音频使用 `TRTC_AUDIO_QUALITY_MUSIC`（全频带立体声，128kbps），避免人声模式的 18kbps 单声道把系统声音压扁；
- `getCustomAudioRenderingFrame()` 使用 48k / 2 声道 / 20ms（3840 字节）；
- `AudioTrack` 使用 `CHANNEL_OUT_STEREO` + `CONTENT_TYPE_MUSIC`；
- 手机麦克风上行仍是 48k 单声道。

关键 API：

- `enableCustomAudioCapture(true)`
- `enableCustomAudioRendering(true)`（必须在 `enterRoom` 前）
- `sendCustomAudioData(TRTCAudioFrame)`
- `getCustomAudioRenderingFrame(TRTCAudioFrame)`
- `AudioRecord.setPreferredDevice()` / `AudioTrack.setPreferredDevice()`
- `getRoutedDevice()` 读取实际路由

## 后台保活

- `CallService` 是前台服务，`foregroundServiceType="microphone"`；
- 开始通话后立即显示常驻通知；
- 通知栏有「挂断」按钮；
- 持有 `PARTIAL_WAKE_LOCK`，降低锁屏后 CPU 休眠导致断流的概率；
- 退到后台、锁屏、切换 App 时，通话由 Service 继续。

Android 13+ 需要通知权限；Android 14+ 需要麦克风前台服务权限，Manifest 已声明。

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
│       └── java/com/example/trtcnativecall/
│           ├── MainActivity.java
│           └── CallService.java
├── .github/workflows/build-apk.yml
└── README.md
```

依赖：

```gradle
implementation("com.tencent.liteav:LiteAVSDK_TRTC:latest.release")
```

## 构建 APK

### Android Studio

1. `File -> Open` 选择 `android-trtc-call`；
2. Gradle Sync；
3. `Build -> Build Bundle(s) / APK(s) -> Build APK(s)`；
4. APK：
   ```text
   app/build/outputs/apk/debug/app-debug.apk
   ```

### GitHub Actions

1. 把整个文件夹推到 GitHub 仓库；
2. Actions → `Build Android APK` → Run workflow；
3. 下载 artifact `trtc-native-call-debug-apk`；
4. 解压得到 `app-debug.apk`。

## 测试

### 手机端

1. 连接蓝牙耳机，确认“媒体音频”开启；
2. 安装 App，授予麦克风、蓝牙、通知权限；
3. 输入麦克风选「内置麦克风」；
4. 输出设备选「蓝牙 A2DP」；
5. 点「开始通话」；
6. 看「实际路由」：
   - 输入实际 = 内置麦克风；
   - 输出实际 = 蓝牙 A2DP；
7. 退到后台/锁屏，确认通知栏仍在、通话不断；
8. 通知栏点「挂断」可结束。

### 电脑端（第二端）

用 `trtc-web-call` 页面：

1. 打开 HTTPS 页面；
2. `UserId` 填 `pc`；
3. 填 `pc` 的 UserSig；
4. 音频源可选：
   - 电脑麦克风；
   - 当前系统声音（屏幕共享音频）；
5. 输出设备选择电脑的扬声器/耳机；
6. 加入房间。

手机端固定是 `And`，电脑端不能用 `And`，否则会互踢。

## 预期结果

- 手机「实际输入 = 内置麦克风」「实际输出 = 蓝牙 A2DP」，耳机只有蓝牙声音；
- 电脑端能收到手机麦克风；
- 手机能听到电脑麦克风或系统声音；
- 手机退到后台/锁屏后，通话继续；
- 通知栏常驻，可挂断。

## 注意事项

- `UserSig` 过期会导致进房失败；需要换新的 UserSig 并重新打包。
- 自定义采集可能让 TRTC 的 AEC 失效；请戴耳机、保持环境安静。
- 输出 `AudioTrack` 固定 `USAGE_MEDIA`；不要改成 `USAGE_VOICE_COMMUNICATION`。
- 测试时关闭小米“音频分享/双设备音频”等设置。
- 部分系统会限制后台麦克风；前台服务 + 通知是 Android 官方要求的方式。
- 当前工程没有在本环境编译；需要 Android Studio 或 GitHub Actions。
