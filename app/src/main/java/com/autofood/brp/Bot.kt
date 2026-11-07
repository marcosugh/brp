package com.autofood.brp

enum class Kind { WATER, FOOD }
enum class Res { OK, FAIL, NO_ITEM }
class Levels(val food: Int, val drink: Int, val sleep: Int)

class Bot(private val svc: AutoService) {
    private class Stop : RuntimeException()

    @Volatile
    var running = false

    @Volatile
    private var busy = false

    @Volatile
    private var cancel = false

    @Volatile
    private var targetPkg = ""

    private val blocked = HashMap<Kind, Long>()
    private var lastAlarm = 0L

    @Volatile
    private var last: Screen? = null

    private val invRe = Regex("inv\\s*\\d+\\s*/\\s*\\d+")

    // ================= controle =================

    fun start(pkg: String) {
        if (busy) {
            Logger.add("Bot ocupado")
            return
        }
        busy = true
        cancel = false
        running = true
        targetPkg = pkg
        svc.refreshButtons()
        Thread {
            try {
                loop()
            } catch (e: Stop) {
                Logger.add("Bot parado")
            } catch (e: Throwable) {
                Logger.add("ERRO: $e")
            } finally {
                running = false
                busy = false
                svc.setStatus("parado")
                svc.refreshButtons()
            }
        }.start()
    }

    fun stop() {
        cancel = true
    }

    fun test(kind: Kind) {
        if (busy) {
            Logger.add("Bot ocupado")
            return
        }
        busy = true
        cancel = false
        svc.setStatus("testando")
        Thread {
            try {
                val r = useItem(kind, Cfg.load(svc))
                Logger.add("Teste $kind: $r")
            } catch (e: Stop) {
                Logger.add("Teste parado")
            } catch (e: Throwable) {
                Logger.add("ERRO: $e")
            } finally {
                busy = false
                svc.setStatus("pronto")
            }
        }.start()
    }

    private fun chk() {
        if (cancel) throw Stop()
    }

    private fun sleepc(ms: Long) {
        var t = 0L
        while (t < ms) {
            chk()
            val step = minOf(200L, ms - t)
            Thread.sleep(step)
            t += step
        }
    }

    private fun isBlocked(k: Kind): Boolean = (blocked[k] ?: 0L) > System.currentTimeMillis()
    private fun blockFor(k: Kind, ms: Long) {
        blocked[k] = System.currentTimeMillis() + ms
    }

    // ================= loop principal =================

    private fun loop() {
        Logger.add("Bot iniciado (jogo: " + (if (targetPkg.isEmpty()) "qualquer" else targetPkg) + ")")
        svc.setStatus("ligado")
        while (true) {
            chk()
            val s = Cfg.load(svc)
            if (targetPkg.isNotEmpty() && svc.foregroundPkg != targetPkg) {
                svc.setStatus("espera")
                sleepc(2000)
                continue
            }
            if (CaptureService.instance == null) {
                svc.setStatus("sem captura")
                sleepc(3000)
                continue
            }
            val lv = readLevels()
            if (lv == null) {
                svc.setStatus("sem leitura")
                sleepc(5000)
                continue
            }
            svc.setStatus("ligado")
            val now = System.currentTimeMillis()
            if (lv.sleep <= s.sleepAlarm && now - lastAlarm > 60000) {
                lastAlarm = now
                svc.alarm()
                Logger.add("ALERTA: sono em ${lv.sleep}%")
            }
            // 1) agua primeiro, ate a meta
            if (s.waterOn && lv.drink <= s.waterTrigger && !isBlocked(Kind.WATER)) {
                consume(Kind.WATER, s)
            }
            // 2) depois comida, ate a meta
            val lv2 = readLevels() ?: lv
            if (s.foodOn && lv2.food <= s.foodTrigger && !isBlocked(Kind.FOOD)) {
                consume(Kind.FOOD, s)
            }
            svc.setStatus("ligado")
            sleepc(s.interval * 1000L)
        }
    }

