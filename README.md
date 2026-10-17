# Audio Route Test（Android 原生）

一个尽量小的 Android 原生测试 App，用来验证：

- 输入：手机内置麦克风（`AudioRecord` + `setPreferredDevice`）；
- 输出：蓝牙 A2DP / 指定 `AudioTrack` 输出（`setPreferredDevice`）；
- 实际路由：`getRoutedDevice()` 读回输入/输出设备；
- 模式：
  - 麦克风回环：内置麦克风 -> 所选输出；
  - 440Hz 测试音：只测输出，不碰麦克风。

它绕开浏览器和 TRTC，直接看 **Android 音频策略**能不能把“内置麦克风 + 蓝牙 A2DP 输出”拆开。

> 这个工程没有在本次会话中编译或运行。当前环境没有 Android SDK / JDK。  
> 需要你用 Android Studio 打开并构建 APK，或者安装 JDK 17 + Android SDK 后用 Gradle 构建。

## 工程结构

```text
android-audio-route-test/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── java/com/example/audioroutetest/MainActivity.java
└── README.md
```

没有 AndroidX、没有第三方依赖，只有一个 `Activity` 和 Java 代码。

## 构建 APK

### 方式 A：Android Studio（推荐）

1. 安装 Android Studio（带 Android SDK）。
2. `File -> Open`，选择 `android-audio-route-test` 文件夹。
3. 等待 Gradle Sync。
   - 如果提示找不到 Gradle wrapper，选择使用本地 Gradle 8.9，或在已安装 Gradle 的情况下执行：
     `gradle wrapper --gradle-version 8.9`
4. `Build -> Build Bundle(s) / APK(s) -> Build APK(s)`。
5. APK 在：
   ```text
   app/build/outputs/apk/debug/app-debug.apk
   ```
6. 把 APK 传到手机安装。需要允许“安装未知来源应用”。

### 方式 B：命令行（如果已有 JDK 17 + Android SDK）

```bash
cd android-audio-route-test

# 如果已有 Gradle 8.9+：
gradle wrapper --gradle-version 8.9
./gradlew assembleDebug
```

Windows：

```powershell
gradle wrapper --gradle-version 8.9
.\gradlew.bat assembleDebug
```

APK 路径同上。

### 方式 C：GitHub Actions 云构建（不用本地 Android SDK）

工程里已经带了 `.github/workflows/build-apk.yml`：

1. 把整个 `android-audio-route-test` 推到一个 GitHub 仓库；
2. 打开仓库的 **Actions → Build Android APK → Run workflow**；
3. 构建完成后，在该次运行页面底部下载 artifact：
   `audio-route-test-debug-apk`；
4. 解压得到 `app-debug.apk`。

这条路不需要你本地安装 Android Studio，但需要一个 GitHub 账号和网络。

## 手机测试步骤

1. 连接蓝牙耳机；在系统蓝牙设置里确认：
   - 媒体音频开启；
   - 通话音频先保持开启（做对比测试）。
2. 打开 App，授予：
   - 麦克风权限；
   - 附近的设备/蓝牙权限（Android 12+）。
3. 点「刷新设备」。
4. 输入选择：
   - `内置麦克风`（Built-in Mic）；
   - 音源选 `MIC`。
5. 输出选择：
   - `蓝牙 A2DP`；
   - 如果没有 A2DP，再试 `蓝牙 SCO/HFP`。
6. 先测「440Hz 测试音」：
   - 只验证输出，不碰麦克风；
   - 听声音从哪里出来。
7. 再测「麦克风回环」：
   - 戴耳机；
   - 对手机麦克风说话、刮麦克风；
   - 听耳机里是否有声音。
8. 看「实际路由」：
   - 输入实际：应该是内置麦克风；
   - 输出实际：应该是蓝牙 A2DP。
9. 如果实际路由和选择不一致，点「复制 JSON」或「分享 JSON」回传。

## 这个 App 能证明什么

- 如果：
  - 输入实际 = 内置麦克风；
  - 输出实际 = 蓝牙 A2DP；
  - 耳机里只有蓝牙声音；
  
  说明 Android 原生层可以拆分，Web 方案失败是浏览器/TRTC 的限制。

- 如果原生 App 也出现：
  - 输出实际 = 内置扬声器；
  - 或输入实际 = 蓝牙 SCO；
  - 或蓝牙没有声音；
  
  说明这台手机的 Android 音频策略本身就不允许“内置麦克风 + 蓝牙 A2DP”同时工作。此时只能换有线/硬件方案。

## 注意事项

- 「麦克风回环」会把麦克风声音送到输出，可能啸叫。请戴耳机、降低音量。
- 不要同时开腾讯会议/TRTC/录音 App，避免音频焦点抢占。
- `AudioTrack` 使用 `USAGE_MEDIA` 是为了偏向 A2DP；如果勾选「USAGE_VOICE_COMMUNICATION」，系统更可能走 HFP/扬声器，可用于对比。
- `setPreferredDevice` 只是“偏好”，系统可以拒绝；所以必须看 `getRoutedDevice()` 的实际结果，不能只看返回值。
- 小米/HyperOS 可能还有“音频分享/双设备音频”等设置，测试前先关闭。
