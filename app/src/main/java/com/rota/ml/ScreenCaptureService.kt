package com.rota.ml

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
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log

class ScreenCaptureService : Service() {

    companion object {
        private const val CHANNEL_ID = "rotaml_screen_capture"
        private const val NOTIFICATION_ID = 4101
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        var onError: ((String) -> Unit)? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var ultimoFrame = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = getResultData(intent)
        if (resultData == null || resultCode == 0) {
            falhar("Autorização de captura de tela inválida")
            return START_NOT_STICKY
        }

        try {
            criarCanalNotificacao()
            val notification = Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("RotaML lendo a tela")
                .setContentText("A captura está ativa para identificar as paradas")
                .setOngoing(true)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, resultData)
            iniciarCaptura()
        } catch (e: Exception) {
            Log.e("ScreenCaptureService", "Erro ao iniciar projeção", e)
            falhar("Erro ao iniciar captura: ${e.message}")
        }
        return START_NOT_STICKY
    }

    private fun iniciarCaptura() {
        val currentProjection = projection ?: throw IllegalStateException("Projeção indisponível")
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        ultimoFrame = 0L

        currentProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                handler.post { pararInterno() }
            }
        }, handler)

        reader.setOnImageAvailableListener({ availableReader ->
            val agora = SystemClock.elapsedRealtime()
            val image = availableReader.acquireLatestImage() ?: return@setOnImageAvailableListener
            if (agora - ultimoFrame < 1400L) {
                image.close()
                return@setOnImageAvailableListener
            }
            ultimoFrame = agora
            val bitmap = converterImagemParaBitmap(image, width, height)
            image.close()
            bitmap?.let {
                val leitor = LeitorEnviosService.instance
                if (leitor != null) leitor.processarBitmapCapturado(it) else it.recycle()
            }
        }, handler)

        virtualDisplay = currentProjection.createVirtualDisplay(
            "RotaML-ScreenCapture",
            width,
            height,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler
        )
    }

    private fun converterImagemParaBitmap(image: android.media.Image, width: Int, height: Int): Bitmap? {
        return try {
            val plane = image.planes.firstOrNull() ?: return null
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * width
            val paddedWidth = width + rowPadding / pixelStride
            val paddedBitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
            paddedBitmap.copyPixelsFromBuffer(plane.buffer)
            val bitmap = Bitmap.createBitmap(paddedBitmap, 0, 0, width, height)
            paddedBitmap.recycle()
            bitmap
        } catch (e: Exception) {
            Log.e("ScreenCaptureService", "Erro convertendo frame", e)
            null
        }
    }

    private fun criarCanalNotificacao() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Leitura ao vivo da tela",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun getResultData(intent: Intent?): Intent? {
        if (intent == null) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
    }

    private fun falhar(message: String) {
        onError?.invoke(message)
        pararInterno()
    }

    private fun pararInterno() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        val currentProjection = projection
        projection = null
        currentProjection?.stop()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        pararInterno()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
