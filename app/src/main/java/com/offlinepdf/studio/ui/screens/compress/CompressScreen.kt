package com.offlinepdf.studio.ui.screens.compress

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.offlinepdf.studio.core.engine.internal.PdfBoxEngineImpl
import com.offlinepdf.studio.core.engine.model.CompressionLevel
import com.offlinepdf.studio.core.engine.model.EngineResult
import com.offlinepdf.studio.core.engine.model.ProcessingProgress
import com.offlinepdf.studio.core.util.FileStorageManager
import com.offlinepdf.studio.ui.components.ProcessingDialog
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompressScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val storageManager = remember { FileStorageManager(context) }
    val engine = remember { PdfBoxEngineImpl(context, storageManager) }

    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var selectedLevel by remember { mutableStateOf(CompressionLevel.MEDIUM) }
    var originalSizeBytes by remember { mutableLongStateOf(0L) }
    var processingProgress by remember { mutableStateOf<ProcessingProgress?>(null) }
    var compressedResultFile by remember { mutableStateOf<File?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            selectedUri = uri
            val (_, size) = storageManager.getUriMetadata(uri)
            originalSizeBytes = size
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Compress PDF", fontWeight = FontWeight.Bold) },
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
                Text(if (selectedUri == null) "Select PDF to Compress" else "Choose Different PDF")
            }

            selectedUri?.let { uri ->
                val (name, _) = remember(uri) { storageManager.getUriMetadata(uri) }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Original Size: ${originalSizeBytes / 1024} KB",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Text("Select Compression Level:", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

                CompressionLevel.entries.forEach { level ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedLevel = level },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (selectedLevel == level)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(14.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (selectedLevel == level),
                                onClick = { selectedLevel = level }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(level.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(level.description, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                Button(
                    onClick = {
                        coroutineScope.launch {
                            val result = engine.compress(
                                sourceUri = uri,
                                level = selectedLevel,
                                outputBaseName = "compressed",
                                onProgress = { progress -> processingProgress = progress }
                            )
                            processingProgress = null
                            when (result) {
                                is EngineResult.Success -> compressedResultFile = result.data
                                is EngineResult.Error -> errorMessage = result.userMessage
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Compress, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Compress On-Device")
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

        compressedResultFile?.let { file ->
            val shareableUri = remember(file) { storageManager.getShareableUri(file) }
            val newSizeBytes = file.length()
            val savedBytes = (originalSizeBytes - newSizeBytes).coerceAtLeast(0L)
            val percent = if (originalSizeBytes > 0) (savedBytes.toFloat() / originalSizeBytes.toFloat() * 100).toInt() else 0

            AlertDialog(
                onDismissRequest = { compressedResultFile = null },
                title = { Text("Compression Done! 📉") },
                text = {
                    Column {
                        Text("New Size: ${newSizeBytes / 1024} KB")
                        Text("Original Size: ${originalSizeBytes / 1024} KB")
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (savedBytes > 0) "Saved $percent% of file size!" else "File was already optimized.",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/pdf"
                            putExtra(Intent.EXTRA_STREAM, shareableUri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share Compressed PDF"))
                    }) {
                        Text("Share")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { compressedResultFile = null }) { Text("Done") }
                }
            )
        }
    }
}
