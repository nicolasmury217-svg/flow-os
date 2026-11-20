package os.flow.launcher.ui

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.os.Bundle
import android.app.KeyguardManager
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import os.flow.launcher.AppEntry
import os.flow.launcher.HomeSlot
import os.flow.launcher.HomeViewModel
import os.flow.launcher.MAX_DOCK
import os.flow.launcher.R
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private const val ROWS = 5
private const val FIRST_PAGE_ROWS = 4

/** Où une icône est affichée : le menu d'appui long en dépend. */
private enum class Place { HOME, DOCK, DRAWER }

/**
 * Accueil façon Pixel : pages d'icônes et dossiers, date/heure et météo sur la première page,
 * dock fixe, page Widgets à droite, tiroir noir par balayage vers le haut et Réglages Flow.
 */
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    widgetHost: AppWidgetHost? = null,
    onPickWidget: () -> Unit = {},
    onRemoveWidget: (Int) -> Unit = {},
) {
    val light = wallpaperIsLight()
    val accent = wallpaperAccent()
    val settings = vm.settings
    val cols = settings.gridCols
    val badged by vm.badgedPackages.collectAsState()
    // Tiroir ou Réglages sur fond noir : icônes de barre d'état claires.
    SystemBarsAppearance(light && !vm.drawerOpen && !vm.settingsOpen)

    val byKey = vm.byKey
    val dockApps = remember(vm.dockKeys, byKey) { vm.dockKeys.mapNotNull { byKey[it] } }
    val drag = remember { DragController() }
    var openFolderId by remember { mutableStateOf<String?>(null) }
    val openFolder = remember(openFolderId, vm.homeSlots) {
        vm.homeSlots.filterIsInstance<HomeSlot.Folder>().firstOrNull { it.id == openFolderId }
    }
    // Avec le widget météo, la première page garde 3 rangées d'icônes au lieu de 4.
    val firstRows = if (vm.weatherWidgetId != -1 && widgetHost != null) FIRST_PAGE_ROWS - 1 else FIRST_PAGE_ROWS
    val basePages = remember(vm.homeSlots, firstRows, cols) { paginateSlots(vm.homeSlots, firstRows, cols) }
    // Pendant un glisser-déposer, une page vide de plus à droite, comme sur un Pixel.
    val appPages = if (drag.active) basePages + listOf(emptyList()) else basePages
    // Dernière page : Widgets libres (toujours accessible en glissant à droite).
    val totalPages = appPages.size + 1
    val pagerState = rememberPagerState { totalPages }
    val density = LocalDensity.current

    // Garde-fous : un appui sur Accueil ou le passage en arrière-plan annule tout glissement en cours.
    LaunchedEffect(vm.resetTick) { drag.cancel(); openFolderId = null }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) drag.cancel() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Bord gauche/droit pendant le glissement : on change de page après un court maintien.
    LaunchedEffect(drag.active) {
        val edge = with(density) { 36.dp.toPx() }
        while (drag.active) {
            if (System.currentTimeMillis() - drag.lastMove > 8_000) { drag.cancel(); break }
            val dir = when {
                drag.pos.x < edge -> -1
                drag.pos.x > drag.screenWidth - edge && drag.screenWidth > 0f -> 1
                else -> 0
            }
            if (dir != 0) {
                delay(500)
                val still = (dir < 0 && drag.pos.x < edge) || (dir > 0 && drag.pos.x > drag.screenWidth - edge)
                if (still && drag.active) {
                    val target = (pagerState.currentPage + dir).coerceIn(0, (appPages.size - 1).coerceAtLeast(0))
                    if (target != pagerState.currentPage) pagerState.animateScrollToPage(target)
                    delay(350)
                }
            } else {
                delay(40)
            }
        }
    }

    // Dépôt : sur le dock (insertion ou échange) ou sur la grille de la page affichée (ou dans un dossier).
    drag.onDrop = drop@{
        val app = drag.app ?: return@drop
        val p = drag.pos
        val dock = drag.dockBounds
        if (dock != null && dock.contains(p)) {
            val others = vm.dockKeys.filter { it != app.key }
            val n = others.size
            val idx = if (n >= MAX_DOCK) {
                ((p.x - dock.left) / (dock.width / n)).toInt().coerceIn(0, n - 1)
            } else {
                val slot = dock.width / (n + 1)
                ((p.x - dock.left) / slot).toInt().coerceIn(0, n)
            }
            vm.moveApp(app.key, toDock = true, index = idx)
        } else {
            val page = pagerState.currentPage
            val g = drag.gridBounds[page]
            if (page < appPages.size && g != null && p.y >= g.top - 60f && p.y <= g.bottom + 60f) {
                val rows = if (page == 0) firstRows else ROWS
                val col = ((p.x - g.left) / (g.width / cols)).toInt().coerceIn(0, cols - 1)
                val row = ((p.y - g.top) / (g.height / rows)).toInt().coerceIn(0, rows - 1)
                val offset = if (page == 0) 0 else cols * firstRows + (page - 1) * cols * ROWS
                vm.moveApp(app.key, toDock = false, index = offset + row * cols + col)
            }
        }
        if (drag.from == Place.DRAWER) vm.closeDrawer()
    }

    LaunchedEffect(vm.resetTick) { if (pagerState.currentPage != 0) pagerState.animateScrollToPage(0) }
    BackHandler(enabled = vm.drawerOpen) { vm.closeDrawer() }
    BackHandler(enabled = openFolder != null && !vm.drawerOpen && !vm.settingsOpen) { openFolderId = null }

    CompositionLocalProvider(LocalDrag provides drag) {
    Box(Modifier.fillMaxSize().onGloballyPositioned { drag.screenWidth = it.size.width.toFloat() }) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .pointerInput(settings.swipeDownNotifications) {
                    var total = 0f
                    detectVerticalDragGestures(
                        onDragStart = { total = 0f },
                        onDragEnd = {
                            if (total < -120f) vm.openDrawer()
                            else if (total > 120f) vm.expandNotifications()
                        },
                    ) { _, dy -> total += dy }
                },
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
                beyondViewportPageCount = totalPages,
            ) { page ->
                if (page < appPages.size) {
                    HomePage(
                        index = page,
                        slots = appPages.getOrElse(page) { emptyList() },
                        cols = cols,
                        light = light,
                        accent = accent,
                        badged = badged,
                        vm = vm,
                        firstRows = firstRows,
                        widgetHost = widgetHost,
                        onOpenFolder = { openFolderId = it },
                    )
                } else {
                    WidgetsPage(
                        widgetIds = vm.extraWidgetIds,
                        widgetHost = widgetHost,
                        light = light,
                        onPickWidget = onPickWidget,
                        onRemoveWidget = onRemoveWidget,
                    )
                }
            }
            PageDots(totalPages, pagerState.currentPage, light)
            if (dockApps.isNotEmpty()) Dock(dockApps, light, accent, badged, vm)
            GoogleSearchBar(vm, light)
        }

        AnimatedVisibility(
            visible = openFolder != null,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(140)),
        ) {
            openFolder?.let { f ->
                FolderOverlay(
                    folder = f,
                    byKey = byKey,
                    themed = settings.themedIcons,
                    accent = accent,
                    badgedPackages = badged,
                    vm = vm,
                    onClose = { openFolderId = null },
                )
            }
        }

        AnimatedVisibility(
            visible = vm.drawerOpen,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            Box(Modifier.graphicsLayer { alpha = if (drag.active && drag.from == Place.DRAWER) 0f else 1f }) {
                AppDrawer(vm, cols, accent, badged)
            }
        }

        AnimatedVisibility(
            visible = vm.settingsOpen,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            FlowSettingsOverlay(
                vm = vm,
                onPickWidget = onPickWidget,
                onClose = { vm.settingsOpen = false },
            )
        }

        // L'icône sous le doigt pendant le glisser-déposer.
        drag.app?.let { a ->
            val half = with(density) { 34.dp.toPx() }
            Box(
                Modifier
                    .offset { IntOffset((drag.pos.x - half).roundToInt(), (drag.pos.y - half).roundToInt()) }
                    .graphicsLayer { alpha = 0.95f },
            ) {
                AppGlyph(a, settings.themedIcons, accent, light, size = 68.dp, hasBadge = false)
            }
        }

        // Intro Flow OS : une fois par redémarrage, après le déverrouillage, avec fondu vers l'accueil.
        AnimatedVisibility(
            visible = vm.showBootAnimation,
            enter = fadeIn(tween(0)),
            exit = fadeOut(tween(450)),
        ) {
            BootAnimationOverlay(onFinished = vm::finishBootAnimation)
        }
    }
    }
}

