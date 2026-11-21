package com.offlinepdf.studio.ui.screens.files

import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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

    // Exact user palette mapped colors
    val bgColor = AppTheme.bg
    val surfaceColor = AppTheme.surface
    val surface2Color = AppTheme.surface2
    val headingColor = AppTheme.heading
    val textColor = AppTheme.text
    val mutedColor = AppTheme.muted
    val borderColor = AppTheme.border
    val primaryColor = AppTheme.primary
    val accentColor = AppTheme.accent
    val errorColor = AppTheme.error

    var processedFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var downloadFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var internalFolders by remember { mutableStateOf<List<StorageFolder>>(emptyList()) }
    var recycleBinFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var favorites by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isLoading by remember { mutableStateOf(false) }

    var navState by remember { mutableStateOf<FilesNavigationState>(FilesNavigationState.RootOverview) }
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var hasPermission by remember { mutableStateOf(PdfStorageScanner.hasAllFilesPermission(context)) }
    var showPermissionRationale by remember { mutableStateOf(false) }

    val recycleBinDir = remember {
        File(context.filesDir, "recycle_bin").apply { if (!exists()) mkdirs() }
    }
    val processedDir = remember {
        File(context.filesDir, "pdf_documents").apply { if (!exists()) mkdirs() }
    }

    fun loadAllFiles(invalidateCache: Boolean = false) {
        if (invalidateCache) {
            PdfStorageScanner.invalidateCache()
        }
        coroutineScope.launch {
            isLoading = true
            hasPermission = PdfStorageScanner.hasAllFilesPermission(context)

            withContext(Dispatchers.IO) {
                // 1. Processed documents in app sandbox (instant)
                val processed = processedDir.listFiles { f -> f.extension.equals("pdf", true) }
                    ?.sortedByDescending { it.lastModified() } ?: emptyList()

                // 2. High-speed indexed Downloads discovery
                val downloads = PdfStorageScanner.getDownloadsPdfs(context)

                // 3. High-speed indexed Internal Storage discovery
                val folders = PdfStorageScanner.getAllInternalStorageFolders(context)

                // 4. Recycle bin
                val bin = recycleBinDir.listFiles { f -> f.extension.equals("pdf", true) }
                    ?.sortedByDescending { it.lastModified() } ?: emptyList()

                withContext(Dispatchers.Main) {
                    processedFiles = processed
                    downloadFiles = downloads
                    internalFolders = folders
                    recycleBinFiles = bin
                    isLoading = false
                }
            }
        }
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val (name, _) = storageManager.getUriMetadata(uri)
                    val target = storageManager.createOutputFile(name)
                    context.contentResolver.openInputStream(uri)?.use { inStream ->
                        target.outputStream().use { outStream ->
                            inStream.copyTo(outStream)
                        }
                    }
                    storageManager.releaseReservation(target)
                    withContext(Dispatchers.Main) {
                        loadAllFiles(invalidateCache = true)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    LaunchedEffect(Unit) {
        loadAllFiles(invalidateCache = false)
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
                                        .background(primaryColor),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PictureAsPdf,
                                        contentDescription = "App Logo",
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Text(
                                    text = "Offline PDF Studio",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleLarge,
                                    color = headingColor
                                )
                            }
                        }
                        is FilesNavigationState.InternalFolderList -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                IconButton(onClick = { navState = FilesNavigationState.RootOverview }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = headingColor)
                                }
                                Text(
                                    text = "Internal Folders",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleLarge,
                                    color = headingColor
                                )
                            }
                        }
                        is FilesNavigationState.FileList -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                IconButton(onClick = {
                                    navState = if (state.title == "All Internal PDFs" || state.title.contains("Folder") || state.title.contains("Documents")) {
                                        FilesNavigationState.InternalFolderList
                                    } else {
                                        FilesNavigationState.RootOverview
                                    }
                                }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = headingColor)
                                }
                                Text(
                                    text = state.title,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleLarge,
                                    color = headingColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                },
                actions = {
                    // Header Dark/Light Mode Instant Toggle Button
                    IconButton(onClick = { themeController.toggleTheme() }) {
                        Icon(
                            imageVector = if (isDark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
                            contentDescription = if (isDark) "Switch to Light Mode" else "Switch to Dark Mode",
                            tint = if (isDark) accentColor else primaryColor
                        )
                    }

                    // Search Button
                    IconButton(onClick = { isSearchActive = !isSearchActive }) {
                        Icon(
                            imageVector = if (isSearchActive) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = "Search",
                            tint = headingColor
                        )
                    }

                    // Profile / Settings Button
                    IconButton(onClick = { onNavigateTo(Screen.Settings) }) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(surface2Color)
                                .border(1.dp, borderColor, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = "Profile",
                                tint = primaryColor,
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
            // Live Search Input when active
            AnimatedVisibility(visible = isSearchActive) {
                Column(modifier = Modifier.padding(bottom = 12.dp)) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search PDF documents...", color = mutedColor) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = mutedColor) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear", tint = mutedColor)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = primaryColor,
                            unfocusedBorderColor = borderColor,
                            focusedTextColor = headingColor,
                            unfocusedTextColor = headingColor,
                            cursorColor = primaryColor
                        ),
                        singleLine = true
                    )
                }
            }

            // Storage Access Banner differentiated by Android API level
            val currentSdk = PdfStorageScanner.sdkIntProvider()
            if (!hasPermission && currentSdk >= Build.VERSION_CODES.R) {
                // Android 11+ (API 30+) All Files Access Settings Flow
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .clickable { showPermissionRationale = true },
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = surfaceColor),
                    border = androidx.compose.foundation.BorderStroke(1.dp, primaryColor.copy(alpha = 0.5f))
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
                                .background(primaryColor.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Outlined.FolderSpecial, contentDescription = null, tint = primaryColor, modifier = Modifier.size(20.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Storage Access", fontWeight = FontWeight.SemiBold, color = headingColor, style = MaterialTheme.typography.bodyMedium)
                            Text("Tap to view why all-folder access is needed and how to grant it", color = textColor, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { showPermissionRationale = true }) {
                            Text("Grant", color = primaryColor, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else if (!hasPermission && currentSdk == Build.VERSION_CODES.Q) {
                // Android 10 (API 29) Scoped Storage: Limited access state, SAF import enabled
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = surfaceColor),
                    border = androidx.compose.foundation.BorderStroke(1.dp, primaryColor.copy(alpha = 0.35f))
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
                                .background(primaryColor.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Outlined.FolderSpecial, contentDescription = null, tint = primaryColor, modifier = Modifier.size(20.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Scoped Storage (Android 10)", fontWeight = FontWeight.SemiBold, color = headingColor, style = MaterialTheme.typography.bodyMedium)
                            Text("Running in limited-access mode. Select any PDF to import via system picker", color = textColor, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { openDocumentLauncher.launch(arrayOf("application/pdf")) }) {
                            Text("Select PDF", color = primaryColor, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else if (!hasPermission && currentSdk <= Build.VERSION_CODES.P) {
                // Android 8.0 - 9.0 (API 26-28) Legacy Storage Flow
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .clickable { PdfStorageScanner.requestAllFilesPermission(context) },
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = surfaceColor),
                    border = androidx.compose.foundation.BorderStroke(1.dp, primaryColor.copy(alpha = 0.5f))
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
                                .background(primaryColor.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Outlined.FolderSpecial, contentDescription = null, tint = primaryColor, modifier = Modifier.size(20.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Storage Permission", fontWeight = FontWeight.SemiBold, color = headingColor, style = MaterialTheme.typography.bodyMedium)
                            Text("Grant read storage permission to scan device documents", color = textColor, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { PdfStorageScanner.requestAllFilesPermission(context) }) {
                            Text("Grant", color = primaryColor, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            if (showPermissionRationale) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showPermissionRationale = false },
                    icon = {
                        Icon(Icons.Outlined.FolderSpecial, contentDescription = null, tint = primaryColor)
                    },
                    title = {
                        Text(
                            text = "All Files Access Required",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = headingColor
                        )
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "Offline PDF Studio operates 100% offline and on-device without internet access. " +
                                        "To automatically discover, organize, and manage your PDF documents across all device folders " +
                                        "(Downloads, Documents, WhatsApp, etc.), Android requires All Files Access.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = textColor
                            )
                            Text(
                                text = "Your files are never transmitted or shared externally. You can grant or revoke this access at any time in system settings.",
                                style = MaterialTheme.typography.bodySmall,
                                color = textColor.copy(alpha = 0.8f)
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showPermissionRationale = false
                                PdfStorageScanner.requestAllFilesPermission(context)
                            }
                        ) {
                            Text("Continue to Settings", color = primaryColor, fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showPermissionRationale = false }) {
                            Text("Not Now", color = textColor)
                        }
                    },
                    containerColor = surfaceColor,
                    tonalElevation = 6.dp
                )
            }

            when (val state = navState) {
                // 1. Root "My storage" screen
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
                            color = headingColor,
                            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            // A. Internal Storage -> Instant folder browser
                            StorageRowItem(
                                title = "Internal Storage",
                                count = totalInternalPdfCount,
                                icon = Icons.Outlined.PhoneAndroid,
                                containerBg = surface2Color,
                                primaryColor = primaryColor,
                                textPrimary = headingColor,
                                textSecondary = mutedColor,
                                onClick = {
                                    navState = FilesNavigationState.InternalFolderList
                                }
                            )

                            // B. Downloads -> All PDF files in downloads
                            StorageRowItem(
                                title = "Downloads",
                                count = downloadFiles.size,
                                icon = Icons.Outlined.Download,
                                containerBg = surface2Color,
                                primaryColor = primaryColor,
                                textPrimary = headingColor,
                                textSecondary = mutedColor,
                                onClick = {
                                    navState = FilesNavigationState.FileList("Downloads", downloadFiles)
                                }
                            )

                            // C. Processed files -> Output documents from PDF studio
                            StorageRowItem(
                                title = "Processed files",
                                count = processedFiles.size,
                                icon = Icons.Outlined.CheckCircle,
                                containerBg = surface2Color,
                                primaryColor = primaryColor,
                                textPrimary = headingColor,
                                textSecondary = mutedColor,
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
                            color = headingColor,
                            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
                        )

                        StorageRowItem(
                            title = "Bin",
                            count = recycleBinFiles.size,
                            icon = Icons.Outlined.Delete,
                            containerBg = surface2Color,
                            primaryColor = errorColor,
                            textPrimary = headingColor,
                            textSecondary = mutedColor,
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
                                color = mutedColor
                            )
                            TextButton(onClick = {
                                val allFiles = internalFolders.flatMap { it.files }.distinctBy { it.absolutePath }
                                navState = FilesNavigationState.FileList("All Internal PDFs", allFiles)
                            }) {
                                Text("View All ($totalInternalPdfCount)", color = primaryColor, fontWeight = FontWeight.Bold)
                            }
                        }

                        if (internalFolders.isEmpty()) {
                            Box(
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Outlined.FolderOpen, contentDescription = null, tint = mutedColor, modifier = Modifier.size(54.dp))
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("No folders found with PDF files", color = mutedColor)
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
                                        colors = CardDefaults.cardColors(containerColor = surfaceColor),
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
                                                    .background(surface2Color),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.Folder,
                                                    contentDescription = folder.name,
                                                    tint = primaryColor,
                                                    modifier = Modifier.size(24.dp)
                                                )
                                            }

                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = folder.name,
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = headingColor,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = folder.path,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = mutedColor,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }

                                            Surface(
                                                shape = RoundedCornerShape(10.dp),
                                                color = surface2Color
                                            ) {
                                                Text(
                                                    text = "${folder.files.size} PDFs",
                                                    color = headingColor,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.SemiBold,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                )
                                            }

                                            Icon(
                                                imageVector = Icons.Default.ChevronRight,
                                                contentDescription = null,
                                                tint = mutedColor,
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
                                color = mutedColor
                            )

                            if (state.isBin && recycleBinFiles.isNotEmpty()) {
                                TextButton(onClick = {
                                    recycleBinFiles.forEach { it.delete() }
                                    loadAllFiles(invalidateCache = true)
                                    Toast.makeText(context, "Bin emptied", Toast.LENGTH_SHORT).show()
                                }) {
                                    Text("Empty Bin", color = errorColor, fontWeight = FontWeight.Bold)
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
                                        tint = mutedColor,
                                        modifier = Modifier.size(54.dp)
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = if (state.isBin) "Recycle Bin is empty" else "No PDF files in this folder",
                                        color = mutedColor,
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
                                            colors = CardDefaults.cardColors(containerColor = surfaceColor),
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
                                                        .background(errorColor.copy(alpha = 0.15f)),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(Icons.Default.Delete, contentDescription = null, tint = errorColor, modifier = Modifier.size(20.dp))
                                                }

                                                Spacer(modifier = Modifier.width(12.dp))

                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(file.name, color = headingColor, maxLines = 1, fontWeight = FontWeight.Medium)
                                                    Text("${file.length() / 1024} KB", color = mutedColor, style = MaterialTheme.typography.bodySmall)
                                                }

                                                TextButton(onClick = {
                                                    val restoredDest = File(processedDir, file.name)
                                                    file.renameTo(restoredDest)
                                                    loadAllFiles(invalidateCache = true)
                                                    Toast.makeText(context, "Restored to Processed files", Toast.LENGTH_SHORT).show()
                                                }) {
                                                    Text("Restore", color = primaryColor, fontWeight = FontWeight.Bold)
                                                }

                                                IconButton(onClick = {
                                                    file.delete()
                                                    loadAllFiles(invalidateCache = true)
                                                    Toast.makeText(context, "Deleted permanently", Toast.LENGTH_SHORT).show()
                                                }) {
                                                    Icon(Icons.Default.DeleteForever, contentDescription = "Permanent Delete", tint = errorColor)
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
                                                loadAllFiles(invalidateCache = true)
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
    primaryColor: Color,
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
                tint = primaryColor,
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
