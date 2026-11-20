# Flow OS

**Flow OS** est un environnement Android léger et rapide conçu pour **redonner de la vitesse et de la fluidité à tout téléphone Android compatible**.

## Pourquoi Flow OS améliore la vitesse du téléphone

- **Sur tout téléphone Android compatible (Android 10+ / API 29+)** : le **Flow Launcher** remplace les surcouches lourdes par un accueil minimaliste (~4 Mo, faible empreinte mémoire, zéro télémétrie, zéro pub) pour une navigation plus réactive au quotidien.
- **Sur les téléphones compatibles LineageOS** : combiné à **LineageOS** + **MindTheGapps** et à un allègement des services inutiles, Flow OS libère de la RAM au repos, réduit les processus en arrière-plan et améliore nettement la fluidité générale — idéal pour donner une seconde vie à un appareil plus ancien.

## Contenu du dépôt

- [`launcher/`](launcher/) — **Flow Launcher** (`os.flow.launcher`, Android 10+) :
  - Accueil multi-pages façon Pixel avec dock fixe et glisser-déposer.
  - **Dossiers** sur l'accueil (aperçu 2×2, renommage en ligne, dissolution automatique).
  - **Icônes thématiques Material You** teintées selon la couleur dominante du fond d'écran.
  - **Raccourcis d'applis** à l'appui long (API `LauncherApps`) et désinstallation rapide.
  - **Pastilles de notification** opt-in (`FlowNotificationListener`).
  - **Widget Météo** intégré + **page Widgets libre** pour ajouter n'importe quel widget Android.
  - **Tiroir d'applis** sombre avec recherche instantanée (normalisation des accents) et **Réglages Flow** (grille 4/5 colonnes, gestes rapides, applis masquées).
  - Intro vidéo Flow OS au premier déverrouillage après démarrage.
- [`jingle/`](jingle/) — **Jingle Flow** (`os.flow.jingle`, Android 11+) : petite appli autonome qui lance le jingle vidéo + audio Flow OS en plein écran d'un simple appui.
- [`assets/`](assets/) — Logo, fond d'écran et vidéo d'intro Flow OS.

## Compilation

Prérequis : JDK 17 et Android SDK (`compileSdk 35`, `build-tools 35.0.0`).

```bash
# Launcher Flow
cd launcher
./gradlew testDebugUnitTest :app:assembleRelease

# Appli Jingle Flow
cd ../jingle
./gradlew :app:assembleRelease
```

L'APK release généré se trouve dans `app/build/outputs/apk/release/app-release.apk`.
