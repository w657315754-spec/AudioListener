package com.openclaw.audiolistener

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import okhttp3.Call
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var btnToggle: Button
    private lateinit var switchMic: Switch
    private lateinit var spinnerLanguage: Spinner
    private lateinit var seekSilence: SeekBar
    private lateinit var tvSilence: TextView
    private lateinit var seekThreshold: SeekBar
    private lateinit var tvThreshold: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvTranscription: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var switchOverlay: Switch
    private lateinit var seekAlpha: SeekBar
    private lateinit var tvAlpha: TextView
    private lateinit var tvFilePath: TextView
    private lateinit var btnNotion: Button
    private lateinit var btnShare: Button
    private lateinit var switchStreaming: Switch
    private lateinit var btnAiSummary: Button
    private lateinit var btnAiSettings: Button
    private lateinit var layoutSummary: LinearLayout
    private lateinit var tvSummary: TextView
    private lateinit var btnCopySummary: Button
    private lateinit var btnShareSummary: Button
    private lateinit var btnCloseSummary: Button

    private var currentSummaryCall: Call? = null

    // language codes matching spinner order
    private val languageCodes = arrayOf("auto", "zh", "en")

    private lateinit var prefs: SharedPreferences

    companion object {
        private const val PREFS_NAME = "audio_listener_settings"
        private const val KEY_USE_MIC = "use_mic"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_SILENCE = "silence_progress"
        private const val KEY_THRESHOLD = "threshold_progress"
        private const val KEY_OVERLAY = "overlay_enabled"
        private const val KEY_ALPHA = "overlay_alpha"
        private const val KEY_STREAMING = "streaming"
    }

    private var transcriptionService: TranscriptionService? = null
    private var isRunning = false
    private var pendingResultCode: Int = Int.MIN_VALUE
    private var pendingResultData: Intent? = null
    private var pendingUseMic: Boolean = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            transcriptionService = (binder as TranscriptionService.LocalBinder).getService()
            
            transcriptionService?.onTranscriptionResult = { text ->
                runOnUiThread { appendTranscription(text) }
            }
            transcriptionService?.onStatusUpdate = { status ->
                runOnUiThread { setStatus(status) }
            }
            transcriptionService?.onStreamingPartial = { partial ->
                runOnUiThread { updateStreamingPartial(partial) }
            }
            // 同步当前阈值到 service
            val threshold = seekThreshold.progress / 100f
            transcriptionService?.speakerThreshold = threshold
            val selectedLanguage = languageCodes[spinnerLanguage.selectedItemPosition]
            val silenceDuration = (seekSilence.progress + 1) * 0.05f
            val intent = Intent(this@MainActivity, TranscriptionService::class.java).apply {
                putExtra(TranscriptionService.EXTRA_USE_MIC, pendingUseMic)
                putExtra(TranscriptionService.EXTRA_LANGUAGE, selectedLanguage)
                putExtra(TranscriptionService.EXTRA_SILENCE_DURATION, silenceDuration)
                putExtra(TranscriptionService.EXTRA_STREAMING, switchStreaming.isChecked)
                if (!pendingUseMic) {
                    putExtra(TranscriptionService.EXTRA_RESULT_CODE, pendingResultCode)
                    putExtra(TranscriptionService.EXTRA_RESULT_DATA, pendingResultData)
                }
            }
            pendingResultCode = Int.MIN_VALUE
            pendingResultData = null
            startForegroundService(intent)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            transcriptionService = null
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            launchService(useMic = false, resultCode = result.resultCode, resultData = result.data!!)
        } else {
            setStatus("权限被拒绝")
            isRunning = false
            updateButton()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            onPermissionsGranted()
        } else {
            setStatus("缺少必要权限")
            isRunning = false
            updateButton()
        }
    }

    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        checkModelAndUpdateUI()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnToggle = findViewById(R.id.btnToggle)
        switchMic = findViewById(R.id.switchMic)
        spinnerLanguage = findViewById(R.id.spinnerLanguage)
        seekSilence = findViewById(R.id.seekSilence)
        tvSilence = findViewById(R.id.tvSilence)
        seekThreshold = findViewById(R.id.seekThreshold)
        tvThreshold = findViewById(R.id.tvThreshold)
        tvStatus = findViewById(R.id.tvStatus)
        tvTranscription = findViewById(R.id.tvTranscription)
        scrollView = findViewById(R.id.scrollView)
        switchOverlay = findViewById(R.id.switchOverlay)
        seekAlpha = findViewById(R.id.seekAlpha)
        tvAlpha = findViewById(R.id.tvAlpha)
        tvFilePath = findViewById(R.id.tvFilePath)
        btnNotion = findViewById(R.id.btnNotion)
        btnShare = findViewById(R.id.btnShare)
        switchStreaming = findViewById(R.id.switchStreaming)
        btnAiSummary = findViewById(R.id.btnAiSummary)
        btnAiSettings = findViewById(R.id.btnAiSettings)
        layoutSummary = findViewById(R.id.layoutSummary)
        tvSummary = findViewById(R.id.tvSummary)
        btnCopySummary = findViewById(R.id.btnCopySummary)
        btnShareSummary = findViewById(R.id.btnShareSummary)
        btnCloseSummary = findViewById(R.id.btnCloseSummary)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // 语言选择 Spinner
        val languageLabels = arrayOf("自动检测", "中文", "English")
        spinnerLanguage.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, languageLabels)

        // 恢复保存的设置
        switchMic.isChecked = prefs.getBoolean(KEY_USE_MIC, false)
        switchStreaming.isChecked = prefs.getBoolean(KEY_STREAMING, false)
        spinnerLanguage.setSelection(prefs.getInt(KEY_LANGUAGE, 0))
        seekSilence.progress = prefs.getInt(KEY_SILENCE, 7)   // 默认 (7+1)*0.05 = 0.40s
        seekThreshold.progress = prefs.getInt(KEY_THRESHOLD, 40) // 默认 40/100 = 0.40

        // 初始化显示文本
        tvSilence.text = "${"%.2f".format((seekSilence.progress + 1) * 0.05f)}s"
        tvThreshold.text = "%.2f".format(seekThreshold.progress / 100f)

        // 停顿间隔 SeekBar: 范围 0.05s ~ 2.00s，步长 0.05s
        // max=39, progress 0~39 → duration = (progress + 1) * 0.05
        seekSilence.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val duration = (progress + 1) * 0.05f
                tvSilence.text = "${"%.2f".format(duration)}s"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // 说话人灵敏度 SeekBar: 范围 0.00 ~ 1.00，默认 0.40
        // max=100, progress 0~100 → threshold = progress / 100
        seekThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val threshold = progress / 100f
                tvThreshold.text = "%.2f".format(threshold)
                transcriptionService?.speakerThreshold = threshold
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        btnToggle.setOnClickListener {
            if (isRunning) stopTranscription() else startTranscription()
        }

        // 悬浮窗开关
        switchOverlay.isChecked = prefs.getBoolean(KEY_OVERLAY, false)
        seekAlpha.progress = prefs.getInt(KEY_ALPHA, 75) // (75+10) = 85%
        tvAlpha.text = "${seekAlpha.progress + 10}%"

        switchOverlay.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                if (Settings.canDrawOverlays(this)) {
                    startService(Intent(this, OverlayService::class.java))
                } else {
                    switchOverlay.isChecked = false
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")))
                }
            } else {
                stopService(Intent(this, OverlayService::class.java))
            }
        }

        seekAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val pct = progress + 10 // 10% ~ 100%
                tvAlpha.text = "$pct%"
                OverlayService.instance?.setOverlayAlpha(pct / 100f)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // 文件路径
        tvFilePath.text = "保存: ${TextSaver.getFilePath()}"

        // Notion 上传
        btnNotion.setOnClickListener {
            if (!NotionUploader.isConfigured()) {
                setStatus("请配置 /sdcard/AudioListener/notion_api_key.txt 和 notion_page_id.txt")
                return@setOnClickListener
            }
            setStatus("正在上传到 Notion...")
            NotionUploader.uploadTodayFile { success, msg ->
                runOnUiThread { setStatus(msg) }
            }
        }

        // AI 修正分享
        btnShare.setOnClickListener {
            val transcription = tvTranscription.text.toString()
            if (transcription.isBlank() || transcription == getString(R.string.transcription_hint)) {
                setStatus("没有可分享的转录内容")
                return@setOnClickListener
            }
            val prompt = "请帮我修正以下语音转录文稿，修正错别字、补充标点符号、理顺语句，保持原意不变：\n\n$transcription"
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, prompt)
            }
            startActivity(Intent.createChooser(shareIntent, "分享到 AI 修正"))
        }

        // AI 总结按钮
        btnAiSummary.setOnClickListener { startAiSummary() }

        // AI 设置按钮
        btnAiSettings.setOnClickListener {
            startActivity(Intent(this, AiSettingsActivity::class.java))
        }

        // 总结区操作
        btnCopySummary.setOnClickListener {
            val text = tvSummary.text.toString()
            if (text.isNotBlank()) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("AI Summary", text))
                Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }
        }
        btnShareSummary.setOnClickListener {
            val text = tvSummary.text.toString()
            if (text.isNotBlank()) {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                startActivity(Intent.createChooser(intent, "分享 AI 总结"))
            }
        }
        btnCloseSummary.setOnClickListener {
            currentSummaryCall?.cancel()
            layoutSummary.visibility = View.GONE
        }

        requestStoragePermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        checkModelAndUpdateUI()
    }

    override fun onPause() {
        super.onPause()
        saveSettings()
    }

    private fun saveSettings() {
        prefs.edit()
            .putBoolean(KEY_USE_MIC, switchMic.isChecked)
            .putBoolean(KEY_STREAMING, switchStreaming.isChecked)
            .putInt(KEY_LANGUAGE, spinnerLanguage.selectedItemPosition)
            .putInt(KEY_SILENCE, seekSilence.progress)
            .putInt(KEY_THRESHOLD, seekThreshold.progress)
            .putBoolean(KEY_OVERLAY, switchOverlay.isChecked)
            .putInt(KEY_ALPHA, seekAlpha.progress)
            .apply()
    }

    private fun requestStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                manageStorageLauncher.launch(intent)
            }
        }
    }

    private fun checkModelAndUpdateUI() {
        val modelDir = File("/sdcard/sherpa-models/sense-voice")
        modelDir.mkdirs()
        val modelFile = File(modelDir, "model.int8.onnx")
        val tokensFile = File(modelDir, "tokens.txt")
        val streamingDir = File("/sdcard/sherpa-models/streaming-paraformer")
        val hasOffline = modelFile.exists() && tokensFile.exists()
        val hasStreaming = File(streamingDir, "encoder.int8.onnx").exists()
                && File(streamingDir, "decoder.int8.onnx").exists()
                && File(streamingDir, "tokens.txt").exists()

        if (!hasOffline && !hasStreaming) {
            btnToggle.isEnabled = false
            tvStatus.text = "模型文件未找到，请将以下文件放入对应目录：\n" +
                "离线：/sdcard/sherpa-models/sense-voice/\n" +
                "流式：/sdcard/sherpa-models/streaming-paraformer/\n\n" +
                "下载地址：https://github.com/k2-fsa/sherpa-onnx/releases"
        } else {
            btnToggle.isEnabled = true
            val hints = mutableListOf<String>()
            if (!hasOffline) hints.add("离线模型未找到（SenseVoice）")
            if (!hasStreaming) hints.add("流式模型未找到（Streaming Paraformer）")
            val speakerModel = File("/sdcard/sherpa-models/speaker/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx")
            if (speakerModel.exists()) hints.add("说话人识别已就绪")
            else hints.add("提示：放入说话人模型可区分发言人")
            if (!isRunning) setStatus("点击开始转录\n${hints.joinToString("\n")}")
        }
    }

    private fun startTranscription() {
        isRunning = true
        updateButton()
        setStatus("请求权限中...")

        val perms = mutableListOf(android.Manifest.permission.RECORD_AUDIO).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            onPermissionsGranted()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun onPermissionsGranted() {
        if (switchMic.isChecked) {
            // 麦克风模式，不需要 MediaProjection
            launchService(useMic = true)
        } else {
            // 系统音频模式，需要 MediaProjection
            setStatus("请求屏幕录制权限...")
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(mgr.createScreenCaptureIntent())
        }
    }

    private fun launchService(useMic: Boolean, resultCode: Int = Int.MIN_VALUE, resultData: Intent? = null) {
        pendingUseMic = useMic
        pendingResultCode = resultCode
        pendingResultData = resultData
        bindService(Intent(this, TranscriptionService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
        setStatus("启动中...")
        tvTranscription.text = ""
    }

    private fun stopTranscription() {
        transcriptionService?.stopCapture()
        runCatching { unbindService(serviceConnection) }
        stopService(Intent(this, TranscriptionService::class.java))
        transcriptionService = null
        isRunning = false
        streamingLineStart = -1
        updateButton()
        setStatus("已停止\n保存路径: ${TextSaver.getFilePath()}")
    }

    private fun appendTranscription(text: String) {
        if (text.isBlank()) return
        // 重置流式中间结果位置
        streamingLineStart = -1
        val current = tvTranscription.text.toString()
        val updated = if (current == getString(R.string.transcription_hint)) text
                      else "$current\n$text"
        tvTranscription.text = updated
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    /** 流式中间结果：覆盖最后一行（未确认的部分） */
    private var streamingLineStart = -1

    private fun updateStreamingPartial(partial: String) {
        val current = tvTranscription.text.toString()
        if (partial.isEmpty()) {
            // 清除中间状态
            streamingLineStart = -1
            return
        }
        if (streamingLineStart < 0) {
            // 记录当前文本末尾位置，作为中间结果的起点
            streamingLineStart = current.length
        }
        val base = if (streamingLineStart == 0 && current == getString(R.string.transcription_hint)) ""
                   else current.substring(0, streamingLineStart.coerceAtMost(current.length))
        val sep = if (base.isEmpty()) "" else "\n"
        tvTranscription.text = "$base$sep🔄 $partial"
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun updateButton() {
        btnToggle.text = if (isRunning) getString(R.string.stop_transcription)
                         else getString(R.string.start_transcription)
        switchMic.isEnabled = !isRunning
        switchStreaming.isEnabled = !isRunning
        spinnerLanguage.isEnabled = !isRunning
        seekSilence.isEnabled = !isRunning
    }

    private fun setStatus(msg: String) {
        tvStatus.text = msg
    }

    private fun startAiSummary() {
        val transcription = tvTranscription.text.toString()
        if (transcription.isBlank() || transcription == getString(R.string.transcription_hint)) {
            setStatus("没有可总结的转录内容")
            return
        }
        if (!AiSummaryService.isNetworkAvailable(this)) {
            setStatus("需要网络连接才能使用 AI 总结")
            return
        }
        if (!AiConfig.isConfigured(this)) {
            setStatus("请先配置 AI API Key")
            startActivity(Intent(this, AiSettingsActivity::class.java))
            return
        }

        // 取消上一次请求
        currentSummaryCall?.cancel()

        layoutSummary.visibility = View.VISIBLE
        tvSummary.text = "正在生成总结..."
        btnAiSummary.isEnabled = false
        btnAiSummary.text = "生成中..."

        currentSummaryCall = AiSummaryService.streamSummary(
            context = this,
            transcription = transcription,
            onChunk = { partial ->
                runOnUiThread { tvSummary.text = partial }
            },
            onDone = { fullText ->
                runOnUiThread {
                    tvSummary.text = fullText
                    btnAiSummary.isEnabled = true
                    btnAiSummary.text = "AI总结"
                    setStatus("AI 总结完成")
                }
            },
            onError = { error ->
                runOnUiThread {
                    tvSummary.text = "❌ $error"
                    btnAiSummary.isEnabled = true
                    btnAiSummary.text = "AI总结"
                    setStatus("AI 总结失败")
                }
            }
        )
    }

    override fun onDestroy() {
        if (isRunning) {
            runCatching { unbindService(serviceConnection) }
        }
        super.onDestroy()
    }
}
