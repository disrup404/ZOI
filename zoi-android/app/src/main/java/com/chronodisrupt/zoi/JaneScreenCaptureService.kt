package com.chronodisrupt.zoi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
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
import android.content.pm.ServiceInfo
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

internal object ScreenCaptureStore {
    @Volatile var onCaptured: ((ByteArray) -> Unit)? = null
    @Volatile var onFailed: (() -> Unit)? = null
}

class JaneScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var captureStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA) as? Intent
        }

        if (resultCode != android.app.Activity.RESULT_OK || resultData == null) {
            failCapture()
            return START_NOT_STICKY
        }

        try {
            createNotificationChannel()
            val notification = Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("Jane screen capture")
                .setContentText("Capturing the screen you chose to share")
                .setOngoing(true)
                .build()

            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, resultData)
            val activeProjection = projection ?: throw IllegalStateException("Screen capture permission was unavailable")

            val metrics = DisplayMetrics()
            val windowManager = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            if (width <= 0 || height <= 0) throw IllegalStateException("Invalid screen dimensions")

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            activeProjection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    releaseCaptureResources()
                    projection = null
                    stopSelf()
                }
            }, mainHandler)

            imageReader?.setOnImageAvailableListener({ reader ->
                if (captureStarted) return@setOnImageAvailableListener
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                captureStarted = true
                try {
                    val jpeg = imageToJpeg(image)
                    mainHandler.post {
                        val callback = ScreenCaptureStore.onCaptured
                        ScreenCaptureStore.onCaptured = null
                        ScreenCaptureStore.onFailed = null
                        callback?.invoke(jpeg)
                        stopSelf()
                    }
                } catch (_: Exception) {
                    failCapture()
                } finally {
                    image.close()
                }
            }, mainHandler)

            val captureSurface = imageReader?.surface ?: throw IllegalStateException("Screen capture surface unavailable")
            virtualDisplay = activeProjection.createVirtualDisplay(
                "JaneScreenContext",
                width,
                height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                captureSurface,
                null,
                mainHandler
            )
        } catch (_: Exception) {
            failCapture()
        }
        return START_NOT_STICKY
    }

    private fun imageToJpeg(image: Image): ByteArray {
        val plane = image.planes.first()
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val paddedWidth = image.width + rowPadding / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        padded.copyPixelsFromBuffer(buffer)
        val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        val maxDimension = max(cropped.width, cropped.height)
        val scale = min(1f, 1920f / maxDimension)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                cropped,
                (cropped.width * scale).toInt().coerceAtLeast(1),
                (cropped.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else cropped
        val output = ByteArrayOutputStream()
        if (!scaled.compress(Bitmap.CompressFormat.JPEG, 75, output)) {
            throw IllegalStateException("JPEG encoding failed")
        }
        if (scaled !== cropped) scaled.recycle()
        if (cropped !== padded) cropped.recycle()
        padded.recycle()
        return output.toByteArray()
    }

    private fun failCapture() {
        mainHandler.post {
            val callback = ScreenCaptureStore.onFailed
            ScreenCaptureStore.onCaptured = null
            ScreenCaptureStore.onFailed = null
            callback?.invoke()
            stopSelf()
        }
    }

    private fun releaseCaptureResources() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { imageReader?.setOnImageAvailableListener(null, null) } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen sharing",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        releaseCaptureResources()
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_RESULT_CODE = "com.chronodisrupt.zoi.SCREEN_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "com.chronodisrupt.zoi.SCREEN_RESULT_DATA"
        private const val CHANNEL_ID = "jane_screen_capture"
        private const val NOTIFICATION_ID = 7301
    }
}
