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

    /** Passa a guardar o log tambem num arquivo de texto (so texto, sem imagens). */
    @Synchronized
    fun init(c: Context) {
        if (file != null) return
        try {
            val f = File(c.filesDir, "log.txt")
            file = f
            if (f.exists()) {
                if (f.length() > 300_000) {
                    val keep = f.readLines().takeLast(1500)
                    f.writeText(keep.joinToString("\n") + "\n")
                }
                lines.addAll(f.readLines().takeLast(200))
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
        while (lines.size > 300) lines.removeAt(0)
        try { file?.appendText(line + "\n") } catch (e: Exception) { }
    }

    @Synchronized
    fun dump(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() {
        lines.clear()
        try { file?.writeText("") } catch (e: Exception) { }
    }
}
