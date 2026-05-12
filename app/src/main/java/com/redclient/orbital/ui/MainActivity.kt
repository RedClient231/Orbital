package com.redclient.orbital.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.redclient.orbital.ui.nav.OrbitalNavHost
import com.redclient.orbital.ui.theme.OrbitalTheme

/**
 * The host's only real Activity — everything inside is Compose.
 *
 * The 40 stub Activities declared alongside this one are never launched
 * by the user; they're fired by the framework only when [GuestLauncher]
 * dispatches a guest-launch intent, and their `onCreate` is intercepted
 * by the framework hooks before it actually runs.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            OrbitalTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val nav = rememberNavController()
                    OrbitalNavHost(nav)
                }
            }
        }
    }
}
