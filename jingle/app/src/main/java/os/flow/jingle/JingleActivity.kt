package os.flow.jingle

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.VideoView

/**
 * Lance le jingle Flow OS (video + son) des l'ouverture de l'appli.
 * Appui sur l'ecran = arret ; fin de la video = fermeture automatique.
 */
class JingleActivity : Activity() {

    private lateinit var video: VideoView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        video = VideoView(this)
        val root = FrameLayout(this).apply {
            setBackgroundColor(BACKGROUND)
            addView(
                video,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ),
            )
            setOnClickListener { finish() }
        }
        setContentView(root)
        hideSystemBars()

        video.setOnCompletionListener { finish() }
        video.setOnErrorListener { _, what, extra ->
            Log.e(TAG, "Lecture du jingle impossible (what=$what, extra=$extra)")
            finish()
            true
        }
        play()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        play()
    }

    override fun onPause() {
        super.onPause()
        // Jamais de son en arriere-plan : quitter l'appli arrete le jingle.
        video.stopPlayback()
        finish()
    }

    private fun play() {
        video.setVideoURI(Uri.parse("android.resource://$packageName/${R.raw.flow_jingle}"))
        video.setOnPreparedListener { player ->
            player.isLooping = false
            player.seekTo(0)
            player.start()
        }
    }

    private fun hideSystemBars() {
        window.insetsController?.let { controller ->
            controller.hide(WindowInsets.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private companion object {
        const val TAG = "FlowJingle"
        val BACKGROUND = Color.parseColor("#F3F5F7")
    }
}
