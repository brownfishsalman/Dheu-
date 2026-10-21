package com.salmansaadiq.dheu

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salmansaadiq.dheu.data.ThemePrefs
import com.salmansaadiq.dheu.ui.nav.DheuRoot
import com.salmansaadiq.dheu.ui.theme.Dheu
import com.salmansaadiq.dheu.ui.theme.DheuTheme
import com.salmansaadiq.dheu.ui.theme.ThemeMode
import com.salmansaadiq.dheu.ui.theme.isDark

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val modes = remember { ThemePrefs.flow(applicationContext) }
            val mode by modes.collectAsStateWithLifecycle(initialValue = ThemeMode.System)
            val dark = isDark(mode)
            // Status / navigation bar icons follow the app's theme, not just the system's.
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                )
                onDispose {}
            }
            DheuTheme(mode) {
                Surface(color = Dheu.colors.canvas, modifier = Modifier.fillMaxSize()) { DheuRoot() }
            }
        }
    }
}
