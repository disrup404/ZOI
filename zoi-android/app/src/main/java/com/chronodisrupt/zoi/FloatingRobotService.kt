package com.chronodisrupt.zoi

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Color as AndroidColor
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * User-controlled animated Jane overlay. The robot wanders slowly, remains draggable,
 * and never reads the screen or records audio from this service.
 */
class FloatingRobotService : Service() {
    private lateinit var windowManager: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var robotView: FloatingRobotView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var movement: ValueAnimator? = null
    private var userDragging = false
    private var serviceStopping = false
    private val wanderRunnable = Runnable { wanderToNextPosition() }

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
            this, 2,
            Intent(this, FloatingRobotService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Jane overlay is active")
            .setContentText("Your animated companion is moving on screen. Tap it to open Jane.")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Turn off", stopIntent).build())
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Jane overlay", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Keeps the user-controlled animated Jane companion visible."
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
        val width = (84 * density).toInt()
        val height = (116 * density).toInt()
        val params = WindowManager.LayoutParams(
            width, height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (12 * density).toInt()
            y = (180 * density).toInt()
        }

        val view = FloatingRobotView(
            this,
            onTap = {
                startActivity(Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                })
            },
            onDragStart = { userDragging = true; cancelWander() },
            onDragEnd = { userDragging = false; scheduleWander() }
        )
        robotView = view
        layoutParams = params
        try {
            windowManager.addView(view, params)
            scheduleWander()
        } catch (_: Exception) {
            robotView = null
            stopSelf()
        }
    }

    private fun cancelWander() {
        handler.removeCallbacks(wanderRunnable)
        movement?.cancel()
        movement = null
    }

    private fun scheduleWander() {
        handler.removeCallbacks(wanderRunnable)
        handler.postDelayed(wanderRunnable, 3500L + Random.nextLong(0L, 3500L))
    }

    private fun wanderToNextPosition() {
        val params = layoutParams ?: return
        if (robotView == null) return
        val metrics = resources.displayMetrics
        val maxX = (metrics.widthPixels - params.width).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - params.height - (72 * metrics.density).toInt()).coerceAtLeast(0)
        val minY = (56 * metrics.density).toInt()
        val targetX = Random.nextInt(0, maxX + 1)
        val targetY = if (maxY > minY) Random.nextInt(minY, maxY + 1) else minY
        val startX = params.x
        val startY = params.y

        movement?.cancel()
        movement = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 4200L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                val fraction = animator.animatedValue as Float
                params.x = (startX + (targetX - startX) * fraction).toInt()
                params.y = (startY + (targetY - startY) * fraction).toInt()
                try { robotView?.let { windowManager.updateViewLayout(it, params) } } catch (_: Exception) {}
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (movement === animation) movement = null
                    if (!userDragging && !serviceStopping) scheduleWander()
                }
            })
            start()
        }
    }

    override fun onDestroy() {
        serviceStopping = true
        cancelWander()
        handler.removeCallbacks(wanderRunnable)
        robotView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        robotView = null
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
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
    context: Context,
    private val onTap: () -> Unit,
    private val onDragStart: () -> Unit,
    private val onDragEnd: () -> Unit
) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var moved = false
    private var dragging = false
    private val touchSlop = 8 * density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val sx = w / 84f
        val sy = h / 116f
        fun x(v: Float) = v * sx
        fun y(v: Float) = v * sy
        val now = SystemClock.uptimeMillis()
        val walk = sin(now / 190.0).toFloat()
        val mood = ((now / 4200L) % 5L).toInt()

        paint.style = Paint.Style.FILL
        paint.color = AndroidColor.argb(42, 42, 190, 235)
        canvas.drawOval(RectF(x(7f), y(104f), x(77f), y(114f)), paint)

        // Alternating legs and feet.
        paint.color = AndroidColor.rgb(64, 85, 101)
        paint.strokeWidth = x(5f)
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(x(34f), y(82f), x(31f + walk * 4f), y(101f), paint)
        canvas.drawLine(x(50f), y(82f), x(53f - walk * 4f), y(101f), paint)
        paint.color = AndroidColor.rgb(25, 43, 57)
        canvas.drawRoundRect(RectF(x(22f + walk * 4f), y(99f), x(39f + walk * 4f), y(105f)), x(3f), y(3f), paint)
        canvas.drawRoundRect(RectF(x(46f - walk * 4f), y(99f), x(63f - walk * 4f), y(105f)), x(3f), y(3f), paint)

        // Swinging arms.
        paint.color = AndroidColor.rgb(76, 101, 119)
        paint.strokeWidth = x(5f)
        canvas.drawLine(x(24f), y(63f), x(14f), y(78f + walk * 3f), paint)
        canvas.drawLine(x(60f), y(63f), x(70f), y(78f - walk * 3f), paint)
        paint.color = AndroidColor.rgb(255, 174, 103)
        canvas.drawCircle(x(14f), y(79f + walk * 3f), x(3f), paint)
        canvas.drawCircle(x(70f), y(79f - walk * 3f), x(3f), paint)

        // Torso and chest light.
        paint.color = AndroidColor.rgb(43, 62, 78)
        canvas.drawRoundRect(RectF(x(23f), y(57f), x(61f), y(89f)), x(11f), y(11f), paint)
        paint.color = AndroidColor.rgb(255, 157, 83)
        canvas.drawRoundRect(RectF(x(36f), y(62f), x(48f), y(65f)), x(2f), y(2f), paint)
        paint.color = AndroidColor.rgb(91, 221, 243)
        canvas.drawCircle(x(42f), y(76f), x(4f), paint)

        // Neck, antenna, helmet and face panel.
        paint.color = AndroidColor.rgb(55, 76, 91)
        canvas.drawRoundRect(RectF(x(36f), y(49f), x(48f), y(62f)), x(4f), y(4f), paint)
        paint.color = AndroidColor.rgb(255, 171, 96)
        paint.strokeWidth = x(2f)
        canvas.drawLine(x(42f), y(8f), x(42f), y(17f), paint)
        canvas.drawCircle(x(42f), y(7f), x(3f), paint)
        paint.color = AndroidColor.rgb(226, 112, 69)
        canvas.drawRoundRect(RectF(x(12f), y(17f), x(72f), y(58f)), x(16f), x(16f), paint)
        paint.color = AndroidColor.rgb(18, 31, 44)
        canvas.drawRoundRect(RectF(x(18f), y(27f), x(66f), y(51f)), x(9f), x(9f), paint)

        // Five rotating expressions: neutral, happy, curious, sleepy and focused.
        paint.color = AndroidColor.rgb(105, 230, 255)
        when (mood) {
            1 -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = x(2.2f)
                canvas.drawArc(RectF(x(25f), y(31f), x(38f), y(43f)), 15f, 145f, false, paint)
                canvas.drawArc(RectF(x(46f), y(31f), x(59f), y(43f)), 15f, 145f, false, paint)
                paint.style = Paint.Style.FILL
            }
            2 -> {
                canvas.drawCircle(x(32f), y(38f), x(4f), paint)
                canvas.drawCircle(x(53f), y(38f), x(5f), paint)
            }
            3 -> {
                paint.strokeWidth = x(2.5f)
                paint.strokeCap = Paint.Cap.ROUND
                canvas.drawLine(x(27f), y(38f), x(38f), y(38f), paint)
                canvas.drawLine(x(47f), y(38f), x(58f), y(38f), paint)
            }
            4 -> {
                canvas.drawRoundRect(RectF(x(27f), y(35f), x(38f), y(40f)), x(2f), x(2f), paint)
                canvas.drawRoundRect(RectF(x(47f), y(35f), x(58f), y(40f)), x(2f), x(2f), paint)
            }
            else -> {
                canvas.drawRoundRect(RectF(x(27f), y(33f), x(38f), y(42f)), x(4f), x(4f), paint)
                canvas.drawRoundRect(RectF(x(47f), y(33f), x(58f), y(42f)), x(4f), x(4f), paint)
            }
        }
        paint.color = AndroidColor.rgb(255, 180, 105)
        paint.strokeWidth = x(2f)
        paint.style = Paint.Style.STROKE
        when (mood) {
            1 -> canvas.drawArc(RectF(x(35f), y(42f), x(49f), y(50f)), 15f, 150f, false, paint)
            2 -> canvas.drawCircle(x(42f), y(46f), x(2f), paint)
            3 -> canvas.drawLine(x(38f), y(46f), x(46f), y(46f), paint)
            4 -> canvas.drawLine(x(38f), y(45f), x(46f), y(45f), paint)
            else -> canvas.drawArc(RectF(x(36f), y(43f), x(48f), y(49f)), 15f, 150f, false, paint)
        }
        paint.style = Paint.Style.FILL
        postInvalidateDelayed(90L)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val lp = layoutParams as? WindowManager.LayoutParams
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                startX = lp?.x ?: 0
                startY = lp?.y ?: 0
                moved = false
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    dragging = true
                    onDragStart()
                }
                if (dragging) {
                    moved = true
                    val current = lp ?: return true
                    current.x = startX + dx.toInt()
                    current.y = startY + dy.toInt()
                    try { manager.updateViewLayout(this, current) } catch (_: Exception) {}
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!moved) onTap() else onDragEnd()
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