/** État du glisser-déposer partagé entre les pages, le dock, le tiroir et l'aperçu. */
private class DragController {
    var app by mutableStateOf<AppEntry?>(null)
    var from by mutableStateOf(Place.HOME)
    var pos by mutableStateOf(Offset.Zero)
    var screenWidth = 0f
    val gridBounds = mutableMapOf<Int, Rect>()
    var dockBounds: Rect? = null
    var onDrop: () -> Unit = {}
    val active: Boolean get() = app != null

    var lastMove = 0L
    fun start(app: AppEntry, from: Place, at: Offset) {
        this.from = from; this.pos = at; this.app = app; lastMove = System.currentTimeMillis()
    }
    fun move(at: Offset) { pos = at; lastMove = System.currentTimeMillis() }
    fun drop() { try { onDrop() } finally { app = null } }
    fun cancel() { app = null }
}

private val LocalDrag = staticCompositionLocalOf<DragController> { error("DragController absent") }

/**
 * Barre de recherche Google façon Pixel : pilule teintée par la couleur du fond d'écran, « G » à gauche,
 * puis Mode IA, recherche vocale et Google Lens à droite. Un appui sur la barre ouvre la recherche.
 */
@Composable
private fun GoogleSearchBar(vm: HomeViewModel, light: Boolean) {
    // Relit l'état de l'appli Google à chaque retour à l'accueil (elle peut avoir été réactivée).
    LaunchedEffect(vm.resetTick) { vm.refreshGoogle() }
    val accent = wallpaperAccent()
    val background = if (light) lerp(accent, Color.White, 0.82f).copy(alpha = 0.94f)
    else lerp(accent, Color.Black, 0.74f).copy(alpha = 0.92f)
    val tint = if (light) lerp(accent, Color.Black, 0.55f) else lerp(accent, Color.White, 0.80f)
    Surface(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 10.dp)
            .height(64.dp)
            .clip(RoundedCornerShape(32.dp))
            .combinedClickableCompat { vm.openGoogleSearch(voice = false) },
        shape = RoundedCornerShape(32.dp),
        color = background,
    ) {
        Row(Modifier.padding(start = 18.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            val g = vm.googleIcon
            if (g != null) Image(g, contentDescription = "Google", Modifier.size(34.dp))
            else Icon(Icons.Default.Search, contentDescription = null, tint = tint)
            Spacer(Modifier.weight(1f))
            if (vm.googleEnabled) {
                IconButton({ vm.openGoogleAiMode() }) {
                    Icon(AiModeIcon, contentDescription = "Mode IA", tint = tint, modifier = Modifier.size(28.dp))
                }
                IconButton({ vm.openGoogleSearch(voice = true) }) {
                    Icon(MicIcon, contentDescription = "Recherche vocale", tint = tint, modifier = Modifier.size(28.dp))
                }
                IconButton({ vm.openGoogleLens() }) {
                    Icon(LensIcon, contentDescription = "Google Lens", tint = tint, modifier = Modifier.size(28.dp))
                }
            }
        }
    }
}

