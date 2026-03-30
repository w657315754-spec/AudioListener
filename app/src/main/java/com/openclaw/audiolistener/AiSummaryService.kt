package com.openclaw.audiolistener

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * AI 总结服务，通过 SSE 流式调用 OpenAI 兼容 API。
 */
object AiSummaryService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private const val SYSTEM_PROMPT = """你是一个专业的语音转录内容总结助手。请对以下转录文本进行结构化总结，输出格式如下：

## 一句话总结
（用一句话概括全文核心内容）

## 核心要点
1. （要点一）
2. （要点二）
3. （要点三）
（3-5条，每条简明扼要）

## 关键词
（用逗号分隔的关键词列表）

注意：
- 直接输出总结内容，不要有多余的开场白
- 如果转录内容较短或信息量不足，适当减少要点数量
- 保持客观准确，不要添加原文没有的信息"""

    fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * 流式请求 AI 总结。
     * @param onChunk 每收到一段文本回调
     * @param onDone 完成回调（完整文本）
     * @param onError 错误回调
     */
    fun streamSummary(
        context: Context,
        transcription: String,
        onChunk: (String) -> Unit,
        onDone: (String) -> Unit,
        onError: (String) -> Unit
    ): Call? {
        val apiKey = AiConfig.getApiKey(context)
        if (apiKey.isBlank()) {
            onError("请先在设置中配置 AI API Key")
            return null
        }

        val baseUrl = AiConfig.getEffectiveUrl(context).trimEnd('/')
        val model = AiConfig.getEffectiveModel(context)
        val url = "$baseUrl/chat/completions"

        val body = JSONObject().apply {
            put("model", model)
            put("stream", true)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                put(JSONObject().put("role", "user").put("content", "请总结以下转录内容：\n\n$transcription"))
            })
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val call = client.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) return
                onError("网络请求失败: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    val errBody = response.body?.string() ?: ""
                    val errMsg = try {
                        JSONObject(errBody).optJSONObject("error")?.optString("message") ?: errBody
                    } catch (_: Exception) { errBody }
                    onError("API 错误 (${response.code}): $errMsg")
                    response.close()
                    return
                }

                val fullText = StringBuilder()
                try {
                    val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        if (call.isCanceled()) break
                        val l = line!!.trim()
                        if (!l.startsWith("data:")) continue
                        val data = l.removePrefix("data:").trim()
                        if (data == "[DONE]") break
                        try {
                            val delta = JSONObject(data)
                                .getJSONArray("choices")
                                .getJSONObject(0)
                                .optJSONObject("delta")
                            val content = delta?.optString("content", "") ?: ""
                            if (content.isNotEmpty()) {
                                fullText.append(content)
                                onChunk(fullText.toString())
                            }
                        } catch (_: Exception) { /* skip malformed chunk */ }
                    }
                    reader.close()
                } catch (e: Exception) {
                    if (!call.isCanceled()) {
                        onError("读取响应失败: ${e.message}")
                        return
                    }
                }
                response.close()
                if (!call.isCanceled()) {
                    onDone(fullText.toString())
                }
            }
        })
        return call
    }
}
