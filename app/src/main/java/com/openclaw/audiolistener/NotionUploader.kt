package com.openclaw.audiolistener

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 将转录文本上传到 Notion 页面。
 *
 * 使用方法：
 * 1. 在 Notion 创建 Integration，获取 API Key
 * 2. 将 API Key 保存到 /sdcard/AudioListener/notion_api_key.txt
 * 3. 将目标页面 ID 保存到 /sdcard/AudioListener/notion_page_id.txt
 * 4. 在 Notion 中将页面共享给你的 Integration
 */
object NotionUploader {
    private const val TAG = "NotionUploader"
    private const val API_VERSION = "2022-06-28"
    private const val BASE_URL = "https://api.notion.com/v1"
    private val executor = Executors.newSingleThreadExecutor()

    private fun getApiKey(): String? {
        val file = File("/sdcard/AudioListener/notion_api_key.txt")
        return if (file.exists()) file.readText().trim() else null
    }

    private fun getPageId(): String? {
        val file = File("/sdcard/AudioListener/notion_page_id.txt")
        return if (file.exists()) file.readText().trim() else null
    }

    fun isConfigured(): Boolean = getApiKey() != null && getPageId() != null

    /**
     * 上传今天的转录文件内容到 Notion 页面（作为子页面）
     */
    fun uploadTodayFile(callback: (Boolean, String) -> Unit) {
        executor.execute {
            try {
                val apiKey = getApiKey()
                val parentPageId = getPageId()
                if (apiKey == null || parentPageId == null) {
                    callback(false, "未配置 Notion API Key 或页面 ID")
                    return@execute
                }

                val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                val file = File("/sdcard/AudioListener/$dateStr.txt")
                if (!file.exists()) {
                    callback(false, "今日无转录文件")
                    return@execute
                }

                val lines = file.readLines().filter { it.isNotBlank() }
                if (lines.isEmpty()) {
                    callback(false, "转录文件为空")
                    return@execute
                }

                // 创建子页面
                val pageId = createPage(apiKey, parentPageId, "转录 $dateStr")
                if (pageId == null) {
                    callback(false, "创建 Notion 页面失败")
                    return@execute
                }

                // 分批追加内容（每批最多 100 个 block）
                val chunks = lines.chunked(100)
                for (chunk in chunks) {
                    appendBlocks(apiKey, pageId, chunk)
                }

                callback(true, "已上传 ${lines.size} 行到 Notion")
            } catch (e: Exception) {
                Log.e(TAG, "Upload failed: ${e.message}", e)
                callback(false, "上传失败: ${e.message}")
            }
        }
    }

    private fun createPage(apiKey: String, parentId: String, title: String): String? {
        val body = JSONObject().apply {
            put("parent", JSONObject().put("page_id", parentId))
            put("properties", JSONObject().put("title", JSONObject()
                .put("title", JSONArray().put(JSONObject()
                    .put("text", JSONObject().put("content", title))))))
        }
        val resp = post("$BASE_URL/pages", apiKey, body.toString())
        return resp?.optString("id")
    }

    private fun appendBlocks(apiKey: String, pageId: String, lines: List<String>) {
        val children = JSONArray()
        for (line in lines) {
            children.put(JSONObject().apply {
                put("object", "block")
                put("type", "paragraph")
                put("paragraph", JSONObject().put("rich_text", JSONArray().put(
                    JSONObject().put("text", JSONObject().put("content", line))
                )))
            })
        }
        val body = JSONObject().put("children", children)
        patch("$BASE_URL/blocks/$pageId/children", apiKey, body.toString())
    }

    private fun post(url: String, apiKey: String, body: String): JSONObject? {
        return request("POST", url, apiKey, body)
    }

    private fun patch(url: String, apiKey: String, body: String): JSONObject? {
        return request("PATCH", url, apiKey, body)
    }

    private fun request(method: String, url: String, apiKey: String, body: String): JSONObject? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.setRequestProperty("Notion-Version", API_VERSION)
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toByteArray()) }
        val code = conn.responseCode
        val resp = if (code in 200..299) conn.inputStream.bufferedReader().readText()
                   else conn.errorStream?.bufferedReader()?.readText() ?: ""
        Log.d(TAG, "$method $url -> $code")
        if (code !in 200..299) {
            Log.e(TAG, "Notion API error: $resp")
            return null
        }
        return JSONObject(resp)
    }
}