/** Couleur dominante du fond d'écran (pour teinter la barre comme Material You). */
@Composable
private fun wallpaperAccent(): Color {
    val context = LocalContext.current
    val accent by produceState(Color(0xFF6D4C41), context) {
        val wm = WallpaperManager.getInstance(context)
        fun compute() = wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)?.primaryColor?.toArgb()?.let { Color(it) }
            ?: Color(0xFF6D4C41)
        value = compute()
        val listener = WallpaperManager.OnColorsChangedListener { _, _ -> value = compute() }
        wm.addOnColorsChangedListener(listener, Handler(Looper.getMainLooper()))
        awaitDispose { wm.removeOnColorsChangedListener(listener) }
    }
    return accent
}

/** Loupe avec étincelle (Mode IA), en traits. */
private val AiModeIcon: ImageVector by lazy {
    val ink = SolidColor(Color.Black)
    ImageVector.Builder("AiMode", 24.dp, 24.dp, 24f, 24f)
        .addPath(addPathNodes("M15,13.5a5.5,5.5 0 1,1 -11,0a5.5,5.5 0 1,1 11,0z"), fill = null, stroke = ink,
            strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round)
        .addPath(addPathNodes("M13.6,17.6L19,23"), fill = null, stroke = ink,
            strokeLineWidth = 2.2f, strokeLineCap = StrokeCap.Round)
        .addPath(addPathNodes("M18.5,1C18.9,3.8 20.2,5.1 23,5.5C20.2,5.9 18.9,7.2 18.5,10C18.1,7.2 16.8,5.9 14,5.5C16.8,5.1 18.1,3.8 18.5,1z"),
            fill = ink)
        .build()
}

