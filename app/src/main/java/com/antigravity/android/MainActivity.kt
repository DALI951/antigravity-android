package com.antigravity.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import com.antigravity.android.ui.AntigravityCinemaApp
import com.antigravity.android.ui.viewmodel.CinemaViewModel

val DarkBackground = Color(0xFF070708)
val DarkSurface = Color(0xFF121316)
val ElectricPurple = Color(0xFF8B6FFF)
val NeonCyan = Color(0xFF00F0FF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val appContainer = (application as? AntigravityApp)?.getOrCreateContainer()
            ?: AppContainer(applicationContext)

        setContent {
            val cinemaViewModel: CinemaViewModel = viewModel(
                factory = appContainer.provideCinemaViewModelFactory()
            )
            AntigravityCinemaApp(
                cinemaViewModel = cinemaViewModel,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
