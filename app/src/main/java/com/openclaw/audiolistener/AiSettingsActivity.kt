package com.openclaw.audiolistener

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class AiSettingsActivity : AppCompatActivity() {

    private lateinit var spinnerProvider: Spinner
    private lateinit var etApiKey: EditText
    private lateinit var layoutCustom: LinearLayout
    private lateinit var etCustomUrl: EditText
    private lateinit var etCustomModel: EditText
    private lateinit var tvProviderHint: TextView
    private lateinit var btnSave: Button
    private lateinit var tvTestResult: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_settings)

        spinnerProvider = findViewById(R.id.spinnerProvider)
        etApiKey = findViewById(R.id.etApiKey)
        layoutCustom = findViewById(R.id.layoutCustom)
        etCustomUrl = findViewById(R.id.etCustomUrl)
        etCustomModel = findViewById(R.id.etCustomModel)
        tvProviderHint = findViewById(R.id.tvProviderHint)
        btnSave = findViewById(R.id.btnSave)
        tvTestResult = findViewById(R.id.tvTestResult)

        val providerNames = AiConfig.PROVIDERS.map { it.name }.toTypedArray()
        spinnerProvider.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, providerNames)

        spinnerProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                layoutCustom.visibility = if (pos == 2) View.VISIBLE else View.GONE
                val p = AiConfig.PROVIDERS[pos]
                tvProviderHint.text = when (pos) {
                    0 -> "免费额度，注册即用：https://open.bigmodel.cn"
                    1 -> "免费额度，注册即用：https://siliconflow.cn"
                    else -> "填写兼容 OpenAI 格式的 API 地址"
                }
                etApiKey.hint = p.keyHint
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 恢复已保存的配置
        spinnerProvider.setSelection(AiConfig.getProviderIndex(this))
        etApiKey.setText(AiConfig.getApiKey(this))
        etCustomUrl.setText(AiConfig.getCustomUrl(this))
        etCustomModel.setText(AiConfig.getCustomModel(this))

        btnSave.setOnClickListener {
            val idx = spinnerProvider.selectedItemPosition
            val key = etApiKey.text.toString().trim()
            val url = etCustomUrl.text.toString().trim()
            val model = etCustomModel.text.toString().trim()

            if (key.isBlank()) {
                tvTestResult.text = "请输入 API Key"
                return@setOnClickListener
            }
            if (idx == 2 && url.isBlank()) {
                tvTestResult.text = "自定义模式需要填写 API 地址"
                return@setOnClickListener
            }
            if (idx == 2 && model.isBlank()) {
                tvTestResult.text = "自定义模式需要填写模型名称"
                return@setOnClickListener
            }

            AiConfig.save(this, idx, key, url, model)
            tvTestResult.text = "✅ 已保存"
            Toast.makeText(this, "AI 配置已保存", Toast.LENGTH_SHORT).show()
        }
    }
}
