package com.autofood.brp

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Logger {
    private val lines = ArrayList<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun add(msg: String) {
        android.util.Log.d("AutoFood", msg)
        lines.add(fmt.format(Date()) + " " + msg)
        while (lines.size > 300) lines.removeAt(0)
    }

    @Synchronized
    fun dump(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() {
        lines.clear()
    }
}
