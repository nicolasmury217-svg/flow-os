package os.flow.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import android.os.UserHandle
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import java.text.Normalizer
import java.util.Locale
import kotlin.math.roundToInt

data class AppEntry(
    val label: String,
    val packageName: String,
    val activityName: String,
    val user: UserHandle,
    val icon: ImageBitmap,
    val monochromeIcon: ImageBitmap? = null,
    val canUninstall: Boolean = false,
) {
    val searchKey: String = label.fold()
    /** Identifiant stable d'une appli (paquet + activité), utilisé pour mémoriser l'accueil. */
    val key: String get() = "$packageName/$activityName"
}

/** Raccourci rapide d'une appli (affiché à l'appui long, comme sur Pixel). */
data class ShortcutEntry(
    val id: String,
    val label: String,
    val info: ShortcutInfo,
)

private val DIACRITICS_REGEX = Regex("\\p{Mn}+")
private val WORD_SPLIT_REGEX = Regex("[\\s\\-_]+")

/** Normalise pour la recherche : minuscules, sans accents. */
fun String.fold(): String =
    Normalizer.normalize(lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(DIACRITICS_REGEX, "")

/** Filtre et classe une liste par pertinence : prefixe de mot d'abord, puis sous-chaine. */
internal fun <T> filterAndRankApps(
    items: List<T>,
    query: String,
    searchKey: (T) -> String,
): List<T> {
    val q = query.fold().trim()
    if (q.isEmpty()) return items
    val prefix = mutableListOf<T>()
    val rest = mutableListOf<T>()
    for (item in items) {
        val key = searchKey(item)
        if (key.startsWith(q) || key.split(WORD_SPLIT_REGEX).any { it.startsWith(q) }) {
            prefix.add(item)
        } else if (key.contains(q)) {
            rest.add(item)
        }
    }
    return prefix + rest
}

private const val GOOGLE_APP = "com.google.android.googlequicksearchbox"

/** Liste et lance les applis via LauncherApps (API officielle pour un launcher). */
class AppRepository(private val context: Context) {
    private val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
    private val iconPx = (52f * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(96)

    fun loadApps(): List<AppEntry> =
        launcherApps.getActivityList(null, Process.myUserHandle())
            .filter { it.applicationInfo.packageName != context.packageName }
            .map {
                val drawable = it.getIcon(0) ?: context.packageManager.defaultActivityIcon
                AppEntry(
                    label = it.label.toString(),
                    packageName = it.applicationInfo.packageName,
                    activityName = it.componentName.className,
                    user = it.user,
                    icon = drawable.toBitmap(iconPx, iconPx).asImageBitmap(),
                    monochromeIcon = extractMonochrome(drawable, iconPx)?.asImageBitmap(),
                    canUninstall = (it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0,
                )
            }
            .sortedBy { it.searchKey }

    /** Charge jusqu'à 4 raccourcis Android natifs pour le menu d'appui long (actif quand Flow est accueil par défaut). */
    fun loadShortcuts(app: AppEntry): List<ShortcutEntry> = runCatching {
        if (!launcherApps.hasShortcutHostPermission()) return emptyList()
        val query = LauncherApps.ShortcutQuery()
            .setPackage(app.packageName)
            .setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED,
            )
        launcherApps.getShortcuts(query, app.user).orEmpty()
            .sortedBy { it.rank }
            .take(4)
            .mapNotNull { info ->
                val label = (info.shortLabel ?: info.longLabel)?.toString()?.trim()
                if (label.isNullOrEmpty()) null else ShortcutEntry(info.id, label, info)
            }
    }.getOrDefault(emptyList())

    fun launchShortcut(shortcut: ShortcutEntry): Boolean = runCatching {
        launcherApps.startShortcut(shortcut.info, null, null)
    }.isSuccess

    fun uninstall(app: AppEntry) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** Déplie le volet de notifications Android (requiert EXPAND_STATUS_BAR). */
    fun expandNotifications() {
        runCatching {
            val service = context.getSystemService("statusbar") ?: return
            Class.forName("android.app.StatusBarManager")
                .getMethod("expandNotificationsPanel")
                .invoke(service)
        }
    }

    fun openNotificationAccessSettings() {
        runCatching {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun openOverlayPermissionSettings() {
        runCatching {
            context.startActivity(
                Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** Recherche Web directe depuis le tiroir d'applis. */
    fun searchWeb(query: String) {
        val uri = android.net.Uri.parse("https://www.google.com/search?q=" + android.net.Uri.encode(query))
        startFirst(
            Intent(Intent.ACTION_WEB_SEARCH).setPackage(GOOGLE_APP)
                .putExtra(android.app.SearchManager.QUERY, query)
                .takeIf { googleAppEnabled() } ?: Intent(Intent.ACTION_VIEW, uri),
            Intent(Intent.ACTION_VIEW, uri),
        )
    }

    fun launchPackage(pkg: String) {
        runCatching {
            context.packageManager.getLaunchIntentForPackage(pkg)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ?.let { context.startActivity(it) }
        }
    }

    fun launch(app: AppEntry): Boolean = runCatching {
        launcherApps.startMainActivity(
            android.content.ComponentName(app.packageName, app.activityName), app.user, null, null,
        )
    }.isSuccess

    fun registerCallback(onChange: () -> Unit): LauncherApps.Callback {
        val callback = object : LauncherApps.Callback() {
            override fun onPackageRemoved(p: String?, u: UserHandle?) = onChange()
            override fun onPackageAdded(p: String?, u: UserHandle?) = onChange()
            override fun onPackageChanged(p: String?, u: UserHandle?) = onChange()
            override fun onPackagesAvailable(p: Array<out String>?, u: UserHandle?, r: Boolean) = onChange()
            override fun onPackagesUnavailable(p: Array<out String>?, u: UserHandle?, r: Boolean) = onChange()
        }
        launcherApps.registerCallback(callback)
        return callback
    }

    /** Vrai si l'appli Google (recherche, voix) est installée et activée. */
    fun googleAppEnabled(): Boolean = runCatching {
        context.packageManager.getApplicationInfo(GOOGLE_APP, 0).enabled
    }.getOrDefault(false)

    /** Icône « G » de l'appli Google, même si elle est désactivée ; null si absente. */
    fun googleIcon(): ImageBitmap? = runCatching {
        val pm = context.packageManager
        val info = pm.getApplicationInfo(GOOGLE_APP, android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS)
        pm.getApplicationIcon(info).toBitmap(iconPx, iconPx).asImageBitmap()
    }.getOrNull()

    /**
     * Barre de recherche de l'accueil : recherche Google (ou vocale) si l'appli Google est active,
     * sinon page google.fr dans le navigateur par défaut.
     */
    fun openGoogleSearch(voice: Boolean) {
        val intent = if (googleAppEnabled()) {
            if (voice) Intent("android.speech.action.WEB_SEARCH").setPackage(GOOGLE_APP)
            else Intent(Intent.ACTION_WEB_SEARCH).setPackage(GOOGLE_APP)
        } else {
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.fr/"))
        }
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.fr/"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
    }

    /** Lance le premier intent qui aboutit (du plus précis au plus général). */
    private fun startFirst(vararg intents: Intent) {
        for (intent in intents) {
            val ok = runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
            if (ok) return
        }
    }

    /** Météo détaillée : recherche « météo » dans l'appli Google (carte météo), sinon dans le navigateur. */
    fun openWeatherDetails() {
        startFirst(
            Intent(Intent.ACTION_WEB_SEARCH).setPackage(GOOGLE_APP).putExtra(android.app.SearchManager.QUERY, "météo"),
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/search?q=m%C3%A9t%C3%A9o")),
        )
    }

    /** Mode IA de la recherche Google (bouton loupe étoilée). */
    fun openGoogleAiMode() {
        val uri = android.net.Uri.parse("https://www.google.com/search?udm=50")
        startFirst(
            Intent(Intent.ACTION_VIEW, uri).setPackage(GOOGLE_APP),
            Intent(Intent.ACTION_VIEW, uri),
        )
    }

    /**
     * Bouton Lens : l'appli Google refuse l'ouverture directe de Lens hors Pixel (testé sur le S20 FE),
     * donc on ouvre l'appli Google, dont la barre de recherche propose Lens ; repli sur lens.google.com.
     */
    fun openGoogleLens() {
        startFirst(
            context.packageManager.getLaunchIntentForPackage(GOOGLE_APP) ?: Intent(),
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://lens.google.com/")),
        )
    }

    /** Dock par défaut, comme sur un Pixel : téléphone, messages, navigateur, appareil photo. */
    fun defaultDock(apps: List<AppEntry>): List<String> {
        val pm = context.packageManager
        val intents = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING),
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER),
            Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
        )
        return intents.mapNotNull { intent ->
            val pkg = runCatching {
                pm.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                    ?.activityInfo?.packageName
            }.getOrNull() ?: return@mapNotNull null
            apps.firstOrNull { it.packageName == pkg }?.key
        }.distinct()
    }

    fun unregisterCallback(callback: LauncherApps.Callback) {
        launcherApps.unregisterCallback(callback)
    }

    fun openSettingsFor(app: AppEntry) {
        runCatching {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

/**
 * Extrait la couche monochrome d'une icône adaptative (Android 13+) pour le mode Material You,
 * recadrée dans la zone centrale (72/108) pour un rendu homogène dans un cercle teinté.
 */
private fun extractMonochrome(drawable: Drawable, size: Int): Bitmap? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    val adaptive = drawable as? AdaptiveIconDrawable ?: return null
    val mono = adaptive.monochrome ?: return null
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val inset = (size * 14f / 72f).roundToInt()
    mono.setBounds(-inset, -inset, size + inset, size + inset)
    mono.draw(canvas)
    return bmp
}
