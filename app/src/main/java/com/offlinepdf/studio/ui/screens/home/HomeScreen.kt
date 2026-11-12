package com.offlinepdf.studio.ui.screens.home

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.offlinepdf.studio.core.engine.model.StudioTool
import com.offlinepdf.studio.core.engine.model.ToolCatalog
import com.offlinepdf.studio.core.engine.model.ToolCategory
import com.offlinepdf.studio.ui.components.PrivacyBadge
import com.offlinepdf.studio.ui.components.ToolCard
import com.offlinepdf.studio.ui.navigation.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Offline PDF Studio",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleLarge
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = "32 Tools",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            PrivacyBadge(modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search 32 offline PDF tools...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Category Filter Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = (selectedCategory == null),
                    onClick = { selectedCategory = null },
                    label = { Text("All (32)") }
                )
                ToolCategory.entries.forEach { cat ->
                    val count = ToolCatalog.allTools.count { it.category == cat }
                    FilterChip(
                        selected = (selectedCategory == cat),
                        onClick = { selectedCategory = if (selectedCategory == cat) null else cat },
                        label = { Text("${cat.title} ($count)") }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Tools List
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(filteredTools, key = { it.id }) { tool ->
                    ToolCatalogCard(
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

@Composable
fun ToolCatalogCard(
    tool: StudioTool,
    onClick: () -> Unit
) {
    val (icon, tint) = getToolIconAndTint(tool.iconName, tool.category)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .padding(14.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = tint.copy(alpha = 0.12f),
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(imageVector = icon, contentDescription = tool.title, tint = tint, modifier = Modifier.size(24.dp))
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = tool.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    tool.badge?.let { badge ->
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = tint.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = badge,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = tint
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = tool.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }
}

private fun getToolIconAndTint(name: String, cat: ToolCategory): Pair<ImageVector, Color> {
    val tint = when (cat) {
        ToolCategory.ORGANIZE -> Color(0xFF1976D2)
        ToolCategory.CONVERT -> Color(0xFF00796B)
        ToolCategory.SECURITY -> Color(0xFFD32F2F)
        ToolCategory.BRANDING -> Color(0xFFF57C00)
        ToolCategory.OPTIMIZE -> Color(0xFF7B1FA2)
        ToolCategory.VIEW -> Color(0xFF388E3C)
    }

    val icon = when (name) {
        "CallMerge" -> Icons.Default.CallMerge
        "CallSplit" -> Icons.Default.CallSplit
        "ViewAgenda" -> Icons.Default.ViewAgenda
        "RotateRight" -> Icons.Default.RotateRight
        "SwapVert" -> Icons.Default.SwapVert
        "ContentCopy" -> Icons.Default.ContentCopy
        "NoteAdd" -> Icons.Default.NoteAdd
        "Crop" -> Icons.Default.Crop
        "Dashboard" -> Icons.Default.Dashboard
        "LayersClear" -> Icons.Default.LayersClear
        "PhotoLibrary" -> Icons.Default.PhotoLibrary
        "Image" -> Icons.Default.Image
        "Filter" -> Icons.Default.Filter
        "Article" -> Icons.Default.Article
        "TextFields" -> Icons.Default.TextFields
        "Lock" -> Icons.Default.Lock
        "LockOpen" -> Icons.Default.LockOpen
        "Badge" -> Icons.Default.Badge
        "CleaningServices" -> Icons.Default.CleaningServices
        "Build" -> Icons.Default.Build
        "BrandingWatermark" -> Icons.Default.BrandingWatermark
        "FormatListNumbered" -> Icons.Default.FormatListNumbered
        "Title" -> Icons.Default.Title
        "Approval" -> Icons.Default.Approval
        "Draw" -> Icons.Default.Draw
        "Compress" -> Icons.Default.Compress
        "Contrast" -> Icons.Default.Contrast
        "DarkMode" -> Icons.Default.DarkMode
        "DeleteSweep" -> Icons.Default.DeleteSweep
        "Visibility" -> Icons.Default.Visibility
        "Bookmarks" -> Icons.Default.Bookmarks
        "Compare" -> Icons.Default.Compare
        else -> Icons.Default.PictureAsPdf
    }
    return Pair(icon, tint)
}
