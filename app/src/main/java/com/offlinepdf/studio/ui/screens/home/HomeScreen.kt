package com.offlinepdf.studio.ui.screens.home

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.offlinepdf.studio.core.engine.model.ToolCatalog
import com.offlinepdf.studio.core.util.FileStorageManager
import com.offlinepdf.studio.ui.components.FileItemCard
import com.offlinepdf.studio.ui.components.PrivacyBadge
import com.offlinepdf.studio.ui.components.ThreeColumnToolItem
import com.offlinepdf.studio.ui.navigation.Screen
import com.offlinepdf.studio.ui.theme.*
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateTo: (Screen) -> Unit
) {
    val context = LocalContext.current
    val storageManager = remember { FileStorageManager(context) }
    var recentFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var favoriteFiles by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showProfileDialog by remember { mutableStateOf(false) }

    fun refreshFiles() {
        val docsDir = File(context.filesDir, "pdf_documents")
        val files = docsDir.listFiles { f -> f.extension.equals("pdf", true) }?.sortedByDescending { it.lastModified() } ?: emptyList()
        recentFiles = files.take(8)
    }

    LaunchedEffect(Unit) {
        refreshFiles()
    }

    val quickTools = remember {
        ToolCatalog.allTools.filter {
            it.id in listOf("merge", "split", "compress", "images_to_pdf", "pdf_to_images", "sign")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(PinkAccent),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PictureAsPdf,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Offline PDF",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleLarge,
                            color = TextPrimaryDark
                        )
                    }
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
                    IconButton(onClick = { onNavigateTo(Screen.Tools) }) {
                        Icon(Icons.Default.Search, contentDescription = "Search", tint = MaterialTheme.colorScheme.onBackground)
                    }
                    IconButton(onClick = { onNavigateTo(Screen.Settings) }) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(AppTheme.surface)
                                .border(1.dp, AppTheme.border, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings", tint = PinkAccent, modifier = Modifier.size(18.dp))
                        }
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
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            PrivacyBadge(modifier = Modifier.padding(top = 4.dp))

            // Quick Access Section
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Quick Tools",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimaryDark
                )
                Text(
                    text = "View all (36)",
                    style = MaterialTheme.typography.bodySmall,
                    color = PinkAccent,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { onNavigateTo(Screen.Tools) }
                )
            }

            val onQuickToolClick: (com.offlinepdf.studio.core.engine.model.StudioTool) -> Unit = { tool ->
                when (tool.id) {
                    "merge" -> onNavigateTo(Screen.Merge)
                    "split" -> onNavigateTo(Screen.Split)
                    "compress" -> onNavigateTo(Screen.Compress)
                    else -> onNavigateTo(Screen.ToolAction(tool.id))
                }
            }

            // Quick Tools 3-column row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                quickTools.take(3).forEach { tool ->
                    Box(modifier = Modifier.weight(1f)) {
                        ThreeColumnToolItem(
                            tool = tool,
                            onClick = { onQuickToolClick(tool) }
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                quickTools.drop(3).take(3).forEach { tool ->
                    Box(modifier = Modifier.weight(1f)) {
                        ThreeColumnToolItem(
                            tool = tool,
                            onClick = { onQuickToolClick(tool) }
                        )
                    }
                }
            }

            // Favorites Section
            val favoriteFileList = recentFiles.filter { it.absolutePath in favoriteFiles }
            if (favoriteFileList.isNotEmpty()) {
                Text(
                    text = "Favorites",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimaryDark
                )
                favoriteFileList.forEach { file ->
                    FileItemCard(
                        file = file,
                        isFavorite = true,
                        onOpen = {
                            val uri = storageManager.getShareableUri(file)
                            onNavigateTo(Screen.Viewer)
                        },
                        onShare = {
                            val uri = storageManager.getShareableUri(file)
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/pdf"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share PDF"))
                        },
                        onDelete = {
                            file.delete()
                            refreshFiles()
                        },
                        onToggleFavorite = {
                            favoriteFiles = favoriteFiles - file.absolutePath
                        }
                    )
                }
            }

            // Recent Files Section
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Recent Files",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimaryDark
                )
                if (recentFiles.isNotEmpty()) {
                    Text(
                        text = "View in Files",
                        style = MaterialTheme.typography.bodySmall,
                        color = PinkAccent,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { onNavigateTo(Screen.Files) }
                    )
                }
            }

            if (recentFiles.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkToolCard),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(DarkSecondaryBg),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Outlined.FolderOpen, contentDescription = null, tint = TextSecondaryDark)
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("No recent files yet", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = TextPrimaryDark)
                        Text("Processed and converted PDFs will appear here automatically.", style = MaterialTheme.typography.bodySmall, color = TextSecondaryDark, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            } else {
                recentFiles.forEach { file ->
                    val isFav = file.absolutePath in favoriteFiles
                    FileItemCard(
                        file = file,
                        isFavorite = isFav,
                        onOpen = {
                            val uri = storageManager.getShareableUri(file)
                            onNavigateTo(Screen.Viewer)
                        },
                        onShare = {
                            val uri = storageManager.getShareableUri(file)
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/pdf"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share PDF"))
                        },
                        onDelete = {
                            file.delete()
                            refreshFiles()
                        },
                        onToggleFavorite = {
                            favoriteFiles = if (isFav) favoriteFiles - file.absolutePath else favoriteFiles + file.absolutePath
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(30.dp))
        }

        if (showProfileDialog) {
            AlertDialog(
                onDismissRequest = { showProfileDialog = false },
                containerColor = DarkSecondaryBg,
                titleContentColor = TextPrimaryDark,
                textContentColor = TextSecondaryDark,
                title = { Text("Offline PDF Studio", fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("• Device-First Architecture")
                        Text("• 100% Offline with Zero Network Access")
                        Text("• Version 2.0.0 (Native Android)")
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showProfileDialog = false }) {
                        Text("OK", color = PinkAccent)
                    }
                }
            )
        }
    }
}
