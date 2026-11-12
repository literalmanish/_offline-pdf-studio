package com.offlinepdf.studio

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.offlinepdf.studio.ui.navigation.Screen
import com.offlinepdf.studio.ui.screens.compress.CompressScreen
import com.offlinepdf.studio.ui.screens.home.HomeScreen
import com.offlinepdf.studio.ui.screens.merge.MergeScreen
import com.offlinepdf.studio.ui.screens.reorder.ReorderScreen
import com.offlinepdf.studio.ui.screens.split.SplitScreen
import com.offlinepdf.studio.ui.screens.tool.ToolActionScreen
import com.offlinepdf.studio.ui.screens.viewer.ViewerScreen
import com.offlinepdf.studio.ui.theme.OfflinePdfStudioTheme

class MainActivity : ComponentActivity() {

    private var incomingPdfUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleIntent(intent)

        setContent {
            OfflinePdfStudioTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
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
    val startDestination = if (initialIncomingUri != null) Screen.Viewer.route else Screen.Home.route

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                onNavigateTo = { targetScreen ->
                    navController.navigate(targetScreen.route)
                }
            )
        }

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
