package com.autofood.brp

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Logger {
    private val lines = ArrayList<String>()
    private val fmt = SimpleDateFormat("dd/MM HH:mm:ss", Locale.US)
    private var file: File? = null

    /** Guarda o log num arquivo de texto SEM limite de tamanho (so texto, sem imagens). */
    @Synchronized
    fun init(c: Context) {
        if (file != null) return
        try {
            val f = File(c.filesDir, "log.txt")
            file = f
            if (f.exists() && f.length() > 0) {
                lines.addAll(f.readLines().takeLast(300))
                lines.add("---- app iniciado ----")
            }
        } catch (e: Exception) {
            android.util.Log.d("AutoFood", "log em arquivo indisponivel: $e")
        }
    }

    @Synchronized
    fun add(msg: String) {
        android.util.Log.d("AutoFood", msg)
        val line = fmt.format(Date()) + " " + msg
        lines.add(line)
        while (lines.size > 1500) lines.removeAt(0)
        try { file?.appendText(line + "\n") } catch (e: Exception) { }
    }

    /** Ultimas linhas em memoria (para a tela). */
    @Synchronized
    fun dump(): String = lines.joinToString("\n")

    /** Log completo do arquivo (para copiar). Limita so o que cabe na area de transferencia. */
    @Synchronized
    fun dumpAll(): String {
        return try {
            val t = file?.readText() ?: return dump()
            if (t.length > 900_000) t.takeLast(900_000) else t
        } catch (e: Exception) {
            dump()
        }
    }

    @Synchronized
    fun clear() {
        lines.clear()
        try { file?.writeText("") } catch (e: Exception) { }
    }
}
