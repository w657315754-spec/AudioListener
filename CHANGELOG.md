# 更新日志

## v1.3.0 (2026-03-17)

### 新功能

- 新增说话人识别：基于 3D-Speaker 嵌入模型，自动区分不同说话人并在转录文字前标注 `[说话人N]`
- 说话人识别为可选功能，模型文件不存在时自动跳过，不影响正常转录
- 使用余弦相似度聚类，自动发现新说话人（最多 10 人），增量更新说话人嵌入向量
- 界面新增「说话人区分灵敏度」滑块，可实时调节余弦相似度阈值（0.00 ~ 1.00，默认 0.40）
  - 值越低：越容易判定为同一说话人
  - 值越高：越容易区分为不同说话人
- 界面新增「识别语言」下拉选择：自动检测 / 中文 / English
- 界面新增「停顿间隔」滑块（0.05s ~ 2.00s，默认 0.40s），控制 VAD 判定语句结束的静音时长
- 所有设置（音频源、语言、停顿间隔、灵敏度）通过 SharedPreferences 自动保存，下次打开恢复
- 转录运行时语言和停顿间隔控件自动禁用，需停止后修改
- 界面提示说话人模型状态，引导用户下载放置

### 使用方法

- 下载 `3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx`（38MB）
- 放入手机 `/sdcard/sherpa-models/speaker/` 目录
- 下载地址：https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-recongition-models

## v1.2.0 (2026-03-17)

### 改进（识别准确率优化）

- 语言参数从 `"zh"` 改为 `"auto"`，让 SenseVoice 自动检测语言，参考官方示例推荐做法
- 移除强制 flush 机制，避免在语句中间截断导致识别错误，改为完全依赖 VAD 自然分段
- VAD `minSilenceDuration` 从 0.25s 调整到 0.4s，减少句中短暂停顿被误判为句子结束
- VAD `minSpeechDuration` 从 0.1s 调整到 0.15s，过滤更多噪声误触发
- VAD `maxSpeechDuration` 从 5s 调整到 15s，允许更长的连续语句完整识别
- 解码线程数从 2 提升到 4，加快识别速度
- 系统音频捕获新增 `USAGE_ASSISTANT` 匹配，可捕获语音助手等更多音频源

## v1.1.1 (2026-03-17)

### 改进

- 优化 VAD 参数：`maxSpeechDuration` 从 30s 降到 5s，`minSilenceDuration` 从 0.5s 降到 0.25s，`threshold` 从 0.5 降到 0.45，响应更快
- 新增强制 flush 机制：语音持续超过 4 秒未产生分段时主动 flush，避免长时间等待
- 最短片段阈值从 250ms 降到 100ms，减少丢弃有效语音

## v1.1.0 (2026-03-17)

### 新功能

- 集成 silero-vad 语音活动检测，按语句边界自动切分音频再送识别，大幅提升准确率
- VAD 模型（silero_vad.onnx，629KB）内置在 APK assets 中，首次启动自动解压，用户无需手动下载
- 状态栏显示 VAD 检测到的语音段时长

### 改进

- 不再使用固定 2 秒切片，改为 VAD 检测到完整语句后再解码
- 自动跳过过短片段（<250ms），减少误识别
- SherpaEngine 新增 `decodeFloat` 方法，VAD 输出的 float 样本直接送解码，避免多余转换

## v1.0.3 (2026-03-17)

### Bug 修复

- 修复系统音频模式显示"缺少必要参数"的问题：`Activity.RESULT_OK` 值为 `-1`，与默认哨兵值冲突，导致 resultCode 校验误判。改用 `Int.MIN_VALUE` 作为哨兵值

## v1.0.2 (2026-03-17)

### 新功能

- 新增麦克风模式开关：界面顶部增加 Switch，可选择「麦克风录音」或「系统音频捕获」两种模式
- 麦克风模式不需要 MediaProjection 权限，可直接录音转录
- 状态栏显示音频振幅信息，方便判断是否采集到有效音频
- 静音检测：当音频振幅过低时跳过解码并在状态栏提示

### 重构

- AudioCapture 拆分为 `startSystemCapture` 和 `startMicCapture` 两个方法，共享录音逻辑
- TranscriptionService 支持 `EXTRA_USE_MIC` 参数切换音频源
- AndroidManifest 添加 `FOREGROUND_SERVICE_MICROPHONE` 权限，服务类型增加 `microphone`

## v1.0.1 (2026-03-17)

### Bug 修复

- **线程安全**：`AudioCapture.isCapturing` 添加 `@Volatile` 注解，修复 `stop()` 后采集线程可能无法及时退出的问题
- **模型加载失败处理**：模型加载失败时不再继续启动音频采集，改为提示用户并自动停止服务

### 性能优化

- **解码线程分离**：语音识别解码从音频采集线程移到独立的 `ExecutorService`，避免阻塞采集导致音频数据丢失
- **音频缓冲区优化**：将 `MutableList<Short>` 替换为 `ShortArray` + offset，消除每个样本的 Short 装箱开销

### 代码清理

- 移除 AudioCapture 和 TranscriptionService 中的高频调试日志，减少 logcat 刷屏
- 移除 SherpaEngine 中的重复日志行
- 使用 `getParcelableExtra(key, class)` 替代已废弃的 API（兼容 API 33 以下）

## v1.0.0

- 初始版本
- 基于 MediaProjection 的系统音频捕获
- sherpa-onnx + SenseVoice 离线中文语音识别
- 前台服务持续转录
