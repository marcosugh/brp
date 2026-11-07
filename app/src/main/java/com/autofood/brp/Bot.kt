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

    @Volatile
    private var cfg: Cfg? = null

    private val blocked = HashMap<Kind, Long>()
    private var lastAlarm = 0L

    @Volatile
    private var last: Screen? = null

    private var offSince = 0L
    private var noRecoverUntil = 0L
    private var recoverFails = 0
    private val popupWords = listOf("promocao", "halloween", "evento", "oferta")
    // posicoes estimadas do X do anuncio (fracao da tela), medidas num print do MEmu
    private val closeCands = listOf(Pair(0.784f, 0.248f), Pair(0.80f, 0.23f), Pair(0.77f, 0.265f))

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
                val c = Cfg.load(svc)
                cfg = c
                val r = useItem(kind, c)
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

    private fun clock(): Long = System.currentTimeMillis()

    private fun sleepc(ms: Long) {
        var t = 0L
        while (t < ms) {
            chk()
            val step = minOf(200L, ms - t)
            Thread.sleep(step)
            t += step
        }
    }

    private fun tapDur(): Long = cfg?.tapDur ?: 50L
    private fun poll(): Long = cfg?.poll ?: 40L
    private fun tmo(): Long = cfg?.waitMs ?: 10000L

    private fun tap(x: Float, y: Float) {
        svc.tap(x, y, tapDur())
    }

    private fun isBlocked(k: Kind): Boolean = (blocked[k] ?: 0L) > clock()
    private fun blockFor(k: Kind, ms: Long) {
        blocked[k] = clock() + ms
    }

    // ================= loop principal =================

    private fun loop() {
        Logger.add("Bot iniciado (jogo: " + (if (targetPkg.isEmpty()) "qualquer" else targetPkg) + ")")
        svc.setStatus("ligado")
        while (true) {
            chk()
            val s = Cfg.load(svc)
            cfg = s
            if (targetPkg.isNotEmpty() && svc.foregroundPkg != targetPkg) {
                svc.setStatus("espera")
                if (offSince == 0L) offSince = clock()
                if (s.autoReopen && clock() - offSince > 10000 && clock() >= noRecoverUntil) {
                    if (recoverGame()) {
                        recoverFails = 0
                    } else {
                        recoverFails++
                        noRecoverUntil = clock() + 60000
                        if (recoverFails >= 3) {
                            svc.alarm()
                            noRecoverUntil = clock() + 300000
                            recoverFails = 0
                            Logger.add("ALERTA: nao consegui reabrir o jogo (nova tentativa em 5 min)")
                        }
                    }
                    offSince = 0L
                } else {
                    sleepc(2000)
                }
                continue
            }
            offSince = 0L
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
            val now = clock()
            if (lv.sleep <= s.sleepAlarm && now - lastAlarm > 60000) {
                lastAlarm = now
                svc.alarm()
                Logger.add("ALERTA: sono em ${lv.sleep}%")
            }
            var lvNow = lv
            // 1) agua primeiro, ate a meta
            if (s.waterOn && lv.drink <= s.waterTrigger && !isBlocked(Kind.WATER)) {
                consume(Kind.WATER, s, lv.drink)
                lvNow = readLevels() ?: lv
            }
            // 2) depois comida, ate a meta
            if (s.foodOn && lvNow.food <= s.foodTrigger && !isBlocked(Kind.FOOD)) {
                consume(Kind.FOOD, s, lvNow.food)
            }
            svc.setStatus("ligado")
            sleepc(s.interval * 1000L)
        }
    }

    private fun consume(kind: Kind, s: Cfg, startLevel: Int) {
        val target = if (kind == Kind.WATER) s.waterTarget else s.foodTarget
        val name = if (kind == Kind.WATER) "agua" else "comida"
        var uses = 0
        var fails = 0
        var cur = startLevel
        while (uses < 12) {
            chk()
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
            // em vez de esperar um tempo fixo: le a barra ate ela subir (ou ate o limite)
            val now = waitLevelAbove(kind, cur, 5000)
            if (now > cur) {
                uses++
                fails = 0
                cur = now
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

    /** Le a barra repetidamente e retorna assim que o valor passar de [cur]; senao devolve o ultimo valor lido. */
    private fun waitLevelAbove(kind: Kind, cur: Int, timeout: Long): Int {
        val end = clock() + timeout
        var v = cur
        while (clock() < end) {
            chk()
            val lv = readLevels()
            if (lv != null) {
                v = if (kind == Kind.WATER) lv.drink else lv.food
                if (v > cur) return v
            }
            if (poll() > 0) sleepc(poll())
        }
        return v
    }

    // ================= recuperar o jogo =================

    private fun hudVisible(): Boolean {
        val bmp = CaptureService.instance?.capture() ?: return false
        val sc = try {
            OcrUtil.scanRegion(bmp, (bmp.width * 0.45f).toInt(), (bmp.height * 0.86f).toInt(), bmp.width, bmp.height, 2)
        } finally {
            bmp.recycle()
        }
        return percents(sc).size >= 3
    }

    private fun hasPopup(sc: Screen) = sc.lines.any { l -> popupWords.any { l.norm.contains(it) } }

    /** Abre o jogo de novo, fecha o anuncio, toca em JOGAR e espera a tela do jogo (barras) aparecer. */
    private fun recoverGame(): Boolean {
        var pkg = targetPkg
        if (pkg.isEmpty()) pkg = svc.findPkgByLabel("brasil roleplay")
        if (pkg.isEmpty()) {
            Logger.add("Nao achei o app Brasil Roleplay para reabrir")
            return false
        }
        Logger.add("Jogo fora da tela: reabrindo $pkg")
        svc.setStatus("reabrindo")
        if (!svc.launchGame(pkg)) return false
        targetPkg = pkg
        val end = clock() + 120000
        var closeTry = 0
        var lastJogar = 0L
        while (clock() < end) {
            chk()
            val sc = shot()
            if (sc == null) {
                sleepc(1000)
                continue
            }
            val jogar = sc.lines.firstOrNull { it.norm.trim() == "jogar" }
            if (hasPopup(sc)) {
                val c = closeCands[closeTry % closeCands.size]
                closeTry++
                Logger.add("Anuncio detectado: fechando (tentativa $closeTry)")
                tap(sc.w * c.first, sc.h * c.second)
                waitFor(1500) { !hasPopup(it) }
                continue
            }
            if (jogar != null) {
                if (clock() - lastJogar > 8000) {
                    Logger.add("Tocando em JOGAR")
                    tap(jogar.rect.exactCenterX(), jogar.rect.exactCenterY())
                    lastJogar = clock()
                }
            } else if (hudVisible()) {
                svc.foregroundPkg = pkg
                Logger.add("Jogo de volta: continuando")
                svc.setStatus("ligado")
                return true
            }
            sleepc(500)
        }
        Logger.add("FALHA ao reabrir | tela: ${last?.dump()?.take(500)}")
        return false
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
            val sc = try {
                OcrUtil.scanRegion(bmp, x0, y0, w, h, 2 + att)
            } finally {
                bmp.recycle()
            }
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

    /** Tira uma foto da tela, le o texto e descarta a imagem na hora. */
    private fun shot(): Screen? {
        chk()
        val b = CaptureService.instance?.capture()
        if (b == null) {
            Logger.add("Sem imagem da tela (captura nao ativa?)")
            return null
        }
        val s = try {
            OcrUtil.scan(b)
        } finally {
            b.recycle()
        }
        last = s
        return s
    }

    /** Fica lendo a tela e retorna no instante em que [pred] for verdadeiro (ou null no tempo limite). */
    private fun waitFor(timeout: Long, pred: (Screen) -> Boolean): Screen? {
        val end = clock() + timeout
        while (clock() < end) {
            chk()
            val sc = shot()
            if (sc != null && pred(sc)) return sc
            if (poll() > 0) sleepc(poll())
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
            tap(l.rect.exactCenterX(), l.rect.exactCenterY())
        } else {
            Logger.add("Botao '$word' nao lido, usando posicao estimada")
            tap(sc.w * fx, sc.h * fy)
        }
    }

    private fun closeDialog(sc: Screen) {
        val l = sc.lines.filter {
            (it.norm.contains("cancelar") || it.norm.contains("fechar")) && it.rect.top > sc.h * 0.4
        }.maxByOrNull { it.rect.top }
        if (l != null) tap(l.rect.exactCenterX(), l.rect.exactCenterY())
        else tap(sc.w * 0.54f, sc.h * 0.775f)
    }

    private fun cleanState(): Boolean {
        for (i in 0 until 4) {
            val sc = shot() ?: return false
            if (!anyDialog(sc)) return true
            closeDialog(sc)
            if (waitFor(1500) { !anyDialog(it) } != null) return true
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
        tap(sc.w * fx, sc.h * fy)
        val gap = cfg?.keyGap ?: 70L
        if (gap > 0) sleepc(gap)
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
                tap(sc.w * 0.30f, sc.h * 0.10f)
                sc = waitFor(tmo()) { keyboardOpen(it) } ?: continue
            }
            if (!sc.has("digite algo")) {
                for (i in 0 until 14) tapFrac(sc, 0.922f, 0.825f)
            }
            typeText(sc, "/bolsos")
            // espera o texto aparecer no campo (o aviso "digite algo" some)
            val v = waitFor(1500) { !it.has("digite algo") }
            if (v == null) {
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

    /**
     * Depois de rolar a lista: espera a lista mudar em relacao a [prev] e parar de se mexer
     * (duas leituras seguidas iguais). Retorna null se nada mudou ate o limite (fim da lista).
     */
    private fun waitSettled(prev: String, timeout: Long): Screen? {
        val end = clock() + timeout
        var cand: Screen? = null
        var candSig = ""
        while (clock() < end) {
            chk()
            val sc = shot() ?: return null
            if (isList(sc)) {
                val sg = signature(sc)
                if (sg != prev) {
                    if (cand != null && sg == candSig) return sc
                    cand = sc
                    candSig = sg
                } else {
                    cand = null
                }
            } else {
                cand = null
            }
            if (poll() > 0) sleepc(poll())
        }
        return cand
    }

    private fun useItem(kind: Kind, s: Cfg): Res {
        val words = if (kind == Kind.WATER) s.waterWords else s.foodWords
        if (!cleanState()) return fail("nao consegui limpar a tela (dialogo desconhecido aberto?)")
        if (!openBolsos()) return fail("nao consegui digitar /bolsos")

        var sc = waitFor(tmo()) { isBolsos(it) } ?: return fail("dialogo Bolsos nao abriu")
        press(sc, "selecionar", 0.454f, 0.772f) // "Abrir Mochila" ja vem marcado

        sc = waitFor(tmo()) { isList(it) } ?: return fail("lista da mochila nao abriu")
        var item = findItem(sc, words)
        var tries = 0
        var sig = signature(sc)
        while (item == null && tries < 6) {
            val (top, bottom) = listRegion(sc)
            svc.swipe(sc.w * 0.5f, (bottom - 30).toFloat(), sc.w * 0.5f, (top + 30).toFloat(), 500)
            val n = waitSettled(sig, 2500) ?: break
            sc = n
            sig = signature(sc)
            item = findItem(sc, words)
            tries++
        }
        if (item == null) {
            Logger.add("Item nao encontrado na mochila. Procurei: $words | Tela: ${sc.dump().take(700)}")
            closeAll()
            return Res.NO_ITEM
        }

        tap(item.rect.exactCenterX(), item.rect.exactCenterY())
        // tocar no item normalmente ja abre o menu: espera aparecer; se nao abrir, usa o botao Selecionar
        var a = waitFor(s.menuWait) { isActions(it) }
        if (a == null) {
            val l = shot()
            if (l != null && isList(l)) {
                press(l, "selecionar", 0.454f, 0.772f)
            }
            a = waitFor(tmo()) { isActions(it) }
        }
        if (a == null) return fail("menu do item (Usar Item) nao abriu")

        press(a, "selecionar", 0.461f, 0.682f) // "Usar Item" ja vem marcado
        val done = waitFor(tmo()) { !anyDialog(it) }
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
            if (waitFor(1500) { !anyDialog(it) } != null) return
        }
    }
}
