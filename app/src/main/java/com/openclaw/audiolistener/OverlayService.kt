package com.openclaw.audiolistener

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 悬浮窗服务。
 * 通过 Intent extra 接收文字（ACTION_APPEND_TEXT），无需跨服务静态引用。
 * 也保留 instance 供 MainActivity 调节透明度等。
 */
class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlayService"
        const val ACTION_APPEND_TEXT = "com.openclaw.audiolistener.APPEND_TEXT"
        const val EXTRA_TEXT = "text"
        @Volatile var instance: OverlayService? = null
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var tvOverlayContent: TextView? = null
    private var scrollView: ScrollView? = null
    private var params: WindowManager.LayoutParams? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayAlpha = 0.85f

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "onCreate")
        createOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        // 通过 Intent 接收追加文字
        if (intent?.action == ACTION_APPEND_TEXT) {
            val text = intent.getStringExtra(EXTRA_TEXT)
            if (!text.isNullOrBlank()) {
                doAppendText(text)
            }
        }
        return START_STICKY
    }

    private fun createOverlay() {
        if (overlayView != null) return
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null)
        tvOverlayContent = overlayView!!.findViewById(R.id.tvOverlayContent)
        scrollView = overlayView!!.findViewById(R.id.overlayScroll)
        val dragHandle = overlayView!!.findViewById<View>(R.id.dragHandle)
        val resizeHandle = overlayView!!.findViewById<View>(R.id.resizeHandle)
        val container = overlayView!!.findViewById<LinearLayout>(R.id.overlayContainer)

        val dm = resources.displayMetrics
        val prefs = getSharedPreferences("overlay_prefs", Context.MODE_PRIVATE)
        overlayAlpha = prefs.getFloat("alpha", 0.85f)
        val savedX = prefs.getInt("x", 0)
        val savedY = prefs.getInt("y", dm.heightPixels / 2)
        val savedW = prefs.getInt("w", (dm.widthPixels * 0.9).toInt())
        val savedH = prefs.getInt("h", (dm.heightPixels * 0.25).toInt())

        container.setBackgroundColor(Color.argb((overlayAlpha * 255).toInt(), 0, 0, 0))

        params = WindowManager.LayoutParams(
            savedW, savedH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX; y = savedY
        }

        setupDragToMove(dragHandle)
        setupDragToResize(resizeHandle)
        windowManager!!.addView(overlayView, params)
        Log.i(TAG, "Overlay window added")
    }

    private fun setupDragToMove(view: View) {
        var sx = 0; var sy = 0; var px = 0; var py = 0
        view.setOnTouchListener { _, e ->
            val p = params ?: return@setOnTouchListener false
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX.toInt(); sy = e.rawY.toInt()
                    px = p.x; py = p.y; true
                }
                MotionEvent.ACTION_MOVE -> {
                    p.x = px + (e.rawX.toInt() - sx)
                    p.y = py + (e.rawY.toInt() - sy)
                    windowManager?.updateViewLayout(overlayView, p); true
                }
                MotionEvent.ACTION_UP -> { savePosition(); true }
                else -> false
            }
        }
    }

    private fun setupDragToResize(handle: View) {
        var sy = 0; var sh = 0; var sx = 0; var sw = 0
        handle.setOnTouchListener { _, e ->
            val p = params ?: return@setOnTouchListener false
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sy = e.rawY.toInt(); sh = p.height
                    sx = e.rawX.toInt(); sw = p.width; true
                }
                MotionEvent.ACTION_MOVE -> {
                    p.height = (sh + (e.rawY.toInt() - sy)).coerceIn(200, resources.displayMetrics.heightPixels)
                    p.width = (sw + (e.rawX.toInt() - sx)).coerceIn(300, resources.displayMetrics.widthPixels)
                    windowManager?.updateViewLayout(overlayView, p); true
                }
                MotionEvent.ACTION_UP -> { savePosition(); true }
                else -> false
            }
        }
    }

    /** 通过 Intent 或直接调用追加文字 */
    fun appendText(text: String) {
        doAppendText(text)
    }

    private fun doAppendText(text: String) {
        Log.i(TAG, "doAppendText: [$text]")
        mainHandler.post {
            val tv = tvOverlayContent ?: run {
                Log.e(TAG, "tvOverlayContent is null"); return@post
            }
            val sv = scrollView ?: return@post
            val cur = tv.text?.toString() ?: ""
            tv.text = if (cur.isBlank()) text else "$cur\n$text"
            sv.post { sv.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    fun setOverlayAlpha(alpha: Float) {
        overlayAlpha = alpha.coerceIn(0.1f, 1.0f)
        overlayView?.findViewById<LinearLayout>(R.id.overlayContainer)
            ?.setBackgroundColor(Color.argb((overlayAlpha * 255).toInt(), 0, 0, 0))
        savePosition()
    }

    fun clearText() {
        mainHandler.post { tvOverlayContent?.text = "" }
    }

    private fun savePosition() {
        val p = params ?: return
        getSharedPreferences("overlay_prefs", Context.MODE_PRIVATE).edit()
            .putInt("x", p.x).putInt("y", p.y)
            .putInt("w", p.width).putInt("h", p.height)
            .putFloat("alpha", overlayAlpha).apply()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        savePosition()
        runCatching { overlayView?.let { windowManager?.removeView(it) } }
        overlayView = null; tvOverlayContent = null; scrollView = null
        instance = null
        super.onDestroy()
        Log.i(TAG, "Overlay destroyed")
    }
}
