package com.openclaw.audiolistener

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.provider.Settings
import android.widget.Button
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var btnToggle: Button
    private lateinit var switchMic: Switch
    private lateinit var seekThreshold: SeekBar
    private lateinit var tvThreshold: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvTranscription: TextView
    private lateinit var scrollView: ScrollView

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
            // 同步当前阈值到 service
            val threshold = (seekThreshold.progress + 20) / 100f
            transcriptionService?.speakerThreshold = threshold
            val intent = Intent(this@MainActivity, TranscriptionService::class.java).apply {
                putExtra(TranscriptionService.EXTRA_USE_MIC, pendingUseMic)
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
        seekThreshold = findViewById(R.id.seekThreshold)
        tvThreshold = findViewById(R.id.tvThreshold)
        tvStatus = findViewById(R.id.tvStatus)
        tvTranscription = findViewById(R.id.tvTranscription)
        scrollView = findViewById(R.id.scrollView)

        // SeekBar: 范围 0.20 ~ 1.00，步长 0.01，默认 0.60
        // max=80 对应 progress 0~80 → threshold = (progress + 20) / 100
        seekThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val threshold = (progress + 20) / 100f
                tvThreshold.text = "%.2f".format(threshold)
                transcriptionService?.speakerThreshold = threshold
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        btnToggle.setOnClickListener {
            if (isRunning) stopTranscription() else startTranscription()
        }

        requestStoragePermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        checkModelAndUpdateUI()
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
        setStatus("已停止")
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