    private fun consume(kind: Kind, s: Cfg) {
        val target = if (kind == Kind.WATER) s.waterTarget else s.foodTarget
        val name = if (kind == Kind.WATER) "agua" else "comida"
        var uses = 0
        var fails = 0
        while (uses < 12) {
            chk()
            val lv = readLevels() ?: return
            val cur = if (kind == Kind.WATER) lv.drink else lv.food
            if (cur >= target) {
                Logger.add("$name em $cur% (meta $target%)")
                return
            }
            svc.setStatus(if (kind == Kind.WATER) "bebendo" else "comendo")
            Logger.add("Usando $name ($cur%, meta $target%)")
            when (useItem(kind, s)) {
                Res.NO_ITEM -> {
                    blockFor(kind, 300000)
                    svc.alarm()
                    svc.setStatus(if (kind == Kind.WATER) "sem item A" else "sem item C")
                    Logger.add("ALERTA: acabou $name na mochila (nova tentativa em 5 min)")
                    return
                }
                Res.FAIL -> {
                    fails++
                    if (fails >= 3) {
                        blockFor(kind, 120000)
                        svc.alarm()
                        Logger.add("ALERTA: 3 falhas seguidas usando $name (nova tentativa em 2 min)")
                        return
                    }
                    continue
                }
                Res.OK -> {}
            }
            sleepc(1500)
            val after = readLevels() ?: return
            val now = if (kind == Kind.WATER) after.drink else after.food
            if (now > cur) {
                uses++
                fails = 0
            } else {
                fails++
                Logger.add("$name nao subiu ($cur% -> $now%)")
                if (fails >= 3) {
                    blockFor(kind, 120000)
                    svc.alarm()
                    Logger.add("ALERTA: $name nao esta subindo (nova tentativa em 2 min)")
                    return
                }
            }
        }
    }

    // ================= leitura do HUD =================

    private fun readLevels(): Levels? {
        for (att in 0 until 3) {
            chk()
            val bmp = CaptureService.instance?.capture()
            if (bmp == null) {
                sleepc(500)
                continue
            }
            val w = bmp.width
            val h = bmp.height
            val x0 = (w * 0.45f).toInt()
            val y0 = (h * 0.86f).toInt()
            val sc = OcrUtil.scanRegion(bmp, x0, y0, w, h, 2 + att)
            val vals = percents(sc)
            if (vals.size >= 3) return Levels(vals[0], vals[1], vals[2])
            Logger.add("HUD: achei ${vals.size} valores. Texto: ${sc.dump()}")
        }
        return null
    }

    private fun percents(sc: Screen): List<Int> {
        val found = ArrayList<Pair<Float, Int>>()
        val strict = Regex("(\\d{1,3})\\s*%")
        for (l in sc.lines) {
            for (m in strict.findAll(l.text)) {
                val v = m.groupValues[1].toInt()
                if (v > 100) continue
                val frac = if (l.text.isEmpty()) 0f else m.range.first.toFloat() / l.text.length
                found.add(Pair(l.rect.left + l.rect.width() * frac, v))
            }
        }
        if (found.size < 3) {
            found.clear()
            val loose = Regex("\\d{1,3}")
            for (l in sc.lines) {
                if (l.text.contains('/') || l.text.contains(':')) continue
                for (m in loose.findAll(l.text)) {
                    val v = m.value.toInt()
                    if (v > 100) continue
                    val frac = if (l.text.isEmpty()) 0f else m.range.first.toFloat() / l.text.length
                    found.add(Pair(l.rect.left + l.rect.width() * frac, v))
                }
            }
        }
        return found.sortedBy { it.first }.map { it.second }
    }

    // ================= telas =================

    private fun shot(): Screen? {
        chk()
        val b = CaptureService.instance?.capture()
        if (b == null) {
            Logger.add("Sem imagem da tela (captura nao ativa?)")
            return null
        }
        val s = OcrUtil.scan(b)
        last = s
        return s
    }