/** Cadre d'appareil photo avec point (Google Lens), en traits. */
private val LensIcon: ImageVector by lazy {
    val ink = SolidColor(Color.Black)
    ImageVector.Builder("Lens", 24.dp, 24.dp, 24f, 24f)
        .addPath(addPathNodes("M3,10V7.5A3.5,3.5 0 0,1 6.5,4H8.5L10,2.5H14L15.5,4H17.5A3.5,3.5 0 0,1 21,7.5V10"),
            fill = null, stroke = ink, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round)
        .addPath(addPathNodes("M3,14.5V17.5A3.5,3.5 0 0,0 6.5,21H10"),
            fill = null, stroke = ink, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round)
        .addPath(addPathNodes("M15.2,12.5a3.2,3.2 0 1,1 -6.4,0a3.2,3.2 0 1,1 6.4,0z"),
            fill = null, stroke = ink, strokeLineWidth = 2f)
        .addPath(addPathNodes("M21,18.5a2.5,2.5 0 1,1 -5,0a2.5,2.5 0 1,1 5,0z"), fill = ink)
        .build()
}

/** Icône micro (tracé Material), sans dépendre de la bibliothèque d'icônes étendue. */
private val MicIcon: ImageVector by lazy {
    ImageVector.Builder("Mic", 24.dp, 24.dp, 24f, 24f).addPath(
        addPathNodes(
            "M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3z" +
                "M17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11L5,11c0,3.41 2.72,6.23 6,6.72L11,21h2v-3.28" +
                "c3.28,-0.48 6,-3.3 6,-6.72h-1.7z",
        ),
        fill = SolidColor(Color.Black),
    ).build()
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit): Modifier = combinedClickable(onClick = onClick)

/** Première page : [firstRows] rangées sous la date/météo ; pages suivantes : 5 rangées. */
private fun paginateSlots(slots: List<HomeSlot>, firstRows: Int, cols: Int): List<List<HomeSlot>> {
    val pages = mutableListOf(slots.take(cols * firstRows))
    slots.drop(cols * firstRows).chunked(cols * ROWS).forEach { pages += it }
    return pages
}

/** Icônes de la barre d'état claires sur fond sombre, sombres sur fond clair. */
@Composable
private fun SystemBarsAppearance(lightBackground: Boolean) {
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = lightBackground
            isAppearanceLightNavigationBars = lightBackground
        }
    }
}

