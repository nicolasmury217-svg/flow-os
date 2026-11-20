package os.flow.launcher.ui

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import os.flow.launcher.AppEntry
import os.flow.launcher.HomeSlot
import os.flow.launcher.HomeViewModel

/**
 * Icône d'appli avec support Material You (couche monochrome teintée par la couleur du fond d'écran)
 * et pastille de notification discrète en haut à droite.
 */
@Composable
internal fun AppGlyph(
    app: AppEntry,
    themed: Boolean,
    accent: Color,
    light: Boolean,
    size: Dp,
    hasBadge: Boolean,
) {
    val mono = if (themed) app.monochromeIcon else null
    Box(Modifier.size(size)) {
        if (mono != null) {
            val bg = if (light) lerp(accent, Color.White, 0.78f) else lerp(accent, Color.Black, 0.68f)
            val fg = if (light) lerp(accent, Color.Black, 0.45f) else lerp(accent, Color.White, 0.78f)
            Box(
                Modifier.fillMaxSize().clip(CircleShape).background(bg),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = mono,
                    contentDescription = app.label,
                    modifier = Modifier.size(size * 0.72f),
                    colorFilter = ColorFilter.tint(fg),
                )
            }
        } else {
            Image(app.icon, contentDescription = app.label, Modifier.fillMaxSize())
        }
        if (hasBadge) {
            val dotColor = lerp(accent, Color.White, 0.35f)
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(11.dp)
                    .clip(CircleShape)
                    .background(dotColor)
                    .border(1.5.dp, Color.Black.copy(alpha = 0.55f), CircleShape),
            )
        }
    }
}

