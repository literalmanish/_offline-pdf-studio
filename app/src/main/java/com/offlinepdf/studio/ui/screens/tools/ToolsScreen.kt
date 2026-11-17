package com.offlinepdf.studio.ui.screens.tools

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.offlinepdf.studio.core.engine.model.StudioTool
import com.offlinepdf.studio.core.engine.model.ToolCatalog
import com.offlinepdf.studio.core.engine.model.ToolCategory
import com.offlinepdf.studio.ui.components.ThreeColumnToolItem
import com.offlinepdf.studio.ui.navigation.Screen
import com.offlinepdf.studio.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(
    onNavigateTo: (Screen) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<ToolCategory?>(null) }

    val filteredTools = remember(searchQuery, selectedCategory) {
        ToolCatalog.allTools.filter { tool ->
            val matchesCategory = selectedCategory == null || tool.category == selectedCategory
            val matchesQuery = searchQuery.isBlank() ||
                    tool.title.contains(searchQuery, ignoreCase = true) ||
                    tool.description.contains(searchQuery, ignoreCase = true)
            matchesCategory && matchesQuery
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "PDF Tools",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                actions = {
                    val themeController = LocalThemeController.current
                    IconButton(onClick = { themeController.toggleTheme() }) {
                        Icon(
                            imageVector = if (themeController.isDark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
                            contentDescription = if (themeController.isDark) "Switch to Light Mode" else "Switch to Dark Mode",
                            tint = if (themeController.isDark) PastelYellow else PinkAccent
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 14.dp)
        ) {
            // Search Input
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search tools...", color = TextSecondaryDark) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondaryDark) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear", tint = TextSecondaryDark)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PinkAccent,
                    unfocusedBorderColor = BorderDark,
                    focusedTextColor = TextPrimaryDark,
                    unfocusedTextColor = TextPrimaryDark,
                    focusedContainerColor = DarkToolCard,
                    unfocusedContainerColor = DarkToolCard
                ),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Category Horizontal Tabs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = (selectedCategory == null),
                    onClick = { selectedCategory = null },
                    label = { Text("All (${ToolCatalog.allTools.size})") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = PinkAccent,
                        selectedLabelColor = DarkMainBg,
                        containerColor = DarkSecondaryBg,
                        labelColor = TextSecondaryDark
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = (selectedCategory == null),
                        borderColor = BorderDark,
                        selectedBorderColor = PinkAccent
                    )
                )

                ToolCategory.entries.forEach { cat ->
                    val count = ToolCatalog.allTools.count { it.category == cat }
                    val isSelected = selectedCategory == cat
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedCategory = if (isSelected) null else cat },
                        label = { Text("${cat.title} ($count)") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = PinkAccent,
                            selectedLabelColor = DarkMainBg,
                            containerColor = DarkSecondaryBg,
                            labelColor = TextSecondaryDark
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = isSelected,
                            borderColor = BorderDark,
                            selectedBorderColor = PinkAccent
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 3-Column Responsive Grid
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(filteredTools, key = { it.id }) { tool ->
                    ThreeColumnToolItem(
                        tool = tool,
                        onClick = {
                            when (tool.id) {
                                "merge" -> onNavigateTo(Screen.Merge)
                                "split" -> onNavigateTo(Screen.Split)
                                "reorder" -> onNavigateTo(Screen.Reorder)
                                "compress" -> onNavigateTo(Screen.Compress)
                                "viewer" -> onNavigateTo(Screen.Viewer)
                                else -> onNavigateTo(Screen.ToolAction(tool.id))
                            }
                        }
                    )
                }
            }
        }
    }
}
