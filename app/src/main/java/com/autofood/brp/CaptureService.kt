package com.autofood.brp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display

class CaptureService : Service() {
    companion object {
        @Volatile
        var instance: CaptureService? = null

        private const val IDLE_MS = 3000L
    }

    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var curW = 0
    private var curH = 0
    private var released = false
    private val lock = Any()

    // economia: com o bot ocioso, o VirtualDisplay deixa de renderizar (surface = null)
    @Volatile private var paused = false
    @Volatile private var pauseDisabled = false
    @Volatile private var lastUse = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val idleCheck = object : Runnable {
        override fun run() {
            if (released) return
            try {
                if (!paused && !pauseDisabled &&
                    System.currentTimeMillis() - lastUse > IDLE_MS &&
                    Cfg.prefs(this@CaptureService).getBoolean("saver", true)
                ) {
                    synchronized(lock) {
                        vd?.surface = null
                        paused = true
                    }
                }
            } catch (e: Exception) {
                pauseDisabled = true
                Logger.add("Pausa da captura indisponivel: $e")
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("af", "AutoFood", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "af")
            .setContentTitle("AutoFood BRP")
            .setContentText("Captura de tela ativa")
            .setSmallIcon(R.drawable.ic_stat)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }

        val code = intent?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data")
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val p = mpm.getMediaProjection(code, data)
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Logger.add("Captura de tela encerrada")
                    release()
                }
            }, Handler(Looper.getMainLooper()))
            projection = p
            val (w, h, d) = realSize()
            setup(w, h, d)
            instance = this
            released = false
            lastUse = System.currentTimeMillis()
            handler.postDelayed(idleCheck, 1000)
            Logger.add("Captura de tela pronta: ${w}x$h")
        } catch (e: Exception) {
            Logger.add("Erro na captura: $e")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun realSize(): Triple<Int, Int, Int> {
        val dm = DisplayMetrics()
        val disp = (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION")
        disp.getRealMetrics(dm)
        return Triple(dm.widthPixels, dm.heightPixels, dm.densityDpi)
    }

    private fun setup(w: Int, h: Int, dpi: Int) {
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        val existing = vd
        if (existing == null) {
            vd = projection!!.createVirtualDisplay(
                "autofood", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null
            )
        } else {
            existing.resize(w, h, dpi)
            existing.surface = r.surface
        }
        paused = false
        val old = reader
        reader = r
        curW = w
        curH = h
        try { old?.close() } catch (e: Exception) { }
    }

    /**
     * Devolve um bitmap NOVO a cada chamada. Quem chama deve chamar recycle() assim que terminar de usar.
     * Nada e gravado em disco.
     */
    fun capture(): Bitmap? {
        lastUse = System.currentTimeMillis()
        try {
            return synchronized(lock) { captureLocked() }
        } finally {
            lastUse = System.currentTimeMillis()
        }
    }

    private fun captureLocked(): Bitmap? {
        if (reader == null) return null
        val (w, h, d) = realSize()
        if (w != curW || h != curH) {
            setup(w, h, d)
            Thread.sleep(700)
        }
        val r = reader ?: return null
        val wasPaused = paused
        if (wasPaused) {
            try {
                vd?.surface = r.surface
            } catch (e: Exception) {
                Logger.add("Erro ao retomar captura: $e")
            }
            paused = false
        }
        // descarta qualquer quadro velho: so vale um quadro gerado a partir de agora
        try { r.acquireLatestImage()?.close() } catch (e: Exception) { }
        var img: Image? = null
        val end = System.currentTimeMillis() + 2500
        while (img == null && System.currentTimeMillis() < end) {
            img = try { r.acquireLatestImage() } catch (e: Exception) { null }
            if (img == null) Thread.sleep(8)
        }
        if (img == null) {
            if (wasPaused && !pauseDisabled) {
                // se retomar nao entregou quadro, desliga a economia para nao atrapalhar o bot
                pauseDisabled = true
                Logger.add("Retomar captura nao entregou imagem: economia de captura desligada")
            }
            return null
        }
        try {
            val plane = img.planes[0]
            val pw = img.width
            val ph = img.height
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * pw
            val tmp = Bitmap.createBitmap(pw + rowPadding / pixelStride, ph, Bitmap.Config.ARGB_8888)
            tmp.copyPixelsFromBuffer(plane.buffer)
            if (rowPadding == 0) return tmp
            val cropped = Bitmap.createBitmap(tmp, 0, 0, pw, ph)
            tmp.recycle()
            return cropped
        } finally {
            img.close()
        }
    }

    private fun release() {
        if (released) return
        released = true
        instance = null
        handler.removeCallbacks(idleCheck)
        val p = projection
        projection = null
        try { vd?.release() } catch (e: Exception) { }
        try { reader?.close() } catch (e: Exception) { }
        vd = null
        reader = null
        try { p?.stop() } catch (e: Exception) { }
        stopSelf()
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }
}
