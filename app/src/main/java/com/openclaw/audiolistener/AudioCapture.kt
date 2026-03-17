package com.openclaw.audiolistener

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioAttributes
import android.util.Log

class AudioCapture {

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val TAG = "AudioCapture"
    }

    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    @Volatile
    private var isCapturing = false

    /** 通过 MediaProjection 捕获系统音频 */
    fun startSystemCapture(projection: MediaProjection, callback: (ShortArray) -> Unit) {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
            .coerceAtLeast(4096)

        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .addMatchingUsage(AudioAttributes.USAGE_ASSISTANT)
            .build()

        audioRecord = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(config)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL)
                    .setEncoding(ENCODING)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .build()

        startRecording(bufferSize, callback)
    }

    /** 通过麦克风捕获音频 */
    fun startMicCapture(callback: (ShortArray) -> Unit) {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
            .coerceAtLeast(4096)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL,
            ENCODING,
            bufferSize
        )

        startRecording(bufferSize, callback)
    }

    private fun startRecording(bufferSize: Int, callback: (ShortArray) -> Unit) {
        val ar = audioRecord
        if (ar == null || ar.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed, state=${ar?.state}")
            return
        }

        ar.startRecording()
        if (ar.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            Log.e(TAG, "startRecording failed, recordingState=${ar.recordingState}")
            return
        }
        isCapturing = true

        captureThread = Thread {
            val buffer = ShortArray(bufferSize / 2)
            while (isCapturing) {
                val read = ar.read(buffer, 0, buffer.size)
                when {
                    read > 0 -> callback(buffer.copyOf(read))
                    read < 0 -> Log.e(TAG, "read error: $read")
                }
            }
        }.also { it.start() }

        Log.i(TAG, "AudioCapture started, bufferSize=$bufferSize")
    }

    fun stop() {
        isCapturing = false
        captureThread?.join(1000)
        captureThread = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        Log.i(TAG, "AudioCapture stopped")
    }
}
