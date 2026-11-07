package com.autofood.brp

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings as AndroidSettings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val nums = LinkedHashMap<String, EditText>()
    private val txts = LinkedHashMap<String, EditText>()
    private val checks = LinkedHashMap<String, Switch>()
    private lateinit var accDot: View
    private lateinit var accTv: TextView
    private lateinit var capDot: View
    private lateinit var capTv: TextView
    private lateinit var logView: TextView
    private lateinit var speedDesc: TextView
    private val speedBtns = ArrayList<TextView>()
    private var speedSel = 1
    private var lastLog = ""
    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            h.postDelayed(this, 2000)
        }
    }

    private val cBg = Color.parseColor("#0E1217")
    private val cCard = Color.parseColor("#171D25")
    private val cField = Color.parseColor("#0E1217")
    private val cText = Color.parseColor("#E8EDF2")
    private val cMuted = Color.parseColor("#8A97A6")
    private val cGreen = Color.parseColor("#22C55E")
    private val cRed = Color.parseColor("#EF4444")
    private val cOnGreen = Color.parseColor("#06210F")

    private val speedNames = arrayOf("Rápido", "Normal", "Seguro")
    private val speedDescs = arrayOf(
        "Toques curtos e leitura contínua da tela. O app age assim que cada tela aparece. Ideal para celular bom e internet estável.",
        "Equilíbrio entre velocidade e segurança. Recomendado para começar.",
        "Toques e pausas mais longos. Use se o celular for lento ou a internet oscilar e algo estiver falhando."
    )

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(radiusDp).toFloat()
        return g
    }

    private fun circle(color: Int): GradientDrawable {
        val g = GradientDrawable()
        g.shape = GradientDrawable.OVAL
        g.setColor(color)
        return g
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        speedSel = Cfg.prefs(this).getInt("speed", 1).coerceIn(0, 2)

        val sv = ScrollView(this)
        sv.setBackgroundColor(cBg)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(24), dp(16), dp(32))
        sv.addView(root)

        // cabecalho
        text(root, "AutoFood BRP", 26f, cText, true)
        text(root, "Comer e beber automático", 14f, cMuted, false)

        // estado
        val st = card(root)
        val a = statusRow(st, "Acessibilidade")
        accDot = a.first
        accTv = a.second
        val c = statusRow(st, "Captura de tela")
        capDot = c.first
        capTv = c.second

        // passo a passo
        val setup = card(root)
        title(setup, "Configuração inicial")
        primary(setup, "1  Ativar acessibilidade") {
            startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        primary(setup, "2  Permitir captura de tela") {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mpm.createScreenCaptureIntent(), 100)
        }
        text(
            setup,
            "3  Abra o jogo. Um painel pequeno aparece sobre ele: AUTO liga e desliga, TA testa a água uma vez, TC testa a comida uma vez. Arraste pela barrinha do topo.",
            13f, cMuted, false
        ).setPadding(0, dp(8), 0, 0)

        // agua
        val w = card(root)
        title(w, "Água")
        check(w, "water_on", "Beber automaticamente", true)
        numRow(w, "water_trigger", "Beber quando a sede ficar em (%) ou menos", 40)
        numRow(w, "water_target", "Beber até chegar em (%)", 100)
        textRow(w, "water_words", "Nome do item de água (separe por vírgula)", "agua")

        // comida
        val f = card(root)
        title(f, "Comida")
        check(f, "food_on", "Comer automaticamente", true)
        numRow(f, "food_trigger", "Comer quando a fome ficar em (%) ou menos", 40)
        numRow(f, "food_target", "Comer até chegar em (%)", 100)
        textRow(f, "food_words", "Nome(s) da comida (separe por vírgula)", "arroz,feijao")

        // velocidade
        val v = card(root)
        title(v, "Velocidade")
        val seg = LinearLayout(this)
        seg.orientation = LinearLayout.HORIZONTAL
        seg.background = rounded(cField, 12)
        seg.setPadding(dp(3), dp(3), dp(3), dp(3))
        for (i in 0 until 3) {
            val b = TextView(this)
            b.text = speedNames[i]
            b.textSize = 14f
            b.typeface = Typeface.DEFAULT_BOLD
            b.gravity = Gravity.CENTER
            b.setPadding(0, dp(10), 0, dp(10))
            b.setOnClickListener {
                speedSel = i
                applySpeedUi()
            }
            seg.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            speedBtns.add(b)
        }
        v.addView(seg)
        speedDesc = text(v, "", 12f, cMuted, false)
        speedDesc.setPadding(0, dp(8), 0, dp(4))
        applySpeedUi()
        numRow(v, "wait_max", "Esperar cada tela aparecer por até (segundos)", 10)
        check(v, "saver", "Economia de bateria (pausa a captura quando ocioso)", true)
        text(
            v,
            "O app não usa tempos fixos entre as telas: ele lê a tela e age assim que o diálogo aparece. O tempo acima é só o limite de espera caso a internet esteja ruim.",
            12f, cMuted, false
        ).setPadding(0, dp(6), 0, 0)

        // outros
        val o = card(root)
        title(o, "Outros")
        numRow(o, "sleep_alarm", "Alarme quando o sono ficar em (%) ou menos", 20)
        numRow(o, "interval", "Verificar a cada (segundos)", 15)

        primary(root, "Salvar") {
            save()
            Toast.makeText(this, "Salvo", Toast.LENGTH_SHORT).show()
        }

        // log
        val lg = card(root)
        title(lg, "Log")
        text(lg, "Se algo falhar, copie o log e me envie.", 12f, cMuted, false)
        val lr = LinearLayout(this)
        lr.orientation = LinearLayout.HORIZONTAL
        lr.setPadding(0, dp(8), 0, dp(8))
        val b1 = secondary("Copiar log") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("log", Logger.dump()))
            Toast.makeText(this, "Log copiado", Toast.LENGTH_SHORT).show()
        }
        val b2 = secondary("Limpar log") {
            Logger.clear()
            refresh()
        }
        val half = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        half.setMargins(0, 0, dp(6), 0)
        lr.addView(b1, half)
        lr.addView(b2, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        lg.addView(lr)
        logView = text(lg, "", 11f, cMuted, false)
        logView.typeface = Typeface.MONOSPACE
        logView.setTextIsSelectable(true)

        setContentView(sv)

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
    }

    override fun onResume() {
        super.onResume()
        h.post(tick)
    }

    override fun onPause() {
        super.onPause()
        h.removeCallbacks(tick)
        save()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100) {
            if (resultCode == RESULT_OK && data != null) {
                stopService(Intent(this, CaptureService::class.java))
                val i = Intent(this, CaptureService::class.java)
                i.putExtra("code", resultCode)
                i.putExtra("data", data)
                startForegroundService(i)
                Toast.makeText(this, "Captura de tela liberada", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Captura de tela negada", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun refresh() {
        val a = AutoService.instance != null
        val c = CaptureService.instance != null
        accTv.text = if (a) "ativa" else "desligada"
        accDot.background = circle(if (a) cGreen else cRed)
        capTv.text = if (c) "ativa" else "desligada"
        capDot.background = circle(if (c) cGreen else cRed)
        val t = Logger.dump()
        if (t != lastLog) {
            lastLog = t
            logView.text = t
        }
    }

    private fun save() {
        val e = Cfg.prefs(this).edit()
        for ((k, v) in nums) {
            val n = v.text.toString().toIntOrNull()
            if (n != null) {
                val r = when (k) {
                    "interval" -> n.coerceIn(5, 600)
                    "wait_max" -> n.coerceIn(3, 60)
                    else -> n.coerceIn(0, 100)
                }
                e.putInt(k, r)
            }
        }
        for ((k, v) in txts) e.putString(k, v.text.toString())
        for ((k, v) in checks) e.putBoolean(k, v.isChecked)
        e.putInt("speed", speedSel)
        e.apply()
    }

    private fun applySpeedUi() {
        for (i in speedBtns.indices) {
            val on = i == speedSel
            speedBtns[i].background = rounded(if (on) cGreen else Color.TRANSPARENT, 10)
            speedBtns[i].setTextColor(if (on) cOnGreen else cText)
        }
        speedDesc.text = speedDescs[speedSel]
    }

    // ---------- helpers de layout ----------

    private fun card(root: LinearLayout): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.background = rounded(cCard, 16)
        c.setPadding(dp(16), dp(14), dp(16), dp(14))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, dp(14), 0, 0)
        root.addView(c, lp)
        return c
    }

    private fun text(root: LinearLayout, s: String, size: Float, color: Int, bold: Boolean): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.typeface = Typeface.DEFAULT_BOLD
        root.addView(t)
        return t
    }

    private fun title(root: LinearLayout, s: String) {
        val t = text(root, s, 16f, cText, true)
        t.setPadding(0, 0, 0, dp(8))
    }

    private fun statusRow(root: LinearLayout, label: String): Pair<View, TextView> {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(4), 0, dp(4))
        val d = View(this)
        d.background = circle(cMuted)
        val dl = LinearLayout.LayoutParams(dp(10), dp(10))
        dl.setMargins(0, 0, dp(10), 0)
        row.addView(d, dl)
        val l = TextView(this)
        l.text = label
        l.textSize = 15f
        l.setTextColor(cText)
        row.addView(l, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val s = TextView(this)
        s.textSize = 14f
        s.setTextColor(cMuted)
        row.addView(s)
        root.addView(row)
        return Pair(d, s)
    }

    private fun primary(root: LinearLayout, label: String, onClick: () -> Unit) {
        val b = TextView(this)
        b.text = label
        b.textSize = 15f
        b.typeface = Typeface.DEFAULT_BOLD
        b.setTextColor(cOnGreen)
        b.gravity = Gravity.CENTER
        b.setPadding(dp(16), dp(14), dp(16), dp(14))
        b.background = rounded(cGreen, 12)
        b.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, dp(8), 0, 0)
        root.addView(b, lp)
    }

    private fun secondary(label: String, onClick: () -> Unit): TextView {
        val b = TextView(this)
        b.text = label
        b.textSize = 14f
        b.setTextColor(cText)
        b.gravity = Gravity.CENTER
        b.setPadding(dp(12), dp(10), dp(12), dp(10))
        b.background = rounded(cField, 10)
        b.setOnClickListener { onClick() }
        return b
    }

    private fun field(et: EditText) {
        et.setTextColor(cText)
        et.setHintTextColor(cMuted)
        et.background = rounded(cField, 10)
        et.setPadding(dp(12), dp(10), dp(12), dp(10))
        et.textSize = 15f
    }

    private fun check(root: LinearLayout, key: String, label: String, def: Boolean) {
        val c = Switch(this)
        c.text = label
        c.textSize = 14f
        c.setTextColor(cText)
        c.isChecked = Cfg.prefs(this).getBoolean(key, def)
        c.setPadding(0, dp(6), 0, dp(6))
        root.addView(c)
        checks[key] = c
    }

    private fun numRow(root: LinearLayout, key: String, label: String, def: Int) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(4), 0, dp(4))
        val tv = TextView(this)
        tv.text = label
        tv.textSize = 14f
        tv.setTextColor(cText)
        row.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val et = EditText(this)
        et.inputType = InputType.TYPE_CLASS_NUMBER
        et.gravity = Gravity.CENTER
        et.setText(Cfg.prefs(this).getInt(key, def).toString())
        field(et)
        val lp = LinearLayout.LayoutParams(dp(80), ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(dp(10), 0, 0, 0)
        row.addView(et, lp)
        root.addView(row)
        nums[key] = et
    }

    private fun textRow(root: LinearLayout, key: String, label: String, def: String) {
        val tv = TextView(this)
        tv.text = label
        tv.textSize = 14f
        tv.setTextColor(cText)
        tv.setPadding(0, dp(6), 0, dp(4))
        root.addView(tv)
        val et = EditText(this)
        et.setText(Cfg.prefs(this).getString(key, def) ?: def)
        field(et)
        root.addView(et, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        txts[key] = et
    }
}
