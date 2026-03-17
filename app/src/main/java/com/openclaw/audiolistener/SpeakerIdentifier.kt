package com.openclaw.audiolistener

import android.util.Log
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig

/**
 * 说话人识别：提取音频段的说话人嵌入向量，通过余弦相似度与已知说话人聚类。
 * 模型文件：3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx
 */
class SpeakerIdentifier {
    companion object {
        private const val TAG = "SpeakerIdentifier"
        private const val MAX_SPEAKERS = 10
    }

    @Volatile
    var similarityThreshold = 0.4f

    private var extractor: SpeakerEmbeddingExtractor? = null
    // 每个说话人保存最近的嵌入向量（取平均）
    private val speakerEmbeddings = mutableListOf<FloatArray>()
    private val speakerSampleCounts = mutableListOf<Int>()

    fun init(modelPath: String): Boolean {
        return try {
            val config = SpeakerEmbeddingExtractorConfig(
                model = modelPath,
                numThreads = 2,
                debug = false,
                provider = "cpu",
            )
            extractor = SpeakerHelper.createExtractor(config)
            Log.i(TAG, "SpeakerEmbeddingExtractor initialized, dim=${extractor?.dim()}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init SpeakerEmbeddingExtractor: ${e.message}", e)
            false
        }
    }

    fun isReady(): Boolean = extractor != null

    /**
     * 识别音频段的说话人，返回说话人编号（从 1 开始）。
     * 如果是新说话人则自动注册。
     */
    fun identify(samples: FloatArray, sampleRate: Int = 16000): Int {
        val ext = extractor ?: return 0
        return try {
            val stream = ext.createStream()
            stream.acceptWaveform(samples, sampleRate)
            if (!ext.isReady(stream)) {
                Log.w(TAG, "Stream not ready, audio too short?")
                return 0
            }
            val embedding = ext.compute(stream)
            stream.release()
            matchOrRegister(embedding)
        } catch (e: Exception) {
            Log.e(TAG, "identify error: ${e.message}", e)
            0
        }
    }

    private fun matchOrRegister(embedding: FloatArray): Int {
        var bestIdx = -1
        var bestSim = -1f

        for (i in speakerEmbeddings.indices) {
            val sim = cosineSimilarity(embedding, speakerEmbeddings[i])
            if (sim > bestSim) {
                bestSim = sim
                bestIdx = i
            }
        }

        if (bestIdx >= 0 && bestSim >= similarityThreshold) {
            // 更新该说话人的平均嵌入（增量平均）
            updateEmbedding(bestIdx, embedding)
            Log.d(TAG, "Matched speaker ${bestIdx + 1}, similarity=${"%.3f".format(bestSim)}")
            return bestIdx + 1
        }

        // 新说话人
        if (speakerEmbeddings.size >= MAX_SPEAKERS) {
            Log.w(TAG, "Max speakers reached ($MAX_SPEAKERS), assigning to closest")
            return if (bestIdx >= 0) bestIdx + 1 else 1
        }

        speakerEmbeddings.add(embedding.copyOf())
        speakerSampleCounts.add(1)
        val id = speakerEmbeddings.size
        Log.i(TAG, "New speaker registered: speaker $id")
        return id
    }

    private fun updateEmbedding(idx: Int, newEmbedding: FloatArray) {
        val count = speakerSampleCounts[idx]
        val current = speakerEmbeddings[idx]
        // 增量平均，最多用最近 20 个样本的权重
        val effectiveCount = count.coerceAtMost(20)
        val weight = 1.0f / (effectiveCount + 1)
        for (i in current.indices) {
            current[i] = current[i] * (1 - weight) + newEmbedding[i] * weight
        }
        speakerSampleCounts[idx] = count + 1
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = Math.sqrt(normA.toDouble()) * Math.sqrt(normB.toDouble())
        return if (denom > 0) (dot / denom).toFloat() else 0f
    }

    fun reset() {
        speakerEmbeddings.clear()
        speakerSampleCounts.clear()
        Log.i(TAG, "Speaker data reset")
    }

    fun release() {
        extractor?.release()
        extractor = null
        reset()
        Log.i(TAG, "SpeakerIdentifier released")
    }
}
