package com.openclaw.audiolistener

import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 将转录文本追加保存到本地文件。
 * 默认路径：/sdcard/AudioListener/2026-03-17.txt
 */
object TextSaver {
    private const val TAG = "TextSaver"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun save(text: String) {
        try {
            val dir = File("/sdcard/AudioListener")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "${dateFormat.format(Date())}.txt")
            val timestamp = timeFormat.format(Date())
            FileWriter(file, true).use { it.appendLine("[$timestamp] $text") }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save: ${e.message}")
        }
    }

    fun getFilePath(): String {
        val dir = File("/sdcard/AudioListener")
        return File(dir, "${dateFormat.format(Date())}.txt").absolutePath
    }
}
