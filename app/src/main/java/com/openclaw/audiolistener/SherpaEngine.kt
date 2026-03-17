package com.openclaw.audiolistener

import com.k2fsa.sherpa.onnx.*
import android.content.Context
import android.util.Log

class SherpaEngine {
    private var offlineRecognizer: OfflineRecognizer? = null
    private var onlineRecognizer: OnlineRecognizer? = null
    private var onlineStream: OnlineStream? = null
    private val TAG = "SherpaEngine"

    var isStreamingMode = false
        private set

    // ── 离线模式（VAD + SenseVoice）──

    fun initOffline(context: Context, modelDir: String, language: String = "auto") {
        try {
            Log.i(TAG, "initOffline: modelDir=$modelDir, language=$language")
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = "$modelDir/model.int8.onnx",
                        language = language,
                        useInverseTextNormalization = true,
                    ),
                    tokens = "$modelDir/tokens.txt",
                    numThreads = 4,
                    debug = false,
                    provider = "cpu",
                ),
            )
            offlineRecognizer = SherpaHelper.createRecognizer(config)
            isStreamingMode = false
            Log.i(TAG, "Offline recognizer initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init offline: ${e.message}", e)
        }
    }

    // ── 流式模式（Streaming Paraformer）──

    fun initStreaming(modelDir: String) {
        try {
            Log.i(TAG, "initStreaming: modelDir=$modelDir")
            val encoderFile = java.io.File("$modelDir/encoder.int8.onnx")
            val decoderFile = java.io.File("$modelDir/decoder.int8.onnx")
            val tokensFile = java.io.File("$modelDir/tokens.txt")
            if (!encoderFile.exists() || !decoderFile.exists() || !tokensFile.exists()) {
                Log.e(TAG, "Streaming model files not found in $modelDir")
                return
            }
            val config = OnlineRecognizerConfig(
                modelConfig = OnlineModelConfig(
                    paraformer = OnlineParaformerModelConfig(
                        encoder = "$modelDir/encoder.int8.onnx",
                        decoder = "$modelDir/decoder.int8.onnx",
                    ),
                    tokens = "$modelDir/tokens.txt",
                    numThreads = 4,
                    debug = false,
                    provider = "cpu",
                ),
                endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(false, 2.4f, 0f),
                    rule2 = EndpointRule(true, 1.2f, 0f),
                    rule3 = EndpointRule(false, 0f, 20f),
                ),
                enableEndpoint = true,
                decodingMethod = "greedy_search",
            )
            onlineRecognizer = SherpaHelper.createOnlineRecognizer(config)
            onlineStream = onlineRecognizer?.createStream()
            isStreamingMode = true
            Log.i(TAG, "Streaming recognizer initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init streaming: ${e.message}", e)
        }
    }

    fun isReady(): Boolean = if (isStreamingMode) onlineRecognizer != null else offlineRecognizer != null

    // ── 离线解码 ──

    fun decodeFloat(samples: FloatArray, sampleRate: Int = 16000): String {
        val r = offlineRecognizer ?: return "[引擎未初始化]"
        return try {
            val stream = r.createStream()
            stream.acceptWaveform(samples, sampleRate)
            r.decode(stream)
            val result = r.getResult(stream)
            stream.release()
            result.text.ifEmpty { "[无识别结果]" }
        } catch (e: Exception) {
            Log.e(TAG, "decodeFloat error: ${e.message}", e)
            "[转录出错: ${e.message}]"
        }
    }

    // ── 流式：喂入音频 ──

    fun feedStreaming(samples: FloatArray, sampleRate: Int = 16000) {
        val stream = onlineStream ?: return
        stream.acceptWaveform(samples, sampleRate)
    }

    // ── 流式：尝试获取结果 ──

    fun getStreamingResult(): StreamingResult? {
        val r = onlineRecognizer ?: return null
        val s = onlineStream ?: return null
        if (!r.isReady(s)) return null
        r.decode(s)
        val result = r.getResult(s)
        val text = result.text.trim()
        val isEndpoint = r.isEndpoint(s)
        if (isEndpoint) {
            r.reset(s)
        }
        return if (text.isNotEmpty()) StreamingResult(text, isEndpoint) else null
    }

    data class StreamingResult(val text: String, val isFinal: Boolean)

    fun release() {
        onlineStream?.release()
        onlineStream = null
        onlineRecognizer?.release()
        onlineRecognizer = null
        offlineRecognizer?.release()
        offlineRecognizer = null
        isStreamingMode = false
        Log.i(TAG, "SherpaEngine released")
    }
}
