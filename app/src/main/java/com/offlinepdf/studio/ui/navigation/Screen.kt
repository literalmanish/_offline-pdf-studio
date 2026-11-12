package com.offlinepdf.studio.ui.navigation

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Merge : Screen("merge")
    data object Split : Screen("split")
    data object Reorder : Screen("reorder")
    data object Compress : Screen("compress")
    data object Viewer : Screen("viewer")
}