    private fun waitFor(timeout: Long, pred: (Screen) -> Boolean): Screen? {
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) {
            chk()
            val sc = shot()
            if (sc != null && pred(sc)) return sc
            sleepc(100)
        }
        return null
    }

    private fun fail(msg: String): Res {
        Logger.add("FALHA: $msg | tela: ${last?.dump()?.take(700)}")
        return Res.FAIL
    }

    private fun isBolsos(sc: Screen) = sc.has("abrir mochila") || sc.has("ver armas") || sc.has("ver drogas")
    private fun isList(sc: Screen) = sc.lines.any { invRe.containsMatchIn(it.norm) } || sc.has("ver bolsos")
    private fun isActions(sc: Screen) = sc.has("destruir") || sc.has("transferir") || sc.has("mover i")
    private fun anyDialog(sc: Screen) = isBolsos(sc) || isList(sc) || isActions(sc)

    private fun keyboardOpen(sc: Screen) =
        sc.has("digite algo") || sc.lines.any { it.norm.contains("lang") && it.rect.top > sc.h * 0.8 }

    private fun press(sc: Screen, word: String, fx: Float, fy: Float) {
        val l = sc.lines.filter { it.norm.contains(word) && it.rect.top > sc.h * 0.4 }.maxByOrNull { it.rect.top }
        if (l != null) {
            svc.tap(l.rect.exactCenterX(), l.rect.exactCenterY())
        } else {
            Logger.add("Botao '$word' nao lido, usando posicao estimada")
            svc.tap(sc.w * fx, sc.h * fy)
        }
    }

    private fun closeDialog(sc: Screen) {
        val l = sc.lines.filter {
            (it.norm.contains("cancelar") || it.norm.contains("fechar")) && it.rect.top > sc.h * 0.4
        }.maxByOrNull { it.rect.top }
        if (l != null) svc.tap(l.rect.exactCenterX(), l.rect.exactCenterY())
        else svc.tap(sc.w * 0.54f, sc.h * 0.775f)
    }

    private fun cleanState(): Boolean {
        for (i in 0 until 4) {
            val sc = shot() ?: return false
            if (!anyDialog(sc)) return true
            closeDialog(sc)
            sleepc(500)
        }
        return false
    }

    // ================= teclado do jogo =================

    private fun keyXY(c: Char): Pair<Float, Float>? {
        val r1 = "qwertyuiop"
        val r2 = "asdfghjkl"
        val r3 = "zxcvbnm"
        var i = r1.indexOf(c)
        if (i >= 0) return Pair(0.0488f + 0.0995f * i, 0.594f)
        i = r2.indexOf(c)
        if (i >= 0) return Pair(0.0942f + 0.1002f * i, 0.709f)
        i = r3.indexOf(c)
        if (i >= 0) return Pair(0.1918f + 0.1018f * i, 0.825f)
        if (c == '/') return Pair(0.046f, 0.943f)
        return null
    }

    private fun tapFrac(sc: Screen, fx: Float, fy: Float) {
        svc.tap(sc.w * fx, sc.h * fy)
        sleepc(130)
    }

    private fun typeText(sc: Screen, text: String) {
        for (c in text) {
            val p = keyXY(c) ?: continue
            tapFrac(sc, p.first, p.second)
        }
    }

    private fun openBolsos(): Boolean {
        for (attempt in 1..3) {
            chk()
            var sc = shot() ?: continue
            if (!keyboardOpen(sc)) {
                svc.tap(sc.w * 0.30f, sc.h * 0.10f)
                sc = waitFor(3000) { keyboardOpen(it) } ?: continue
            }
            if (!sc.has("digite algo")) {
                for (i in 0 until 14) tapFrac(sc, 0.922f, 0.825f)
                sleepc(300)
            }
            typeText(sc, "/bolsos")
            sleepc(300)
            val v = shot() ?: continue
            if (v.has("digite algo")) {
                Logger.add("Nada foi digitado (teclado nao respondeu). Tentativa $attempt")
                continue
            }
            tapFrac(sc, 0.950f, 0.943f)
            return true
        }
        return false
    }

    // ================= item =================

    private fun listRegion(sc: Screen): Pair<Int, Int> {
        val title = sc.lines.firstOrNull { invRe.containsMatchIn(it.norm) } ?: sc.find("ver bolsos")
        val sel = sc.lines.filter { it.norm.contains("selecionar") }.maxByOrNull { it.rect.top }
        val top = title?.rect?.bottom ?: (sc.h * 0.2f).toInt()
        val bottom = sel?.rect?.top ?: (sc.h * 0.75f).toInt()
        return Pair(top, bottom)
    }

    private fun findItem(sc: Screen, words: List<String>): OLine? {
        val (top, bottom) = listRegion(sc)
        return sc.lines.filter { l ->
            l.rect.top >= top - 4 && l.rect.bottom <= bottom + 4 &&
                !l.norm.contains("ver bolsos") &&
                words.any { w -> l.norm.contains(w) }
        }.minByOrNull { it.rect.top }
    }

    private fun signature(sc: Screen): String {
        val (top, bottom) = listRegion(sc)
        return sc.lines.filter { it.rect.top >= top - 4 && it.rect.bottom <= bottom + 4 }
            .joinToString("|") { it.norm }
    }

    private fun useItem(kind: Kind, s: Cfg): Res {
        val words = if (kind == Kind.WATER) s.waterWords else s.foodWords
        if (!cleanState()) return fail("nao consegui limpar a tela (dialogo desconhecido aberto?)")
        if (!openBolsos()) return fail("nao consegui digitar /bolsos")

        var sc = waitFor(7000) { isBolsos(it) } ?: return fail("dialogo Bolsos nao abriu")
        press(sc, "selecionar", 0.454f, 0.772f) // "Abrir Mochila" ja vem marcado

        sc = waitFor(7000) { isList(it) } ?: return fail("lista da mochila nao abriu")
        var item = findItem(sc, words)
        var tries = 0
        var sig = signature(sc)
        while (item == null && tries < 6) {
            val (top, bottom) = listRegion(sc)
            svc.swipe(sc.w * 0.5f, (bottom - 30).toFloat(), sc.w * 0.5f, (top + 30).toFloat(), 500)
            sleepc(700)
            val n = shot() ?: break
            if (!isList(n)) break
            sc = n
            val ns = signature(sc)
            if (ns == sig) break
            sig = ns
            item = findItem(sc, words)
            tries++
        }
        if (item == null) {
            Logger.add("Item nao encontrado na mochila. Procurei: $words | Tela: ${sc.dump().take(700)}")
            closeAll()
            return Res.NO_ITEM
        }

        svc.tap(item.rect.exactCenterX(), item.rect.exactCenterY())
        sleepc(500)
        var a = shot()
        if (a != null && isActions(a)) {
            // tocar no item ja abriu o menu
        } else if (a != null && isList(a)) {
            press(a, "selecionar", 0.454f, 0.772f)
            a = waitFor(5000) { isActions(it) }
        } else {
            a = waitFor(4000) { isActions(it) }
        }
        if (a == null || !isActions(a)) return fail("menu do item (Usar Item) nao abriu")

        press(a, "selecionar", 0.461f, 0.682f) // "Usar Item" ja vem marcado
        val done = waitFor(5000) { !anyDialog(it) }
        if (done == null) {
            closeAll()
            return fail("menu continuou aberto depois de usar")
        }
        return Res.OK
    }

    private fun closeAll() {
        for (i in 0 until 3) {
            val sc = shot() ?: return
            if (!anyDialog(sc)) return
            closeDialog(sc)
            sleepc(500)
        }
    }
}
