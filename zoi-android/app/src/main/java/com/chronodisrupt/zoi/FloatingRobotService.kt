package com.chronodisrupt.zoi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Color as AndroidColor
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs

/**
 * User-controlled floating companion. The bubble is intentionally passive:
 * it does not inspect the screen, record audio, or perform actions by itself.
 */
class FloatingRobotService : Service() {
    private lateinit var windowManager: WindowManager
    private var robotView: FloatingRobotView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startAsForegroundService()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showRobot()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (robotView == null) showRobot()
        return START_STICKY
    }

    private fun startAsForegroundService() {
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, FloatingRobotService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Zoi is available")
            .setContentText("Your floating companion is active. Tap the robot to open Zoi.")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Turn off", stopIntent).build())
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Floating companion",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Keeps the Zoi floating robot available above other apps."
                }
            )
        }
    }

    private fun showRobot() {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        val density = resources.displayMetrics.density
        val size = (62 * density).toInt()
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (12 * density).toInt()
            y = (190 * density).toInt()
        }
        val view = FloatingRobotView(this) {
            val open = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(open)
        }
        robotView = view
        layoutParams = params
        try {
            windowManager.addView(view, params)
        } catch (_: Exception) {
            robotView = null
            stopSelf()
        }
    }

    override fun onDestroy() {
        robotView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
                // The system may already have removed the overlay.
            }
        }
        robotView = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.chronodisrupt.zoi.action.STOP_FLOATING_ROBOT"
        private const val CHANNEL_ID = "zoi_floating_companion"
        private const val NOTIFICATION_ID = 2401
    }
}

private class FloatingRobotView(
    context: android.content.Context,
    private val onTap: () -> Unit
) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var moved = false
    private val touchSlop = 8 * density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f

        // Soft outer glow.
        paint.color = AndroidColor.argb(55, 255, 132, 74)
        canvas.drawCircle(cx, cy, w * 0.48f, paint)

        // Compact armored head.
        paint.color = AndroidColor.rgb(49, 30, 31)
        canvas.drawRoundRect(
            RectF(w * .16f, h * .15f, w * .84f, h * .84f),
            w * .22f, w * .22f, paint
        )
        paint.color = AndroidColor.rgb(235, 105, 57)
        canvas.drawRoundRect(
            RectF(w * .22f, h * .20f, w * .78f, h * .78f),
            w * .18f, w * .18f, paint
        )

        // Face panel.
        paint.color = AndroidColor.rgb(18, 29, 42)
        canvas.drawRoundRect(
            RectF(w * .27f, h * .34f, w * .73f, h * .64f),
            w * .10f, w * .10f, paint
        )
        // Cyan eyes.
        paint.color = AndroidColor.rgb(100, 235, 255)
        canvas.drawRoundRect(
            RectF(w * .34f, h * .42f, w * .44f, h * .53f),
            w * .035f, w * .035f, paint
        )
        canvas.drawRoundRect(
            RectF(w * .56f, h * .42f, w * .66f, h * .53f),
            w * .035f, w * .035f, paint
        )
        // Antenna.
        paint.color = AndroidColor.rgb(255, 178, 96)
        paint.strokeWidth = 2.5f * density
        canvas.drawLine(cx, h * .05f, cx, h * .19f, paint)
        canvas.drawCircle(cx, h * .045f, 3.5f * density, paint)
        // Side armor.
        paint.color = AndroidColor.rgb(255, 170, 90)
        canvas.drawRoundRect(
            RectF(w * .08f, h * .40f, w * .20f, h * .64f),
            w * .05f, w * .05f, paint
        )
        canvas.drawRoundRect(
            RectF(w * .80f, h * .40f, w * .92f, h * .64f),
            w * .05f, w * .05f, paint
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val params = (context.getSystemService(WINDOW_SERVICE) as WindowManager)
        val lp = (layoutParams as? WindowManager.LayoutParams)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                moved = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (abs(dx) > touchSlop || abs(dy) > touchSlop) moved = true
                val current = lp ?: return true
                current.x = startX + dx.toInt()
                current.y = startY + dy.toInt()
                try { params.updateViewLayout(this, current) } catch (_: Exception) {}
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (moved) {
                    startX += dx.toInt()
                    startY += dy.toInt()
                } else {
                    onTap()
                }
                performClick()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