/** Icône de dossier sur l'accueil : cercle translucide + aperçu 2×2 des 4 premières applis. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FolderIcon(
    folder: HomeSlot.Folder,
    byKey: Map<String, AppEntry>,
    themed: Boolean,
    accent: Color,
    light: Boolean,
    showLabel: Boolean,
    badgedPackages: Set<String>,
    vm: HomeViewModel,
    onOpen: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val preview = remember(folder.keys, byKey) { folder.keys.mapNotNull { byKey[it] } }
    val hasBadge = preview.any { it.packageName in badgedPackages }
    val ink = if (light) FlowInk else Color.White
    val shadow = Shadow(if (light) Color.White.copy(alpha = 0.7f) else Color.Black.copy(alpha = 0.7f), blurRadius = 8f)
    val bg = (if (light) Color.White else Color.Black).copy(alpha = 0.34f)

    Box {
        Column(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .combinedClickable(onClick = onOpen, onLongClick = { menu = true })
                .padding(vertical = 6.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(54.dp)) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(bg)
                        .border(0.8.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                        .padding(7.dp),
                ) {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            MiniFolderCell(preview.getOrNull(0), themed, accent, light, Modifier.weight(1f))
                            MiniFolderCell(preview.getOrNull(1), themed, accent, light, Modifier.weight(1f))
                        }
                        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            MiniFolderCell(preview.getOrNull(2), themed, accent, light, Modifier.weight(1f))
                            MiniFolderCell(preview.getOrNull(3), themed, accent, light, Modifier.weight(1f))
                        }
                    }
                }
                if (hasBadge) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .size(11.dp)
                            .clip(CircleShape)
                            .background(lerp(accent, Color.White, 0.35f))
                            .border(1.5.dp, Color.Black.copy(alpha = 0.55f), CircleShape),
                    )
                }
            }
            if (showLabel) {
                Spacer(Modifier.height(6.dp))
                Text(
                    folder.name,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    color = ink,
                    style = LocalTextStyle.current.copy(shadow = shadow),
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Ouvrir / Renommer") }, onClick = { menu = false; onOpen() })
            DropdownMenuItem(
                text = { Text("Dissoudre le dossier") },
                onClick = { menu = false; vm.dissolveFolderById(folder.id) },
            )
        }
    }
}

@Composable
private fun MiniFolderCell(app: AppEntry?, themed: Boolean, accent: Color, light: Boolean, modifier: Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (app != null) AppGlyph(app, themed, accent, light, size = 18.dp, hasBadge = false)
    }
}

/** Dossier ouvert façon Pixel : titre modifiable en haut + grille d'applis. */
@Composable
internal fun FolderOverlay(
    folder: HomeSlot.Folder,
    byKey: Map<String, AppEntry>,
    themed: Boolean,
    accent: Color,
    badgedPackages: Set<String>,
    vm: HomeViewModel,
    onClose: () -> Unit,
) {
    var title by remember(folder.id) { mutableStateOf(folder.name) }
    val apps = remember(folder.keys, byKey) { folder.keys.mapNotNull { byKey[it] } }
    val noRipple = remember { MutableInteractionSource() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.68f))
            .clickable(interactionSource = noRipple, indication = null) {
                vm.renameFolderById(folder.id, title)
                onClose()
            }
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 24.dp, vertical = 56.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(interactionSource = noRipple, indication = null, onClick = {}),
            shape = RoundedCornerShape(32.dp),
            color = Color(0xFF1B1C20).copy(alpha = 0.96f),
        ) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                BasicTextField(
                    value = title,
                    onValueChange = { title = it; vm.renameFolderById(folder.id, it) },
                    singleLine = true,
                    cursorBrush = SolidColor(Color.White),
                    textStyle = TextStyle(
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(apps, key = { it.key }) { app ->
                        FolderAppTile(app, folder.id, themed, accent, app.packageName in badgedPackages, vm, onClose)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderAppTile(
    app: AppEntry,
    folderId: String,
    themed: Boolean,
    accent: Color,
    hasBadge: Boolean,
    vm: HomeViewModel,
    onClose: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box(contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .combinedClickable(
                    onClick = { onClose(); vm.launch(app) },
                    onLongClick = { menu = true },
                )
                .padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppGlyph(app, themed, accent, light = false, size = 54.dp, hasBadge = hasBadge)
            Spacer(Modifier.height(6.dp))
            Text(
                app.label,
                color = Color.White,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Sortir du dossier") },
                onClick = { menu = false; vm.removeFromFolder(folderId, app.key); vm.addToHome(app) },
            )
            DropdownMenuItem(
                text = { Text("Retirer de l'accueil") },
                onClick = { menu = false; vm.removeFromFolder(folderId, app.key) },
            )
            DropdownMenuItem(
                text = { Text("Infos sur l'appli") },
                onClick = { menu = false; onClose(); vm.openAppSettings(app) },
            )
        }
    }
}

/** Page Widgets libre (à droite des pages d'applis) : widgets Android ajoutés par l'utilisateur. */
@Composable
internal fun WidgetsPage(
    widgetIds: List<Int>,
    widgetHost: AppWidgetHost?,
    light: Boolean,
    onPickWidget: () -> Unit,
    onRemoveWidget: (Int) -> Unit,
) {
    val context = LocalContext.current
    val awm = remember(context) { AppWidgetManager.getInstance(context) }
    val ink = if (light) FlowInk else Color.White

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Widgets", color = ink, fontSize = 20.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = (if (light) Color.White else Color.Black).copy(alpha = 0.35f),
                modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onPickWidget),
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = ink, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("Ajouter", color = ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (widgetIds.isEmpty() || widgetHost == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Appuie sur « Ajouter » pour poser\nSpotify, Agenda, Notes ou tout autre widget.",
                    color = ink.copy(alpha = 0.78f),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(widgetIds, key = { it }) { id ->
                    val info = remember(id) { awm.getAppWidgetInfo(id) }
                    val minHeightDp = ((info?.minHeight ?: 140) / context.resources.displayMetrics.density)
                        .toInt().coerceIn(110, 260)
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = (if (light) Color.White else Color.Black).copy(alpha = 0.22f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Box(Modifier.fillMaxWidth().padding(6.dp)) {
                            HostedWidget(widgetHost, id, Modifier.fillMaxWidth().height(minHeightDp.dp))
                            IconButton(
                                onClick = { onRemoveWidget(id) },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.45f)),
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Retirer", tint = Color.White, modifier = Modifier.size(15.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
