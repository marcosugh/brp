package com.autofood.brp

import android.graphics.Bitmap
import kotlin.math.hypot

/**
 * Acha o "X" branco do anuncio olhando os PIXELS (nao depende de texto nem de resolucao).
 * Procura, na metade direita e numa faixa abaixo do cabecalho do jogo, um pequeno objeto branco,
 * quase quadrado, com formato de X (poucos pixels dentro do retangulo) e isolado de outros brancos.
 * Tudo e medido em fracao da ALTURA da tela, entao serve para qualquer resolucao.
 */
object XFinder {
    /**
     * @param tried pontos ja tocados (ignora candidatos perto deles)
     * @param maxDistH distancia maxima (em alturas de tela) da posicao esperada do X
     * @return centro do X em pixels da tela, ou null
     */
    fun find(bmp: Bitmap, tried: List<Pair<Float, Float>>, maxDistH: Float): Pair<Float, Float>? {
        val W = bmp.width
        val H = bmp.height
        val x0 = (W * 0.50f).toInt()
        val y0 = (H * 0.20f).toInt()
        val x1 = (W * 0.99f).toInt()
        val y1 = (H * 0.45f).toInt()
        val ww = x1 - x0
        val hh = y1 - y0
        if (ww < 20 || hh < 20) return null
        val px = IntArray(ww * hh)
        bmp.getPixels(px, 0, ww, x0, y0, ww, hh)
        val mask = BooleanArray(ww * hh)
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            mask[i] = r >= 185 && g >= 185 && b >= 185
        }
        val label = IntArray(ww * hh)
        val stack = IntArray(ww * hh)
        val minS = H * 0.012f
        val maxS = H * 0.055f
        val ex = W / 2f + 0.505f * H
        val ey = 0.246f * H
        var best: Pair<Float, Float>? = null
        var bestD = Float.MAX_VALUE
        var next = 0
        for (start in mask.indices) {
            if (!mask[start] || label[start] != 0) continue
            next++
            var sp = 0
            stack[sp++] = start
            label[start] = next
            var minX = ww
            var maxX = 0
            var minY = hh
            var maxY = 0
            var cnt = 0
            while (sp > 0) {
                val p = stack[--sp]
                val cx = p % ww
                val cy = p / ww
                cnt++
                if (cx < minX) minX = cx
                if (cx > maxX) maxX = cx
                if (cy < minY) minY = cy
                if (cy > maxY) maxY = cy
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = cx + dx
                        val ny = cy + dy
                        if (nx < 0 || ny < 0 || nx >= ww || ny >= hh) continue
                        val q = ny * ww + nx
                        if (mask[q] && label[q] == 0) {
                            label[q] = next
                            stack[sp++] = q
                        }
                    }
                }
            }
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            if (bw < minS || bh < minS || bw > maxS || bh > maxS) continue
            val asp = bw.toFloat() / bh
            if (asp < 0.7f || asp > 1.45f) continue
            val fill = cnt.toFloat() / (bw * bh)
            if (fill < 0.08f || fill > 0.65f) continue
            // isolamento: quase nenhum outro branco ao redor
            val m = (maxOf(bw, bh) * 0.7f).toInt()
            val ya = maxOf(0, minY - m)
            val yb = minOf(hh - 1, maxY + m)
            val xa = maxOf(0, minX - m)
            val xb = minOf(ww - 1, maxX + m)
            var other = 0
            var area = 0
            for (yy in ya..yb) {
                for (xx in xa..xb) {
                    area++
                    val idx = yy * ww + xx
                    if (mask[idx] && label[idx] != next) other++
                }
            }
            if (other > area * 0.04f) continue
            val cxp = x0 + (minX + maxX) / 2f
            val cyp = y0 + (minY + maxY) / 2f
            var skip = false
            for (t in tried) {
                if (hypot(cxp - t.first, cyp - t.second) < H * 0.03f) skip = true
            }
            if (skip) continue
            val d = hypot(cxp - ex, cyp - ey)
            if (d > maxDistH * H) continue
            if (d < bestD) {
                bestD = d
                best = Pair(cxp, cyp)
            }
        }
        return best
    }
}
