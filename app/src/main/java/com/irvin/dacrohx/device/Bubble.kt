package com.irvin.dacrohx.device

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.IBinder
import android.provider.Settings
import android.service.quicksettings.TileService
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.irvin.dacrohx.MainActivity
import com.irvin.dacrohx.R
import com.irvin.dacrohx.ai.Assistant
import com.irvin.dacrohx.ai.Mode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Burbuja flotante de Irvin: un núcleo rojo que se queda encima de cualquier app
 * (incluidos juegos). Tócala para hablarle; arrástrala para moverla.
 */
class BubbleService : Service() {

    companion object {
        private const val CH = "burbuja_v1"
        private const val ID = 4343
        const val ACTION_STOP = "com.irvin.dacrohx.BUBBLE_STOP"
        @Volatile var running = false
            private set

        fun canDraw(ctx: Context) = Settings.canDrawOverlays(ctx)

        fun askPermission(ctx: Context) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }

        fun start(ctx: Context) { ctx.startForegroundService(Intent(ctx, BubbleService::class.java)) }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, BubbleService::class.java)) }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var wm: WindowManager
    private var orb: OrbView? = null
    private var label: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Assistant.get(this).store.updateSettings { it.copy(bubble = false) }
            stopSelf(); return START_NOT_STICKY
        }
        if (!canDraw(this)) { stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Burbuja de Irvin", NotificationManager.IMPORTANCE_MIN))
        val stop = PendingIntent.getService(this, 21, Intent(this, BubbleService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_mic).setContentTitle("Burbuja de Irvin activa")
            .setContentText("Tócala para hablar").setOngoing(true).setSilent(true)
            .addAction(0, "Quitar burbuja", stop).build()
        try {
            ServiceCompat.startForeground(this, ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) { stopSelf(); return START_NOT_STICKY }
        if (orb == null) show()
        running = true
        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun show() {
        wm = getSystemService(WindowManager::class.java)
        val dp = resources.displayMetrics.density
        val size = (64 * dp).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val o = OrbView(this)
        val t = TextView(this).apply {
            setTextColor(android.graphics.Color.WHITE); textSize = 14f; maxWidth = (240 * dp).toInt()
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            background = GradientDrawable().apply { setColor(0xEE111118.toInt()); cornerRadius = 14 * dp; setStroke((1 * dp).toInt(), 0xFFFF2340.toInt()) }
            visibility = View.GONE
        }
        root.addView(FrameLayout(this).apply { addView(o, FrameLayout.LayoutParams(size, size)) })
        root.addView(t)
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = (220 * dp).toInt() }

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        o.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > 12 || abs(dy) > 12) moved = true
                    p.x = startX + dx.toInt(); p.y = startY + dy.toInt()
                    try { wm.updateViewLayout(root, p) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> { if (!moved) onTap(); true }
                else -> false
            }
        }
        wm.addView(root, p)
        orb = o; label = t; params = p

        // Refleja el estado de Irvin (escuchando, pensando, hablando) en la burbuja.
        val a = Assistant.get(this)
        scope.launch { a.mode.collect { m -> o.mode = m } }
        scope.launch {
            a.store.chat.collect { list ->
                val last = list.lastOrNull()
                if (last != null && last.role == "model" && !last.pending && last.text.isNotBlank() && a.mode.value != Mode.IDLE) {
                    t.text = last.text.take(220); t.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun onTap() {
        val a = Assistant.get(this)
        if (a.mode.value != Mode.IDLE) { a.stopAll(); return }
        label?.visibility = View.GONE
        scope.launch {
            a.quickVoiceTurn()
            delay(6000)
            label?.visibility = View.GONE
        }
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        try { (orb?.parent?.parent as? View)?.let { wm.removeView(it) } } catch (_: Exception) {}
        super.onDestroy()
    }

    /** Núcleo rojo dibujado a mano que gira y late según el estado. */
    class OrbView(ctx: Context) : View(ctx) {
        var mode: Mode = Mode.IDLE
            set(v) { field = v; invalidate() }
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private var angle = 0f

        override fun onDraw(c: Canvas) {
            val r = width / 2f
            val cx = r; val cy = r
            p.style = Paint.Style.FILL
            p.shader = RadialGradient(cx, cy, r, intArrayOf(0x66FF2340, 0x00FF2340), null, Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, r, p)
            p.shader = null
            p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.09f; p.strokeCap = Paint.Cap.ROUND
            p.color = 0xFFFF2340.toInt()
            val speed = when (mode) { Mode.THINKING -> 14f; Mode.LISTENING, Mode.SPEAKING -> 7f; else -> 2.5f }
            angle = (angle + speed) % 360
            val d = r * 0.78f
            c.drawArc(cx - d, cy - d, cx + d, cy + d, angle, 110f, false, p)
            c.drawArc(cx - d, cy - d, cx + d, cy + d, angle + 180, 110f, false, p)
            p.style = Paint.Style.FILL
            val pulse = if (mode == Mode.LISTENING || mode == Mode.SPEAKING) 1f + 0.18f * kotlin.math.sin(System.currentTimeMillis() / 90.0).toFloat() else 1f
            val cr = r * 0.42f * pulse
            p.shader = RadialGradient(cx - cr * 0.3f, cy - cr * 0.3f, cr * 1.3f, intArrayOf(0xFFFFFFFF.toInt(), 0xFFFF2340.toInt(), 0xFF5A0010.toInt()), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, cr, p)
            p.shader = null
            postInvalidateOnAnimation()
        }
    }
}

/** Widget de pantalla de inicio: botón para hablar con Irvin. */
class IrvinWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val pi = PendingIntent.getActivity(
            ctx, 31, Intent(ctx, MainActivity::class.java).setAction("com.irvin.dacrohx.VOZ"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        for (id in ids) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_irvin)
            v.setOnClickPendingIntent(R.id.widget_root, pi)
            mgr.updateAppWidget(id, v)
        }
    }
}

/** Botón en los ajustes rápidos (deslizar la barra de arriba): "Hablar con Irvin". */
class IrvinTile : TileService() {
    override fun onClick() {
        val pi = PendingIntent.getActivity(
            this, 32, Intent(this, MainActivity::class.java).setAction("com.irvin.dacrohx.VOZ").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        startActivityAndCollapse(pi)
    }
}
