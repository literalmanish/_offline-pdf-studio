package com.offlinepdf.studio.ui.screens.compress

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.offlinepdf.studio.core.engine.internal.PdfBoxEngineImpl
import com.offlinepdf.studio.core.engine.model.CompressionLevel
import com.offlinepdf.studio.core.engine.model.EngineResult
import com.offlinepdf.studio.core.engine.model.ProcessingProgress
import com.offlinepdf.studio.core.util.FileStorageManager
import com.offlinepdf.studio.ui.components.ProcessCompleteDownloadDialog
import com.offlinepdf.studio.ui.components.ProcessingDialog
import com.offlinepdf.studio.ui.theme.*
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
        containerColor = DarkMainBg,
        contentColor = Color.White,
        topBar = {
            TopAppBar(
                title = { Text("Compress PDF", fontWeight = FontWeight.Bold, color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkMainBg,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
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
                colors = ButtonDefaults.buttonColors(containerColor = PinkAccent, contentColor = Color.Black)
            ) {
                Icon(Icons.Default.FileOpen, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (selectedUri == null) "Select PDF to Compress" else "Choose Different PDF",
                    fontWeight = FontWeight.Bold
                )
            }

            selectedUri?.let { uri ->
                val (name, _) = remember(uri) { storageManager.getUriMetadata(uri) }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkToolCard),
                    border = BorderStroke(1.dp, BorderDark)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Original Size: ${originalSizeBytes / 1024} KB",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SecondaryText
                        )
                    }
                }

                Text("Select Compression Level:", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Color.White)

                CompressionLevel.entries.forEach { level ->
                    val isSelected = selectedLevel == level
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedLevel = level },
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) DarkSecondaryBg else DarkToolCard
                        ),
                        border = BorderStroke(1.dp, if (isSelected) PinkAccent else BorderDark)
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(14.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { selectedLevel = level },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = PinkAccent,
                                    unselectedColor = SecondaryText
                                )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(level.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                                Text(level.description, style = MaterialTheme.typography.bodySmall, color = SecondaryText)
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
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PinkAccent, contentColor = Color.Black)
                ) {
                    Icon(Icons.Default.Compress, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Compress On-Device", fontWeight = FontWeight.Bold)
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
                title = { Text("Notice", color = Color.White) },
                text = { Text(error, color = SecondaryText) },
                confirmButton = {
                    TextButton(onClick = { errorMessage = null }) {
                        Text("OK", color = PinkAccent)
                    }
                }
            )
        }

        compressedResultFile?.let { file ->
            val newSizeBytes = file.length()
            val savedBytes = (originalSizeBytes - newSizeBytes).coerceAtLeast(0L)
            val percent = if (originalSizeBytes > 0) (savedBytes.toFloat() / originalSizeBytes.toFloat() * 100).toInt() else 0
            val desc = if (savedBytes > 0) {
                "Reduced from ${originalSizeBytes / 1024} KB to ${newSizeBytes / 1024} KB (Saved $percent%)"
            } else {
                "Document optimized (${newSizeBytes / 1024} KB)"
            }

            ProcessCompleteDownloadDialog(
                file = file,
                title = "Compression Complete! 📉",
                description = desc,
                onDismiss = { compressedResultFile = null }
            )
        }
    }
}
