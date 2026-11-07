package com.autofood.brp

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings as AndroidSettings
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val nums = LinkedHashMap<String, EditText>()
    private val txts = LinkedHashMap<String, EditText>()
    private val checks = LinkedHashMap<String, CheckBox>()
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private var lastLog = ""
    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            h.postDelayed(this, 2000)
        }
    }

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sv = ScrollView(this)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(16), dp(16), dp(32))
        sv.addView(root)

        label(root, "AutoFood BRP", 22f)
        status = label(root, "", 14f)

        button(root, "1) Ativar acessibilidade") {
            startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        button(root, "2) Permitir captura de tela") {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mpm.createScreenCaptureIntent(), 100)
        }
        label(
            root,
            "3) Abra o jogo. Vai aparecer um painel pequeno: AUTO liga/desliga, TA testa agua uma vez, TC testa comida uma vez. Arraste pelo ::::",
            13f
        )

        label(root, "\nAgua", 16f)
        check(root, "water_on", "Beber automaticamente", true)
        numRow(root, "water_trigger", "Beber quando sede ficar em (%) ou menos", 40)
        numRow(root, "water_target", "Beber ate chegar em (%)", 100)
        textRow(root, "water_words", "Nome do item de agua (separe por virgula)", "agua")

        label(root, "\nComida", 16f)
        check(root, "food_on", "Comer automaticamente", true)
        numRow(root, "food_trigger", "Comer quando fome ficar em (%) ou menos", 40)
        numRow(root, "food_target", "Comer ate chegar em (%)", 100)
        textRow(root, "food_words", "Nome(s) da comida (separe por virgula)", "arroz,feijao")

        label(root, "\nOutros", 16f)
        numRow(root, "sleep_alarm", "Alarme quando sono ficar em (%) ou menos", 20)
        numRow(root, "interval", "Verificar a cada (segundos)", 15)

        button(root, "Salvar") {
            save()
            Toast.makeText(this, "Salvo", Toast.LENGTH_SHORT).show()
        }

        label(root, "\nLog (mande isso pra mim se der erro)", 16f)
        button(root, "Copiar log") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("log", Logger.dump()))
            Toast.makeText(this, "Log copiado", Toast.LENGTH_SHORT).show()
        }
        button(root, "Limpar log") {
            Logger.clear()
            refresh()
        }
        logView = label(root, "", 11f)
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
        val a = if (AutoService.instance != null) "ATIVA" else "desligada"
        val c = if (CaptureService.instance != null) "ATIVA" else "desligada"
        status.text = "Acessibilidade: $a\nCaptura de tela: $c"
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
            if (n != null) e.putInt(k, if (k == "interval") n.coerceIn(5, 600) else n.coerceIn(0, 100))
        }
        for ((k, v) in txts) e.putString(k, v.text.toString())
        for ((k, v) in checks) e.putBoolean(k, v.isChecked)
        e.apply()
    }

    // ---------- helpers de layout ----------

    private fun label(root: LinearLayout, text: String, size: Float): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = size
        root.addView(t)
        return t
    }

    private fun button(root: LinearLayout, text: String, onClick: () -> Unit) {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        root.addView(b)
    }

    private fun check(root: LinearLayout, key: String, label: String, def: Boolean) {
        val c = CheckBox(this)
        c.text = label
        c.isChecked = Cfg.prefs(this).getBoolean(key, def)
        root.addView(c)
        checks[key] = c
    }

    private fun numRow(root: LinearLayout, key: String, label: String, def: Int) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val tv = TextView(this)
        tv.text = label
        row.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val et = EditText(this)
        et.inputType = InputType.TYPE_CLASS_NUMBER
        et.setText(Cfg.prefs(this).getInt(key, def).toString())
        row.addView(et, LinearLayout.LayoutParams(dp(90), ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(row)
        nums[key] = et
    }

    private fun textRow(root: LinearLayout, key: String, label: String, def: String) {
        val tv = TextView(this)
        tv.text = label
        root.addView(tv)
        val et = EditText(this)
        et.setText(Cfg.prefs(this).getString(key, def) ?: def)
        root.addView(et)
        txts[key] = et
    }
}
