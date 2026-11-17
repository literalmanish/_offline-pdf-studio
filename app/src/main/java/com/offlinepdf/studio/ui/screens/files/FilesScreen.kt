package com.offlinepdf.studio.ui.screens.files

import android.content.Intent
import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.offlinepdf.studio.core.util.FileStorageManager
import com.offlinepdf.studio.ui.components.FileItemCard
import com.offlinepdf.studio.ui.components.StorageDashboardCard
import com.offlinepdf.studio.ui.navigation.Screen
import com.offlinepdf.studio.ui.theme.*
import java.io.File

enum class FileFilterType {
    ALL,
    DOWNLOADS,
    PROCESSED,
    FAVORITES,
    RECYCLE_BIN
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    onNavigateTo: (Screen) -> Unit
) {
    val context = LocalContext.current
    val storageManager = remember { FileStorageManager(context) }

    var selectedFilter by remember { mutableStateOf(FileFilterType.ALL) }
    var allFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var favorites by remember { mutableStateOf<Set<String>>(emptySet()) }
    var recycleBin by remember { mutableStateOf<List<File>>(emptyList()) }
    var renameTargetFile by remember { mutableStateOf<File?>(null) }
    var newFileName by remember { mutableStateOf("") }

    fun loadFiles() {
        val appDocs = File(context.filesDir, "pdf_documents").apply { if (!exists()) mkdirs() }
        val processed = appDocs.listFiles { f -> f.extension.equals("pdf", true) }?.toList() ?: emptyList()

        val downloadsDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "OfflinePDFStudio")
        val downloads = if (downloadsDir.exists()) {
            downloadsDir.listFiles { f -> f.extension.equals("pdf", true) }?.toList() ?: emptyList()
        } else emptyList()

        val combined = (processed + downloads).distinctBy { it.absolutePath }
        allFiles = combined.filter { it !in recycleBin }
    }

    LaunchedEffect(Unit) {
        loadFiles()
    }

    val downloadsCount = remember(allFiles) {
        allFiles.count { it.absolutePath.contains("Download", ignoreCase = true) }
    }
    val processedCount = remember(allFiles) {
        allFiles.count { it.absolutePath.contains("pdf_documents") }
    }
    val favoritesCount = remember(allFiles, favorites) {
        allFiles.count { it.absolutePath in favorites }
    }

    val displayFiles = remember(selectedFilter, allFiles, favorites, recycleBin) {
        when (selectedFilter) {
            FileFilterType.ALL -> allFiles
            FileFilterType.DOWNLOADS -> allFiles.filter { it.absolutePath.contains("Download", ignoreCase = true) }
            FileFilterType.PROCESSED -> allFiles.filter { it.absolutePath.contains("pdf_documents") }
            FileFilterType.FAVORITES -> allFiles.filter { it.absolutePath in favorites }
            FileFilterType.RECYCLE_BIN -> recycleBin
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Files & Storage",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimaryDark
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkMainBg)
            )
        },
        containerColor = DarkMainBg
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            // Dashboard Storage Overview
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = (selectedFilter == FileFilterType.ALL),
                    onClick = { selectedFilter = FileFilterType.ALL },
                    label = { Text("All (${allFiles.size})") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = PinkAccent,
                        selectedLabelColor = DarkMainBg,
                        containerColor = DarkSecondaryBg,
                        labelColor = TextSecondaryDark
                    )
                )
                FilterChip(
                    selected = (selectedFilter == FileFilterType.DOWNLOADS),
                    onClick = { selectedFilter = FileFilterType.DOWNLOADS },
                    label = { Text("Downloads ($downloadsCount)") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = PinkAccent,
                        selectedLabelColor = DarkMainBg,
                        containerColor = DarkSecondaryBg,
                        labelColor = TextSecondaryDark
                    )
                )
                FilterChip(
                    selected = (selectedFilter == FileFilterType.PROCESSED),
                    onClick = { selectedFilter = FileFilterType.PROCESSED },
                    label = { Text("Processed ($processedCount)") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = PinkAccent,
                        selectedLabelColor = DarkMainBg,
                        containerColor = DarkSecondaryBg,
                        labelColor = TextSecondaryDark
                    )
                )
                FilterChip(
                    selected = (selectedFilter == FileFilterType.FAVORITES),
                    onClick = { selectedFilter = FileFilterType.FAVORITES },
                    label = { Text("Favorites ($favoritesCount)") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = PinkAccent,
                        selectedLabelColor = DarkMainBg,
                        containerColor = DarkSecondaryBg,
                        labelColor = TextSecondaryDark
                    )
                )
                FilterChip(
                    selected = (selectedFilter == FileFilterType.RECYCLE_BIN),
                    onClick = { selectedFilter = FileFilterType.RECYCLE_BIN },
                    label = { Text("Bin (${recycleBin.size})") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = PinkAccent,
                        selectedLabelColor = DarkMainBg,
                        containerColor = DarkSecondaryBg,
                        labelColor = TextSecondaryDark
                    )
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // File Listing
            if (displayFiles.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.Folder, contentDescription = null, tint = TextSecondaryDark, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("No files in this category", color = TextSecondaryDark, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(displayFiles, key = { it.absolutePath }) { file ->
                        if (selectedFilter == FileFilterType.RECYCLE_BIN) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = DarkToolCard),
                                border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .padding(12.dp)
                                        .fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(file.name, color = TextPrimaryDark, maxLines = 1)
                                        Text("${file.length() / 1024} KB", color = TextSecondaryDark, style = MaterialTheme.typography.bodySmall)
                                    }
                                    TextButton(onClick = {
                                        recycleBin = recycleBin - file
                                        allFiles = allFiles + file
                                    }) {
                                        Text("Restore", color = PinkAccent)
                                    }
                                    IconButton(onClick = {
                                        file.delete()
                                        recycleBin = recycleBin - file
                                    }) {
                                        Icon(Icons.Default.DeleteForever, contentDescription = "Permanent Delete", tint = StatusError)
                                    }
                                }
                            }
                        } else {
                            val isFav = file.absolutePath in favorites
                            FileItemCard(
                                file = file,
                                isFavorite = isFav,
                                onOpen = { onNavigateTo(Screen.Viewer) },
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
                                    recycleBin = recycleBin + file
                                    allFiles = allFiles - file
                                    Toast.makeText(context, "Moved to Recycle Bin", Toast.LENGTH_SHORT).show()
                                },
                                onToggleFavorite = {
                                    favorites = if (isFav) favorites - file.absolutePath else favorites + file.absolutePath
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
