package com.autofood.brp

import android.content.Context
import android.content.SharedPreferences

class Cfg(
    val waterOn: Boolean,
    val waterTrigger: Int,
    val waterTarget: Int,
    val foodOn: Boolean,
    val foodTrigger: Int,
    val foodTarget: Int,
    val sleepAlarm: Int,
    val interval: Int,
    val waterWords: List<String>,
    val foodWords: List<String>
) {
    companion object {
        fun prefs(c: Context): SharedPreferences = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)

        private fun words(s: String): List<String> =
            s.split(",").map { OcrUtil.normalize(it).trim() }.filter { it.isNotEmpty() }

        fun load(c: Context): Cfg {
            val p = prefs(c)
            return Cfg(
                p.getBoolean("water_on", true),
                p.getInt("water_trigger", 40),
                p.getInt("water_target", 100),
                p.getBoolean("food_on", true),
                p.getInt("food_trigger", 40),
                p.getInt("food_target", 100),
                p.getInt("sleep_alarm", 20),
                p.getInt("interval", 15),
                words(p.getString("water_words", "agua") ?: "agua"),
                words(p.getString("food_words", "arroz,feijao") ?: "arroz,feijao")
            )
        }
    }
}