@Composable
private fun HomePage(
    index: Int,
    slots: List<HomeSlot>,
    cols: Int,
    light: Boolean,
    accent: Color,
    badged: Set<String>,
    vm: HomeViewModel,
    firstRows: Int,
    widgetHost: AppWidgetHost?,
    onOpenFolder: (String) -> Unit,
) {
    val byKey = vm.byKey
    val s = vm.settings
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Spacer(Modifier.height(20.dp))
        if (index == 0) {
            Glance(light, vm)
            if (widgetHost != null && vm.weatherWidgetId != -1) {
                Spacer(Modifier.height(8.dp))
                HostedWidget(widgetHost, vm.weatherWidgetId, Modifier.fillMaxWidth().height(116.dp))
            }
            Spacer(Modifier.height(12.dp))
        }
        val rows = if (index == 0) firstRows else ROWS
        val drag = LocalDrag.current
        Column(Modifier.fillMaxWidth().weight(1f).onGloballyPositioned { drag.gridBounds[index] = it.boundsInRoot() }) {
            for (r in 0 until rows) {
                Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    for (c in 0 until cols) {
                        val slot = slots.getOrNull(r * cols + c)
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            when (slot) {
                                is HomeSlot.App -> byKey[slot.key]?.let { app ->
                                    AppIcon(
                                        app = app,
                                        light = light,
                                        accent = accent,
                                        hasBadge = app.packageName in badged,
                                        place = Place.HOME,
                                        vm = vm,
                                        showLabel = s.showLabels,
                                    )
                                }
                                is HomeSlot.Folder -> FolderIcon(
                                    folder = slot,
                                    byKey = byKey,
                                    themed = s.themedIcons,
                                    accent = accent,
                                    light = light,
                                    showLabel = s.showLabels,
                                    badgedPackages = badged,
                                    vm = vm,
                                    onOpen = { onOpenFolder(slot.id) },
                                )
                                null -> Box(
                                    Modifier
                                        .fillMaxSize()
                                        .pointerInput(s.doubleTapAction) {
                                            detectTapGestures(onDoubleTap = { vm.onDoubleTapHome() })
                                        },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Affiche un widget Android hébergé, en lui indiquant sa taille réelle en dp. */
@Composable
internal fun HostedWidget(host: AppWidgetHost, id: Int, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val info = AppWidgetManager.getInstance(ctx).getAppWidgetInfo(id)
            host.createView(ctx, id, info).apply {
                setPadding(0, 0, 0, 0)
                addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
                    val d = v.resources.displayMetrics.density
                    val w = ((r - l) / d).toInt(); val h = ((b - t) / d).toInt()
                    if (w > 0 && h > 0) (v as AppWidgetHostView).updateAppWidgetSize(Bundle(), w, h, w, h)
                }
            }
        },
    )
}

/** Date et heure en haut de la première page (appui long = Réglages Flow). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Glance(light: Boolean, vm: HomeViewModel) {
    val now by produceState(LocalDateTime.now()) {
        while (true) { value = LocalDateTime.now(); delay(10_000) }
    }
    val ink = if (light) FlowInk else Color.White
    val shadow = Shadow(if (light) Color.White.copy(alpha = 0.8f) else Color.Black.copy(alpha = 0.6f), blurRadius = 16f)
    val textStyle = LocalTextStyle.current.copy(shadow = shadow)

    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.refreshWeather(force = true)
    }
    LaunchedEffect(vm.resetTick) {
        while (true) { vm.refreshWeather(); delay(20 * 60_000L) }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .combinedClickable(onClick = {}, onLongClick = { vm.settingsOpen = true }),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                now.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.FRENCH))
                    .replaceFirstChar { it.titlecase(Locale.FRENCH) },
                fontSize = 18.sp, color = ink, style = textStyle,
            )
            val w = vm.weather
            when {
                vm.weatherWidgetId != -1 -> Unit
                w != null -> Text(
                    "   ${w.icon} ${w.tempC} °C",
                    fontSize = 18.sp, color = ink, style = textStyle,
                    modifier = Modifier.clickable { vm.openWeatherDetails() },
                )
                !vm.weatherPermission -> Text(
                    "   Afficher la météo",
                    fontSize = 15.sp, color = ink.copy(alpha = 0.8f), style = textStyle,
                    modifier = Modifier.clickable { askLocation.launch(Manifest.permission.ACCESS_COARSE_LOCATION) },
                )
            }
        }
        Text(
            now.format(DateTimeFormatter.ofPattern("HH:mm")),
            fontSize = 56.sp, fontWeight = FontWeight.Light, color = ink,
            style = LocalTextStyle.current.copy(shadow = shadow),
        )
    }
}

@Composable
private fun PageDots(count: Int, current: Int, light: Boolean) {
    if (count < 2) { Spacer(Modifier.height(14.dp)); return }
    val ink = if (light) FlowInk else Color.White
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.Center) {
        repeat(count) { i ->
            val selected = i == current
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .size(if (selected) 8.dp else 6.dp)
                    .clip(CircleShape)
                    .background(ink.copy(alpha = if (selected) 1f else 0.45f)),
            )
        }
    }
}

@Composable
private fun Dock(apps: List<AppEntry>, light: Boolean, accent: Color, badged: Set<String>, vm: HomeViewModel) {
    val drag = LocalDrag.current
    Surface(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .onGloballyPositioned { drag.dockBounds = it.boundsInRoot() },
        shape = RoundedCornerShape(28.dp),
        color = (if (light) Color.White else Color.Black).copy(alpha = 0.25f),
    ) {
        Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            apps.forEach { app ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    AppIcon(
                        app = app,
                        light = light,
                        accent = accent,
                        hasBadge = app.packageName in badged,
                        place = Place.DOCK,
                        vm = vm,
                        showLabel = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun AppIcon(
    app: AppEntry,
    light: Boolean,
    accent: Color,
    hasBadge: Boolean,
    place: Place,
    vm: HomeViewModel,
    showLabel: Boolean = true,
) {
    var menu by remember { mutableStateOf(false) }
    val drag = LocalDrag.current
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val dragged = drag.app?.key == app.key && place != Place.DRAWER
    val ink = if (light) FlowInk else Color.White
    val shadow = Shadow(if (light) Color.White.copy(alpha = 0.7f) else Color.Black.copy(alpha = 0.7f), blurRadius = 8f)
    Box {
        Column(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .graphicsLayer { alpha = if (dragged) 0.25f else 1f }
                .onGloballyPositioned { coords[0] = it }
                .pointerInput(app.key, place) {
                    val slop = viewConfiguration.touchSlop
                    fun toRoot(local: Offset): Offset = coords[0]?.localToRoot(local) ?: local
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val longPress = awaitLongPressOrCancellation(down.id)
                        if (longPress == null) {
                            val up = currentEvent.changes.firstOrNull { it.id == down.id }
                            if (up != null && !up.pressed && !up.isConsumed &&
                                (up.position - down.position).getDistance() < slop
                            ) {
                                up.consume()
                                vm.launch(app)
                            }
                            return@awaitEachGesture
                        }
                        menu = true
                        var dragging = false
                        try {
                            val completed = drag(longPress.id) { change ->
                                if (!dragging && (change.position - longPress.position).getDistance() > slop) {
                                    dragging = true
                                    menu = false
                                    drag.start(app, place, toRoot(change.position))
                                }
                                if (dragging) drag.move(toRoot(change.position))
                                change.consume()
                            }
                            if (dragging) { if (completed) drag.drop() else drag.cancel() }
                        } finally {
                            if (dragging && drag.app?.key == app.key) drag.cancel()
                        }
                    }
                }
                .padding(vertical = 6.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppGlyph(
                app = app,
                themed = vm.settings.themedIcons,
                accent = accent,
                light = if (place == Place.DRAWER) false else light,
                size = 54.dp,
                hasBadge = hasBadge,
            )
            if (showLabel) {
                Spacer(Modifier.height(6.dp))
                Text(
                    app.label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center, color = ink,
                    style = LocalTextStyle.current.copy(shadow = if (place == Place.DRAWER) null else shadow),
                )
            }
        }
        if (menu) {
            val shortcuts = remember(app.key) { vm.shortcutsFor(app) }
            val existingFolders = remember(vm.homeSlots) { vm.folders() }
            DropdownMenu(expanded = true, onDismissRequest = { menu = false }) {
                if (shortcuts.isNotEmpty()) {
                    shortcuts.forEach { sc ->
                        DropdownMenuItem(
                            text = { Text(sc.label, fontWeight = FontWeight.Medium) },
                            onClick = { menu = false; vm.launchShortcut(sc) },
                        )
                    }
                    HorizontalDivider()
                }
                when (place) {
                    Place.HOME -> {
                        DropdownMenuItem(
                            text = { Text("Regrouper en dossier") },
                            onClick = { menu = false; vm.createFolderWithNext(app) },
                        )
                        existingFolders.take(3).forEach { folder ->
                            DropdownMenuItem(
                                text = { Text("Mettre dans « ${folder.name} »") },
                                onClick = { menu = false; vm.addAppToExistingFolder(app.key, folder.id) },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Retirer de l'accueil") },
                            onClick = { vm.removeFromHome(app); menu = false },
                        )
                    }
                    Place.DRAWER -> {
                        if (!vm.isOnHome(app)) {
                            DropdownMenuItem(
                                text = { Text("Ajouter à l'accueil") },
                                onClick = { vm.addToHome(app); menu = false },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Masquer du tiroir") },
                            onClick = {
                                menu = false
                                vm.updateSettings { c -> c.copy(hiddenKeys = c.hiddenKeys + app.key) }
                            },
                        )
                    }
                    Place.DOCK -> Unit
                }
                DropdownMenuItem(
                    text = { Text("Infos sur l'appli") },
                    onClick = { vm.openAppSettings(app); menu = false },
                )
                if (app.canUninstall) {
                    DropdownMenuItem(
                        text = { Text("Désinstaller", color = Color(0xFFD93025)) },
                        onClick = { vm.uninstallApp(app); menu = false },
                    )
                }
            }
        }
    }
}

/** Tiroir de toutes les applis : recherche + bouton Réglages Flow en haut, fermeture par balayage ou Retour. */
@Composable
private fun AppDrawer(vm: HomeViewModel, cols: Int, accent: Color, badged: Set<String>) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    LaunchedEffect(vm.drawerOpen) { if (!vm.drawerOpen) { keyboard?.hide(); focusManager.clearFocus(force = true) } }
    val gridState = rememberLazyGridState()
    val closeOnPullDown = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 40f && source == NestedScrollSource.UserInput) vm.closeDrawer()
                return Offset.Zero
            }
        }
    }
    Surface(Modifier.fillMaxSize(), color = DrawerBlack.copy(alpha = 0.97f)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        var total = 0f
                        detectVerticalDragGestures(
                            onDragStart = { total = 0f },
                            onDragEnd = { if (total > 80f) vm.closeDrawer() },
                        ) { _, dy -> total += dy }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .padding(vertical = 10.dp)
                        .width(36.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.35f)),
                )
            }
            SearchField(vm)
            val apps = vm.results
            if (apps.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.no_result), color = Color.White.copy(alpha = 0.7f))
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(cols),
                    state = gridState,
                    modifier = Modifier.fillMaxSize().nestedScroll(closeOnPullDown),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(apps, key = { it.key }) { app ->
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            AppIcon(
                                app = app,
                                light = false,
                                accent = accent,
                                hasBadge = app.packageName in badged,
                                place = Place.DRAWER,
                                vm = vm,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(vm: HomeViewModel) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextField(
            value = vm.query,
            onValueChange = { vm.query = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.bar_hint)) },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            leadingIcon = { Icon(Icons.Default.Search, null, tint = Color.White.copy(alpha = 0.8f)) },
            trailingIcon = {
                if (vm.query.isNotEmpty()) {
                    IconButton({ vm.query = "" }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.clear_search), tint = Color.White.copy(alpha = 0.8f))
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { vm.submit() }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = DrawerField, unfocusedContainerColor = DrawerField,
                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                focusedPlaceholderColor = Color.White.copy(alpha = 0.6f), unfocusedPlaceholderColor = Color.White.copy(alpha = 0.6f),
                cursorColor = Color.White,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
        )
        Spacer(Modifier.width(6.dp))
        IconButton(onClick = { vm.closeDrawer(); vm.settingsOpen = true }) {
            Icon(Icons.Default.Settings, contentDescription = "Réglages Flow", tint = Color.White.copy(alpha = 0.85f))
        }
    }
}

