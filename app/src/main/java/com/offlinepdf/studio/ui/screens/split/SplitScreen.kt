package com.offlinepdf.studio.ui.screens.split

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.offlinepdf.studio.core.engine.internal.PdfBoxEngineImpl
import com.offlinepdf.studio.core.engine.model.EngineResult
import com.offlinepdf.studio.core.engine.model.ProcessingProgress
import com.offlinepdf.studio.core.util.FileStorageManager
import com.offlinepdf.studio.ui.components.ProcessingDialog
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val storageManager = remember { FileStorageManager(context) }
    val engine = remember { PdfBoxEngineImpl(context, storageManager) }

    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var totalPages by remember { mutableIntStateOf(0) }
    var rangeInput by remember { mutableStateOf("1-2") }
    var processingProgress by remember { mutableStateOf<ProcessingProgress?>(null) }
    var splitResultFiles by remember { mutableStateOf<List<File>?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            selectedUri = uri
            coroutineScope.launch {
                try {
                    totalPages = engine.getPageCount(uri)
                    rangeInput = if (totalPages > 1) "1-${totalPages / 2}, ${totalPages / 2 + 1}-$totalPages" else "1"
                } catch (e: Exception) {
                    errorMessage = "Failed to read document: ${e.message}"
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Split PDF", fontWeight = FontWeight.Bold) },
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
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Button(
                onClick = { filePickerLauncher.launch(arrayOf("application/pdf")) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.FileOpen, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (selectedUri == null) "Select PDF to Split" else "Choose Different PDF")
            }

            selectedUri?.let { uri ->
                val (name, size) = remember(uri) { storageManager.getUriMetadata(uri) }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Total Pages: $totalPages • ${size / 1024} KB",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                OutlinedTextField(
                    value = rangeInput,
                    onValueChange = { rangeInput = it },
                    label = { Text("Page Ranges (e.g. 1-3, 4-6)") },
                    placeholder = { Text("1-2, 3-5") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Text(
                    "Format: Comma-separated page ranges (e.g. '1-3, 4-5' creates 2 files).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Button(
                    onClick = {
                        val parsedRanges = parseRanges(rangeInput, totalPages)
                        if (parsedRanges.isEmpty()) {
                            errorMessage = "Invalid page range syntax. Example: 1-2, 3-5"
                            return@Button
                        }
                        coroutineScope.launch {
                            val result = engine.split(
                                sourceUri = uri,
                                ranges = parsedRanges,
                                outputBaseName = "split",
                                onProgress = { progress -> processingProgress = progress }
                            )
                            processingProgress = null
                            when (result) {
                                is EngineResult.Success -> splitResultFiles = result.data
                                is EngineResult.Error -> errorMessage = result.userMessage
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    enabled = totalPages > 0
                ) {
                    Text("Split PDF")
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

        splitResultFiles?.let { files ->
            AlertDialog(
                onDismissRequest = { splitResultFiles = null },
                title = { Text("Split Complete! 📄") },
                text = { Text("Successfully created ${files.size} document parts.") },
                confirmButton = {
                    Button(onClick = {
                        val firstFile = files.firstOrNull() ?: return@Button
                        val uri = storageManager.getShareableUri(firstFile)
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/pdf"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share First Part"))
                    }) {
                        Text("Share First File")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { splitResultFiles = null }) { Text("Done") }
                }
            )
        }
    }
}

private fun parseRanges(input: String, maxPage: Int): List<IntRange> {
    val results = mutableListOf<IntRange>()
    val parts = input.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    for (part in parts) {
        if (part.contains("-")) {
            val bounds = part.split("-").mapNotNull { it.trim().toIntOrNull() }
            if (bounds.size == 2 && bounds[0] in 1..maxPage && bounds[1] in 1..maxPage && bounds[0] <= bounds[1]) {
                results.add(bounds[0]..bounds[1])
            }
        } else {
            val single = part.toIntOrNull()
            if (single != null && single in 1..maxPage) {
                results.add(single..single)
            }
        }
    }
    return results
}
