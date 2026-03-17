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
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "transcription_channel"
        private const val TAG = "TranscriptionService"
        private const val SAMPLE_RATE = 16000

        @Volatile var overlayInstance: OverlayService? = null
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

    var speakerThreshold: Float
        get() = speakerIdentifier.similarityThreshold
        set(value) { speakerIdentifier.similarityThreshold = value }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())

        val useMic = intent?.getBooleanExtra(EXTRA_USE_MIC, false) ?: false
        val language = intent?.getStringExtra(EXTRA_LANGUAGE) ?: "auto"
        val silenceDuration = intent?.getFloatExtra(EXTRA_SILENCE_DURATION, 0.4f) ?: 0.4f
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        Thread {
            mainHandler.post { onStatusUpdate?.invoke("初始化中，请稍候...") }

            // 初始化 VAD
            val vadModelPath = extractAsset("silero_vad.onnx")
            if (vadModelPath == null) {
                mainHandler.post { onStatusUpdate?.invoke("VAD 模型解压失败") }
                stopSelf()
                return@Thread
            }
            initVad(vadModelPath, silenceDuration)

            // 初始化语音识别引擎
            sherpaEngine.init(this, "/sdcard/sherpa-models/sense-voice", language)
            if (!sherpaEngine.isReady()) {
                Log.e(TAG, "Model failed to load")
                mainHandler.post { onStatusUpdate?.invoke("模型加载失败，请检查 /sdcard/sherpa-models/sense-voice/ 目录") }
                stopSelf()
                return@Thread
            }
            Log.i(TAG, "Model and VAD loaded")

            // 初始化说话人识别（可选，模型不存在则跳过）
            val speakerModelPath = "/sdcard/sherpa-models/speaker/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx"
            if (java.io.File(speakerModelPath).exists()) {
                if (speakerIdentifier.init(speakerModelPath)) {
                    Log.i(TAG, "Speaker identifier loaded")
                } else {
                    Log.w(TAG, "Speaker identifier init failed, continuing without speaker ID")
                }
            } else {
                Log.i(TAG, "Speaker model not found at $speakerModelPath, speaker ID disabled")
            }

            if (useMic) {
                val mode = if (speakerIdentifier.isReady()) "VAD + 说话人识别" else "VAD"
                mainHandler.post { onStatusUpdate?.invoke("麦克风转录中（${mode}已启用）...") }
                startMicCapture()
            } else if (resultCode != Int.MIN_VALUE && resultData != null) {
                val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = mgr.getMediaProjection(resultCode, resultData)
                val mode = if (speakerIdentifier.isReady()) "VAD + 说话人识别" else "VAD"
                mainHandler.post { onStatusUpdate?.invoke("系统音频转录中（${mode}已启用）...") }
                startSystemCapture()
            } else {
                Log.e(TAG, "Missing MediaProjection data and not mic mode")
                mainHandler.post { onStatusUpdate?.invoke("缺少必要参数") }
                stopSelf()
            }
        }.start()

        return START_NOT_STICKY
    }

    private fun extractAsset(name: String): String? {
        val outFile = File(filesDir, name)
        if (outFile.exists()) return outFile.absolutePath
        return try {
            assets.open(name).use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
            outFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract asset $name: ${e.message}")
            null
        }
    }

    private fun initVad(modelPath: String, silenceDuration: Float = 0.4f) {
        val config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = modelPath,
                threshold = 0.45f,
                minSilenceDuration = silenceDuration,
                minSpeechDuration = 0.15f,
                windowSize = 512,
                maxSpeechDuration = 15f,
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
            provider = "cpu",
            debug = false,
        )
        vad = Vad(null, config)
        Log.i(TAG, "VAD initialized")
    }

    private fun startSystemCapture() {
        val projection = mediaProjection ?: return
        audioCapture.startSystemCapture(projection) { pcm -> onAudioData(pcm) }
        Log.i(TAG, "System audio capture started")
    }

    private fun startMicCapture() {
        audioCapture.startMicCapture { pcm -> onAudioData(pcm) }
        Log.i(TAG, "Mic capture started")
    }

    private val vadLock = Any()

    private fun onAudioData(pcm: ShortArray) {
        val v = vad ?: return
        val floatSamples = FloatArray(pcm.size) { pcm[it] / 32768.0f }

        synchronized(vadLock) {
            var offset = 0
            while (offset + 512 <= floatSamples.size) {
                val window = floatSamples.copyOfRange(offset, offset + 512)
                v.acceptWaveform(window)

                while (!v.empty()) {
                    val segment = v.front()
                    v.pop()
                    val samples = segment.samples
                    val durationMs = (samples.size * 1000) / SAMPLE_RATE
                    Log.i(TAG, "VAD segment: ${samples.size} samples (${durationMs}ms)")

                    if (samples.size < SAMPLE_RATE / 10) {
                        Log.d(TAG, "Skipping short segment (<100ms)")
                        continue
                    }

                    mainHandler.post { onStatusUpdate?.invoke("语音段 ${durationMs}ms，识别中...") }

                    val samplesCopy = samples.copyOf()
                    decodeExecutor.execute {
                        val result = sherpaEngine.decodeFloat(samplesCopy, SAMPLE_RATE)
                        Log.i(TAG, "Decode result: [$result]")
                        if (result.isNotBlank() && result != "[无识别结果]") {
                            // 说话人识别
                            val speakerId = if (speakerIdentifier.isReady()) {
                                speakerIdentifier.identify(samplesCopy, SAMPLE_RATE)
                            } else 0
                            val displayText = if (speakerId > 0) {
                                "[说话人$speakerId] $result"
                            } else {
                                result
                            }
                            Log.i(TAG, "Speaker: $speakerId, text: $result")
                            mainHandler.post {
                                onTranscriptionResult?.invoke(displayText)
                                onStatusUpdate?.invoke("转录中...")
                                val overlay = overlayInstance ?: OverlayService.instance
                                if (overlay != null) {
                                    overlay.appendText(displayText)
                                }
                            }
                            // 保存到文件
                            TextSaver.save(displayText)
                        }
                    }
                }
                offset += 512
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
        decodeExecutor.shutdownNow()
        sherpaEngine.release()
        speakerIdentifier.release()
        vad?.release()
        vad = null
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
