package com.autofood.brp

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.text.Normalizer

class OLine(val text: String, val rect: Rect) {
    val norm: String = OcrUtil.normalize(text)
}

class Screen(val w: Int, val h: Int, val lines: List<OLine>) {
    /** true quando so um retangulo da tela foi lido (leitura rapida). */
    var partial = false
    fun find(sub: String): OLine? = lines.firstOrNull { it.norm.contains(sub) }
    fun has(sub: String): Boolean = find(sub) != null
    fun dump(): String = lines.joinToString(" | ") { it.text }
}

object OcrUtil {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val marks = Regex("\\p{M}+")

    fun normalize(s: String): String {
        val n = Normalizer.normalize(s, Normalizer.Form.NFD)
        return n.replace(marks, "").lowercase()
    }

    /** Aquece o OCR (a primeira leitura e lenta). */
    fun warmUp() {
        try {
            val b = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            try { scan(b) } finally { b.recycle() }
        } catch (e: Exception) { }
    }

    /** Le o bitmap. NAO recicla: quem chamou e dono da imagem. */
    fun scan(bmp: Bitmap): Screen {
        val out = ArrayList<OLine>()
        try {
            val res = Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)))
            for (b in res.textBlocks) {
                for (l in b.lines) {
                    val r = l.boundingBox ?: continue
                    out.add(OLine(l.text, r))
                }
            }
        } catch (e: Exception) {
            Logger.add("OCR erro: $e")
        }
        return Screen(bmp.width, bmp.height, out)
    }

    /**
     * OCR numa regiao ampliada (bom para texto pequeno). Retorna coordenadas da imagem original.
     * Recicla as imagens intermediarias (recorte e ampliada). O bitmap original continua com quem chamou.
     */
    fun scanRegion(bmp: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int, scale: Int): Screen {
        val crop = Bitmap.createBitmap(bmp, x0, y0, x1 - x0, y1 - y0)
        val up = Bitmap.createScaledBitmap(crop, crop.width * scale, crop.height * scale, true)
        val s = try {
            scan(up)
        } finally {
            if (up !== crop) up.recycle()
            if (crop !== bmp) crop.recycle()
        }
        val mapped = s.lines.map {
            OLine(
                it.text,
                Rect(
                    it.rect.left / scale + x0, it.rect.top / scale + y0,
                    it.rect.right / scale + x0, it.rect.bottom / scale + y0
                )
            )
        }
        return Screen(bmp.width, bmp.height, mapped)
    }
}
