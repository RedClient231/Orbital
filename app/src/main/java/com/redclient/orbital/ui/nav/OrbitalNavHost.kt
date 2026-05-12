package com.redclient.orbital.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.redclient.orbital.ui.screen.home.HomeScreen
import com.redclient.orbital.ui.screen.importer.ImportScreen

/**
 * Top-level navigation graph. The app has two screens for v1:
 *  - Home: list of imported guests + Launch buttons
 *  - Import: file picker that invokes [ApkImporter]
 */
object OrbitalRoutes {
    const val HOME = "home"
    const val IMPORT = "import"
}

@Composable
fun OrbitalNavHost(nav: NavHostController) {
    NavHost(navController = nav, startDestination = OrbitalRoutes.HOME) {
        composable(OrbitalRoutes.HOME) {
            HomeScreen(onImportClick = { nav.navigate(OrbitalRoutes.IMPORT) })
        }
        composable(OrbitalRoutes.IMPORT) {
            ImportScreen(onDone = { nav.popBackStack() })
        }
    }
}
