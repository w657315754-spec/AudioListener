package com.openclaw.audiolistener

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlayService"
        @Volatile var instance: OverlayService? = null
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var tvOverlayContent: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var params: WindowManager.LayoutParams

    private var overlayAlpha = 0.85f
    private var overlayWidth = 0
    private var overlayHeight = 0

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val dm = resources.displayMetrics
        overlayWidth = (dm.widthPixels * 0.9).toInt()
        overlayHeight = (dm.heightPixels * 0.25).toInt()

        // 恢复保存的设置
        val prefs = getSharedPreferences("overlay_prefs", Context.MODE_PRIVATE)
        overlayAlpha = prefs.getFloat("alpha", 0.85f)
        val savedX = prefs.getInt("x", 0)
        val savedY = prefs.getInt("y", dm.heightPixels / 2)
        val savedW = prefs.getInt("w", overlayWidth)
        val savedH = prefs.getInt("h", overlayHeight)

        overlayWidth = savedW
        overlayHeight = savedH

        createOverlayView()

        params = WindowManager.LayoutParams(
            overlayWidth,
            overlayHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX
            y = savedY
        }

        windowManager.addView(overlayView, params)
        Log.i(TAG, "Overlay created")
    }

    private fun createOverlayView() {
        val dp8 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics).toInt()

        tvOverlayContent = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(dp8, dp8, dp8, dp8)
        }

        scrollView = ScrollView(this).apply {
            addView(tvOverlayContent)
            isVerticalScrollBarEnabled = true
        }

        // 底部拖动条（用于缩放）
        val resizeHandle = View(this).apply {
            setBackgroundColor(Color.argb(120, 255, 255, 255))
            minimumHeight = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6f, resources.displayMetrics).toInt()
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb((overlayAlpha * 255).toInt(), 0, 0, 0))
            addView(scrollView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(resizeHandle, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        // 拖动移动
        setupDragToMove(scrollView)
        // 底部条拖动缩放
        setupDragToResize(resizeHandle)

        overlayView = container
    }

    private fun setupDragToMove(view: View) {
        var startX = 0
        var startY = 0
        var startParamX = 0
        var startParamY = 0
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX.toInt()
                    startY = event.rawY.toInt()
                    startParamX = params.x
                    startParamY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startParamX + (event.rawX.toInt() - startX)
                    params.y = startParamY + (event.rawY.toInt() - startY)
                    windowManager.updateViewLayout(overlayView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    savePosition()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupDragToResize(handle: View) {
        var startY = 0
        var startHeight = 0
        var startX = 0
        var startWidth = 0
        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY.toInt()
                    startHeight = params.height
                    startX = event.rawX.toInt()
                    startWidth = params.width
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val newH = (startHeight + (event.rawY.toInt() - startY)).coerceIn(200, resources.displayMetrics.heightPixels)
                    val newW = (startWidth + (event.rawX.toInt() - startX)).coerceIn(300, resources.displayMetrics.widthPixels)
                    params.height = newH
                    params.width = newW
                    windowManager.updateViewLayout(overlayView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    savePosition()
                    true
                }
                else -> false
            }
        }
    }

    fun appendText(text: String) {
        val current = tvOverlayContent.text.toString()
        val updated = if (current.isBlank()) text else "$current\n$text"
        tvOverlayContent.text = updated
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    fun setOverlayAlpha(alpha: Float) {
        overlayAlpha = alpha.coerceIn(0.1f, 1.0f)
        val bg = overlayView as? LinearLayout ?: return
        bg.setBackgroundColor(Color.argb((overlayAlpha * 255).toInt(), 0, 0, 0))
        savePosition()
    }

    fun clearText() {
        tvOverlayContent.text = ""
    }

    private fun savePosition() {
        getSharedPreferences("overlay_prefs", Context.MODE_PRIVATE).edit()
            .putInt("x", params.x)
            .putInt("y", params.y)
            .putInt("w", params.width)
            .putInt("h", params.height)
            .putFloat("alpha", overlayAlpha)
            .apply()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        savePosition()
        runCatching { windowManager.removeView(overlayView) }
        instance = null
        super.onDestroy()
        Log.i(TAG, "Overlay destroyed")
    }
}
