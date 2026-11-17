package com.offlinepdf.studio.ui.screens.split

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.offlinepdf.studio.core.engine.internal.PdfBoxEngineImpl
import com.offlinepdf.studio.core.engine.model.EngineResult
import com.offlinepdf.studio.core.engine.model.ProcessingProgress
import com.offlinepdf.studio.core.util.FileStorageManager
import com.offlinepdf.studio.ui.components.ProcessingDialog
import com.offlinepdf.studio.ui.theme.*
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
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            TopAppBar(
                title = { Text("Split PDF", fontWeight = FontWeight.Bold, color = AppTheme.heading) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = AppTheme.heading)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = AppTheme.heading,
                    navigationIconContentColor = AppTheme.heading
                )
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
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PinkAccent,
                    contentColor = Color.Black
                )
            ) {
                Icon(Icons.Default.FileOpen, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (selectedUri == null) "Select PDF to Split" else "Choose Different PDF",
                    fontWeight = FontWeight.Bold
                )
            }

            selectedUri?.let { uri ->
                val (name, size) = remember(uri) { storageManager.getUriMetadata(uri) }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkToolCard),
                    border = BorderStroke(1.dp, BorderDark)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = AppTheme.heading)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Total Pages: $totalPages • ${size / 1024} KB",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SecondaryText
                        )
                    }
                }

                OutlinedTextField(
                    value = rangeInput,
                    onValueChange = { rangeInput = it },
                    label = { Text("Page Ranges (e.g. 1-3, 4-6)") },
                    placeholder = { Text("1-2, 3-5", color = SecondaryText) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PinkAccent,
                        unfocusedBorderColor = BorderDark,
                        focusedLabelColor = PinkAccent,
                        unfocusedLabelColor = SecondaryText,
                        focusedTextColor = AppTheme.heading,
                        unfocusedTextColor = AppTheme.heading,
                        cursorColor = PinkAccent
                    )
                )

                Text(
                    "Format: Comma-separated page ranges (e.g. '1-3, 4-5' creates 2 files).",
                    style = MaterialTheme.typography.bodySmall,
                    color = SecondaryText
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
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    enabled = totalPages > 0,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PinkAccent,
                        contentColor = Color.Black,
                        disabledContainerColor = DarkSecondaryBg,
                        disabledContentColor = SecondaryText
                    )
                ) {
                    Text("Split PDF", fontWeight = FontWeight.Bold)
                }
            }
        }

        processingProgress?.let { progress ->
            ProcessingDialog(progress = progress, onCancel = { processingProgress = null })
        }

        errorMessage?.let { error ->
            AlertDialog(
                onDismissRequest = { errorMessage = null },
                containerColor = DarkSecondaryBg,
                title = { Text("Notice", color = AppTheme.heading) },
                text = { Text(error, color = SecondaryText) },
                confirmButton = {
                    TextButton(onClick = { errorMessage = null }) {
                        Text("OK", color = PinkAccent)
                    }
                }
            )
        }

        splitResultFiles?.let { files ->
            var downloadedCount by remember { mutableIntStateOf(0) }
            AlertDialog(
                onDismissRequest = { splitResultFiles = null },
                containerColor = DarkSecondaryBg,
                title = { Text("Split Complete! 📄", fontWeight = FontWeight.Bold, color = AppTheme.heading) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Successfully created ${files.size} document parts.", color = AppTheme.text)

                        if (downloadedCount > 0) {
                            Text(
                                "Downloaded $downloadedCount / ${files.size} parts to Downloads folder!",
                                color = PinkAccent,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Button(
                            onClick = {
                                var count = 0
                                files.forEach { f ->
                                    val uri = storageManager.saveToPublicDownloads(f)
                                    if (uri != null) count++
                                }
                                downloadedCount = count
                                Toast.makeText(context, "Saved $count files to Downloads/OfflinePDFStudio", Toast.LENGTH_LONG).show()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = PinkAccent, contentColor = Color.White),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Direct Download All (${files.size} Parts)", fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = {
                                val firstFile = files.firstOrNull() ?: return@OutlinedButton
                                val uri = storageManager.getShareableUri(firstFile)
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/pdf"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Share First Part"))
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = AppTheme.heading),
                            border = BorderStroke(1.dp, BorderDark),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Share First File")
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { splitResultFiles = null }) { Text("Done", color = PinkAccent) }
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
