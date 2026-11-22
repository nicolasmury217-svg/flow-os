package os.flow.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import os.flow.launcher.DoubleTapAction
import os.flow.launcher.HomeViewModel

/** Écran Réglages Flow : personnalisation, pastilles, widgets et applis masquées. */
@Composable
internal fun FlowSettingsOverlay(
    vm: HomeViewModel,
    onPickWidget: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    var showHiddenPicker by remember { mutableStateOf(false) }
    val s = vm.settings

    Surface(Modifier.fillMaxSize(), color = Color(0xFF111216)) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (showHiddenPicker) "Applis masquées" else "Paramètres Flow",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { if (showHiddenPicker) showHiddenPicker = false else onClose() }) {
                    Icon(Icons.Default.Close, contentDescription = "Fermer", tint = Color.White)
                }
            }

            if (showHiddenPicker) {
                Text(
                    "Les applis cochées n'apparaissent plus dans le tiroir.",
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(10.dp))
                LazyColumn(Modifier.fillMaxSize()) {
                    items(vm.apps, key = { it.key }) { app ->
                        val hidden = app.key in s.hiddenKeys
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.updateSettings { cur ->
                                        val next = if (hidden) cur.hiddenKeys - app.key else cur.hiddenKeys + app.key
                                        cur.copy(hiddenKeys = next)
                                    }
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(app.label, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            Checkbox(
                                checked = hidden,
                                onCheckedChange = { checked ->
                                    vm.updateSettings { cur ->
                                        val next = if (checked) cur.hiddenKeys + app.key else cur.hiddenKeys - app.key
                                        cur.copy(hiddenKeys = next)
                                    }
                                },
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    item {
                        SettingsSwitchRow(
                            title = "Icônes thématiques (Material You)",
                            subtitle = "Teinte les icônes compatibles avec la couleur du fond d'écran",
                            checked = s.themedIcons,
                            onCheckedChange = { vm.updateSettings { c -> c.copy(themedIcons = it) } },
                        )
                    }
                    item {
                        SettingsActionRow(
                            title = "Grille de l'accueil",
                            subtitle = "${s.gridCols} colonnes × 5 rangées",
                            value = "${s.gridCols} col.",
                            onClick = { vm.updateSettings { c -> c.copy(gridCols = if (c.gridCols == 4) 5 else 4) } },
                        )
                    }
                    item {
                        SettingsSwitchRow(
                            title = "Afficher les noms d'applis",
                            subtitle = "Libellés sous les icônes de l'accueil",
                            checked = s.showLabels,
                            onCheckedChange = { vm.updateSettings { c -> c.copy(showLabels = it) } },
                        )
                    }
                    item { HorizontalDivider(color = Color.White.copy(alpha = 0.1f), modifier = Modifier.padding(vertical = 6.dp)) }
                    item {
                        SettingsSwitchRow(
                            title = "Balayage vers le bas = notifications",
                            subtitle = "Glisser vers le bas sur l'accueil ouvre le volet",
                            checked = s.swipeDownNotifications,
                            onCheckedChange = { vm.updateSettings { c -> c.copy(swipeDownNotifications = it) } },
                        )
                    }
                    item {
                        SettingsActionRow(
                            title = "Double-tap sur l'accueil",
                            subtitle = "Action sur une zone vide de l'écran",
                            value = s.doubleTapAction.label,
                            onClick = {
                                val all = DoubleTapAction.entries
                                val next = all[(all.indexOf(s.doubleTapAction) + 1) % all.size]
                                vm.updateSettings { c -> c.copy(doubleTapAction = next) }
                            },
                        )
                    }
                    item { HorizontalDivider(color = Color.White.copy(alpha = 0.1f), modifier = Modifier.padding(vertical = 6.dp)) }
                    item {
                        SettingsSwitchRow(
                            title = "Flow Island (Dynamic Island)",
                            subtitle = if (vm.overlayPermissionGranted) {
                                "Pilule animée autour de la caméra (musique, notifications, charge)"
                            } else {
                                "Autoriser « Afficher par-dessus les autres applis » pour l'activer"
                            },
                            checked = s.dynamicIsland && vm.overlayPermissionGranted,
                            onCheckedChange = { enabled ->
                                if (enabled && !vm.overlayPermissionGranted) {
                                    vm.updateSettings { c -> c.copy(dynamicIsland = true) }
                                    vm.openOverlayPermissionSettings()
                                } else {
                                    vm.updateSettings { c -> c.copy(dynamicIsland = enabled) }
                                }
                            },
                        )
                    }
                    item {
                        SettingsActionRow(
                            title = "Pastilles de notification",
                            subtitle = if (vm.notificationAccessGranted) {
                                "Point discret sur les icônes avec notification non lue"
                            } else {
                                "Autoriser « Pastilles Flow » dans Android pour les activer"
                            },
                            value = if (vm.notificationAccessGranted) "Activées" else "Activer",
                            onClick = { vm.openNotificationAccessSettings() },
                        )
                    }
                    item {
                        SettingsActionRow(
                            title = "Ajouter un widget libre",
                            subtitle = "Affiché sur la page Widgets (à droite des applis)",
                            value = "${vm.extraWidgetIds.size} actif(s)",
                            onClick = { onClose(); onPickWidget() },
                        )
                    }
                    item {
                        SettingsActionRow(
                            title = "Applis masquées du tiroir",
                            subtitle = "Choisir les applis à cacher dans la liste",
                            value = "${s.hiddenKeys.size}",
                            onClick = { showHiddenPicker = true },
                        )
                    }
                    item {
                        SettingsActionRow(
                            title = "Regrouper Google & IA",
                            subtitle = "Crée les dossiers « Google » et « IA » en tête de l'accueil",
                            value = "Organiser",
                            onClick = { vm.organizeGoogleAndAi(); onClose() },
                        )
                    }
                    item { HorizontalDivider(color = Color.White.copy(alpha = 0.1f), modifier = Modifier.padding(vertical = 6.dp)) }
                    item {
                        SettingsActionRow(
                            title = "Luminosité très faible (Extra Dim)",
                            subtitle = "Descendre la luminosité sous le minimum matériel",
                            value = "Ouvrir",
                            onClick = { vm.openExtraDimSettings() },
                        )
                    }
                    item {
                        SettingsActionRow(
                            title = "Sons & sonneries",
                            subtitle = "Volume, sonnerie, notification et alarme",
                            value = "Ouvrir",
                            onClick = { vm.openSoundSettings() },
                        )
                    }
                    item {
                        SettingsActionRow(
                            title = "Fond d'écran & couleurs",
                            subtitle = "Changer le fond d'écran et les couleurs Material You",
                            value = "Ouvrir",
                            onClick = { vm.openWallpaperPicker() },
                        )
                    }
                    item {
                        SettingsActionRow(
                            title = "Jingle Flow OS",
                            subtitle = "Lancer l'animation vidéo et sonore Flow OS",
                            value = "Jouer",
                            onClick = { vm.launchFlowJingle() },
                        )
                    }
                    item {
                        Text(
                            "Flow OS · v1.1.0",
                            color = Color.White.copy(alpha = 0.45f),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 18.dp, bottom = 24.dp, start = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = Color.White.copy(alpha = 0.62f), fontSize = 12.5.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsActionRow(
    title: String,
    subtitle: String,
    value: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = Color.White.copy(alpha = 0.62f), fontSize = 12.5.sp)
        }
        Surface(shape = RoundedCornerShape(12.dp), color = Color.White.copy(alpha = 0.14f)) {
            Text(
                value,
                color = Color.White,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}
