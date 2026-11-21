package os.flow.launcher

import android.app.Application
import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Nombre maximal d'applis dans le dock, comme sur un Pixel. */
const val MAX_DOCK = 5

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val currentBootToken = readBootToken(app)
    private val repo = AppRepository(app)
    private var refreshJob: Job? = null
    private val appsCallback = repo.registerCallback { refresh() }

    var showBootAnimation by mutableStateOf(
        currentBootToken != null && currentBootToken != prefs.getString(KEY_LAST_BOOT_TOKEN, null),
    )
        private set

    var settings by mutableStateOf(readFlowSettings(prefs)); private set
    var settingsOpen by mutableStateOf(false)
    var apps by mutableStateOf<List<AppEntry>>(emptyList()); private set
    var query by mutableStateOf("")
    var drawerOpen by mutableStateOf(false)
    /** Incrémenté à chaque retour Accueil pour forcer la fermeture du clavier. */
    var resetTick by mutableStateOf(0); private set

    /** Éléments posés sur les pages d'accueil (applis seules ou dossiers). */
    var homeSlots by mutableStateOf<List<HomeSlot>>(emptyList()); private set
    /** Applis du dock, fixe en bas de toutes les pages. */
    var dockKeys by mutableStateOf<List<String>>(emptyList()); private set
    /** Index clé → appli, pour retrouver vite les icônes de l'accueil. */
    var byKey by mutableStateOf<Map<String, AppEntry>>(emptyMap()); private set

    /** Pastilles de notifications actives (paquets avec notification non lue). */
    val badgedPackages: StateFlow<Set<String>> = FlowNotificationListener.badgedPackages
    var notificationAccessGranted by mutableStateOf(FlowNotificationListener.isEnabled(app)); private set

    /** Widget Météo Pixel hébergé en haut de la première page (-1 : aucun). */
    var weatherWidgetId by mutableStateOf(-1)
    /** Widgets libres ajoutés par l'utilisateur sur la page Widgets. */
    var extraWidgetIds by mutableStateOf(readWidgetIds(prefs)); private set

    init {
        refresh()
    }

    fun finishBootAnimation() {
        currentBootToken?.let { prefs.edit().putString(KEY_LAST_BOOT_TOKEN, it).apply() }
        showBootAnimation = false
    }

    fun updateSettings(transform: (FlowSettings) -> FlowSettings) {
        val next = transform(settings)
        settings = next
        writeFlowSettings(prefs, next)
    }

    fun refreshNotificationAccess() {
        notificationAccessGranted = FlowNotificationListener.isEnabled(getApplication())
    }

    fun openNotificationAccessSettings() = repo.openNotificationAccessSettings()

    fun addExtraWidget(id: Int) {
        if (id <= 0 || id in extraWidgetIds) return
        extraWidgetIds = extraWidgetIds + id
        saveWidgetIds()
    }

    fun removeExtraWidget(id: Int) {
        extraWidgetIds = extraWidgetIds.filterNot { it == id }
        saveWidgetIds()
    }

    private fun saveWidgetIds() {
        prefs.edit().putString(KEY_EXTRA_WIDGETS, extraWidgetIds.joinToString(",")).apply()
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val list = withContext(Dispatchers.Default) { repo.loadApps() }
            apps = list
            byKey = list.associateBy { it.key }
            syncLayout(list)
            refreshGoogle()
            refreshNotificationAccess()
        }
    }

    private fun syncLayout(list: List<AppEntry>) {
        val known = list.map { it.key }
        val knownSet = known.toSet()
        val dock = if (prefs.contains(KEY_DOCK)) readList(KEY_DOCK).filter { it in knownSet } else repo.defaultDock(list)
        val seen = prefs.getStringSet(KEY_SEEN, null)?.toSet()
        val slots = if (prefs.contains(KEY_HOME)) {
            val kept = pruneHomeSlots(parseHomeSlots(prefs.getString(KEY_HOME, "").orEmpty()), knownSet)
            val placed = allSlotKeys(kept)
            val fresh = if (seen == null) emptyList() else known.filter { it !in seen && it !in placed && it !in dock }
            kept + fresh.map { HomeSlot.App(it) }
        } else {
            known.filter { it !in dock }.map { HomeSlot.App(it) }
        }
        val finalSlots = if (!prefs.getBoolean(KEY_AUTO_GROUP_V1, false)) {
            prefs.edit().putBoolean(KEY_AUTO_GROUP_V1, true).apply()
            groupGoogleAndAi(slots, dock, known)
        } else {
            slots
        }
        dockKeys = dock
        homeSlots = finalSlots
        prefs.edit()
            .putString(KEY_DOCK, dock.joinToString("\n"))
            .putString(KEY_HOME, serializeHomeSlots(finalSlots))
            .putStringSet(KEY_SEEN, knownSet)
            .apply()
    }

    /** Regroupe manuellement toutes les applis Google et IA dans leurs deux dossiers en tête d'accueil. */
    fun organizeGoogleAndAi() {
        val known = apps.map { it.key }
        saveSlots(groupGoogleAndAi(homeSlots, dockKeys, known))
    }

    private fun saveSlots(slots: List<HomeSlot>, dock: List<String> = dockKeys) {
        homeSlots = slots
        dockKeys = dock
        prefs.edit()
            .putString(KEY_HOME, serializeHomeSlots(slots))
            .putString(KEY_DOCK, dock.joinToString("\n"))
            .apply()
    }

    private fun readList(key: String): List<String> =
        prefs.getString(key, "").orEmpty().split("\n").filter { it.isNotBlank() }

    fun isOnHome(app: AppEntry): Boolean = app.key in allSlotKeys(homeSlots) || app.key in dockKeys

    fun addToHome(app: AppEntry) {
        if (isOnHome(app)) return
        saveSlots(homeSlots + HomeSlot.App(app.key))
    }

    fun removeFromHome(app: AppEntry) {
        saveSlots(removeKeyFromSlots(homeSlots, app.key))
    }

    /**
     * Glisser-déposer d'une appli : sur le dock (insertion/échange), sur un dossier (ajout dedans),
     * ou sur une case de la grille.
     */
    fun moveApp(key: String, toDock: Boolean, index: Int) {
        if (toDock) {
            val dock = dockKeys.toMutableList()
            val cleanedSlots = removeKeyFromSlots(homeSlots, key).toMutableList()
            dock.remove(key)
            if (dock.size >= MAX_DOCK) {
                val i = index.coerceIn(0, dock.size - 1)
                val displaced = dock[i]
                dock[i] = key
                cleanedSlots.add(HomeSlot.App(displaced))
            } else {
                dock.add(index.coerceIn(0, dock.size), key)
            }
            saveSlots(cleanedSlots, dock)
            return
        }
        val target = homeSlots.getOrNull(index)
        if (target is HomeSlot.Folder && key !in target.keys) {
            val dock = dockKeys.filterNot { it == key }
            saveSlots(addAppToFolder(homeSlots, key, target.id), dock)
            return
        }
        val dock = dockKeys.filterNot { it == key }
        val cleaned = removeKeyFromSlots(homeSlots, key).toMutableList()
        cleaned.add(index.coerceIn(0, cleaned.size), HomeSlot.App(key))
        saveSlots(cleaned, dock)
    }

    /** Regroupe une appli de l'accueil dans un nouveau dossier (ou un dossier existant). */
    fun createFolderWithNext(app: AppEntry) {
        val idx = homeSlots.indexOfFirst { it is HomeSlot.App && it.key == app.key }
        if (idx == -1) return
        val neighborIdx = when {
            idx + 1 < homeSlots.size -> idx + 1
            idx - 1 >= 0 -> idx - 1
            else -> return
        }
        val next = mergeAppsIntoFolder(homeSlots, app.key, neighborIdx, UUID.randomUUID().toString().take(8))
        saveSlots(next)
    }

    fun addAppToExistingFolder(appKey: String, folderId: String) {
        val dock = dockKeys.filterNot { it == appKey }
        saveSlots(addAppToFolder(homeSlots, appKey, folderId), dock)
    }

    fun removeFromFolder(folderId: String, appKey: String) {
        saveSlots(removeAppFromFolder(homeSlots, folderId, appKey))
    }

    fun renameFolderById(folderId: String, newName: String) {
        saveSlots(renameFolder(homeSlots, folderId, newName))
    }

    fun dissolveFolderById(folderId: String) {
        saveSlots(dissolveFolder(homeSlots, folderId))
    }

    fun folders(): List<HomeSlot.Folder> = homeSlots.filterIsInstance<HomeSlot.Folder>()

    fun openDrawer() { drawerOpen = true }
    fun expandNotifications() { if (settings.swipeDownNotifications) repo.expandNotifications() }

    fun onDoubleTapHome() {
        when (settings.doubleTapAction) {
            DoubleTapAction.NONE -> Unit
            DoubleTapAction.SEARCH -> openGoogleSearch(voice = false)
            DoubleTapAction.DRAWER -> openDrawer()
        }
    }

    // ---- Barre Google ----
    var googleEnabled by mutableStateOf(false); private set
    var googleIcon by mutableStateOf<ImageBitmap?>(null); private set

    fun refreshGoogle() {
        googleEnabled = repo.googleAppEnabled()
        if (googleIcon == null) googleIcon = repo.googleIcon()
    }

    // ---- Météo de l'accueil ----
    private val weatherRepo = WeatherRepository(app)
    var weather by mutableStateOf<Weather?>(null); private set
    var weatherPermission by mutableStateOf(weatherRepo.hasPermission()); private set
    private var lastWeather = 0L

    fun refreshWeather(force: Boolean = false) {
        weatherPermission = weatherRepo.hasPermission()
        if (!weatherPermission) return
        if (!force && System.currentTimeMillis() - lastWeather < 20 * 60_000L && weather != null) return
        viewModelScope.launch {
            weatherRepo.current()?.let { weather = it; lastWeather = System.currentTimeMillis() }
        }
    }

    fun openGoogleSearch(voice: Boolean = false) = repo.openGoogleSearch(voice)
    fun openGoogleAiMode() = repo.openGoogleAiMode()
    fun openWeatherDetails() = repo.openWeatherDetails()
    fun openGoogleLens() = repo.openGoogleLens()

    /** Applis visibles dans le tiroir (hors applis masquées), filtrées par la recherche. */
    val results: List<AppEntry> by derivedStateOf {
        val visible = apps.filter { it.key !in settings.hiddenKeys }
        filterAndRankApps(visible, query) { it.searchKey }
    }

    fun shortcutsFor(app: AppEntry): List<ShortcutEntry> = repo.loadShortcuts(app)

    fun launchShortcut(shortcut: ShortcutEntry) {
        repo.launchShortcut(shortcut)
        closeDrawer()
    }

    fun launch(app: AppEntry) {
        if (!repo.launch(app)) refresh()
        closeDrawer()
    }

    fun openAppSettings(app: AppEntry) = repo.openSettingsFor(app)
    fun uninstallApp(app: AppEntry) = repo.uninstall(app)
    fun submit() { results.firstOrNull()?.let { launch(it) } }
    fun clear() { query = ""; resetTick++ }

    fun closeDrawer() {
        query = ""
        drawerOpen = false
    }

    fun goHome() {
        closeDrawer()
        settingsOpen = false
        resetTick++
    }

    override fun onCleared() {
        repo.unregisterCallback(appsCallback)
        super.onCleared()
    }

    private companion object {
        const val PREFS_NAME = "flow_launcher"
        const val KEY_LAST_BOOT_TOKEN = "last_boot_token"
        const val KEY_HOME = "home_layout_v1"
        const val KEY_DOCK = "dock_layout_v1"
        const val KEY_SEEN = "seen_apps_v1"
        const val KEY_EXTRA_WIDGETS = "extra_widgets_v1"
        const val KEY_AUTO_GROUP_V1 = "auto_group_google_ai_v1"

        fun readWidgetIds(prefs: android.content.SharedPreferences): List<Int> =
            prefs.getString(KEY_EXTRA_WIDGETS, "").orEmpty()
                .split(",")
                .mapNotNull { it.trim().toIntOrNull() }
                .filter { it > 0 }

        fun readBootToken(app: Application): String? {
            val bootId = runCatching { File("/proc/sys/kernel/random/boot_id").readText().trim() }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
            val bootCount = runCatching {
                Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT).toString()
            }.getOrNull()
            return listOfNotNull(bootId, bootCount)
                .takeIf { it.isNotEmpty() }
                ?.joinToString(":")
        }
    }
}
