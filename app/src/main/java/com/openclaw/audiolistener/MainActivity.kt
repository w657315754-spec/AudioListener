package com.openclaw.audiolistener

import android.app.Activity
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
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
    }

    private var transcriptionService: TranscriptionService? = null
    private var isRunning = false
    private var pendingResultCode: Int = Int.MIN_VALUE
    private var pendingResultData: Intent? = null
    private var pendingUseMic: Boolean = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            transcriptionService = (binder as TranscriptionService.LocalBinder).getService()
            
            if (switchOverlay.isChecked) {
                TranscriptionService.overlayInstance = OverlayService.instance
            }
            
            transcriptionService?.onTranscriptionResult = { text ->
                runOnUiThread { appendTranscription(text) }
            }
            transcriptionService?.onStatusUpdate = { status ->
                runOnUiThread { setStatus(status) }
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

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // 语言选择 Spinner
        val languageLabels = arrayOf("自动检测", "中文", "English")
        spinnerLanguage.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, languageLabels)

        // 恢复保存的设置
        switchMic.isChecked = prefs.getBoolean(KEY_USE_MIC, false)
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
                    TranscriptionService.overlayInstance = OverlayService.instance
                } else {
                    switchOverlay.isChecked = false
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")))
                }
            } else {
                stopService(Intent(this, OverlayService::class.java))
                TranscriptionService.overlayInstance = null
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
        if (!modelFile.exists() || !tokensFile.exists()) {
            btnToggle.isEnabled = false
            tvStatus.text = "模型文件未找到，请将以下文件放入 /sdcard/sherpa-models/sense-voice/：\n• model.int8.onnx\n• tokens.txt\n\n下载地址：https://github.com/k2-fsa/sherpa-onnx/releases"
        } else {
            btnToggle.isEnabled = true
            val speakerModel = File("/sdcard/sherpa-models/speaker/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx")
            val hint = if (speakerModel.exists()) "点击开始转录（说话人识别已就绪）"
                       else "点击开始转录\n提示：放入说话人模型可区分发言人\n路径：/sdcard/sherpa-models/speaker/\n文件：3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx"
            if (!isRunning) setStatus(hint)
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
        updateButton()
        setStatus("已停止\n保存路径: ${TextSaver.getFilePath()}")
    }

    private fun appendTranscription(text: String) {
        if (text.isBlank()) return
        val current = tvTranscription.text.toString()
        val updated = if (current == getString(R.string.transcription_hint)) text
                      else "$current\n$text"
        tvTranscription.text = updated
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun updateButton() {
        btnToggle.text = if (isRunning) getString(R.string.stop_transcription)
                         else getString(R.string.start_transcription)
        switchMic.isEnabled = !isRunning
        spinnerLanguage.isEnabled = !isRunning
        seekSilence.isEnabled = !isRunning
    }

    private fun setStatus(msg: String) {
        tvStatus.text = msg
    }

    override fun onDestroy() {
        if (isRunning) {
            runCatching { unbindService(serviceConnection) }
        }
        super.onDestroy()
    }
}
