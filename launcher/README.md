# Flow Launcher

Launcher Android (Kotlin, Jetpack Compose) de Flow OS : écran d'accueil minimal (horloge), tiroir d'applis (balayage vers le haut ou saisie), barre unique en bas qui filtre et lance les applis (Entrée = première appli).

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew testDebugUnitTest :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell cmd package set-home-activity os.flow.launcher/.MainActivity
```

Pour une compilation debug : `./gradlew :app:assembleDebug`. La variante `release` utilise R8 (`isMinifyEnabled = true`, `isShrinkResources = true`) et la signature debug pour la réinstallation directe sur l'appareil de test (à remplacer par un keystore dédié avant distribution).
