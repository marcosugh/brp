package com.autofood.brp

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings as AndroidSettings
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

class MainActivity : Activity() {
    private val nums = LinkedHashMap<String, EditText>()
    private val txts = LinkedHashMap<String, EditText>()
    private val checks = LinkedHashMap<String, Switch>()
    private lateinit var accDot: View
    private lateinit var accTv: TextView
    private lateinit var capDot: View
    private lateinit var capTv: TextView
    private lateinit var botDot: View
    private lateinit var botTv: TextView
    private lateinit var logView: TextView
    private lateinit var speedDesc: TextView
    private lateinit var sessionBox: LinearLayout
    private val speedBtns = ArrayList<TextView>()
    private var speedSel = 1
    private var lastLog = ""
    private var lastSession = ""
    private var skipSave = false
    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            h.postDelayed(this, 2000)
        }
    }

    private val cBg = Color.parseColor("#0B0F14")
    private val cCard = Color.parseColor("#121820")
    private val cStroke = Color.parseColor("#1E2733")
    private val cField = Color.parseColor("#0B0F14")
    private val cText = Color.parseColor("#E6EBF1")
    private val cMuted = Color.parseColor("#8793A3")
    private val cGray = Color.parseColor("#5B6675")
    private val cGreen = Color.parseColor("#22C55E")
    private val cRed = Color.parseColor("#EF4444")
    private val cAmber = Color.parseColor("#F59E0B")
    private val cBlue = Color.parseColor("#38BDF8")
    private val cOnGreen = Color.parseColor("#04210F")

    private val speedNames = arrayOf("Rápido", "Normal", "Seguro")
    private val speedDescs = arrayOf(
        "Toques curtos e leitura contínua da tela. O app age assim que cada tela aparece. Ideal para celular bom e internet estável.",
        "Equilíbrio entre velocidade e segurança. Recomendado para começar.",
        "Toques e pausas mais longos. Use se o celular for lento ou a internet oscilar e algo estiver falhando."
    )

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

    /** Fundo com efeito de toque. */
    private fun ripple(content: GradientDrawable): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(Color.parseColor("#33FFFFFF")), content, null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.init(applicationContext)
        speedSel = Cfg.prefs(this).getInt("speed", 1).coerceIn(0, 2)

        val sv = ScrollView(this)
        sv.setBackgroundColor(cBg)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(24), dp(16), dp(32))
        sv.addView(root)

        // cabecalho
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val logo = ImageView(this)
        logo.setImageResource(R.mipmap.ic_launcher)
        head.addView(logo, LinearLayout.LayoutParams(dp(48), dp(48)))
        val ht = LinearLayout(this)
        ht.orientation = LinearLayout.VERTICAL
        ht.setPadding(dp(12), 0, 0, 0)
        text(ht, "AutoFood BRP", 24f, cText, true)
        text(ht, "Comer e beber automático  ·  v" + version(), 13f, cMuted, false)
        head.addView(ht)
        root.addView(head)

        // estado
        val st = card(root)
        section(st, "Estado")
        val a = statusRow(st, "Acessibilidade")
        accDot = a.first
        accTv = a.second
        val c = statusRow(st, "Captura de tela")
        capDot = c.first
        capTv = c.second
        val b = statusRow(st, "Automação")
        botDot = b.first
        botTv = b.second

        // ultima sessao
        val ss = card(root)
        section(ss, "Última sessão")
        sessionBox = LinearLayout(this)
        sessionBox.orientation = LinearLayout.VERTICAL
        ss.addView(sessionBox)

        // passo a passo
        val setup = card(root)
        section(setup, "Configuração inicial")
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
        ).setPadding(0, dp(10), 0, 0)

        // agua
        val w = card(root)
        section(w, "Água")
        check(w, "water_on", "Beber automaticamente", true)
        numRow(w, "water_trigger", "Beber quando a sede ficar em (%) ou menos", 40)
        numRow(w, "water_target", "Beber até chegar em (%)", 100)
        textRow(w, "water_words", "Nome do item de água (separe por vírgula)", "agua")

        // comida
        val f = card(root)
        section(f, "Comida")
        check(f, "food_on", "Comer automaticamente", true)
        numRow(f, "food_trigger", "Comer quando a fome ficar em (%) ou menos", 40)
        numRow(f, "food_target", "Comer até chegar em (%)", 100)
        textRow(f, "food_words", "Nome(s) da comida (separe por vírgula)", "arroz,feijao")

        // velocidade
        val v = card(root)
        section(v, "Velocidade")
        val seg = LinearLayout(this)
        seg.orientation = LinearLayout.HORIZONTAL
        seg.background = rounded(cField, 12, cStroke, 1)
        seg.setPadding(dp(3), dp(3), dp(3), dp(3))
        for (i in 0 until 3) {
            val sb = TextView(this)
            sb.text = speedNames[i]
            sb.textSize = 14f
            sb.typeface = Typeface.DEFAULT_BOLD
            sb.gravity = Gravity.CENTER
            sb.setPadding(0, dp(10), 0, dp(10))
            sb.setOnClickListener {
                speedSel = i
                applySpeedUi()
            }
            seg.addView(sb, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            speedBtns.add(sb)
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
        section(o, "Outros")
        numRow(o, "sleep_alarm", "Alarme quando o sono ficar em (%) ou menos", 20)
        numRow(o, "interval", "Verificar a cada (segundos)", 15)
        check(o, "auto_reopen", "Reabrir o jogo sozinho se ele fechar", true)

        primary(root, "Salvar") {
            save()
            Toast.makeText(this, "Salvo", Toast.LENGTH_SHORT).show()
        }

        // backup
        val bk = card(root)
        section(bk, "Backup das configurações")
        text(bk, "As configurações ficam salvas no aparelho. Use o backup para guardar uma cópia ou levar para outro celular.", 12f, cMuted, false)
        val br = LinearLayout(this)
        br.orientation = LinearLayout.HORIZONTAL
        br.setPadding(0, dp(10), 0, 0)
        val half = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        half.setMargins(0, 0, dp(6), 0)
        br.addView(secondary("Copiar configurações", cText) { exportCfg() }, half)
        br.addView(secondary("Colar configurações", cText) { importCfg() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bk.addView(br)

        // log
        val lg = card(root)
        section(lg, "Log")
        text(lg, "O log completo fica salvo em arquivo de texto. Se algo falhar, copie e envie.", 12f, cMuted, false)
        val lr = LinearLayout(this)
        lr.orientation = LinearLayout.HORIZONTAL
        lr.setPadding(0, dp(10), 0, dp(8))
        val half2 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        half2.setMargins(0, 0, dp(6), 0)
        lr.addView(secondary("Copiar log", cText) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("log", Logger.dumpAll()))
            Toast.makeText(this, "Log copiado", Toast.LENGTH_SHORT).show()
        }, half2)
        lr.addView(secondary("Limpar log", cRed) {
            Logger.clear()
            refresh()
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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

    private fun version(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    private fun setState(dot: View, tv: TextView, label: String, color: Int) {
        tv.text = label
        tv.setTextColor(color)
        dot.background = circle(color)
    }

    private fun refresh() {
        val acc = AutoService.instance
        if (acc != null) setState(accDot, accTv, "Ativa", cGreen) else setState(accDot, accTv, "Desligada", cGray)
        if (CaptureService.instance != null) setState(capDot, capTv, "Ativa", cGreen) else setState(capDot, capTv, "Desligada", cGray)
        when {
            acc == null -> setState(botDot, botTv, "Indisponível", cGray)
            acc.bot.running -> setState(botDot, botTv, "Rodando", cGreen)
            else -> setState(botDot, botTv, "Parada", cGray)
        }
        val t = Logger.dump()
        if (t != lastLog) {
            lastLog = t
            logView.text = colorLog(t)
        }
        val p = Cfg.prefs(this)
        val key = p.getString("last_session", "") + p.getLong("last_session_at", 0L) + p.getInt("tot_sessions", 0)
        if (key != lastSession) {
            lastSession = key
            renderSession()
        }
    }

    private fun colorLog(t: String): CharSequence {
        val sb = SpannableStringBuilder()
        for (ln in t.lines().takeLast(100)) {
            val start = sb.length
            sb.append(ln).append('\n')
            val col = when {
                ln.contains("ALERTA") || ln.contains("ERRO") || ln.contains("FALHA") -> cRed
                ln.contains("Jogo de volta") || ln.contains("RESUMO") || ln.contains("Bot iniciado") -> cGreen
                ln.contains(" t:") -> cGray
                else -> cMuted
            }
            sb.setSpan(ForegroundColorSpan(col), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    private fun renderSession() {
        sessionBox.removeAllViews()
        val p = Cfg.prefs(this)
        val rows = (p.getString("last_session", "") ?: "").lines().filter { it.contains("|") }
        if (rows.isEmpty()) {
            text(sessionBox, "Nenhuma sessão ainda. O resultado aparece aqui quando você parar o AUTO.", 13f, cMuted, false)
            return
        }
        text(sessionBox, "Encerrada em " + Stats.date(p.getLong("last_session_at", 0L)), 12f, cMuted, false)
            .setPadding(0, 0, 0, dp(10))
        val tiles = LinearLayout(this)
        tiles.orientation = LinearLayout.HORIZONTAL
        val cols = intArrayOf(cText, cBlue, cAmber)
        for (i in 0 until minOf(3, rows.size)) {
            val kv = rows[i].split("|", limit = 2)
            val tile = LinearLayout(this)
            tile.orientation = LinearLayout.VERTICAL
            tile.gravity = Gravity.CENTER
            tile.background = rounded(cField, 12, cStroke, 1)
            tile.setPadding(dp(6), dp(12), dp(6), dp(12))
            val big = text(tile, kv[1], 20f, cols[i], true)
            big.gravity = Gravity.CENTER
            val cap = text(tile, kv[0], 11f, cMuted, false)
            cap.gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(if (i == 0) 0 else dp(6), 0, 0, 0)
            tiles.addView(tile, lp)
        }
        sessionBox.addView(tiles)
        for (i in 3 until rows.size) {
            val kv = rows[i].split("|", limit = 2)
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.setPadding(0, dp(8), 0, 0)
            val l = TextView(this)
            l.text = kv[0]
            l.textSize = 13f
            l.setTextColor(cMuted)
            row.addView(l, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val r = TextView(this)
            r.text = kv[1]
            r.textSize = 13f
            val bad = (kv[0] == "Falhas" || kv[0] == "Itens acabaram" || kv[0] == "Alarmes") && kv[1] != "0"
            r.setTextColor(if (bad) cRed else cText)
            row.addView(r)
            sessionBox.addView(row)
        }
        val tw = p.getInt("tot_water", 0)
        val tf = p.getInt("tot_food", 0)
        text(
            sessionBox,
            "Total acumulado: $tw águas, $tf comidas em " + Stats.fmt(p.getLong("tot_ms", 0L)) +
                " (" + p.getInt("tot_sessions", 0) + " sessões)",
            12f, cMuted, false
        ).setPadding(0, dp(12), 0, 0)
    }

    private fun save() {
        if (skipSave) return
        val e = Cfg.prefs(this).edit()
        for ((k, v) in nums) {
            val n = v.text.toString().toIntOrNull()
            if (n != null) e.putInt(k, clampNum(k, n))
        }
        for ((k, v) in txts) e.putString(k, v.text.toString())
        for ((k, v) in checks) e.putBoolean(k, v.isChecked)
        e.putInt("speed", speedSel)
        e.apply()
    }

    private fun clampNum(k: String, n: Int): Int = when (k) {
        "interval" -> n.coerceIn(5, 600)
        "wait_max" -> n.coerceIn(3, 60)
        else -> n.coerceIn(0, 100)
    }

    private fun exportCfg() {
        save()
        val j = JSONObject()
        val p = Cfg.prefs(this)
        for (k in nums.keys) j.put(k, p.getInt(k, 0))
        for (k in txts.keys) j.put(k, p.getString(k, "") ?: "")
        for (k in checks.keys) j.put(k, p.getBoolean(k, false))
        j.put("speed", p.getInt("speed", 1))
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("autofood-config", "AUTOFOOD-CONFIG " + j.toString()))
        Toast.makeText(this, "Configurações copiadas. Cole em algum lugar para guardar.", Toast.LENGTH_LONG).show()
    }

    private fun importCfg() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val t = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            if (!t.startsWith("AUTOFOOD-CONFIG ")) {
                Toast.makeText(this, "Nada para colar: copie antes as configurações", Toast.LENGTH_LONG).show()
                return
            }
            val j = JSONObject(t.removePrefix("AUTOFOOD-CONFIG "))
            val e = Cfg.prefs(this).edit()
            for (k in nums.keys) if (j.has(k)) e.putInt(k, clampNum(k, j.getInt(k)))
            for (k in txts.keys) if (j.has(k)) e.putString(k, j.getString(k))
            for (k in checks.keys) if (j.has(k)) e.putBoolean(k, j.getBoolean(k))
            if (j.has("speed")) e.putInt("speed", j.getInt("speed").coerceIn(0, 2))
            e.apply()
            Toast.makeText(this, "Configurações restauradas", Toast.LENGTH_SHORT).show()
            skipSave = true
            recreate()
        } catch (ex: Exception) {
            Toast.makeText(this, "Texto de configuração inválido", Toast.LENGTH_LONG).show()
        }
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
        c.background = rounded(cCard, 16, cStroke, 1)
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

    private fun section(root: LinearLayout, s: String) {
        val t = text(root, s.uppercase(), 12f, cGreen, true)
        t.letterSpacing = 0.1f
        t.setPadding(0, 0, 0, dp(10))
    }

    private fun statusRow(root: LinearLayout, label: String): Pair<View, TextView> {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(5), 0, dp(5))
        val d = View(this)
        d.background = circle(cGray)
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
        s.typeface = Typeface.DEFAULT_BOLD
        s.setTextColor(cGray)
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
        b.background = ripple(rounded(cGreen, 12))
        b.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, dp(8), 0, 0)
        root.addView(b, lp)
    }

    private fun secondary(label: String, color: Int, onClick: () -> Unit): TextView {
        val b = TextView(this)
        b.text = label
        b.textSize = 14f
        b.setTextColor(color)
        b.gravity = Gravity.CENTER
        b.setPadding(dp(12), dp(11), dp(12), dp(11))
        b.background = ripple(rounded(cField, 10, cStroke, 1))
        b.setOnClickListener { onClick() }
        return b
    }

    private fun field(et: EditText) {
        et.setTextColor(cText)
        et.setHintTextColor(cMuted)
        et.background = rounded(cField, 10, cStroke, 1)
        et.setPadding(dp(12), dp(10), dp(12), dp(10))
        et.textSize = 15f
    }

    private fun tint(sw: Switch) {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        sw.thumbTintList = ColorStateList(states, intArrayOf(cGreen, Color.parseColor("#9AA5B3")))
        sw.trackTintList = ColorStateList(states, intArrayOf(Color.parseColor("#1F6F3F"), Color.parseColor("#2B3542")))
    }

    private fun check(root: LinearLayout, key: String, label: String, def: Boolean) {
        val c = Switch(this)
        c.text = label
        c.textSize = 14f
        c.setTextColor(cText)
        c.isChecked = Cfg.prefs(this).getBoolean(key, def)
        c.setPadding(0, dp(6), 0, dp(6))
        tint(c)
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
