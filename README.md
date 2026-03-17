# AudioListener

Android 离线语音转录工具。支持捕获系统音频（MediaProjection）或麦克风录音，使用 [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 进行离线语音识别。支持两种识别模式：离线模式（SenseVoice + VAD 分段）和流式模式（Streaming Paraformer，边说边出字）。可选说话人识别区分不同发言人。

## 功能

- 两种音频源：系统音频捕获（媒体、游戏、语音助手等）/ 麦克风录音
- 两种识别模式：
  - 离线模式：SenseVoice + silero-vad，按语句边界自动切分后整段识别，准确率高
  - 流式模式：Streaming Paraformer，边说边出字，实时显示中间结果（🔄 前缀），端点检测自动断句
- 说话人识别（可选，仅离线模式）：基于 3D-Speaker 嵌入模型，自动区分不同发言人并标注 `[说话人N]`
- 说话人区分灵敏度可通过界面滑块实时调节
- 悬浮窗实时显示转录内容，可在其他应用上方查看，支持拖动移动、拖动缩放、透明度调节
- 转录文本自动保存到 `/sdcard/AudioListener/日期.txt`，带时间戳
- Notion 上传：将当天转录文件上传为 Notion 子页面
- 所有设置（音频源、语言、停顿间隔、灵敏度、悬浮窗）自动保存
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

### 3. 流式识别模型（可选，流式模式需要）

从 sherpa-onnx 发布页下载 Streaming Paraformer 双语模型：

- 下载地址：https://github.com/k2-fsa/sherpa-onnx/releases
- 模型名称：`sherpa-onnx-streaming-paraformer-bilingual-zh-en`
- 需要的文件：
  - `encoder.int8.onnx` — 编码器
  - `decoder.int8.onnx` — 解码器
  - `tokens.txt` — 词表文件

放入设备路径：

```
/sdcard/sherpa-models/streaming-paraformer/
├── encoder.int8.onnx
├── decoder.int8.onnx
└── tokens.txt
```

直接下载链接：
- [sherpa-onnx-streaming-paraformer-bilingual-zh-en.tar.bz2](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-paraformer-bilingual-zh-en.tar.bz2)（解压后取上述三个文件）

### 4. VAD 模型（已内置）

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
├── MainActivity.kt          # 主界面，权限请求、服务绑定、设置控件
├── TranscriptionService.kt  # 前台服务，协调 VAD + 语音识别 + 说话人识别（支持离线/流式双模式）
├── AudioCapture.kt          # 系统音频 / 麦克风音频捕获
├── SherpaEngine.kt          # 封装 sherpa-onnx 离线/流式识别引擎
├── SpeakerIdentifier.kt     # 说话人嵌入提取 + 余弦相似度聚类
├── OverlayService.kt        # 悬浮窗服务，通过 Intent 接收转录文字
├── TextSaver.kt             # 转录文本保存到本地文件
├── NotionUploader.kt        # 上传转录文件到 Notion
├── SherpaHelper.java        # Java 辅助类（绕过 Kotlin null-safety）
└── SpeakerHelper.java       # Java 辅助类（说话人模型加载）

app/src/main/res/layout/
├── activity_main.xml        # 主界面布局
└── overlay_layout.xml       # 悬浮窗布局
```

## 悬浮窗

开启悬浮窗开关后，转录文字会实时显示在屏幕上方的浮动窗口中，切换到其他应用也能看到。

- 顶部拖动条：按住拖动可移动窗口位置
- 底部拖动条：按住拖动可调节窗口大小
- 透明度滑块：调节悬浮窗背景透明度（10% ~ 100%）
- 位置、大小、透明度自动保存，下次打开恢复

首次使用需授予「显示在其他应用上方」权限。

## 文本保存与 Notion 上传

转录文本自动保存到 `/sdcard/AudioListener/YYYY-MM-DD.txt`，每条记录带时间戳。

如需上传到 Notion，在 `/sdcard/AudioListener/` 目录下创建两个配置文件：

- `notion_api_key.txt` — 填入 Notion Integration Token
- `notion_page_id.txt` — 填入目标页面 ID

点击界面底部「上传Notion」按钮即可将当天文件上传为子页面。

## 工作流程

### 离线模式（默认）

1. 用户选择音频源（系统音频 / 麦克风），点击「开始转录」
2. 请求必要权限，启动前台服务
3. 加载 VAD 模型、SenseVoice 识别模型、说话人模型（如有）
4. 音频数据实时送入 silero-vad 检测语音活动
5. VAD 检测到完整语句后，将音频段送入 SenseVoice 识别文字
6. 同时用 3D-Speaker 提取说话人嵌入，通过余弦相似度匹配或注册新说话人
7. 结果以 `[说话人N] 识别文字` 格式显示在界面上

### 流式模式

1. 开启「流式识别」开关，选择音频源，点击「开始转录」
2. 加载 Streaming Paraformer 模型
3. 音频数据实时喂入 OnlineRecognizer
4. 中间结果以 🔄 前缀实时覆盖显示
5. 端点检测到句子结束后，确认为最终结果并追加显示

## 依赖

- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) v1.12.29 — 离线语音识别引擎（AAR 包位于 `app/libs/`）
- AndroidX AppCompat, Material, ConstraintLayout, Lifecycle

## License

见项目根目录 LICENSE 文件。
