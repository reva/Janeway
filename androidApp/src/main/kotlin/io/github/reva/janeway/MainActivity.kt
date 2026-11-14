package io.github.reva.janeway

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.reva.janeway.library.AndroidAudioLibrary
import io.github.reva.janeway.ui.App

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val library = AndroidAudioLibrary(applicationContext)
        setContent { App(library, BuildConfig.ACOUSTID_API_KEY.ifBlank { null }) }
    }
}
