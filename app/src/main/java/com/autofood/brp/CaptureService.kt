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
    }

    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var curW = 0
    private var curH = 0
    private var released = false
    private val lock = Any()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("af", "AutoFood", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "af")
            .setContentTitle("AutoFood BRP")
            .setContentText("Captura de tela ativa")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
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
        val old = reader
        reader = r
        curW = w
        curH = h
        try { old?.close() } catch (e: Exception) { }
    }

    fun capture(): Bitmap? {
        return synchronized(lock) { captureLocked() }
    }

    private fun captureLocked(): Bitmap? {
        if (reader == null) return null
        val (w, h, d) = realSize()
        if (w != curW || h != curH) {
            setup(w, h, d)
            Thread.sleep(700)
        }
        val r = reader ?: return null
        try { r.acquireLatestImage()?.close() } catch (e: Exception) { }
        Thread.sleep(130)
        var img: Image? = null
        val end = System.currentTimeMillis() + 2500
        while (img == null && System.currentTimeMillis() < end) {
            img = try { r.acquireLatestImage() } catch (e: Exception) { null }
            if (img == null) Thread.sleep(40)
        }
        if (img == null) return null
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
