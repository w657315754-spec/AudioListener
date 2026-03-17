package com.openclaw.audiolistener

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.k2fsa.sherpa.onnx.*
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class TranscriptionService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_USE_MIC = "use_mic"
        const val EXTRA_LANGUAGE = "language"
        const val EXTRA_SILENCE_DURATION = "silence_duration"
        const val EXTRA_STREAMING = "streaming"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "transcription_channel"
        private const val TAG = "TranscriptionService"
        private const val SAMPLE_RATE = 16000
    }

    inner class LocalBinder : Binder() {
        fun getService() = this@TranscriptionService
    }

    private val binder = LocalBinder()
    private val audioCapture = AudioCapture()
    private val sherpaEngine = SherpaEngine()
    private val speakerIdentifier = SpeakerIdentifier()
    private var vad: Vad? = null
    private var mediaProjection: MediaProjection? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val decodeExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile var onTranscriptionResult: ((String) -> Unit)? = null
    @Volatile var onStatusUpdate: ((String) -> Unit)? = null
    @Volatile var onStreamingPartial: ((String) -> Unit)? = null

    var speakerThreshold: Float
        get() = speakerIdentifier.similarityThreshold
        set(value) { speakerIdentifier.similarityThreshold = value }

    private var useStreaming = false
    @Volatile private var lastPartialText = ""
    // 流式模式：上一次端点确认的时间戳，用于判断长停顿插入段落分隔
    @Volatile private var lastEndpointTime = 0L
    // 长停顿阈值（毫秒），超过此值插入空行
    private val paragraphPauseMs = 3000L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())

        val useMic = intent?.getBooleanExtra(EXTRA_USE_MIC, false) ?: false
        val language = intent?.getStringExtra(EXTRA_LANGUAGE) ?: "auto"
        val silenceDuration = intent?.getFloatExtra(EXTRA_SILENCE_DURATION, 0.4f) ?: 0.4f
        useStreaming = intent?.getBooleanExtra(EXTRA_STREAMING, false) ?: false
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        Thread {
            mainHandler.post { onStatusUpdate?.invoke("初始化中，请稍候...") }

            if (useStreaming) {
                initStreamingMode()
            } else {
                initOfflineMode(language, silenceDuration)
            }

            if (!sherpaEngine.isReady()) {
                val hint = if (useStreaming)
                    "流式模型加载失败，请检查 /sdcard/sherpa-models/streaming-paraformer/ 目录"
                else
                    "模型加载失败，请检查 /sdcard/sherpa-models/sense-voice/ 目录"
                mainHandler.post { onStatusUpdate?.invoke(hint) }
                stopSelf()
                return@Thread
            }

            if (!useStreaming) {
                initSpeakerIdentifier()
            }

            val modeDesc = if (useStreaming) "流式" else {
                val sp = if (speakerIdentifier.isReady()) " + 说话人识别" else ""
                "VAD$sp"
            }

            if (useMic) {
                mainHandler.post { onStatusUpdate?.invoke("麦克风转录中（${modeDesc}）...") }
                startMicCapture()
            } else if (resultCode != Int.MIN_VALUE && resultData != null) {
                val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = mgr.getMediaProjection(resultCode, resultData)
                mainHandler.post { onStatusUpdate?.invoke("系统音频转录中（${modeDesc}）...") }
                startSystemCapture()
            } else {
                mainHandler.post { onStatusUpdate?.invoke("缺少必要参数") }
                stopSelf()
            }
        }.start()

        return START_NOT_STICKY
    }

    private fun initStreamingMode() {
        sherpaEngine.initStreaming("/sdcard/sherpa-models/streaming-paraformer")
        Log.i(TAG, "Streaming mode initialized, ready=${sherpaEngine.isReady()}")
    }

    private fun initOfflineMode(language: String, silenceDuration: Float) {
        val vadModelPath = extractAsset("silero_vad.onnx")
        if (vadModelPath != null) {
            initVad(vadModelPath, silenceDuration)
        }
        sherpaEngine.initOffline(this, "/sdcard/sherpa-models/sense-voice", language)
        Log.i(TAG, "Offline mode initialized, ready=${sherpaEngine.isReady()}")
    }

    private fun initSpeakerIdentifier() {
        val path = "/sdcard/sherpa-models/speaker/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx"
        if (File(path).exists()) {
            if (speakerIdentifier.init(path)) {
                Log.i(TAG, "Speaker identifier loaded")
            }
        }
    }

    private fun extractAsset(name: String): String? {
        val outFile = File(filesDir, name)
        if (outFile.exists()) return outFile.absolutePath
        return try {
            assets.open(name).use { input ->
                FileOutputStream(outFile).use { output -> input.copyTo(output) }
            }
            outFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "extractAsset failed: ${e.message}")
            null
        }
    }

    private fun initVad(modelPath: String, silenceDuration: Float) {
        try {
            val config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = modelPath,
                    threshold = 0.45f,
                    minSilenceDuration = silenceDuration,
                    minSpeechDuration = 0.15f,
                    maxSpeechDuration = 15f,
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 2,
                provider = "cpu",
            )
            vad = Vad(null, config)
            Log.i(TAG, "VAD initialized, silenceDuration=$silenceDuration")
        } catch (e: Exception) {
            Log.e(TAG, "VAD init failed: ${e.message}", e)
        }
    }

    private fun startSystemCapture() {
        val mp = mediaProjection ?: return
        audioCapture.startSystemCapture(mp) { data -> onAudioData(data) }
    }

    private fun startMicCapture() {
        audioCapture.startMicCapture { data -> onAudioData(data) }
    }

    private fun onAudioData(data: ShortArray) {
        if (useStreaming) {
            onAudioDataStreaming(data)
        } else {
            onAudioDataOffline(data)
        }
    }

    /** 流式模式：直接喂入 OnlineRecognizer，轮询结果 */
    private fun onAudioDataStreaming(data: ShortArray) {
        val samples = FloatArray(data.size) { data[it] / 32768.0f }
        sherpaEngine.feedStreaming(samples, SAMPLE_RATE)

        val result = sherpaEngine.getStreamingResult() ?: return
        if (result.isFinal) {
            // 端点检测到句子结束 → 作为最终结果
            var text = result.text.trim()
            if (text.isNotEmpty()) {
                // 简单标点：根据末尾字符判断
                text = addSimplePunctuation(text)
                // 长停顿段落分隔
                val now = System.currentTimeMillis()
                val needParagraph = lastEndpointTime > 0 && (now - lastEndpointTime) > paragraphPauseMs
                lastEndpointTime = now

                lastPartialText = ""
                mainHandler.post { onStreamingPartial?.invoke("") }
                if (needParagraph) {
                    emitResult("\n$text")
                } else {
                    emitResult(text)
                }
            }
        } else {
            // 中间结果 → 覆盖显示
            val text = result.text.trim()
            if (text != lastPartialText) {
                lastPartialText = text
                mainHandler.post { onStreamingPartial?.invoke(text) }
            }
        }
    }

    /** 简单标点规则：给没有标点的句子末尾加标点 */
    private fun addSimplePunctuation(text: String): String {
        if (text.isEmpty()) return text
        val last = text.last()
        // 已有标点则不加
        if (last in "。，！？、；：…—,.!?;:") return text
        // 疑问词结尾加问号
        val questionEndings = charArrayOf('吗', '呢', '吧', '么', '嘛', '啊')
        return if (last in questionEndings) "${text}？" else "${text}。"
    }

    /** 离线模式：VAD 分段 → SenseVoice 解码 */
    private fun onAudioDataOffline(data: ShortArray) {
        val v = vad ?: return
        val samples = FloatArray(data.size) { data[it] / 32768.0f }
        v.acceptWaveform(samples)

        while (!v.empty()) {
            val segment = v.front()
            v.pop()
            val duration = segment.samples.size.toFloat() / SAMPLE_RATE
            if (duration < 0.1f) continue

            val segSamples = segment.samples
            decodeExecutor.submit {
                val text = sherpaEngine.decodeFloat(segSamples, SAMPLE_RATE)
                if (text.isNotBlank() && text != "[无识别结果]" && text != "[引擎未初始化]") {
                    val labeled = if (speakerIdentifier.isReady()) {
                        val label = speakerIdentifier.identify(segSamples, SAMPLE_RATE)
                        "[$label] $text"
                    } else text
                    emitResult(labeled)
                }
            }
        }
    }

    private fun emitResult(text: String) {
        TextSaver.save(text)
        mainHandler.post { onTranscriptionResult?.invoke(text) }
        // 仅在悬浮窗已启动时发送文字，避免自动弹出悬浮窗
        if (OverlayService.instance != null) {
            try {
                val overlayIntent = Intent(this, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_APPEND_TEXT
                    putExtra(OverlayService.EXTRA_TEXT, text)
                }
                startService(overlayIntent)
            } catch (e: Exception) {
                Log.w(TAG, "Overlay send failed: ${e.message}")
            }
        }
    }

    fun stopCapture() {
        audioCapture.stop()
        mediaProjection?.stop()
        mediaProjection = null
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        stopCapture()
        sherpaEngine.release()
        speakerIdentifier.release()
        vad?.release()
        vad = null
        decodeExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AudioListener")
            .setContentText("转录中...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "转录服务",
                NotificationManager.IMPORTANCE_LOW
            )
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(channel)
        }
    }
}
