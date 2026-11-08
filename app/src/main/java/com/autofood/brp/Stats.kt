package com.autofood.brp

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Contadores de uma sessao do AUTO. O resumo e salvo em texto quando o AUTO para. */
class Stats {
    val startMs = System.currentTimeMillis()
    var water = 0
    var food = 0
    var fails = 0
    var noItem = 0
    var alarms = 0
    var reopens = 0
    var reconnects = 0
    var serverExits = 0
    var useCount = 0
    var useMsTotal = 0L
    var useMsBest = 0L
    var first: Levels? = null
    var last: Levels? = null

    fun addUse(ms: Long) {
        useCount++
        useMsTotal += ms
        if (useMsBest == 0L || ms < useMsBest) useMsBest = ms
    }

    private fun lv(l: Levels?): String =
        if (l == null) "-" else "fome ${l.food}%  sede ${l.drink}%  sono ${l.sleep}%"

    /** Salva o resumo e os totais acumulados. Linhas no formato "titulo|valor". */
    fun finish(c: Context): String {
        val dur = System.currentTimeMillis() - startMs
        val avg = if (useCount > 0) useMsTotal / useCount else 0L
        val rows = ArrayList<String>()
        rows.add("Duração|" + fmt(dur))
        rows.add("Água|$water")
        rows.add("Comida|$food")
        rows.add("Tempo médio por uso|" + (if (useCount > 0) secs(avg) else "-"))
        rows.add("Uso mais rápido|" + (if (useCount > 0) secs(useMsBest) else "-"))
        rows.add("Falhas|$fails")
        rows.add("Itens acabaram|$noItem")
        rows.add("Alarmes|$alarms")
        rows.add("Jogo reaberto|$reopens")
        rows.add("Reconexões (/rec)|$reconnects")
        rows.add("Saídas por reinício do servidor|$serverExits")
        rows.add("Barras no início|" + lv(first))
        rows.add("Barras no fim|" + lv(last))
        val text = rows.joinToString("\n")
        val p = Cfg.prefs(c)
        p.edit()
            .putString("last_session", text)
            .putLong("last_session_at", System.currentTimeMillis())
            .putInt("tot_water", p.getInt("tot_water", 0) + water)
            .putInt("tot_food", p.getInt("tot_food", 0) + food)
            .putLong("tot_ms", p.getLong("tot_ms", 0L) + dur)
            .putInt("tot_sessions", p.getInt("tot_sessions", 0) + 1)
            .apply()
        return text
    }

    companion object {
        fun fmt(ms: Long): String {
            val s = ms / 1000
            val h = s / 3600
            val m = (s % 3600) / 60
            val sec = s % 60
            return if (h > 0) "${h}h ${m}min" else if (m > 0) "${m}min ${sec}s" else "${sec}s"
        }

        fun secs(ms: Long): String = String.format(Locale.US, "%.1fs", ms / 1000.0)

        fun date(ms: Long): String = SimpleDateFormat("dd/MM HH:mm", Locale.US).format(Date(ms))
    }
}
