package os.flow.launcher

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
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
import android.view.ViewOutlineProvider
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
 * Flow Island (Dynamic Island) inspiré de l'iPhone 14 Pro :
 * - Charge : « 100% » vert + icône batterie verte
 * - Appel entrant : photo ronde + nom + boutons ronds rouge (raccrocher) / vert (décrocher)
 * - Appel en cours : chrono vert « 0:08 » + onde audio animée (tap pour raccrocher)
 * - Musique : pochette arrondie + titre/artiste + égaliseur animé + barre de progression + ◀◀ ❚❚ ▶▶
 */
class FlowIslandService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var container: FrameLayout
    private lateinit var pill: LinearLayout
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var nowPlaying: NowPlaying? = null
    private var activeCall: IslandCall? = null
    private var activeAlert: IslandAlert? = null
    private var batteryBannerPct: Int? = null
    private var mediaExpanded = false
    private var callExpanded = false
    private var wasPlugged: Boolean? = null

    // Vues mises à jour chaque seconde sans recréer toute la pilule.
    private var elapsedLabel: TextView? = null
    private var remainingLabel: TextView? = null
    private var progressBar: IslandProgressBarView? = null
    private var callTimerLabel: TextView? = null

    private val clearAlertRunnable = Runnable {
        activeAlert?.let { FlowNotificationListener.clearAlert(it.id) }
        activeAlert = null
        render()
    }
    private val clearBatteryRunnable = Runnable {
        batteryBannerPct = null
        render()
    }
    private val collapseMediaRunnable = Runnable {
        mediaExpanded = false
        render()
    }
    private val tickerRunnable = object : Runnable {
        override fun run() {
            updateLiveTickers()
            mainHandler.postDelayed(this, 1_000L)
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val pct = if (level >= 0) (level * 100 / scale) else -1
            if (wasPlugged == false && plugged && pct >= 0) {
                showBatteryBanner(pct)
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
        mainHandler.post(tickerRunnable)

        scope.launch {
            FlowNotificationListener.activeCall.collect { call ->
                activeCall = call
                if (call == null) callExpanded = false
                render()
            }
        }
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
        if (::container.isInitialized) runCatching { wm.removeView(container) }
        super.onDestroy()
    }

    private fun showBatteryBanner(pct: Int) {
        batteryBannerPct = pct
        mainHandler.removeCallbacks(clearBatteryRunnable)
        mainHandler.postDelayed(clearBatteryRunnable, 3_500L)
        render()
    }

    private fun updateLiveTickers() {
        val m = nowPlaying
        if (m != null && mediaExpanded) {
            val pos = m.currentPositionMs()
            elapsedLabel?.text = formatDurationShort(pos)
            remainingLabel?.text = if (m.durationMs > 0L) formatRemainingShort(pos, m.durationMs) else ""
            progressBar?.progress = progressFraction(pos, m.durationMs)
        }
        val c = activeCall
        if (c != null && c.answerIntent == null) {
            val elapsed = (System.currentTimeMillis() - c.startedAtMs).coerceAtLeast(0L)
            callTimerLabel?.text = formatDurationShort(elapsed)
        }
    }

    private fun buildOverlay() {
        container = FrameLayout(this)
        pill = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = pillBackground(dp(20).toFloat())
            elevation = dp(10).toFloat()
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
        elapsedLabel = null
        remainingLabel = null
        progressBar = null
        callTimerLabel = null

        val transition = AutoTransition().apply {
            duration = 260
            interpolator = OvershootInterpolator(0.8f)
        }
        TransitionManager.beginDelayedTransition(container, transition)
        pill.removeAllViews()

        val call = activeCall
        val alert = activeAlert
        val pct = batteryBannerPct
        val media = nowPlaying

        when {
            call != null && (call.answerIntent != null || callExpanded) -> renderCallExpanded(call)
            call != null -> renderCallCompact(call)
            alert != null -> renderAlert(alert)
            pct != null -> renderBattery(pct)
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
            if (pct in 0..100) showBatteryBanner(pct)
        }
        pill.addView(View(this), LinearLayout.LayoutParams(dp(112), dp(26)))
    }

    /** Pilule Batterie verte façon iPhone 14 Pro : « 100% » vert + icône batterie verte. */
    private fun renderBattery(percent: Int) {
        pill.background = pillBackground(dp(18).toFloat())
        pill.setPadding(dp(14), dp(6), dp(14), dp(6))
        pill.setOnClickListener {
            batteryBannerPct = null
            render()
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            TextView(this).apply {
                text = "$percent%"
                setTextColor(Color.parseColor("#34C759"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            },
        )
        row.addView(View(this), LinearLayout.LayoutParams(dp(34), dp(18)))
        row.addView(IslandBatteryIconView(this, percent), LinearLayout.LayoutParams(dp(26), dp(13)))
        pill.addView(row)
    }

    /** Appel en cours compact : icône téléphone verte + chrono « 0:08 » à gauche, onde audio animée à droite. */
    private fun renderCallCompact(call: IslandCall) {
        pill.background = pillBackground(dp(20).toFloat())
        pill.setPadding(dp(14), dp(8), dp(14), dp(8))
        pill.setOnClickListener {
            callExpanded = true
            render()
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            IslandVectorIconView(this, IslandIconKind.PHONE_ANSWER, Color.parseColor("#34C759")),
            LinearLayout.LayoutParams(dp(14), dp(14)),
        )
        val elapsed = (System.currentTimeMillis() - call.startedAtMs).coerceAtLeast(0L)
        val timer = TextView(this).apply {
            text = formatDurationShort(elapsed)
            setTextColor(Color.parseColor("#34C759"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(dp(6), 0, 0, 0)
        }
        callTimerLabel = timer
        row.addView(timer)
        row.addView(View(this), LinearLayout.LayoutParams(dp(38), dp(22)))
        row.addView(
            IslandWaveformView(this, CALL_WAVE_COLORS, active = true),
            LinearLayout.LayoutParams(dp(30), dp(16)),
        )
        pill.addView(row)
    }

    /** Appel entrant / élargi (capture 2) : photo ronde + libellé gris + nom blanc + gros boutons rouge/vert. */
    private fun renderCallExpanded(call: IslandCall) {
        pill.background = pillBackground(dp(36).toFloat())
        // 34dp en haut : dégage la barre d'état pour que les boutons Décrocher / Raccrocher soient 100 % réactifs.
        pill.setPadding(dp(16), dp(34), dp(16), dp(16))
        pill.setOnClickListener {
            runCatching { call.contentIntent?.send() }
            if (call.answerIntent == null) {
                callExpanded = false
                render()
            }
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        row.addView(buildAvatarView(call.avatar, call.packageName, dp(48), circle = true))

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(10), 0)
        }
        textCol.addView(
            TextView(this).apply {
                text = call.subtitle
                setTextColor(Color.parseColor("#8E8E93"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
        )
        textCol.addView(
            TextView(this).apply {
                text = call.callerName
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.MARQUEE
                marqueeRepeatLimit = -1
                isSelected = true
            },
        )
        row.addView(textCol, LinearLayout.LayoutParams(dp(156), LinearLayout.LayoutParams.WRAP_CONTENT))

        if (call.declineIntent != null) {
            val lp = LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginEnd = dp(10) }
            row.addView(
                roundCallButton(IslandIconKind.PHONE_DECLINE, "#FF3B30") {
                    runCatching { call.declineIntent.send() }
                    FlowNotificationListener.clearCall(call.key)
                },
                lp,
            )
        }
        if (call.answerIntent != null) {
            row.addView(
                roundCallButton(IslandIconKind.PHONE_ANSWER, "#34C759") {
                    runCatching { call.answerIntent.send() }
                },
                LinearLayout.LayoutParams(dp(46), dp(46)),
            )
        }
        pill.addView(row)
    }

    private fun roundCallButton(kind: IslandIconKind, bgHex: String, onTap: () -> Unit): FrameLayout =
        FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(bgHex))
            }
            addView(
                IslandVectorIconView(this@FlowIslandService, kind, Color.WHITE),
                FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER),
            )
            setOnClickListener { onTap() }
        }

    /** Notification entrante : icône + titre + aperçu sous la barre d'état. */
    private fun renderAlert(alert: IslandAlert) {
        pill.background = pillBackground(dp(28).toFloat())
        pill.setPadding(dp(16), dp(34), dp(16), dp(14))
        pill.setOnClickListener {
            runCatching { alert.contentIntent?.send() }
            mainHandler.removeCallbacks(clearAlertRunnable)
            clearAlertRunnable.run()
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(buildAvatarView(null, alert.packageName, dp(36), circle = false))

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        textCol.addView(
            TextView(this).apply {
                text = alert.title
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
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
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                },
            )
        }
        row.addView(textCol, LinearLayout.LayoutParams(dp(256), LinearLayout.LayoutParams.WRAP_CONTENT))
        pill.addView(row)
    }

    /** Musique compacte : pochette à gauche + égaliseur doré à droite + bandeau titre/contrôles cliquables sous la barre d'état. */
    private fun renderMediaCompact(media: NowPlaying) {
        pill.background = pillBackground(dp(26).toFloat())
        pill.setPadding(dp(14), dp(8), dp(14), dp(10))
        pill.setOnClickListener {
            mediaExpanded = true
            mainHandler.removeCallbacks(collapseMediaRunnable)
            mainHandler.postDelayed(collapseMediaRunnable, 8_000L)
            render()
        }

        // Ligne haute autour de la caméra : pochette à gauche, égaliseur doré animé à droite.
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topRow.addView(buildAvatarView(media.albumArt, media.packageName, dp(22), circle = false))
        topRow.addView(View(this), LinearLayout.LayoutParams(0, dp(26), 1f))
        topRow.addView(
            IslandWaveformView(this, MUSIC_WAVE_COLORS, active = media.isPlaying),
            LinearLayout.LayoutParams(dp(24), dp(16)),
        )
        pill.addView(topRow, LinearLayout.LayoutParams(dp(246), LinearLayout.LayoutParams.WRAP_CONTENT))

        // Ligne basse (sous la barre d'état) : titre défilant + ◀◀ ❚❚ ▶▶ cliquables.
        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        val title = TextView(this).apply {
            text = if (media.artist.isNotEmpty()) "${media.title} · ${media.artist}" else media.title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isSelected = true
            setOnClickListener {
                mediaExpanded = true
                mainHandler.removeCallbacks(collapseMediaRunnable)
                mainHandler.postDelayed(collapseMediaRunnable, 8_000L)
                render()
            }
        }
        bottomRow.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        fun miniVectorBtn(kind: IslandIconKind, action: () -> Unit): FrameLayout = FrameLayout(this).apply {
            setPadding(dp(7), dp(4), dp(7), dp(4))
            addView(
                IslandVectorIconView(this@FlowIslandService, kind, Color.WHITE),
                FrameLayout.LayoutParams(dp(15), dp(15), Gravity.CENTER),
            )
            setOnClickListener { action() }
        }
        bottomRow.addView(miniVectorBtn(IslandIconKind.PREV) { FlowNotificationListener.skipPrevious(this) })
        bottomRow.addView(
            miniVectorBtn(if (media.isPlaying) IslandIconKind.PAUSE else IslandIconKind.PLAY) {
                FlowNotificationListener.togglePlayPause(this)
            },
        )
        bottomRow.addView(miniVectorBtn(IslandIconKind.NEXT) { FlowNotificationListener.skipNext(this) })
        pill.addView(bottomRow, LinearLayout.LayoutParams(dp(260), LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    /** Carte Musique élargie (bas de la capture 1) : pochette 48dp + titre/artiste + égaliseur + barre de progression + ◀◀ ❚❚ ▶▶. */
    private fun renderMediaExpanded(media: NowPlaying) {
        pill.background = pillBackground(dp(36).toFloat())
        pill.setPadding(dp(20), dp(36), dp(20), dp(18))
        pill.setOnClickListener {
            mediaExpanded = false
            mainHandler.removeCallbacks(collapseMediaRunnable)
            render()
        }

        // 1. Header : pochette arrondie + titre/artiste + égaliseur doré
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
        header.addView(buildAvatarView(media.albumArt, media.packageName, dp(50), circle = false))

        val metaCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(10), 0)
        }
        metaCol.addView(
            TextView(this).apply {
                text = media.title
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.MARQUEE
                marqueeRepeatLimit = -1
                isSelected = true
            },
        )
        if (media.artist.isNotEmpty()) {
            metaCol.addView(
                TextView(this).apply {
                    text = media.artist
                    setTextColor(Color.parseColor("#9A9AA4"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.MARQUEE
                    marqueeRepeatLimit = -1
                    isSelected = true
                },
            )
        }
        header.addView(metaCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(
            IslandWaveformView(this, MUSIC_WAVE_COLORS, active = media.isPlaying),
            LinearLayout.LayoutParams(dp(26), dp(18)),
        )
        pill.addView(header, LinearLayout.LayoutParams(dp(306), LinearLayout.LayoutParams.WRAP_CONTENT))

        // 2. Barre de progression réelle : 2:57  ━━━━━━────  -1:32
        val pos = media.currentPositionMs()
        val progressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(14))
        }
        val elapsedTv = TextView(this).apply {
            text = formatDurationShort(pos)
            setTextColor(Color.parseColor("#8E8E93"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        val bar = IslandProgressBarView(this).apply {
            progress = progressFraction(pos, media.durationMs)
        }
        val remainingTv = TextView(this).apply {
            text = if (media.durationMs > 0L) formatRemainingShort(pos, media.durationMs) else ""
            setTextColor(Color.parseColor("#8E8E93"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        elapsedLabel = elapsedTv
        progressBar = bar
        remainingLabel = remainingTv

        progressRow.addView(elapsedTv)
        val barLp = LinearLayout.LayoutParams(0, dp(5), 1f).apply {
            marginStart = dp(10)
            marginEnd = dp(10)
        }
        progressRow.addView(bar, barLp)
        progressRow.addView(remainingTv)
        pill.addView(progressRow, LinearLayout.LayoutParams(dp(306), LinearLayout.LayoutParams.WRAP_CONTENT))

        // 3. Gros boutons vectoriels blancs : ◀◀   ❚❚ / ▶   ▶▶
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun bigBtn(kind: IslandIconKind, sizeDp: Int, action: () -> Unit): FrameLayout = FrameLayout(this).apply {
            setPadding(dp(24), dp(6), dp(24), dp(6))
            addView(
                IslandVectorIconView(this@FlowIslandService, kind, Color.WHITE),
                FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp), Gravity.CENTER),
            )
            setOnClickListener {
                action()
                mainHandler.removeCallbacks(collapseMediaRunnable)
                mainHandler.postDelayed(collapseMediaRunnable, 8_000L)
            }
        }
        controls.addView(bigBtn(IslandIconKind.PREV, 24) { FlowNotificationListener.skipPrevious(this) })
        controls.addView(
            bigBtn(if (media.isPlaying) IslandIconKind.PAUSE else IslandIconKind.PLAY, 28) {
                FlowNotificationListener.togglePlayPause(this)
            },
        )
        controls.addView(bigBtn(IslandIconKind.NEXT, 24) { FlowNotificationListener.skipNext(this) })
        pill.addView(controls, LinearLayout.LayoutParams(dp(306), LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    /** Image pochette (coins arrondis) ou avatar contact (cercle parfait), avec repli sur l'icône d'appli. */
    private fun buildAvatarView(bitmap: Bitmap?, pkg: String, sizePx: Int, circle: Boolean): ImageView =
        ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
            scaleType = ImageView.ScaleType.CENTER_CROP
            if (bitmap != null) {
                setImageBitmap(bitmap)
            } else {
                runCatching { setImageDrawable(packageManager.getApplicationIcon(pkg)) }
            }
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    if (circle) outline.setOval(0, 0, view.width, view.height)
                    else outline.setRoundRect(0, 0, view.width, view.height, dp(10).toFloat())
                }
            }
        }

    private fun pillBackground(radiusPx: Float): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusPx
        setColor(Color.BLACK)
        setStroke(dp(1), Color.parseColor("#1FFFFFFF"))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private val MUSIC_WAVE_COLORS = intArrayOf(
            Color.parseColor("#FF9F0A"),
            Color.parseColor("#FFD60A"),
            Color.parseColor("#FF6B00"),
            Color.parseColor("#FFD60A"),
            Color.parseColor("#FF9F0A"),
        )
        private val CALL_WAVE_COLORS = intArrayOf(
            Color.parseColor("#34C759"),
            Color.parseColor("#FF9F0A"),
            Color.parseColor("#34C759"),
            Color.parseColor("#FFD60A"),
            Color.parseColor("#34C759"),
        )

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
