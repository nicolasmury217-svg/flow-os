package os.flow.launcher

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import os.flow.launcher.ui.FlowSettingsOverlay
import os.flow.launcher.ui.FlowTheme

/**
 * Application autonome « Paramètres Flow » visible dans le tiroir et sur l'accueil.
 * Partage directement les préférences et services du launcher Flow pour appliquer chaque réglage instantanément.
 */
class FlowSettingsActivity : ComponentActivity() {
    private val vm: HomeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val darkBars = SystemBarStyle.dark(Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = darkBars, navigationBarStyle = darkBars)
        setContent {
            FlowTheme {
                FlowSettingsOverlay(
                    vm = vm,
                    onPickWidget = {
                        // Ouvre l'accueil Flow où l'utilisateur peut gérer sa page Widgets.
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        )
                        finish()
                    },
                    onClose = { finish() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.reloadFromPrefs()
    }
}
