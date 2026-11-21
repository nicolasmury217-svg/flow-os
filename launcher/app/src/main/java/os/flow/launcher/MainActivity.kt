package os.flow.launcher

import android.app.WallpaperManager
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import java.util.concurrent.Executors
import os.flow.launcher.ui.FlowTheme
import os.flow.launcher.ui.HomeScreen

class MainActivity : ComponentActivity() {
    private val vm: HomeViewModel by viewModels()

    /** Hôte des widgets Android (Météo Pixel + widgets libres de l'utilisateur). */
    lateinit var widgetHost: AppWidgetHost
        private set
    private var pendingWidgetId = -1
    private var pendingExtraWidgetId = -1

    private val bindWidget = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val id = pendingWidgetId
        pendingWidgetId = -1
        if (id == -1) return@registerForActivityResult
        if (res.resultCode == RESULT_OK) finishWidgetSetup(id) else widgetHost.deleteAppWidgetId(id)
    }

    private val pickExtraWidget = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val fallbackId = pendingExtraWidgetId
        pendingExtraWidgetId = -1
        val id = res.data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, fallbackId) ?: fallbackId
        if (id <= 0) return@registerForActivityResult
        if (res.resultCode != RESULT_OK) {
            runCatching { widgetHost.deleteAppWidgetId(id) }
            return@registerForActivityResult
        }
        val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(id)
        if (info == null) {
            runCatching { widgetHost.deleteAppWidgetId(id) }
            return@registerForActivityResult
        }
        vm.addExtraWidget(id)
        val optional = info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL != 0
        if (info.configure != null && !optional) {
            runCatching { widgetHost.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CONFIGURE_EXTRA, null) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val lightBars = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = lightBars, navigationBarStyle = lightBars)
        applyCleanWallpaperOnce()
        widgetHost = AppWidgetHost(applicationContext, WIDGET_HOST_ID)
        setupWeatherWidget()
        onBackPressedDispatcher.addCallback(this) { }
        setContent {
            FlowTheme {
                HomeScreen(
                    vm = vm,
                    widgetHost = widgetHost,
                    onPickWidget = ::launchExtraWidgetPicker,
                    onRemoveWidget = ::removeExtraWidget,
                )
            }
        }
    }

    private fun launchExtraWidgetPicker() {
        val id = widgetHost.allocateAppWidgetId()
        pendingExtraWidgetId = id
        val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_PICK)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        runCatching { pickExtraWidget.launch(intent) }.onFailure {
            pendingExtraWidgetId = -1
            runCatching { widgetHost.deleteAppWidgetId(id) }
        }
    }

    private fun removeExtraWidget(id: Int) {
        vm.removeExtraWidget(id)
        runCatching { widgetHost.deleteAppWidgetId(id) }
    }

    private fun applyCleanWallpaperOnce() {
        val prefs = getSharedPreferences("flow_launcher", MODE_PRIVATE)
        if (prefs.getBoolean(PREF_CLEAN_WALLPAPER_APPLIED, false)) return

        val appCtx = applicationContext
        val executor = Executors.newSingleThreadExecutor()
        executor.execute {
            try {
                appCtx.assets.open("wallpaper_flow_os.png").use { stream ->
                    WallpaperManager.getInstance(appCtx).setStream(
                        stream,
                        null,
                        false,
                        WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK,
                    )
                }
                prefs.edit().putBoolean(PREF_CLEAN_WALLPAPER_APPLIED, true).apply()
            } catch (_: Throwable) {
                // Ne bloque pas le launcher si le service wallpaper est indisponible.
            } finally {
                executor.shutdown()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        runCatching { widgetHost.startListening() }
    }

    override fun onResume() {
        super.onResume()
        vm.reloadFromPrefs()
    }

    override fun onStop() {
        super.onStop()
        runCatching { widgetHost.stopListening() }
    }

    private fun setupWeatherWidget() {
        val awm = AppWidgetManager.getInstance(this)
        val prefs = getSharedPreferences("flow_launcher", MODE_PRIVATE)
        val legacy = prefs.getInt(PREF_WEATHER_WIDGET_LEGACY, -1)
        if (legacy != -1) {
            runCatching { widgetHost.deleteAppWidgetId(legacy) }
            prefs.edit().remove(PREF_WEATHER_WIDGET_LEGACY).apply()
        }
        var id = prefs.getInt(PREF_WEATHER_WIDGET, -1)
        if (id != -1 && awm.getAppWidgetInfo(id) != null) { vm.weatherWidgetId = id; return }
        if (id != -1) { widgetHost.deleteAppWidgetId(id); prefs.edit().remove(PREF_WEATHER_WIDGET).apply() }
        if (awm.installedProviders.none { it.provider == WEATHER_WIDGET }) { vm.weatherWidgetId = -1; return }
        id = widgetHost.allocateAppWidgetId()
        if (awm.bindAppWidgetIdIfAllowed(id, WEATHER_WIDGET)) {
            finishWidgetSetup(id)
        } else {
            pendingWidgetId = id
            bindWidget.launch(
                Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, WEATHER_WIDGET),
            )
        }
    }

    private fun finishWidgetSetup(id: Int) {
        getSharedPreferences("flow_launcher", MODE_PRIVATE).edit().putInt(PREF_WEATHER_WIDGET, id).apply()
        vm.weatherWidgetId = id
        val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(id) ?: return
        val optional = info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL != 0
        if (info.configure != null && !optional) {
            runCatching { widgetHost.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CONFIGURE_WIDGET, null) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        vm.goHome()
    }

    private companion object {
        const val PREF_CLEAN_WALLPAPER_APPLIED = "clean_wallpaper_v1"
        const val PREF_WEATHER_WIDGET = "weather_widget_id_v2"
        const val PREF_WEATHER_WIDGET_LEGACY = "weather_widget_id_v1"
        const val WIDGET_HOST_ID = 0x464C
        const val REQ_CONFIGURE_WIDGET = 4242
        const val REQ_CONFIGURE_EXTRA = 4243
        val WEATHER_WIDGET = ComponentName(
            "com.google.android.apps.weather",
            "com.google.android.apps.weather.widget.CurrentForecastWidgetReceiver",
        )
    }
}
