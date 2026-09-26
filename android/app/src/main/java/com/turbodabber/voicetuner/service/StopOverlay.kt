package com.turbodabber.voicetuner.service

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button

/** Small non-focusable window leaves the rest of the screen available to other apps. */
class StopOverlay(context: Context, onStop: () -> Unit) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private val button = Button(context).apply {
        text = "■ STOP · VoiceTuner"
        contentDescription = "Zatrzymaj mikrofon VoiceTuner"
        setTextColor(0xFF101510.toInt())
        backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFB5F56A.toInt())
        setOnClickListener { onStop() }
    }
    private var attached = false
    fun show() {
        if (attached) return
        manager.addView(button, WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 96
        })
        attached = true
    }
    fun remove() {
        if (attached) { runCatching { manager.removeViewImmediate(button) }; attached = false }
    }
}
