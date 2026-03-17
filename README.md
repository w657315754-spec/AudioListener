# AudioListener

Android 系统音频实时转录工具。通过 MediaProjection 捕获设备正在播放的音频，使用 [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) + SenseVoice 模型进行离线语音识别，将结果实时显示在屏幕上。

## 功能

- 捕获系统音频播放（媒体、游戏等），非麦克风录音
- 基于 sherpa-onnx 的离线语音识别，无需联网
- 每 2 秒自动分段识别并输出结果
- 前台服务运行，支持后台持续转录

## 环境要求

### 编译环境

| 依赖 | 版本 |
|------|------|
| JDK | 17 |
| Android SDK | API 34 (Android 14) |
| Android Gradle Plugin | 8.2.0 |
| Kotlin | 1.9.20 |
| Gradle | 8.14（wrapper 自动下载） |

推荐使用 Android Studio Hedgehog (2023.1.1) 或更新版本。

### 运行环境

- Android 10+（API 29），因为 `AudioPlaybackCapture` API 从 Android 10 开始支持
- 需要授予以下权限：
  - 存储权限（用于读取模型文件）
  - 录音权限
  - 通知权限（Android 13+）
  - 屏幕录制权限（MediaProjection）

## 模型准备

应用使用 SenseVoice 模型进行中文语音识别，需要手动下载模型文件并放到设备上。

1. 从 [sherpa-onnx releases](https://github.com/k2-fsa/sherpa-onnx/releases) 下载以下文件：
   - `model.int8.onnx`
   - `tokens.txt`

2. 将文件放入设备的 `/sdcard/sherpa-models/sense-voice/` 目录：

```
/sdcard/sherpa-models/sense-voice/
├── model.int8.onnx
└── tokens.txt
```

应用启动时会检测模型文件是否存在，缺失时会在界面上提示。

## 编译运行

```bash
# 克隆项目
git clone <repo-url>
cd AudioListener

# 编译 Debug APK
./gradlew assembleDebug

# APK 输出路径
# app/build/outputs/apk/debug/app-debug.apk
```

或直接用 Android Studio 打开项目，连接设备后点击 Run。

## 项目结构

```
app/src/main/java/com/openclaw/audiolistener/
├── MainActivity.kt          # 主界面，权限请求与服务绑定
├── TranscriptionService.kt  # 前台服务，协调音频捕获与语音识别
├── AudioCapture.kt          # 通过 MediaProjection 捕获系统音频 PCM 数据
├── SherpaEngine.kt          # 封装 sherpa-onnx 离线识别引擎
└── SherpaHelper.java        # Java 辅助类，绕过 Kotlin null-safety 传递 null AssetManager
```

## 工作流程

1. 用户点击「开始转录」
2. 请求录音、通知权限 → 请求 MediaProjection 屏幕录制授权
3. 启动前台服务 `TranscriptionService`
4. 子线程加载 SenseVoice 模型
5. `AudioCapture` 通过 `AudioPlaybackCaptureConfiguration` 捕获系统音频（16kHz, Mono, PCM 16bit）
6. 每积攒 2 秒音频数据，送入 `SherpaEngine` 进行离线识别
7. 识别结果回调到主线程，追加显示在界面上

## 依赖

- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) — 离线语音识别引擎（AAR 包位于 `app/libs/`）
- AndroidX AppCompat, Material, ConstraintLayout, Lifecycle

## License

见项目根目录 LICENSE 文件。
