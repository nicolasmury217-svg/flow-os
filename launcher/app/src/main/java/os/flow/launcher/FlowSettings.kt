package os.flow.launcher

import android.content.SharedPreferences

/** Action déclenchée par un double-tap sur une zone libre de l'accueil. */
enum class DoubleTapAction(val label: String) {
    NONE("Désactivé"),
    SEARCH("Recherche Google"),
    DRAWER("Tiroir d'applis"),
}

/** Préférences utilisateur du launcher Flow (immuable). */
data class FlowSettings(
    val themedIcons: Boolean = false,
    val gridCols: Int = 4,
    val showLabels: Boolean = true,
    val swipeDownNotifications: Boolean = true,
    val doubleTapAction: DoubleTapAction = DoubleTapAction.SEARCH,
    val hiddenKeys: Set<String> = emptySet(),
)

internal fun readFlowSettings(prefs: SharedPreferences): FlowSettings = FlowSettings(
    themedIcons = prefs.getBoolean(KEY_THEMED_ICONS, false),
    gridCols = prefs.getInt(KEY_GRID_COLS, 4).coerceIn(4, 5),
    showLabels = prefs.getBoolean(KEY_SHOW_LABELS, true),
    swipeDownNotifications = prefs.getBoolean(KEY_SWIPE_DOWN_NOTIFS, true),
    doubleTapAction = runCatching {
        DoubleTapAction.valueOf(prefs.getString(KEY_DOUBLE_TAP, null) ?: DoubleTapAction.SEARCH.name)
    }.getOrDefault(DoubleTapAction.SEARCH),
    hiddenKeys = prefs.getStringSet(KEY_HIDDEN_KEYS, emptySet()).orEmpty().toSet(),
)

internal fun writeFlowSettings(prefs: SharedPreferences, s: FlowSettings) {
    prefs.edit()
        .putBoolean(KEY_THEMED_ICONS, s.themedIcons)
        .putInt(KEY_GRID_COLS, s.gridCols.coerceIn(4, 5))
        .putBoolean(KEY_SHOW_LABELS, s.showLabels)
        .putBoolean(KEY_SWIPE_DOWN_NOTIFS, s.swipeDownNotifications)
        .putString(KEY_DOUBLE_TAP, s.doubleTapAction.name)
        .putStringSet(KEY_HIDDEN_KEYS, s.hiddenKeys)
        .apply()
}

private const val KEY_THEMED_ICONS = "pref_themed_icons"
private const val KEY_GRID_COLS = "pref_grid_cols"
private const val KEY_SHOW_LABELS = "pref_show_labels"
private const val KEY_SWIPE_DOWN_NOTIFS = "pref_swipe_down_notifs"
private const val KEY_DOUBLE_TAP = "pref_double_tap"
private const val KEY_HIDDEN_KEYS = "pref_hidden_keys"
