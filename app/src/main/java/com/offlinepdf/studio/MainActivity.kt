package com.offlinepdf.studio

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.offlinepdf.studio.ui.components.AppBottomNavigationBar
import com.offlinepdf.studio.ui.navigation.Screen
import com.offlinepdf.studio.ui.screens.compress.CompressScreen
import com.offlinepdf.studio.ui.screens.files.FilesScreen
import com.offlinepdf.studio.ui.screens.home.HomeScreen
import com.offlinepdf.studio.ui.screens.merge.MergeScreen
import com.offlinepdf.studio.ui.screens.reorder.ReorderScreen
import com.offlinepdf.studio.ui.screens.scanner.ScannerScreen
import com.offlinepdf.studio.ui.screens.settings.SettingsScreen
import com.offlinepdf.studio.ui.screens.split.SplitScreen
import com.offlinepdf.studio.ui.screens.tool.ToolActionScreen
import com.offlinepdf.studio.ui.screens.tools.ToolsScreen
import com.offlinepdf.studio.ui.screens.viewer.ViewerScreen
import com.offlinepdf.studio.ui.theme.DarkMainBg
import com.offlinepdf.studio.ui.theme.OfflinePdfStudioTheme

class MainActivity : ComponentActivity() {

    private var incomingPdfUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleIntent(intent)

        setContent {
            OfflinePdfStudioTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkMainBg
                ) {
                    AppNavigation(
                        initialIncomingUri = incomingPdfUri
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
            incomingPdfUri = intent.data
        }
    }
}

@Composable
fun AppNavigation(
    initialIncomingUri: Uri?
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: Screen.Home.route

    val bottomNavRoutes = remember {
        setOf(
            Screen.Home.route,
            Screen.Files.route,
            Screen.Tools.route,
            Screen.Scanner.route,
            Screen.Settings.route
        )
    }

    val showBottomBar = currentRoute in bottomNavRoutes
    val startDestination = if (initialIncomingUri != null) Screen.Viewer.route else Screen.Home.route

    Scaffold(
        containerColor = DarkMainBg,
        bottomBar = {
            if (showBottomBar) {
                AppBottomNavigationBar(
                    currentRoute = currentRoute,
                    onNavigate = { targetRoute ->
                        navController.navigate(targetRoute) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(innerPadding)
        ) {
            // Tab 1: Home
            composable(Screen.Home.route) {
                HomeScreen(
                    onNavigateTo = { targetScreen ->
                        navController.navigate(targetScreen.route)
                    }
                )
            }

            // Tab 2: Files
            composable(Screen.Files.route) {
                FilesScreen(
                    onNavigateTo = { targetScreen ->
                        navController.navigate(targetScreen.route)
                    }
                )
            }

            // Tab 3: Tools (3-column responsive grid)
            composable(Screen.Tools.route) {
                ToolsScreen(
                    onNavigateTo = { targetScreen ->
                        navController.navigate(targetScreen.route)
                    }
                )
            }

            // Tab 4: Scanner
            composable(Screen.Scanner.route) {
                ScannerScreen(
                    onNavigateTo = { targetScreen ->
                        navController.navigate(targetScreen.route)
                    }
                )
            }

            // Tab 5: Settings
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onNavigateTo = { targetScreen ->
                        navController.navigate(targetScreen.route)
                    }
                )
            }

            // Detail Feature Screens
            composable(Screen.Merge.route) {
                MergeScreen(onBack = { navController.popBackStack() })
            }

            composable(Screen.Split.route) {
                SplitScreen(onBack = { navController.popBackStack() })
            }

            composable(Screen.Reorder.route) {
                ReorderScreen(onBack = { navController.popBackStack() })
            }

            composable(Screen.Compress.route) {
                CompressScreen(onBack = { navController.popBackStack() })
            }

            composable(Screen.Viewer.route) {
                ViewerScreen(
                    initialUri = initialIncomingUri,
                    onBack = { navController.popBackStack() }
                )
            }

            composable(
                route = Screen.ToolAction.ROUTE_PATTERN,
                arguments = listOf(navArgument("toolId") { type = NavType.StringType })
            ) { backStackEntry ->
                val toolId = backStackEntry.arguments?.getString("toolId") ?: "rotate"
                ToolActionScreen(
                    toolId = toolId,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
