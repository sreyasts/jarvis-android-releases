package dev.jarvis.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.*
import android.widget.ImageButton
import android.widget.LinearLayout
import dev.jarvis.core.ActivationSource
import kotlin.math.abs
import kotlinx.coroutines.*

class FloatingButtonService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var bubble: LinearLayout? = null
    private lateinit var handle: BubbleView
    private lateinit var controls: LinearLayout
    private lateinit var params: WindowManager.LayoutParams
    private var docked = false
    private var onRight = true
    private var closing = false
    private val placement by lazy { getSharedPreferences("floating_placement", MODE_PRIVATE) }
    private lateinit var windows: WindowManager

    override fun onCreate() {
        super.onCreate()
        windows = getSystemService(WindowManager::class.java)
        val channel = NotificationChannel(CHANNEL, "Jarvis floating button", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, FloatingButtonService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(dev.jarvis.app.R.drawable.ic_jarvis_notification)
            .setContentTitle("Jarvis is within reach").setContentText("Tap the floating button to talk. Dock it to keep your screen clear.")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Hide overlay", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(42, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(42, notification)
        scope.launch {
            JarvisPreferences(this@FloatingButtonService).settings.collect { settings ->
                if (!settings.enabled || !settings.floatingButton || !Settings.canDrawOverlays(this@FloatingButtonService)) stopSelf()
                else if (bubble == null) showBubble()
            }
        }
        scope.launch {
            JarvisOverlayActivity.visible.collect { visible ->
                bubble?.visibility = if (visible || closing) View.GONE else View.VISIBLE
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) hide()
        return START_STICKY
    }

    private fun hide() {
        if (closing) return
        closing = true
        bubble?.visibility = View.GONE
        scope.launch {
            try { JarvisPreferences(this@FloatingButtonService).setFloating(false) }
            finally { stopSelf() }
        }
    }

    private fun showBubble() {
        params = WindowManager.LayoutParams(dp(56) + 2 * dp(48), dp(56), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            if (Build.VERSION.SDK_INT >= 30) {
                fitInsetsTypes = safeInsetTypes()
                isFitInsetsIgnoringVisibility = true
            }
        }
        onRight = placement.getBoolean("right", true)
        params.x = screenSize().x - params.width - dp(12)
        params.y = dp(placement.getInt("height_dp", 200))
        handle = BubbleView(this)
        controls = LinearLayout(this).apply {
            addView(control(R.drawable.ic_dock_overlay, "Dock Jarvis at screen edge") { dock() })
            addView(control(android.R.drawable.ic_menu_close_clear_cancel, "Close floating Jarvis") { hide() })
        }
        val view = LinearLayout(this).apply {
            visibility = if (JarvisOverlayActivity.visible.value || closing) View.GONE else View.VISIBLE
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            background = GradientDrawable().apply {
                setColor(Color.argb(230, 5, 17, 26))
                setStroke(dp(1), Color.rgb(41, 67, 82))
                cornerRadius = dp(28).toFloat()
            }
            addView(handle)
            addView(controls)
        }
        var startX = 0
        var startY = 0
        var downX = 0f
        var downY = 0f
        var dragged = false
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX = params.x; startY = params.y; downX = event.rawX; downY = event.rawY; dragged = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) + abs(dy) > slop) dragged = true
                    if (dragged) {
                        if (docked && (if (onRight) -dx else dx) > dp(24)) {
                            setDocked(false)
                            startX = if (onRight) screenSize().x - params.width else 0
                        }
                        params.x = if (docked && onRight) screenSize().x - params.width
                            else if (docked) 0 else startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        place()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragged) savePlacement() else handle.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> { if (dragged) savePlacement(); true }
                else -> true
            }
        }
        handle.setOnClickListener {
            startActivity(JarvisOverlayActivity.intent(this, ActivationSource.OVERLAY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
        setDocked(placement.getBoolean("docked", false))
        if (docked) params.x = if (onRight) screenSize().x - params.width else 0
        try {
            windows.addView(view, params)
            bubble = view
            place()
        } catch (_: WindowManager.BadTokenException) { stopSelf() }
        catch (_: WindowManager.InvalidDisplayException) { stopSelf() }
        catch (_: SecurityException) { stopSelf() }
    }

    private fun control(icon: Int, label: String, onClick: () -> Unit) = ImageButton(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(56))
        setImageResource(icon)
        setColorFilter(Color.rgb(190, 238, 255))
        setPadding(dp(12), dp(16), dp(12), dp(16))
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        contentDescription = label
        setBackgroundResource(android.R.drawable.list_selector_background)
        setOnClickListener { onClick() }
    }

    private fun dock() {
        val width = screenSize().x
        onRight = params.x + params.width / 2 >= width / 2
        setDocked(true)
        params.x = if (onRight) width - params.width else 0
        place()
        savePlacement()
    }

    private fun savePlacement() {
        placement.edit().putBoolean("docked", docked).putBoolean("right", onRight)
            .putInt("height_dp", (params.y / resources.displayMetrics.density).toInt()).apply()
    }

    private fun setDocked(value: Boolean) {
        docked = value
        params.width = if (value) dp(28) else dp(56) + 2 * dp(48)
        handle.layoutParams = LinearLayout.LayoutParams(dp(if (value) 28 else 56), dp(56))
        handle.contentDescription = if (value) "Talk to Jarvis. Swipe inward to expand."
            else "Talk to Jarvis. Drag to move."
        controls.visibility = if (value) View.GONE else View.VISIBLE
        if (Build.VERSION.SDK_INT >= 29) {
            handle.systemGestureExclusionRects = if (value) listOf(Rect(0, 0, params.width, params.height)) else emptyList()
        }
        handle.invalidate()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (bubble == null) return
        if (docked) params.x = if (onRight) screenSize().x - params.width else 0
        place()
    }

    private fun place() {
        val view = bubble ?: return
        val size = screenSize()
        params.x = params.x.coerceIn(0, (size.x - params.width).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (size.y - params.height).coerceAtLeast(0))
        windows.updateViewLayout(view, params)
    }

    @android.annotation.TargetApi(30)
    private fun safeInsetTypes() = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()

    @Suppress("DEPRECATION")
    private fun screenSize(): Point {
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = windows.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(safeInsetTypes())
            return Point(metrics.bounds.width() - insets.left - insets.right,
                metrics.bounds.height() - insets.top - insets.bottom)
        }
        return Point().also { windows.defaultDisplay.getSize(it) }
    }

    private fun dp(value: Int) = kotlin.math.round(value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        scope.cancel()
        bubble?.let { try { windows.removeView(it) } catch (_: IllegalArgumentException) { } }
        bubble = null
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    private inner class BubbleView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun performClick(): Boolean = super.performClick()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (docked) {
                val x = width / 2f
                val y = height / 2f
                val direction = if (onRight) -1 else 1
                paint.color = Color.rgb(53, 207, 255)
                paint.strokeWidth = dp(2).toFloat()
                paint.strokeCap = Paint.Cap.ROUND
                canvas.drawLine(x - direction * dp(2), y - dp(5), x + direction * dp(3), y, paint)
                canvas.drawLine(x + direction * dp(3), y, x - direction * dp(2), y + dp(5), paint)
                return
            }
            val center = width / 2f; val radius = width * .46f
            paint.style = Paint.Style.FILL; paint.color = Color.rgb(5, 17, 26); canvas.drawCircle(center, center, radius, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = width * .035f; paint.color = Color.rgb(53, 207, 255)
            canvas.drawCircle(center, center, radius - 2, paint)
            canvas.drawArc(RectF(width * .24f, width * .24f, width * .76f, width * .76f), 15f, 290f, false, paint)
            paint.style = Paint.Style.FILL; canvas.drawCircle(center, center, width * .12f, paint)
        }
    }
    companion object {
        private const val CHANNEL = "jarvis_floating"
        private const val STOP = "dev.jarvis.STOP"
    }
}
