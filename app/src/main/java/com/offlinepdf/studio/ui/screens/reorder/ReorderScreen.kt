package com.offlinepdf.studio.ui.screens.reorder

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.offlinepdf.studio.core.engine.internal.PdfBoxEngineImpl
import com.offlinepdf.studio.core.engine.model.EngineResult
import com.offlinepdf.studio.core.engine.model.PageItem
import com.offlinepdf.studio.core.engine.model.ProcessingProgress
import com.offlinepdf.studio.core.renderer.PdfRendererHelper
import com.offlinepdf.studio.core.util.FileStorageManager
import com.offlinepdf.studio.ui.components.ProcessingDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReorderScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val storageManager = remember { FileStorageManager(context) }
    val engine = remember { PdfBoxEngineImpl(context, storageManager) }

    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var pages by remember { mutableStateOf<List<PageItem>>(emptyList()) }
    var isLoadingThumbnails by remember { mutableStateOf(false) }
    var processingProgress by remember { mutableStateOf<ProcessingProgress?>(null) }
    var savedResultFile by remember { mutableStateOf<File?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            selectedUri = uri
            isLoadingThumbnails = true
            coroutineScope.launch {
                try {
                    val helper = PdfRendererHelper.fromUri(context, uri)
                    val count = helper.pageCount
                    val loaded = mutableListOf<PageItem>()

                    for (i in 0 until count) {
                        val thumb = helper.renderThumbnail(i, maxDimension = 280)
                        loaded.add(PageItem(pageIndex = i, thumbnail = thumb))
                    }
                    helper.close()
                    pages = loaded
                } catch (e: Exception) {
                    errorMessage = "Failed loading pages: ${e.message}"
                } finally {
                    isLoadingThumbnails = false
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reorder & Delete Pages", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            Button(
                onClick = { filePickerLauncher.launch(arrayOf("application/pdf")) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.UploadFile, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (selectedUri == null) "Select PDF Document" else "Choose Different PDF")
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (isLoadingThumbnails) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (pages.isNotEmpty()) {
                Text(
                    text = "Tap page to toggle delete. Use arrows to change order.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 130.dp),
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    itemsIndexed(pages) { index, item ->
                        PageThumbnailCard(
                            item = item,
                            canMoveLeft = index > 0,
                            canMoveRight = index < pages.size - 1,
                            onToggleDelete = {
                                pages = pages.toMutableList().also {
                                    it[index] = item.copy(isMarkedForDeletion = !item.isMarkedForDeletion)
                                }
                            },
                            onRotate = {
                                pages = pages.toMutableList().also {
                                    it[index] = item.copy(rotationDegrees = (item.rotationDegrees + 90) % 360)
                                }
                            },
                            onMoveLeft = {
                                if (index > 0) {
                                    val list = pages.toMutableList()
                                    val temp = list[index]
                                    list[index] = list[index - 1]
                                    list[index - 1] = temp
                                    pages = list
                                }
                            },
                            onMoveRight = {
                                if (index < pages.size - 1) {
                                    val list = pages.toMutableList()
                                    val temp = list[index]
                                    list[index] = list[index + 1]
                                    list[index + 1] = temp
                                    pages = list
                                }
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                val remainingCount = pages.count { !it.isMarkedForDeletion }
                Button(
                    onClick = {
                        val activePages = pages.filter { !it.isMarkedForDeletion }
                        if (activePages.isEmpty()) {
                            errorMessage = "Cannot save an empty document. At least 1 page must remain."
                            return@Button
                        }
                        val uri = selectedUri ?: return@Button
                        val orderedIndices = activePages.map { it.pageIndex }
                        val rotations = activePages.associate { it.pageIndex to it.rotationDegrees }

                        coroutineScope.launch {
                            val result = engine.reorderAndFilter(
                                sourceUri = uri,
                                pageIndices = orderedIndices,
                                rotations = rotations,
                                outputBaseName = "reordered",
                                onProgress = { progress -> processingProgress = progress }
                            )
                            processingProgress = null
                            when (result) {
                                is EngineResult.Success -> savedResultFile = result.data
                                is EngineResult.Error -> errorMessage = result.userMessage
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    enabled = remainingCount > 0
                ) {
                    Text("Save Modified PDF ($remainingCount pages)")
                }
            }
        }

        processingProgress?.let { progress ->
            ProcessingDialog(progress = progress, onCancel = { processingProgress = null })
        }

        errorMessage?.let { error ->
            AlertDialog(
                onDismissRequest = { errorMessage = null },
                title = { Text("Notice") },
                text = { Text(error) },
                confirmButton = { TextButton(onClick = { errorMessage = null }) { Text("OK") } }
            )
        }

        savedResultFile?.let { file ->
            val shareableUri = remember(file) { storageManager.getShareableUri(file) }
            AlertDialog(
                onDismissRequest = { savedResultFile = null },
                title = { Text("Document Saved! ✨") },
                text = { Text("Modified PDF created: ${file.name}") },
                confirmButton = {
                    Button(onClick = {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/pdf"
                            putExtra(Intent.EXTRA_STREAM, shareableUri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share PDF"))
                    }) {
                        Text("Share")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { savedResultFile = null }) { Text("Done") }
                }
            )
        }
    }
}

@Composable
private fun PageThumbnailCard(
    item: PageItem,
    canMoveLeft: Boolean,
    canMoveRight: Boolean,
    onToggleDelete: () -> Unit,
    onRotate: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (item.isMarkedForDeletion) 0.35f else 1f)
            .border(
                width = if (item.isMarkedForDeletion) 2.dp else 1.dp,
                color = if (item.isMarkedForDeletion) Color.Red else Color.LightGray,
                shape = RoundedCornerShape(8.dp)
            ),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .height(130.dp)
                    .fillMaxWidth()
                    .clickable(onClick = onToggleDelete),
                contentAlignment = Alignment.Center
            ) {
                if (item.thumbnail != null) {
                    Image(
                        bitmap = item.thumbnail.asImageBitmap(),
                        contentDescription = "Page ${item.displayIndex}",
                        modifier = Modifier
                            .fillMaxSize()
                            .rotate(item.rotationDegrees.toFloat())
                    )
                } else {
                    Text("P. ${item.displayIndex}")
                }

                if (item.isMarkedForDeletion) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Deleted",
                        tint = Color.Red,
                        modifier = Modifier.size(48.dp)
                    )
                }
            }

            Text("Page ${item.displayIndex}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = onMoveLeft, enabled = canMoveLeft, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Move Left", modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = onRotate, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.RotateRight, contentDescription = "Rotate", modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = onMoveRight, enabled = canMoveRight, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Move Right", modifier = Modifier.size(16.dp).rotate(180f))
                }
            }
        }
    }
}
