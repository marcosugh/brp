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
    val foodWords: List<String>,
    val speed: Int,      // 0 = Rapido, 1 = Normal, 2 = Seguro
    val waitMax: Int,    // segundos maximos esperando um dialogo aparecer
    val saver: Boolean,  // pausar a captura quando ocioso
    val autoReopen: Boolean, // reabrir o jogo se ele fechar
    val autoReconnect: Boolean, // /rec + Reconectar se os dialogos nao abrirem
    val autoServer: Boolean // sair (/q) e entrar de novo se o servidor reiniciar
) {
    /** Duracao do toque (ms). */
    val tapDur: Long get() = when (speed) { 0 -> 35L; 2 -> 70L; else -> 50L }

    /** Pausa entre teclas do teclado do jogo (ms). Nao ha sinal na tela para cada tecla. */
    val keyGap: Long get() = when (speed) { 0 -> 25L; 2 -> 130L; else -> 70L }

    /** Pausa entre uma leitura da tela e outra enquanto espera algo aparecer (ms). */
    val poll: Long get() = when (speed) { 0 -> 0L; 2 -> 100L; else -> 40L }

    /** Quanto esperar o menu do item abrir antes de usar o botao Selecionar (ms). */
    val menuWait: Long get() = when (speed) { 0 -> 900L; 2 -> 1800L; else -> 1200L }

    val waitMs: Long get() = waitMax * 1000L

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
                words(p.getString("food_words", "arroz,feijao") ?: "arroz,feijao"),
                p.getInt("speed", 1).coerceIn(0, 2),
                p.getInt("wait_max", 10).coerceIn(3, 60),
                p.getBoolean("saver", true),
                p.getBoolean("auto_reopen", true),
                p.getBoolean("auto_reconnect", true),
                p.getBoolean("auto_server", true)
            )
        }
    }
}
