package com.autofood.brp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AutoService : AccessibilityService() {
    companion object {
        @Volatile
        var instance: AutoService? = null

        private val C_PANEL = Color.parseColor("#E6111820")
        private val C_STROKE = Color.parseColor("#33FFFFFF")
        private val C_SLATE = Color.parseColor("#2B3542")
        private val C_GREEN = Color.parseColor("#22C55E")
        private val C_BLUE = Color.parseColor("#0EA5E9")
        private val C_AMBER = Color.parseColor("#D97706")
        private val C_GRAY = Color.parseColor("#8A97A6")
        private val C_WARN = Color.parseColor("#F59E0B")
    }

    @Volatile
    var foregroundPkg: String = ""

    val bot: Bot by lazy { Bot(this) }

    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var box: LinearLayout? = null
    private var statusTv: TextView? = null
    private var dot: View? = null
    private var autoBtn: TextView? = null
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

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(radiusDp).toFloat()
        if (strokeDp > 0) g.setStroke(dp(strokeDp), strokeColor)
        return g
    }

    private fun circle(color: Int): GradientDrawable {
        val g = GradientDrawable()
        g.shape = GradientDrawable.OVAL
        g.setColor(color)
        return g
    }

    private fun pill(text: String, color: Int, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = 12f
        t.setTextColor(Color.WHITE)
        t.typeface = Typeface.DEFAULT_BOLD
        t.gravity = Gravity.CENTER
        t.setPadding(dp(14), dp(8), dp(14), dp(8))
        t.background = rounded(color, 14)
        t.setOnClickListener { onClick() }
        return t
    }

    private fun createPanel() {
        if (box != null) return
        val w = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = w

        val b = LinearLayout(this)
        b.orientation = LinearLayout.VERTICAL
        b.background = rounded(C_PANEL, 18, C_STROKE, 1)
        b.setPadding(dp(8), dp(2), dp(8), dp(8))

        // alca para arrastar
        val grip = LinearLayout(this)
        grip.gravity = Gravity.CENTER
        val bar = View(this)
        bar.background = rounded(Color.parseColor("#66FFFFFF"), 3)
        grip.addView(bar, LinearLayout.LayoutParams(dp(32), dp(4)))
        b.addView(grip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(18)))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        val ab = pill("AUTO", C_SLATE) { toggle() }
        autoBtn = ab
        val gap = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        gap.setMargins(dp(3), 0, dp(3), 0)
        row.addView(ab, gap)
        row.addView(pill("TA", C_BLUE) { bot.test(Kind.WATER) }, gap)
        row.addView(pill("TC", C_AMBER) { bot.test(Kind.FOOD) }, gap)
        b.addView(row)

        val srow = LinearLayout(this)
        srow.orientation = LinearLayout.HORIZONTAL
        srow.gravity = Gravity.CENTER
        srow.setPadding(0, dp(6), 0, 0)
        val d = View(this)
        d.background = circle(C_GRAY)
        dot = d
        val dl = LinearLayout.LayoutParams(dp(8), dp(8))
        dl.setMargins(0, 0, dp(6), 0)
        srow.addView(d, dl)
        val st = TextView(this)
        st.text = "parado"
        st.setTextColor(Color.parseColor("#C9D3DE"))
        st.textSize = 11f
        statusTv = st
        srow.addView(st)
        b.addView(srow)

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

        grip.setOnTouchListener { _, ev ->
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

    private fun statusColor(t: String): Int = when {
        t.startsWith("sem") || t.startsWith("espera") || t.startsWith("parando") -> C_WARN
        t == "ligado" || t == "bebendo" || t == "comendo" -> C_GREEN
        t == "testando" -> C_BLUE
        else -> C_GRAY
    }

    fun setStatus(t: String) {
        main.post {
            statusTv?.text = t
            dot?.background = circle(statusColor(t))
        }
    }

    fun refreshButtons() {
        main.post {
            val on = bot.running
            autoBtn?.text = if (on) "AUTO ON" else "AUTO"
            autoBtn?.background = rounded(if (on) C_GREEN else C_SLATE, 14)
        }
    }

    // ---------- toques ----------

    /** O toque ja espera o Android confirmar o gesto; nao ha pausa extra depois. */
    fun tap(x: Float, y: Float, dur: Long = 50L): Boolean {
        val path = Path()
        path.moveTo(x, y)
        return gesture(path, dur)
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

    // ---------- reabrir o jogo ----------

    fun launchGame(pkg: String): Boolean {
        return try {
            val i = packageManager.getLaunchIntentForPackage(pkg) ?: return false
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            startActivity(i)
            true
        } catch (e: Exception) {
            Logger.add("Erro ao abrir o jogo: $e")
            false
        }
    }

    /** Procura entre os apps instalados um cujo nome contenha [word] (ja normalizado). */
    fun findPkgByLabel(word: String): String {
        try {
            val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            for (ri in packageManager.queryIntentActivities(q, 0)) {
                val label = ri.loadLabel(packageManager).toString()
                if (OcrUtil.normalize(label).contains(word)) return ri.activityInfo.packageName
            }
        } catch (e: Exception) {
            Logger.add("Erro procurando o jogo: $e")
        }
        return ""
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
