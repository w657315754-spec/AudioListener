package com.openclaw.audiolistener

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class ModelSetupActivity : AppCompatActivity() {

    private lateinit var tvTitle: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvProgress: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnDownload: Button
    private lateinit var btnSkip: Button

    private val downloadManager by lazy { ModelDownloadManager(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_model_setup)

        tvTitle = findViewById(R.id.tvSetupTitle)
        tvStatus = findViewById(R.id.tvSetupStatus)
        tvProgress = findViewById(R.id.tvSetupProgress)
        progressBar = findViewById(R.id.progressSetup)
        btnDownload = findViewById(R.id.btnStartDownload)
        btnSkip = findViewById(R.id.btnSkipSetup)

        tvTitle.text = "语音识别模型下载"
        tvStatus.text = "需要下载语音识别模型才能使用转录功能\n模型大小约 230MB，请确保网络畅通"
        progressBar.visibility = View.GONE
        tvProgress.visibility = View.GONE

        btnDownload.setOnClickListener { startDownload() }
        btnSkip.setOnClickListener { finish() }
    }

    private fun startDownload() {
        btnDownload.isEnabled = false
        btnSkip.isEnabled = false
        progressBar.visibility = View.VISIBLE
        tvProgress.visibility = View.VISIBLE
        progressBar.max = 100
        tvStatus.text = "正在下载..."

        Thread {
            downloadManager.downloadFiles(
                files = ModelDownloadManager.SENSE_VOICE_FILES,
                targetDir = ModelDownloadManager.SENSE_VOICE_DIR,
                onProgress = { p ->
                    runOnUiThread {
                        progressBar.progress = p.percent
                        val mb = p.bytesDownloaded / 1_000_000
                        val totalMb = p.totalBytes / 1_000_000
                        tvProgress.text = "${p.currentFile}  ${mb}MB / ${totalMb}MB  (${p.percent}%)"
                        tvStatus.text = "正在下载 (${p.fileIndex + 1}/${p.totalFiles})..."
                    }
                },
                onComplete = {
                    runOnUiThread {
                        tvStatus.text = "✅ 模型下载完成！"
                        tvProgress.text = ""
                        progressBar.progress = 100
                        btnDownload.text = "完成"
                        btnDownload.isEnabled = true
                        btnDownload.setOnClickListener { finish() }
                        btnSkip.visibility = View.GONE
                    }
                },
                onError = { error ->
                    runOnUiThread {
                        tvStatus.text = "❌ $error"
                        btnDownload.text = "重试"
                        btnDownload.isEnabled = true
                        btnDownload.setOnClickListener { startDownload() }
                        btnSkip.isEnabled = true
                    }
                }
            )
        }.start()
    }

    override fun onDestroy() {
        downloadManager.cancel()
        super.onDestroy()
    }
}
