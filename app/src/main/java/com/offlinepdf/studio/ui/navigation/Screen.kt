package com.offlinepdf.studio.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String) {
    // 5 Main Bottom Navigation Tabs
    data object Home : Screen("home")
    data object Files : Screen("files")
    data object Tools : Screen("tools")
    data object Scanner : Screen("scanner")
    data object Settings : Screen("settings")

    // Tool Detail Screens
    data object Merge : Screen("merge")
    data object Split : Screen("split")
    data object Reorder : Screen("reorder")
    data object Compress : Screen("compress")
    data object Viewer : Screen("viewer")
    data class ToolAction(val toolId: String) : Screen("tool/$toolId") {
        companion object {
            const val ROUTE_PATTERN = "tool/{toolId}"
        }
    }
}

enum class BottomNavItem(
    val route: String,
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    HOME("home", "Home", Icons.Filled.Home, Icons.Outlined.Home),
    FILES("files", "Files", Icons.Filled.Folder, Icons.Outlined.Folder),
    TOOLS("tools", "Tools", Icons.Filled.GridView, Icons.Outlined.GridView),
    SCANNER("scanner", "Scanner", Icons.Filled.DocumentScanner, Icons.Outlined.DocumentScanner),
    SETTINGS("settings", "Settings", Icons.Filled.Settings, Icons.Outlined.Settings)
}
