package com.offlinepdf.studio.ui.screens.scanner

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.offlinepdf.studio.core.engine.internal.PdfBoxEngineImpl
import com.offlinepdf.studio.core.engine.model.EngineResult
import com.offlinepdf.studio.core.engine.model.ProcessingProgress
import com.offlinepdf.studio.core.util.FileStorageManager
import com.offlinepdf.studio.ui.components.FileItemCard
import com.offlinepdf.studio.ui.components.ProcessCompleteDownloadDialog
import com.offlinepdf.studio.ui.components.ProcessingDialog
import com.offlinepdf.studio.ui.navigation.Screen
import com.offlinepdf.studio.ui.theme.*
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(
    onNavigateTo: (Screen) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val storageManager = remember { FileStorageManager(context) }
    val engine = remember { PdfBoxEngineImpl(context, storageManager) }

    var selectedTab by remember { mutableIntStateOf(0) } // 0: Scanned Images, 1: Converted PDFs
    var capturedImages by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var convertedPdfs by remember { mutableStateOf<List<File>>(emptyList()) }
    var processingProgress by remember { mutableStateOf<ProcessingProgress?>(null) }
    var generatedPdf by remember { mutableStateOf<File?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun refreshConverted() {
        val docsDir = File(context.filesDir, "pdf_documents")
        convertedPdfs = docsDir.listFiles { f -> f.name.startsWith("scanned_", ignoreCase = true) }?.toList() ?: emptyList()
    }

    LaunchedEffect(Unit) {
        refreshConverted()
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            capturedImages = capturedImages + uris
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            val tempImg = File(context.cacheDir, "scan_${System.currentTimeMillis()}.jpg")
            tempImg.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
            val uri = storageManager.getShareableUri(tempImg)
            capturedImages = capturedImages + uri
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Document Scanner",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimaryDark
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkMainBg)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { cameraLauncher.launch(null) },
                containerColor = PinkAccent,
                contentColor = DarkMainBg,
                icon = { Icon(Icons.Default.CameraAlt, contentDescription = null) },
                text = { Text("Scan Page", fontWeight = FontWeight.Bold) },
                shape = RoundedCornerShape(16.dp)
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
            // Tab Selector
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = DarkSecondaryBg,
                contentColor = PinkAccent,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = PinkAccent
                    )
                }
            ) {
                Tab(
                    selected = (selectedTab == 0),
                    onClick = { selectedTab = 0 },
                    text = { Text("Scanned Images (${capturedImages.size})", fontWeight = FontWeight.SemiBold) }
                )
                Tab(
                    selected = (selectedTab == 1),
                    onClick = { selectedTab = 1 },
                    text = { Text("Converted PDFs (${convertedPdfs.size})", fontWeight = FontWeight.SemiBold) }
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Action Quick Buttons for Camera & Gallery
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { cameraLauncher.launch(null) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = DarkToolCard),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark)
                ) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null, tint = PastelGreen)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Take Photo", color = TextPrimaryDark)
                }

                Button(
                    onClick = { galleryLauncher.launch("image/*") },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = DarkToolCard),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark)
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null, tint = PastelBlue)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("From Gallery", color = TextPrimaryDark)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (selectedTab == 0) {
                if (capturedImages.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .background(DarkSecondaryBg),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Outlined.DocumentScanner, contentDescription = null, tint = PinkAccent, modifier = Modifier.size(32.dp))
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("No scanned pages yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = TextPrimaryDark)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Use the camera or gallery to scan document pages", style = MaterialTheme.typography.bodySmall, color = TextSecondaryDark)
                        }
                    }
                } else {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${capturedImages.size} Pages Staged", color = TextSecondaryDark, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { capturedImages = emptyList() }) {
                                Text("Clear All", color = StatusError)
                            }
                        }

                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(capturedImages.size) { index ->
                                val uri = capturedImages[index]
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(containerColor = DarkToolCard),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .padding(12.dp)
                                            .fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Page ${index + 1}",
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimaryDark,
                                            modifier = Modifier.weight(1f)
                                        )
                                        IconButton(onClick = {
                                            capturedImages = capturedImages.toMutableList().also { it.removeAt(index) }
                                        }) {
                                            Icon(Icons.Default.Close, contentDescription = "Remove", tint = TextSecondaryDark)
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    val res = engine.imagesToPdf(
                                        imageUris = capturedImages,
                                        outputBaseName = "scanned_document",
                                        onProgress = { progress -> processingProgress = progress }
                                    )
                                    processingProgress = null
                                    when (res) {
                                        is EngineResult.Success -> {
                                            generatedPdf = res.data
                                            capturedImages = emptyList()
                                            refreshConverted()
                                            selectedTab = 1
                                        }
                                        is EngineResult.Error -> errorMessage = res.userMessage
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = PinkAccent, contentColor = DarkMainBg)
                        ) {
                            Icon(Icons.Default.PictureAsPdf, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Convert ${capturedImages.size} Pages to PDF", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                // Converted PDFs Tab
                if (convertedPdfs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No converted scanned PDFs yet", color = TextSecondaryDark)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 80.dp)
                    ) {
                        items(convertedPdfs, key = { it.absolutePath }) { file ->
                            FileItemCard(
                                file = file,
                                isFavorite = false,
                                onOpen = { onNavigateTo(Screen.Viewer) },
                                onShare = {
                                    val uri = storageManager.getShareableUri(file)
                                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = "application/pdf"
                                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(android.content.Intent.createChooser(intent, "Share Scanned PDF"))
                                },
                                onDelete = {
                                    file.delete()
                                    refreshConverted()
                                },
                                onToggleFavorite = {}
                            )
                        }
                    }
                }
            }
        }

        processingProgress?.let { progress ->
            ProcessingDialog(progress = progress, onCancel = { processingProgress = null })
        }

        generatedPdf?.let { file ->
            ProcessCompleteDownloadDialog(
                file = file,
                title = "Scan Converted! 📄",
                onDismiss = { generatedPdf = null }
            )
        }

        errorMessage?.let { error ->
            AlertDialog(
                onDismissRequest = { errorMessage = null },
                containerColor = DarkSecondaryBg,
                titleContentColor = TextPrimaryDark,
                textContentColor = TextSecondaryDark,
                title = { Text("Notice") },
                text = { Text(error) },
                confirmButton = { TextButton(onClick = { errorMessage = null }) { Text("OK", color = PinkAccent) } }
            )
        }
    }
}
