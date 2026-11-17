package com.offlinepdf.studio.ui.screens.files

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlinepdf.studio.core.util.FileStorageManager
import com.offlinepdf.studio.ui.components.FileItemCard
import com.offlinepdf.studio.ui.navigation.Screen
import com.offlinepdf.studio.ui.theme.*
import java.io.File

enum class StorageFolderCategory(val title: String) {
    DOWNLOADS("Downloads"),
    PROCESSED("Processed files"),
    BIN("Bin")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    onNavigateTo: (Screen) -> Unit
) {
    val context = LocalContext.current
    val storageManager = remember { FileStorageManager(context) }

    var processedFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var downloadFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var recycleBinFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var favorites by remember { mutableStateOf<Set<String>>(emptySet()) }

    var activeCategory by remember { mutableStateOf<StorageFolderCategory?>(null) }
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val recycleBinDir = remember {
        File(context.filesDir, "recycle_bin").apply { if (!exists()) mkdirs() }
    }
    val processedDir = remember {
        File(context.filesDir, "pdf_documents").apply { if (!exists()) mkdirs() }
    }
    val downloadsDir = remember {
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "OfflinePDFStudio").apply {
            if (!exists()) mkdirs()
        }
    }

    fun loadAllFiles() {
        processedFiles = processedDir.listFiles { f -> f.extension.equals("pdf", true) }?.sortedByDescending { it.lastModified() } ?: emptyList()
        downloadFiles = downloadsDir.listFiles { f -> f.extension.equals("pdf", true) }?.sortedByDescending { it.lastModified() } ?: emptyList()
        recycleBinFiles = recycleBinDir.listFiles { f -> f.extension.equals("pdf", true) }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    LaunchedEffect(Unit) {
        loadAllFiles()
    }

    // System File Browser Launcher for "Internal Storage"
    val systemFileBrowserLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onNavigateTo(Screen.Viewer)
        }
    }

    val totalInternalCount = processedFiles.size + downloadFiles.size

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (activeCategory != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            IconButton(onClick = {
                                activeCategory = null
                                searchQuery = ""
                                isSearchActive = false
                            }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimaryDark)
                            }
                            Text(
                                text = activeCategory!!.title,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleLarge,
                                color = TextPrimaryDark
                            )
                        }
                    } else {
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
                                    tint = DarkMainBg,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Text(
                                text = "Offline PDF Studio",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleLarge,
                                color = TextPrimaryDark
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { isSearchActive = !isSearchActive }) {
                        Icon(
                            imageVector = if (isSearchActive) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = "Search",
                            tint = TextPrimaryDark
                        )
                    }
                    IconButton(onClick = { onNavigateTo(Screen.Settings) }) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF292831)),
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
            // Live Search Bar when active
            AnimatedVisibility(visible = isSearchActive) {
                Column(modifier = Modifier.padding(bottom = 12.dp)) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search PDF files...", color = TextSecondaryDark) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondaryDark) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear", tint = TextSecondaryDark)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PinkAccent,
                            unfocusedBorderColor = BorderDark,
                            focusedTextColor = TextPrimaryDark,
                            unfocusedTextColor = TextPrimaryDark,
                            cursorColor = PinkAccent
                        ),
                        singleLine = true
                    )
                }
            }

            // Detail View when a storage folder is opened
            if (activeCategory != null) {
                val currentCategoryFiles = when (activeCategory) {
                    StorageFolderCategory.DOWNLOADS -> downloadFiles
                    StorageFolderCategory.PROCESSED -> processedFiles
                    StorageFolderCategory.BIN -> recycleBinFiles
                    null -> emptyList()
                }

                val filteredFiles = remember(currentCategoryFiles, searchQuery) {
                    if (searchQuery.isBlank()) {
                        currentCategoryFiles
                    } else {
                        currentCategoryFiles.filter { it.name.contains(searchQuery, ignoreCase = true) }
                    }
                }

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
                        color = TextSecondaryDark
                    )

                    if (activeCategory == StorageFolderCategory.BIN && recycleBinFiles.isNotEmpty()) {
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
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = if (activeCategory == StorageFolderCategory.BIN) Icons.Outlined.Delete else Icons.Outlined.FolderOpen,
                                contentDescription = null,
                                tint = TextSecondaryDark,
                                modifier = Modifier.size(54.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = if (activeCategory == StorageFolderCategory.BIN) "Recycle Bin is empty" else "No PDF files found",
                                color = TextSecondaryDark,
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
                            if (activeCategory == StorageFolderCategory.BIN) {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = CardDefaults.cardColors(containerColor = DarkToolCard),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark)
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
                                            Text(file.name, color = TextPrimaryDark, maxLines = 1, fontWeight = FontWeight.Medium)
                                            Text("${file.length() / 1024} KB", color = TextSecondaryDark, style = MaterialTheme.typography.bodySmall)
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
            } else {
                // Main "My Storage" Screen layout inspired by reference design
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // Section 1: My storage
                    SectionHeading("My storage")

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        // A. Internal Storage
                        StorageRowItem(
                            title = "Internal Storage",
                            count = totalInternalCount,
                            icon = Icons.Outlined.PhoneAndroid,
                            onClick = {
                                systemFileBrowserLauncher.launch(arrayOf("application/pdf"))
                            }
                        )

                        // B. Downloads
                        StorageRowItem(
                            title = "Downloads",
                            count = downloadFiles.size,
                            icon = Icons.Outlined.Download,
                            onClick = {
                                activeCategory = StorageFolderCategory.DOWNLOADS
                            }
                        )

                        // C. Processed files
                        StorageRowItem(
                            title = "Processed files",
                            count = processedFiles.size,
                            icon = Icons.Outlined.CheckCircle,
                            onClick = {
                                activeCategory = StorageFolderCategory.PROCESSED
                            }
                        )
                    }

                    // Section 2: Cloud Storage
                    SectionHeading("Cloud storage")

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkToolCard),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark)
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color(0xFF292831)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.CloudOff,
                                    contentDescription = "Cloud Offline",
                                    tint = PinkAccent,
                                    modifier = Modifier.size(26.dp)
                                )
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "100% On-Device Mode",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimaryDark
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Cloud sync disabled for privacy. Zero files leave this hardware.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondaryDark
                                )
                            }
                        }
                    }

                    // Section 3: Others
                    SectionHeading("Others")

                    StorageRowItem(
                        title = "Bin",
                        count = recycleBinFiles.size,
                        icon = Icons.Outlined.Delete,
                        onClick = {
                            activeCategory = StorageFolderCategory.BIN
                        }
                    )

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = TextPrimaryDark,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    )
}

@Composable
private fun StorageRowItem(
    title: String,
    count: Int,
    icon: ImageVector,
    iconTint: Color = PinkAccent,
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
        // 56 dp rounded container with 26 dp icon as specified
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF292831)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = iconTint,
                modifier = Modifier.size(26.dp)
            )
        }

        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimaryDark,
            modifier = Modifier.weight(1f)
        )

        Text(
            text = "$count",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondaryDark,
            fontWeight = FontWeight.Normal
        )

        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = TextSecondaryDark,
            modifier = Modifier.size(20.dp)
        )
    }
}
