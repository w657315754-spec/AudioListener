package com.openclaw.audiolistener

import android.content.Context
import android.util.Log
import okhttp3.*
import java.io.File
import java.io.IOException

/**
 * 管理 sherpa-onnx 模型文件的下载。
 * 直接从 HuggingFace 下载单个文件，避免 tar.bz2 解压。
 */
class ModelDownloadManager(private val context: Context) {

    companion object {
        private const val TAG = "ModelDownload"

        // SenseVoice 模型（离线识别，必需）
        private const val BASE_URL = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main"
        val SENSE_VOICE_FILES = listOf(
            ModelFile("model.int8.onnx", "$BASE_URL/model.int8.onnx", 230_000_000L),
            ModelFile("tokens.txt", "$BASE_URL/tokens.txt", 500_000L),
        )
        const val SENSE_VOICE_DIR = "/sdcard/sherpa-models/sense-voice"

        // 流式模型（可选）
        private const val STREAMING_BASE = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en/resolve/main"
        val STREAMING_FILES = listOf(
            ModelFile("encoder.int8.onnx", "$STREAMING_BASE/encoder.int8.onnx", 70_000_000L),
            ModelFile("decoder.int8.onnx", "$STREAMING_BASE/decoder.int8.onnx", 12_000_000L),
            ModelFile("tokens.txt", "$STREAMING_BASE/tokens.txt", 100_000L),
        )
        const val STREAMING_DIR = "/sdcard/sherpa-models/streaming-paraformer"

        // 说话人识别模型（可选）
        private const val SPEAKER_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx"
        val SPEAKER_FILES = listOf(
            ModelFile("3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx", SPEAKER_URL, 38_000_000L),
        )
        const val SPEAKER_DIR = "/sdcard/sherpa-models/speaker"
    }

    data class ModelFile(val name: String, val url: String, val estimatedSize: Long)

    data class DownloadProgress(
        val currentFile: String,
        val fileIndex: Int,
        val totalFiles: Int,
        val bytesDownloaded: Long,
        val totalBytes: Long,
    ) {
        val percent: Int get() = if (totalBytes > 0) (bytesDownloaded * 100 / totalBytes).toInt() else 0
    }

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private var cancelled = false

    fun isSenseVoiceReady(): Boolean {
        val dir = File(SENSE_VOICE_DIR)
        return File(dir, "model.int8.onnx").exists() && File(dir, "tokens.txt").exists()
    }

    fun isStreamingReady(): Boolean {
        val dir = File(STREAMING_DIR)
        return File(dir, "encoder.int8.onnx").exists()
                && File(dir, "decoder.int8.onnx").exists()
                && File(dir, "tokens.txt").exists()
    }

    fun isSpeakerReady(): Boolean {
        return File(File(SPEAKER_DIR), "3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx").exists()
    }

    fun cancel() { cancelled = true }

    /**
     * 下载一组模型文件到指定目录。在后台线程调用。
     */
    fun downloadFiles(
        files: List<ModelFile>,
        targetDir: String,
        onProgress: (DownloadProgress) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit,
    ) {
        cancelled = false
        val dir = File(targetDir)
        if (!dir.exists()) dir.mkdirs()

        val totalSize = files.sumOf { it.estimatedSize }
        var downloadedSoFar = 0L

        for ((index, file) in files.withIndex()) {
            if (cancelled) {
                onError("下载已取消")
                return
            }

            val targetFile = File(dir, file.name)
            val tempFile = File(dir, "${file.name}.tmp")

            // 断点续传：检查临时文件
            var startByte = 0L
            if (tempFile.exists()) {
                startByte = tempFile.length()
            }

            val requestBuilder = Request.Builder().url(file.url)
            if (startByte > 0) {
                requestBuilder.header("Range", "bytes=$startByte-")
            }

            try {
                val response = client.newCall(requestBuilder.build()).execute()
                if (!response.isSuccessful && response.code != 206) {
                    onError("下载失败: ${file.name} (HTTP ${response.code})")
                    response.close()
                    return
                }

                val body = response.body ?: run {
                    onError("下载失败: ${file.name} (空响应)")
                    return
                }

                val contentLength = body.contentLength()
                val fileTotal = if (contentLength > 0) contentLength + startByte else file.estimatedSize

                val outputStream = if (startByte > 0 && response.code == 206) {
                    tempFile.outputStream().apply { /* append mode */ }
                    java.io.FileOutputStream(tempFile, true)
                } else {
                    startByte = 0
                    tempFile.outputStream()
                }

                val buffer = ByteArray(8192)
                val inputStream = body.byteStream()
                var bytesRead: Int
                var fileDownloaded = startByte

                outputStream.use { out ->
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        if (cancelled) {
                            onError("下载已取消")
                            return
                        }
                        out.write(buffer, 0, bytesRead)
                        fileDownloaded += bytesRead
                        downloadedSoFar += bytesRead

                        onProgress(DownloadProgress(
                            currentFile = file.name,
                            fileIndex = index,
                            totalFiles = files.size,
                            bytesDownloaded = downloadedSoFar,
                            totalBytes = totalSize,
                        ))
                    }
                }
                response.close()

                // 下载完成，重命名
                if (targetFile.exists()) targetFile.delete()
                tempFile.renameTo(targetFile)
                Log.i(TAG, "Downloaded: ${file.name} (${fileDownloaded} bytes)")

            } catch (e: IOException) {
                Log.e(TAG, "Download error: ${file.name}", e)
                onError("下载出错: ${file.name}\n${e.message}")
                return
            }
        }

        onComplete()
    }
}
