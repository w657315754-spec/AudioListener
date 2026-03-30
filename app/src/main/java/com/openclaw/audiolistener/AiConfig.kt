package com.openclaw.audiolistener

import android.content.Context
import android.content.SharedPreferences

/**
 * AI 总结配置管理。
 * 预置供应商：智谱 GLM、硅基流动 SiliconFlow、自定义 OpenAI 兼容接口。
 */
object AiConfig {
    private const val PREFS = "ai_config"
    private const val KEY_PROVIDER = "provider"       // 0=智谱, 1=硅基流动, 2=自定义
    private const val KEY_API_KEY = "api_key"
    private const val KEY_CUSTOM_URL = "custom_url"
    private const val KEY_CUSTOM_MODEL = "custom_model"

    data class Provider(
        val name: String,
        val baseUrl: String,
        val defaultModel: String,
        val keyHint: String
    )

    val PROVIDERS = listOf(
        Provider("智谱 GLM（免费额度）", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash", "智谱 API Key"),
        Provider("硅基流动 SiliconFlow（免费额度）", "https://api.siliconflow.cn/v1", "Qwen/Qwen2.5-7B-Instruct", "SiliconFlow API Key"),
        Provider("自定义 OpenAI 兼容接口", "", "", "API Key")
    )

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getProviderIndex(ctx: Context): Int = prefs(ctx).getInt(KEY_PROVIDER, 0)
    fun getApiKey(ctx: Context): String = prefs(ctx).getString(KEY_API_KEY, "") ?: ""
    fun getCustomUrl(ctx: Context): String = prefs(ctx).getString(KEY_CUSTOM_URL, "") ?: ""
    fun getCustomModel(ctx: Context): String = prefs(ctx).getString(KEY_CUSTOM_MODEL, "") ?: ""

    fun save(ctx: Context, providerIndex: Int, apiKey: String, customUrl: String, customModel: String) {
        prefs(ctx).edit()
            .putInt(KEY_PROVIDER, providerIndex)
            .putString(KEY_API_KEY, apiKey)
            .putString(KEY_CUSTOM_URL, customUrl)
            .putString(KEY_CUSTOM_MODEL, customModel)
            .apply()
    }

    /** 获取当前生效的 base URL */
    fun getEffectiveUrl(ctx: Context): String {
        val idx = getProviderIndex(ctx)
        return if (idx == 2) getCustomUrl(ctx) else PROVIDERS[idx].baseUrl
    }

    /** 获取当前生效的模型名 */
    fun getEffectiveModel(ctx: Context): String {
        val idx = getProviderIndex(ctx)
        return if (idx == 2) getCustomModel(ctx) else PROVIDERS[idx].defaultModel
    }

    fun isConfigured(ctx: Context): Boolean = getApiKey(ctx).isNotBlank()
}
