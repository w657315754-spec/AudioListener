package com.openclaw.audiolistener

import com.k2fsa.sherpa.onnx.*
import android.content.Context
import android.util.Log

class SherpaEngine {
    private var recognizer: OfflineRecognizer? = null
    private val TAG = "SherpaEngine"

    fun init(context: Context, modelDir: String) {
        try {
            Log.i(TAG, "init() called, modelDir=$modelDir")
            val modelFile = java.io.File("$modelDir/model.int8.onnx")
            val tokensFile = java.io.File("$modelDir/tokens.txt")
            Log.i(TAG, "model.int8.onnx exists=${modelFile.exists()}, size=${modelFile.length()}")
            Log.i(TAG, "tokens.txt exists=${tokensFile.exists()}, size=${tokensFile.length()}")
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = "$modelDir/model.int8.onnx",
                        language = "auto",
                        useInverseTextNormalization = true,
                    ),
                    tokens = "$modelDir/tokens.txt",
                    numThreads = 4,
                    debug = false,
                    provider = "cpu",
                ),
            )
            // 用 Java helper 绕过 Kotlin null-safety，JNI 层把路径当文件系统绝对路径处理
            recognizer = SherpaHelper.createRecognizer(config)
            Log.i(TAG, "Sherpa-ONNX initialized from $modelDir")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init Sherpa-ONNX: ${e.message}", e)
        }
    }

    fun isReady(): Boolean = recognizer != null

    fun decode(pcm: ShortArray, sampleRate: Int = 16000): String {
        val r = recognizer ?: run {
            Log.e(TAG, "decode called but recognizer is null!")
            return "[引擎未初始化，请将模型文件放入 /sdcard/sherpa-models/sense-voice/]"
        }
        return try {
            val floatArray = FloatArray(pcm.size) { pcm[it] / 32768.0f }
            decodeFloat(floatArray, sampleRate)
        } catch (e: Exception) {
            Log.e(TAG, "decode error: ${e.message}", e)
            "[转录出错: ${e.message}]"
        }
    }

    fun decodeFloat(samples: FloatArray, sampleRate: Int = 16000): String {
        val r = recognizer ?: run {
            Log.e(TAG, "decodeFloat called but recognizer is null!")
            return "[引擎未初始化]"
        }
        return try {
            val stream = r.createStream()
            stream.acceptWaveform(samples, sampleRate)
            r.decode(stream)
            val result = r.getResult(stream)
            stream.release()
            Log.d(TAG, "decode result: ${result.text}")
            result.text.ifEmpty { "[无识别结果]" }
        } catch (e: Exception) {
            Log.e(TAG, "decodeFloat error: ${e.message}", e)
            "[转录出错: ${e.message}]"
        }
    }

    fun release() {
        recognizer?.release()
        recognizer = null
        Log.i(TAG, "SherpaEngine released")
    }
}
