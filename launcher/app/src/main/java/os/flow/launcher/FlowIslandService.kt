package os.flow.launcher

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Flow Island (Dynamic Island) : pilule noire animée autour du poinçon caméra,
 * affichée par-dessus toutes les applis (musique en cours, notifications, branchement chargeur).
 */
class FlowIslandService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var container: FrameLayout
    private lateinit var pill: LinearLayout
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var nowPlaying: NowPlaying? = null
    private var activeAlert: IslandAlert? = null
    private var chargingText: String? = null
    private var mediaExpanded = false
    private var wasPlugged: Boolean? = null

    private val clearAlertRunnable = Runnable {
        activeAlert?.let { FlowNotificationListener.clearAlert(it.id) }
        activeAlert = null
        render()
    }
    private val clearChargeRunnable = Runnable {
        chargingText = null
        render()
    }
    private val collapseMediaRunnable = Runnable {
        mediaExpanded = false
        render()
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val pct = if (level >= 0) (level * 100 / scale) else -1
            if (wasPlugged == false && plugged && pct >= 0) {
                showChargeBanner("En charge · $pct %")
            }
            wasPlugged = plugged
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        wm = getSystemService(WindowManager::class.java)
        buildOverlay()
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        scope.launch {
            FlowNotificationListener.nowPlaying.collect { media ->
                nowPlaying = media
                if (media == null) mediaExpanded = false
                render()
            }
        }
        scope.launch {
            FlowNotificationListener.latestAlert.collect { alert ->
                if (alert != null && alert.id != activeAlert?.id) {
                    activeAlert = alert
                    mediaExpanded = false
                    mainHandler.removeCallbacks(clearAlertRunnable)
                    mainHandler.postDelayed(clearAlertRunnable, 4_500L)
                    render()
                }
            }
        }
        render()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(batteryReceiver) }
        if (::container.isInitialized) {
            runCatching { wm.removeView(container) }
        }
        super.onDestroy()
    }

    private fun showChargeBanner(text: String) {
        chargingText = text
        mainHandler.removeCallbacks(clearChargeRunnable)
        mainHandler.postDelayed(clearChargeRunnable, 3_500L)
        render()
    }

    private fun buildOverlay() {
        container = FrameLayout(this)
        pill = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = pillBackground(dp(20).toFloat())
            elevation = dp(8).toFloat()
        }
        container.addView(
            pill,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ),
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(7)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        wm.addView(container, params)
    }

    private fun render() {
        if (!::pill.isInitialized) return
        val transition = AutoTransition().apply {
            duration = 260
            interpolator = OvershootInterpolator(0.85f)
        }
        TransitionManager.beginDelayedTransition(container, transition)
        pill.removeAllViews()

        val alert = activeAlert
        val charge = chargingText
        val media = nowPlaying

        when {
            alert != null -> renderAlert(alert)
            charge != null -> renderCharging(charge)
            media != null && mediaExpanded -> renderMediaExpanded(media)
            media != null -> renderMediaCompact(media)
            else -> renderIdle()
        }
    }

    private fun renderIdle() {
        pill.background = pillBackground(dp(16).toFloat())
        pill.setPadding(0, 0, 0, 0)
        pill.setOnClickListener {
            val bm = getSystemService(BatteryManager::class.java)
            val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (pct in 0..100) showChargeBanner("Batterie · $pct %")
        }
        pill.addView(View(this), LinearLayout.LayoutParams(dp(106), dp(26)))
    }

    private fun renderCharging(text: String) {
        pill.background = pillBackground(dp(18).toFloat())
        pill.setPadding(dp(14), dp(7), dp(14), dp(7))
        pill.setOnClickListener {
            chargingText = null
            render()
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val bolt = TextView(this).apply {
            this.text = "⚡"
            setTextColor(Color.parseColor("#34C759"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        val label = TextView(this).apply {
            this.text = "  $text"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        row.addView(bolt)
        row.addView(label)
        pill.addView(row, LinearLayout.LayoutParams(dp(196), LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun renderAlert(alert: IslandAlert) {
        pill.background = pillBackground(dp(26).toFloat())
        pill.setPadding(dp(14), dp(10), dp(16), dp(10))
        pill.setOnClickListener {
            runCatching { alert.contentIntent?.send() }
            mainHandler.removeCallbacks(clearAlertRunnable)
            clearAlertRunnable.run()
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val iconView = ImageView(this).apply {
            runCatching { setImageDrawable(packageManager.getApplicationIcon(alert.packageName)) }
        }
        row.addView(iconView, LinearLayout.LayoutParams(dp(34), dp(34)))

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        textCol.addView(
            TextView(this).apply {
                text = alert.title
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
        )
        if (alert.text.isNotEmpty()) {
            textCol.addView(
                TextView(this).apply {
                    text = alert.text
                    setTextColor(Color.parseColor("#D0D0D6"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
            )
        }
        row.addView(textCol, LinearLayout.LayoutParams(dp(248), LinearLayout.LayoutParams.WRAP_CONTENT))
        pill.addView(row)
    }

    private fun renderMediaCompact(media: NowPlaying) {
        pill.background = pillBackground(dp(18).toFloat())
        pill.setPadding(dp(10), dp(5), dp(12), dp(5))
        pill.setOnClickListener {
            mediaExpanded = true
            mainHandler.removeCallbacks(collapseMediaRunnable)
            mainHandler.postDelayed(collapseMediaRunnable, 6_000L)
            render()
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val iconView = ImageView(this).apply {
            runCatching { setImageDrawable(packageManager.getApplicationIcon(media.packageName)) }
        }
        row.addView(iconView, LinearLayout.LayoutParams(dp(20), dp(20)))
        val title = TextView(this).apply {
            text = media.title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(8), 0, dp(8), 0)
        }
        row.addView(title, LinearLayout.LayoutParams(dp(136), LinearLayout.LayoutParams.WRAP_CONTENT))
        val stateBadge = TextView(this).apply {
            text = if (media.isPlaying) "♫" else "❚❚"
            setTextColor(if (media.isPlaying) Color.parseColor("#1DB954") else Color.parseColor("#9A9AA2"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        row.addView(stateBadge)
        pill.addView(row)
    }

    private fun renderMediaExpanded(media: NowPlaying) {
        pill.background = pillBackground(dp(28).toFloat())
        pill.setPadding(dp(16), dp(12), dp(16), dp(12))
        pill.setOnClickListener {
            mediaExpanded = false
            mainHandler.removeCallbacks(collapseMediaRunnable)
            render()
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener {
                runCatching {
                    packageManager.getLaunchIntentForPackage(media.packageName)
                        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ?.let { startActivity(it) }
                }
                mediaExpanded = false
                render()
            }
        }
        val iconView = ImageView(this).apply {
            runCatching { setImageDrawable(packageManager.getApplicationIcon(media.packageName)) }
        }
        header.addView(iconView, LinearLayout.LayoutParams(dp(36), dp(36)))

        val metaCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        metaCol.addView(
            TextView(this).apply {
                text = media.title
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
        )
        if (media.artist.isNotEmpty()) {
            metaCol.addView(
                TextView(this).apply {
                    text = media.artist
                    setTextColor(Color.parseColor("#B5B5BE"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
            )
        }
        header.addView(metaCol, LinearLayout.LayoutParams(dp(240), LinearLayout.LayoutParams.WRAP_CONTENT))
        pill.addView(header)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }
        fun ctrlBtn(symbol: String, action: () -> Unit): TextView = TextView(this).apply {
            text = symbol
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
            setPadding(dp(22), dp(4), dp(22), dp(4))
            setOnClickListener {
                action()
                mainHandler.removeCallbacks(collapseMediaRunnable)
                mainHandler.postDelayed(collapseMediaRunnable, 6_000L)
            }
        }
        controls.addView(ctrlBtn("⏮") { FlowNotificationListener.skipPrevious() })
        controls.addView(ctrlBtn(if (media.isPlaying) "⏸" else "▶") { FlowNotificationListener.togglePlayPause() })
        controls.addView(ctrlBtn("⏭") { FlowNotificationListener.skipNext() })
        pill.addView(controls)
    }

    private fun pillBackground(radiusPx: Float): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusPx
        setColor(Color.BLACK)
        setStroke(dp(1), Color.parseColor("#2A2A2F"))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        fun sync(context: Context, enabled: Boolean) {
            val intent = Intent(context, FlowIslandService::class.java)
            if (enabled && Settings.canDrawOverlays(context)) {
                runCatching { context.startService(intent) }
            } else {
                runCatching { context.stopService(intent) }
            }
        }
    }
}
