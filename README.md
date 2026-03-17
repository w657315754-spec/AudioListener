# AudioListener

Android 离线语音转录工具。支持捕获系统音频（MediaProjection）或麦克风录音，使用 [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) + SenseVoice 进行离线语音识别，集成 VAD 语音活动检测按语句自动分段，可选说话人识别区分不同发言人。

## 功能

- 两种音频源：系统音频捕获（媒体、游戏、语音助手等）/ 麦克风录音
- 离线语音识别，无需联网，基于 SenseVoice 模型
- silero-vad 语音活动检测，按语句边界自动切分，不再固定时间切片
- 说话人识别（可选）：基于 3D-Speaker 嵌入模型，自动区分不同发言人并标注 `[说话人N]`
- 说话人区分灵敏度可通过界面滑块实时调节
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

### 运行环境

- Android 10+（API 29）
- 需要授予权限：存储、录音、通知（Android 13+）、屏幕录制（系统音频模式）

## 模型准备

### 1. 语音识别模型（必需）

从 SenseVoice 预训练模型下载：

- 下载地址：https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models
- 模型名称：`sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17`
- 需要的文件：
  - `model.int8.onnx` — 量化模型（约 230MB）
  - `tokens.txt` — 词表文件

放入设备路径：

```
/sdcard/sherpa-models/sense-voice/
├── model.int8.onnx
└── tokens.txt
```

直接下载链接：
- [model.int8.onnx](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17.tar.bz2)（解压后取 `model.int8.onnx` 和 `tokens.txt`）

### 2. 说话人识别模型（可选）

用于区分不同发言人，不放此模型则跳过说话人识别功能。

- 下载地址：https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-recongition-models
- 推荐模型：`3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx`（38MB，中文优化）

放入设备路径：

```
/sdcard/sherpa-models/speaker/
└── 3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx
```

直接下载链接：
- [3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx](https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx)

### 3. VAD 模型（已内置）

silero-vad 模型（`silero_vad.onnx`，629KB）已打包在 APK assets 中，首次启动自动解压，无需手动下载。

## 编译运行

```bash
cd AudioListener

# 编译 Debug APK
./gradlew assembleDebug

# 输出路径：app/build/outputs/apk/debug/app-debug.apk
```

## 项目结构

```
app/src/main/java/com/openclaw/audiolistener/
├── MainActivity.kt          # 主界面，权限请求、服务绑定、灵敏度滑块
├── TranscriptionService.kt  # 前台服务，协调 VAD + 语音识别 + 说话人识别
├── AudioCapture.kt          # 系统音频 / 麦克风音频捕获
├── SherpaEngine.kt          # 封装 sherpa-onnx 离线识别引擎
├── SpeakerIdentifier.kt     # 说话人嵌入提取 + 余弦相似度聚类
├── SherpaHelper.java        # Java 辅助类（绕过 Kotlin null-safety）
└── SpeakerHelper.java       # Java 辅助类（说话人模型加载）
```

## 工作流程

1. 用户选择音频源（系统音频 / 麦克风），点击「开始转录」
2. 请求必要权限，启动前台服务
3. 加载 VAD 模型、SenseVoice 识别模型、说话人模型（如有）
4. 音频数据实时送入 silero-vad 检测语音活动
5. VAD 检测到完整语句后，将音频段送入 SenseVoice 识别文字
6. 同时用 3D-Speaker 提取说话人嵌入，通过余弦相似度匹配或注册新说话人
7. 结果以 `[说话人N] 识别文字` 格式显示在界面上

## 依赖

- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) v1.12.29 — 离线语音识别引擎（AAR 包位于 `app/libs/`）
- AndroidX AppCompat, Material, ConstraintLayout, Lifecycle

## License

见项目根目录 LICENSE 文件。