/** Couleurs du tiroir façon Pixel en thème sombre. */
private val DrawerBlack = Color(0xFF0F0F11)
private val DrawerField = Color(0xFF26262B)

/** Vrai si le fond d'écran annonce qu'il supporte du texte sombre (fond clair). */
@Composable
private fun wallpaperIsLight(): Boolean {
    val context = LocalContext.current
    val isLight by produceState(true, context) {
        val wm = WallpaperManager.getInstance(context)
        fun compute() = wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)?.let {
            it.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT != 0
        } ?: true
        value = compute()
        val listener = WallpaperManager.OnColorsChangedListener { _, _ -> value = compute() }
        wm.addOnColorsChangedListener(listener, Handler(Looper.getMainLooper()))
        awaitDispose { wm.removeOnColorsChangedListener(listener) }
    }
    return isLight
}

/** Animation de démarrage Flow OS avec particules et son (jouée une fois par boot après déverrouillage). */
@Composable
private fun BootAnimationOverlay(onFinished: () -> Unit) {
    val context = LocalContext.current
    var readyToPlay by remember { mutableStateOf(isDeviceUnlockedAndInteractive(context)) }

    LaunchedEffect(readyToPlay) {
        if (!readyToPlay) {
            while (!isDeviceUnlockedAndInteractive(context)) {
                delay(200)
            }
            readyToPlay = true
        } else {
            delay(6_500)
            onFinished()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFF3F5F7))
            // Un appui passe l'intro.
            .pointerInput(Unit) { detectTapGestures { onFinished() } },
        contentAlignment = Alignment.Center,
    ) {
        if (readyToPlay) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    VideoView(ctx).apply {
                        setBackgroundColor(android.graphics.Color.rgb(243, 245, 247))
                        setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE)
                        setVideoURI(Uri.parse("android.resource://${ctx.packageName}/${R.raw.boot_flow_os}"))
                        setOnPreparedListener { mp ->
                            mp.isLooping = false
                            // Muet si le téléphone est en silencieux ou vibreur.
                            val audio = ctx.getSystemService(AudioManager::class.java)
                            if (audio?.ringerMode != AudioManager.RINGER_MODE_NORMAL) mp.setVolume(0f, 0f)
                            mp.setOnInfoListener { _, what, _ ->
                                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                }
                                false
                            }
                            start()
                        }
                        setOnCompletionListener { onFinished() }
                        setOnErrorListener { _, _, _ ->
                            onFinished()
                            true
                        }
                    }
                },
            )
        }
    }
}

private fun isDeviceUnlockedAndInteractive(context: Context): Boolean {
    val km = context.getSystemService(KeyguardManager::class.java)
    val pm = context.getSystemService(PowerManager::class.java)
    return km != null && !km.isKeyguardLocked && !km.isDeviceLocked && pm?.isInteractive == true
}
