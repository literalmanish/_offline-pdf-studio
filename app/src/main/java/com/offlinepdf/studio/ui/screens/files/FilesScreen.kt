package com.offlinepdf.studio.ui.screens.files

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlinepdf.studio.core.util.FileStorageManager
import com.offlinepdf.studio.core.util.PdfStorageScanner
import com.offlinepdf.studio.core.util.StorageFolder
import com.offlinepdf.studio.ui.components.FileItemCard
import com.offlinepdf.studio.ui.navigation.Screen
import com.offlinepdf.studio.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed class FilesNavigationState {
    data object RootOverview : FilesNavigationState()
    data object InternalFolderList : FilesNavigationState()
    data class FileList(val title: String, val files: List<File>, val isBin: Boolean = false) : FilesNavigationState()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    onNavigateTo: (Screen) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val storageManager = remember { FileStorageManager(context) }
    val themeController = LocalThemeController.current
    val isDark = themeController.isDark

    // Theme responsive colors
    val bgColor = if (isDark) DarkMainBg else LightMainBg
    val cardBg = if (isDark) DarkToolCard else LightToolCard
    val iconContainerBg = if (isDark) DarkBottomNav else BorderLight
    val textPrimary = if (isDark) TextPrimaryDark else TextPrimaryLight
    val textSecondary = if (isDark) TextSecondaryDark else TextSecondaryLight
    val borderColor = if (isDark) BorderDark else BorderLight

    var processedFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var downloadFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var internalFolders by remember { mutableStateOf<List<StorageFolder>>(emptyList()) }
    var recycleBinFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var favorites by remember { mutableStateOf<Set<String>>(emptySet()) }

    var navState by remember { mutableStateOf<FilesNavigationState>(FilesNavigationState.RootOverview) }
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var hasPermission by remember { mutableStateOf(PdfStorageScanner.hasAllFilesPermission()) }

    val recycleBinDir = remember {
        File(context.filesDir, "recycle_bin").apply { if (!exists()) mkdirs() }
    }
    val processedDir = remember {
        File(context.filesDir, "pdf_documents").apply { if (!exists()) mkdirs() }
    }

    fun loadAllFiles() {
        coroutineScope.launch {
            hasPermission = PdfStorageScanner.hasAllFilesPermission()

            withContext(Dispatchers.IO) {
                // 1. Processed files
                val processed = processedDir.listFiles { f -> f.extension.equals("pdf", true) }
                    ?.sortedByDescending { it.lastModified() } ?: emptyList()

                // 2. All Downloads PDFs (scans entire Downloads tree + media store)
                val downloads = PdfStorageScanner.getDownloadsPdfs(context)

                // 3. Scan all internal storage folders containing PDFs
                val folders = PdfStorageScanner.getAllInternalStorageFolders(context)

                // 4. Recycle bin files
                val bin = recycleBinDir.listFiles { f -> f.extension.equals("pdf", true) }
                    ?.sortedByDescending { it.lastModified() } ?: emptyList()

                withContext(Dispatchers.Main) {
                    processedFiles = processed
                    downloadFiles = downloads
                    internalFolders = folders
                    recycleBinFiles = bin
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        loadAllFiles()
    }

    val totalInternalPdfCount = remember(internalFolders, processedFiles, downloadFiles) {
        val allUnique = (internalFolders.flatMap { it.files } + processedFiles + downloadFiles).distinctBy { it.absolutePath }
        allUnique.size
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    when (val state = navState) {
                        is FilesNavigationState.RootOverview -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(PinkAccent),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PictureAsPdf,
                                        contentDescription = "App Logo",
                                        tint = Color.Black,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Text(
                                    text = "Offline PDF Studio",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleLarge,
                                    color = textPrimary
                                )
                            }
                        }
                        is FilesNavigationState.InternalFolderList -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                IconButton(onClick = { navState = FilesNavigationState.RootOverview }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = textPrimary)
                                }
                                Text(
                                    text = "Internal Folders",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleLarge,
                                    color = textPrimary
                                )
                            }
                        }
                        is FilesNavigationState.FileList -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                IconButton(onClick = {
                                    navState = if (state.title == "All Internal PDFs" || state.title.contains("Folder")) {
                                        FilesNavigationState.InternalFolderList
                                    } else {
                                        FilesNavigationState.RootOverview
                                    }
                                }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = textPrimary)
                                }
                                Text(
                                    text = state.title,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleLarge,
                                    color = textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                },
                actions = {
                    // Header Dark/Light Mode Toggle Button
                    IconButton(onClick = { themeController.toggleTheme() }) {
                        Icon(
                            imageVector = if (isDark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
                            contentDescription = if (isDark) "Switch to Light Mode" else "Switch to Dark Mode",
                            tint = if (isDark) PastelYellow else PinkAccent
                        )
                    }

                    // Search Button
                    IconButton(onClick = { isSearchActive = !isSearchActive }) {
                        Icon(
                            imageVector = if (isSearchActive) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = "Search",
                            tint = textPrimary
                        )
                    }

                    // Profile / Settings Avatar
                    IconButton(onClick = { onNavigateTo(Screen.Settings) }) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(iconContainerBg),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = "Profile",
                                tint = PinkAccent,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = bgColor)
            )
        },
        containerColor = bgColor
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            // Live Search Input when search is active
            AnimatedVisibility(visible = isSearchActive) {
                Column(modifier = Modifier.padding(bottom = 12.dp)) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search PDF files across storage...", color = textSecondary) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = textSecondary) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear", tint = textSecondary)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PinkAccent,
                            unfocusedBorderColor = borderColor,
                            focusedTextColor = textPrimary,
                            unfocusedTextColor = textPrimary,
                            cursorColor = PinkAccent
                        ),
                        singleLine = true
                    )
                }
            }

            // Storage Permission Banner if not yet granted on Android 11+
            if (!hasPermission) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .clickable { PdfStorageScanner.requestAllFilesPermission(context) },
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = cardBg),
                    border = androidx.compose.foundation.BorderStroke(1.dp, PinkAccent.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(PinkAccent.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Outlined.FolderSpecial, contentDescription = null, tint = PinkAccent, modifier = Modifier.size(20.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Full Storage Access", fontWeight = FontWeight.SemiBold, color = textPrimary, style = MaterialTheme.typography.bodyMedium)
                            Text("Tap to grant access to view all folders and PDFs on device", color = textSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { PdfStorageScanner.requestAllFilesPermission(context) }) {
                            Text("Grant", color = PinkAccent, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            when (val state = navState) {
                // 1. Root My Storage Overview
                is FilesNavigationState.RootOverview -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        Text(
                            text = "My storage",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = textPrimary,
                            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            // A. Internal Storage -> Opens folders list
                            StorageRowItem(
                                title = "Internal Storage",
                                count = totalInternalPdfCount,
                                icon = Icons.Outlined.PhoneAndroid,
                                containerBg = iconContainerBg,
                                textPrimary = textPrimary,
                                textSecondary = textSecondary,
                                onClick = {
                                    navState = FilesNavigationState.InternalFolderList
                                }
                            )

                            // B. Downloads -> Opens all downloaded PDFs
                            StorageRowItem(
                                title = "Downloads",
                                count = downloadFiles.size,
                                icon = Icons.Outlined.Download,
                                containerBg = iconContainerBg,
                                textPrimary = textPrimary,
                                textSecondary = textSecondary,
                                onClick = {
                                    navState = FilesNavigationState.FileList("Downloads", downloadFiles)
                                }
                            )

                            // C. Processed files -> Opens processed documents
                            StorageRowItem(
                                title = "Processed files",
                                count = processedFiles.size,
                                icon = Icons.Outlined.CheckCircle,
                                containerBg = iconContainerBg,
                                textPrimary = textPrimary,
                                textSecondary = textSecondary,
                                onClick = {
                                    navState = FilesNavigationState.FileList("Processed files", processedFiles)
                                }
                            )
                        }

                        // Others Section
                        Text(
                            text = "Others",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = textPrimary,
                            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
                        )

                        StorageRowItem(
                            title = "Bin",
                            count = recycleBinFiles.size,
                            icon = Icons.Outlined.Delete,
                            containerBg = iconContainerBg,
                            textPrimary = textPrimary,
                            textSecondary = textSecondary,
                            onClick = {
                                navState = FilesNavigationState.FileList("Bin", recycleBinFiles, isBin = true)
                            }
                        )

                        Spacer(modifier = Modifier.height(28.dp))
                    }
                }

                // 2. Internal Storage Folder Listing
                is FilesNavigationState.InternalFolderList -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${internalFolders.size} Folders containing PDFs",
                                style = MaterialTheme.typography.bodyMedium,
                                color = textSecondary
                            )
                            TextButton(onClick = {
                                val allFiles = internalFolders.flatMap { it.files }.distinctBy { it.absolutePath }
                                navState = FilesNavigationState.FileList("All Internal PDFs", allFiles)
                            }) {
                                Text("View All ($totalInternalPdfCount)", color = PinkAccent, fontWeight = FontWeight.Bold)
                            }
                        }

                        if (internalFolders.isEmpty()) {
                            Box(
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Outlined.FolderOpen, contentDescription = null, tint = textSecondary, modifier = Modifier.size(54.dp))
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("No folders found with PDF files", color = textSecondary)
                                }
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(bottom = 24.dp)
                            ) {
                                items(internalFolders, key = { it.id }) { folder ->
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                navState = FilesNavigationState.FileList(folder.name, folder.files)
                                            },
                                        shape = RoundedCornerShape(14.dp),
                                        colors = CardDefaults.cardColors(containerColor = cardBg),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .padding(14.dp)
                                                .fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(46.dp)
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .background(iconContainerBg),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.Folder,
                                                    contentDescription = folder.name,
                                                    tint = PinkAccent,
                                                    modifier = Modifier.size(24.dp)
                                                )
                                            }

                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = folder.name,
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = textPrimary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = folder.path,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = textSecondary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }

                                            Surface(
                                                shape = RoundedCornerShape(10.dp),
                                                color = iconContainerBg
                                            ) {
                                                Text(
                                                    text = "${folder.files.size} PDFs",
                                                    color = textPrimary,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.SemiBold,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                )
                                            }

                                            Icon(
                                                imageVector = Icons.Default.ChevronRight,
                                                contentDescription = null,
                                                tint = textSecondary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 3. File List View (Downloads, Processed, or Folder detail)
                is FilesNavigationState.FileList -> {
                    val filteredFiles = remember(state.files, searchQuery) {
                        if (searchQuery.isBlank()) {
                            state.files
                        } else {
                            state.files.filter { it.name.contains(searchQuery, ignoreCase = true) }
                        }
                    }

                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${filteredFiles.size} document${if (filteredFiles.size == 1) "" else "s"}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = textSecondary
                            )

                            if (state.isBin && recycleBinFiles.isNotEmpty()) {
                                TextButton(onClick = {
                                    recycleBinFiles.forEach { it.delete() }
                                    loadAllFiles()
                                    Toast.makeText(context, "Bin emptied", Toast.LENGTH_SHORT).show()
                                }) {
                                    Text("Empty Bin", color = StatusError, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        if (filteredFiles.isEmpty()) {
                            Box(
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = if (state.isBin) Icons.Outlined.Delete else Icons.Outlined.FolderOpen,
                                        contentDescription = null,
                                        tint = textSecondary,
                                        modifier = Modifier.size(54.dp)
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = if (state.isBin) "Recycle Bin is empty" else "No PDF files in this folder",
                                        color = textSecondary,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(bottom = 24.dp)
                            ) {
                                items(filteredFiles, key = { it.absolutePath }) { file ->
                                    if (state.isBin) {
                                        Card(
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(14.dp),
                                            colors = CardDefaults.cardColors(containerColor = cardBg),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .padding(14.dp)
                                                    .fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(40.dp)
                                                        .clip(RoundedCornerShape(10.dp))
                                                        .background(StatusError.copy(alpha = 0.15f)),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(Icons.Default.Delete, contentDescription = null, tint = StatusError, modifier = Modifier.size(20.dp))
                                                }

                                                Spacer(modifier = Modifier.width(12.dp))

                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(file.name, color = textPrimary, maxLines = 1, fontWeight = FontWeight.Medium)
                                                    Text("${file.length() / 1024} KB", color = textSecondary, style = MaterialTheme.typography.bodySmall)
                                                }

                                                TextButton(onClick = {
                                                    val restoredDest = File(processedDir, file.name)
                                                    file.renameTo(restoredDest)
                                                    loadAllFiles()
                                                    Toast.makeText(context, "Restored to Processed files", Toast.LENGTH_SHORT).show()
                                                }) {
                                                    Text("Restore", color = PinkAccent, fontWeight = FontWeight.Bold)
                                                }

                                                IconButton(onClick = {
                                                    file.delete()
                                                    loadAllFiles()
                                                    Toast.makeText(context, "Deleted permanently", Toast.LENGTH_SHORT).show()
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
                                                val dest = File(recycleBinDir, file.name)
                                                file.renameTo(dest)
                                                loadAllFiles()
                                                Toast.makeText(context, "Moved to Bin", Toast.LENGTH_SHORT).show()
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
        }
    }
}

@Composable
private fun StorageRowItem(
    title: String,
    count: Int,
    icon: ImageVector,
    containerBg: Color,
    textPrimary: Color,
    textSecondary: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 56 dp rounded container with 26 dp icon
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(containerBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = PinkAccent,
                modifier = Modifier.size(26.dp)
            )
        }

        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = textPrimary,
            modifier = Modifier.weight(1f)
        )

        Text(
            text = "$count",
            style = MaterialTheme.typography.bodyMedium,
            color = textSecondary,
            fontWeight = FontWeight.Normal
        )

        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = textSecondary,
            modifier = Modifier.size(20.dp)
        )
    }
}
