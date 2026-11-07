package com.autofood.brp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AutoService : AccessibilityService() {
    companion object {
        @Volatile
        var instance: AutoService? = null
    }

    @Volatile
    var foregroundPkg: String = ""

    val bot: Bot by lazy { Bot(this) }

    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var box: LinearLayout? = null
    private var statusTv: TextView? = null
    private var autoBtn: Button? = null
    private var dx = 0f
    private var dy = 0f
    private var ox = 0
    private var oy = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        createPanel()
        Logger.add("Acessibilidade conectada")
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null || e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val p = e.packageName?.toString() ?: return
        if (p == packageName || p == "android" || p.startsWith("com.android.systemui") || p.contains("inputmethod")) return
        if (p != foregroundPkg) {
            foregroundPkg = p
            Logger.add("App em primeiro plano: $p")
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        try { bot.stop() } catch (e: Exception) { }
        try { box?.let { wm?.removeView(it) } } catch (e: Exception) { }
        box = null
        instance = null
        return super.onUnbind(intent)
    }

    // ---------- painel flutuante ----------

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()

    private fun mkBtn(text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.textSize = 11f
        b.isAllCaps = false
        b.minWidth = 0
        b.minimumWidth = 0
        b.minHeight = 0
        b.minimumHeight = 0
        b.setPadding(dp(8), dp(4), dp(8), dp(4))
        b.setOnClickListener { onClick() }
        return b
    }

    private fun createPanel() {
        if (box != null) return
        val w = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = w

        val b = LinearLayout(this)
        b.orientation = LinearLayout.VERTICAL
        b.setBackgroundColor(Color.argb(170, 0, 0, 0))
        b.setPadding(dp(4), dp(4), dp(4), dp(4))

        val handle = TextView(this)
        handle.text = "::::"
        handle.setTextColor(Color.WHITE)
        handle.gravity = Gravity.CENTER
        b.addView(handle)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val ab = mkBtn("AUTO") { toggle() }
        autoBtn = ab
        row.addView(ab)
        row.addView(mkBtn("TA") { bot.test(Kind.WATER) })
        row.addView(mkBtn("TC") { bot.test(Kind.FOOD) })
        b.addView(row)

        val st = TextView(this)
        st.text = "parado"
        st.setTextColor(Color.WHITE)
        st.textSize = 11f
        statusTv = st
        b.addView(st)

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = dp(10)
        p.y = dp(110)

        handle.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    dx = ev.rawX
                    dy = ev.rawY
                    ox = p.x
                    oy = p.y
                }
                MotionEvent.ACTION_MOVE -> {
                    p.x = ox + (ev.rawX - dx).toInt()
                    p.y = oy + (ev.rawY - dy).toInt()
                    try { w.updateViewLayout(b, p) } catch (e: Exception) { }
                }
            }
            true
        }

        w.addView(b, p)
        box = b
    }

    private fun toggle() {
        if (bot.running) {
            bot.stop()
            setStatus("parando")
        } else {
            if (foregroundPkg.isEmpty()) Logger.add("Jogo nao detectado: vou agir em qualquer app. Reabra o jogo depois de ativar a acessibilidade.")
            bot.start(foregroundPkg)
        }
        refreshButtons()
    }

    fun setStatus(t: String) {
        main.post { statusTv?.text = t }
    }

    fun refreshButtons() {
        main.post { autoBtn?.text = if (bot.running) "AUTO ON" else "AUTO" }
    }

    // ---------- toques ----------

    fun tap(x: Float, y: Float): Boolean {
        val path = Path()
        path.moveTo(x, y)
        val ok = gesture(path, 70)
        try { Thread.sleep(80) } catch (e: Exception) { }
        return ok
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, dur: Long): Boolean {
        val path = Path()
        path.moveTo(x1, y1)
        path.lineTo(x2, y2)
        return gesture(path, dur)
    }

    private fun gesture(path: Path, dur: Long): Boolean {
        try {
            val g = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, dur))
                .build()
            val latch = CountDownLatch(1)
            var ok = false
            val cb = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    ok = true
                    latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    latch.countDown()
                }
            }
            if (!dispatchGesture(g, cb, null)) return false
            latch.await(4, TimeUnit.SECONDS)
            return ok
        } catch (e: Exception) {
            Logger.add("Erro no toque: $e")
            return false
        }
    }

    // ---------- alarme ----------

    fun alarm() {
        try {
            ToneGenerator(AudioManager.STREAM_ALARM, 100).startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1500)
        } catch (e: Exception) { }
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createOneShot(800, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) { }
    }
}
