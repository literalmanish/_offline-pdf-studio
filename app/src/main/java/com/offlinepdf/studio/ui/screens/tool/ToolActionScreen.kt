package com.offlinepdf.studio.ui.screens.tool

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.offlinepdf.studio.core.engine.internal.PdfBoxEngineImpl
import com.offlinepdf.studio.core.engine.model.*
import com.offlinepdf.studio.core.util.FileStorageManager
import com.offlinepdf.studio.ui.components.ProcessingDialog
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolActionScreen(
    toolId: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val storageManager = remember { FileStorageManager(context) }
    val engine = remember { PdfBoxEngineImpl(context, storageManager) }

    val tool = remember(toolId) { ToolCatalog.allTools.find { it.id == toolId } }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var selectedMultiUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var processingProgress by remember { mutableStateOf<ProcessingProgress?>(null) }
    var resultFile by remember { mutableStateOf<File?>(null) }
    var resultFiles by remember { mutableStateOf<List<File>?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var resultMessage by remember { mutableStateOf<String?>(null) }

    // Parameters for tools
    var paramText1 by remember { mutableStateOf("") }
    var paramText2 by remember { mutableStateOf("") }
    var paramSlider by remember { mutableFloatStateOf(0.25f) }
    var selectedOption by remember { mutableIntStateOf(0) }
    var drawingPoints = remember { mutableStateListOf<Offset?>() }

    val singlePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            selectedUri = uri
            if (toolId == "edit_metadata") {
                coroutineScope.launch {
                    val meta = engine.getMetadata(uri)
                    paramText1 = meta.title
                    paramText2 = meta.author
                }
            } else if (toolId == "bookmarks") {
                coroutineScope.launch {
                    val list = engine.extractBookmarks(uri)
                    resultMessage = if (list.isNotEmpty()) {
                        "Found ${list.size} bookmarks:\n\n" + list.joinToString("\n• ", prefix = "• ")
                    } else {
                        "No bookmarks or outline entries found in this document."
                    }
                }
            }
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            selectedMultiUris = uris
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tool?.title ?: "PDF Tool", fontWeight = FontWeight.Bold) },
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
            tool?.let {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(it.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(it.description, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            // File Pickers
            if (toolId == "images_to_pdf") {
                Button(
                    onClick = { imagePickerLauncher.launch("image/*") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.AddPhotoAlternate, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (selectedMultiUris.isEmpty()) "Select Images" else "Selected ${selectedMultiUris.size} Images")
                }
            } else if (toolId != "text_to_pdf") {
                Button(
                    onClick = { singlePickerLauncher.launch(arrayOf("application/pdf")) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.FileOpen, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (selectedUri == null) "Select PDF Document" else "Change Selected PDF")
                }

                selectedUri?.let { uri ->
                    val (name, size) = remember(uri) { storageManager.getUriMetadata(uri) }
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(name, fontWeight = FontWeight.SemiBold)
                            Text("${size / 1024} KB", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            // Tool-Specific Parameter Inputs
            when (toolId) {
                "rotate" -> {
                    Text("Select Rotation Angle:", fontWeight = FontWeight.SemiBold)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(90, 180, 270).forEach { deg ->
                            FilterChip(
                                selected = (selectedOption == deg),
                                onClick = { selectedOption = deg },
                                label = { Text("$deg°") }
                            )
                        }
                    }
                }
                "watermark" -> {
                    OutlinedTextField(
                        value = paramText1,
                        onValueChange = { paramText1 = it },
                        label = { Text("Watermark Text") },
                        placeholder = { Text("e.g. CONFIDENTIAL") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Opacity: ${(paramSlider * 100).toInt()}%")
                    Slider(value = paramSlider, onValueChange = { paramSlider = it }, valueRange = 0.1f..0.8f)
                }
                "protect" -> {
                    OutlinedTextField(
                        value = paramText1,
                        onValueChange = { paramText1 = it },
                        label = { Text("Enter Password to Encrypt") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                "unlock" -> {
                    OutlinedTextField(
                        value = paramText1,
                        onValueChange = { paramText1 = it },
                        label = { Text("Enter Document Password") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                "edit_metadata" -> {
                    OutlinedTextField(
                        value = paramText1,
                        onValueChange = { paramText1 = it },
                        label = { Text("Document Title") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = paramText2,
                        onValueChange = { paramText2 = it },
                        label = { Text("Author Name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                "page_numbers" -> {
                    Text("Numbering Position:", fontWeight = FontWeight.SemiBold)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Bottom Center", "Bottom Right", "Top Center").forEachIndexed { idx, label ->
                            FilterChip(
                                selected = (selectedOption == idx),
                                onClick = { selectedOption = idx },
                                label = { Text(label) }
                            )
                        }
                    }
                }
                "stamp" -> {
                    Text("Select Stamp Type:", fontWeight = FontWeight.SemiBold)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StandardStamp.entries.take(4).forEachIndexed { idx, s ->
                            FilterChip(
                                selected = (selectedOption == idx),
                                onClick = { selectedOption = idx },
                                label = { Text(s.label) }
                            )
                        }
                    }
                }
                "sign" -> {
                    Text("Draw Signature Below:", fontWeight = FontWeight.SemiBold)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(8.dp))
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = { offset -> drawingPoints.add(offset) },
                                    onDrag = { change, _ -> drawingPoints.add(change.position) },
                                    onDragEnd = { drawingPoints.add(null) }
                                )
                            }
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            for (i in 0 until drawingPoints.size - 1) {
                                val p1 = drawingPoints[i]
                                val p2 = drawingPoints[i + 1]
                                if (p1 != null && p2 != null) {
                                    drawLine(
                                        color = androidx.compose.ui.graphics.Color.Black,
                                        start = p1,
                                        end = p2,
                                        strokeWidth = 5f,
                                        cap = StrokeCap.Round
                                    )
                                }
                            }
                        }
                    }
                    Button(onClick = { drawingPoints.clear() }, modifier = Modifier.align(Alignment.End)) {
                        Text("Clear Signature")
                    }
                }
                "text_to_pdf" -> {
                    OutlinedTextField(
                        value = paramText1,
                        onValueChange = { paramText1 = it },
                        label = { Text("Document Title") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = paramText2,
                        onValueChange = { paramText2 = it },
                        label = { Text("Content / Notes") },
                        minLines = 6,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                "crop" -> {
                    Text("Crop Margin: ${paramSlider.toInt()} points")
                    Slider(value = paramSlider, onValueChange = { paramSlider = it }, valueRange = 10f..100f)
                }
                "delete_pages" -> {
                    OutlinedTextField(
                        value = paramText1,
                        onValueChange = { paramText1 = it },
                        label = { Text("Pages to delete (e.g. 1, 3, 5)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                "blank_pages" -> {
                    OutlinedTextField(
                        value = paramText1,
                        onValueChange = { paramText1 = it },
                        label = { Text("Insert blank page after (e.g. 1, 2)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                "header_footer" -> {
                    OutlinedTextField(
                        value = paramText1,
                        onValueChange = { paramText1 = it },
                        label = { Text("Header Text (Top)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = paramText2,
                        onValueChange = { paramText2 = it },
                        label = { Text("Footer Text (Bottom)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Action Execution Button
            Button(
                onClick = {
                    coroutineScope.launch {
                        val uri = selectedUri
                        val progressCallback: (ProcessingProgress) -> Unit = { processingProgress = it }

                        when (toolId) {
                            "rotate" -> {
                                if (uri == null) return@launch
                                val angle = if (selectedOption == 0) 90 else selectedOption
                                val res = engine.rotatePages(uri, angle, "rotated", progressCallback)
                                processingProgress = null
                                when (res) {
                                    is EngineResult.Success -> resultFile = res.data
                                    is EngineResult.Error -> errorMessage = res.userMessage
                                }
                            }
                            "reverse" -> {
                                if (uri == null) return@launch
                                val res = engine.reversePages(uri, "reversed", progressCallback)
                                processingProgress = null
                                when (res) {
                                    is EngineResult.Success -> resultFile = res.data
                                    is EngineResult.Error -> errorMessage = res.userMessage
                                }
                            }
                            "watermark" -> {
                                if (uri == null || paramText1.isBlank()) {
                                    errorMessage = "Please enter watermark text."
                                    return@launch
                                }
                                val res = engine.addWatermark(uri, paramText1, WatermarkPosition.CENTER_DIAGONAL, paramSlider, "watermarked", progressCallback)
                                processingProgress = null
                                when (res) {
                                    is EngineResult.Success -> resultFile = res.data
                                    is EngineResult.Error -> errorMessage = res.userMessage
                                }
                            }
                            "protect" -> {
                                if (uri == null || paramText1.isBlank()) {
                                    errorMessage = "Please enter an encryption password."
                                    return@launch
                                }
                                val res = engine.protectPdf(uri, paramText1, paramText1, "protected", progressCallback)
                                processingProgress = null
                                when (res) {
                                    is EngineResult.Success -> resultFile = res.data
                                    is EngineResult.Error -> errorMessage = res.userMessage
                                }
                            }
                            "unlock" -> {
                                if (uri == null || paramText1.isBlank()) {
                                    errorMessage = "Please enter the PDF password."
                                    return@launch
                                }
                                val res = engine.unlockPdf(uri, paramText1, "unlocked", progressCallback)
                                processingProgress = null
                                when (res) {
                                    is EngineResult.Success -> resultFile = res.data
                                    is EngineResult.Error -> errorMessage = res.userMessage
                                }
                            }
                            "edit_metadata" -> {
                                if (uri == null) return@launch
                                val meta = DocumentMetadata(title = paramText1, author = paramText2)
                                val res = engine.editMetadata(uri, meta, "metadata_updated", progressCallback)
                                processingProgress = null
                                when (res) {
                                    is EngineResult.Success -> resultFile = res.data
                                    is EngineResult.Error -> errorMessage = res.userMessage
                                }
                            }
                            "sanitize" -> {
                                if (uri == null) return@launch
                                val res = engine.sanitizeMetadata(uri, "sanitized", progressCallback)
                                processingProgress = null
                                when (res) {
                                    is EngineResult.Success -> resultFile = res.data
                                    is EngineResult.Error -> errorMessage = res.userMessage
                                }
                            }
                            "flatten" -> {
                                if (uri == null) return@launch
                                val res = engine.flattenPdf(uri, "flattened", progressCallback)
                                processingProgress = null
                                when (res) {
                                    is EngineResult.Success -> resultFile = res.data
                                    is EngineResult.Error -> errorMessage = res.userMessage
                                }
                            }
                            "grayscale" -> {
                                if (uri == null) return@launch
                                val res = engine.convertToGrayscale(uri, "grayscale", progressCallback)
                                processingProgress = null
                                when (res) {
                                    is EngineResult.Success -> resultFile = res.data
                                    is EngineResult.Error -> errorMessage = res.userMessage
                                }
                            }
                            "invert_colors" -> {
                                if (uri == null) return@launch
                                val res = engine.invertColors(uri, "night_mode", progressCallback)
                                processingProgress = null
                                when (res) {
                                    is EngineResult.Success -> resultFile = res.data
                                    is EngineResult.Error -> errorMessage = res.userMessage
                                }
                            }
                            "repair" -> {
                                if (uri == null) return@launch
                                val res = engine.repairPdf(uri, "repaired", progressCallback)
                                processingProgress = null
                                when (res) {
                                    is EngineResult.Success -> resultFile = res.data
                                    is EngineResult.Error -> errorMessage = res.userMessage
                                }
                            }
                            "page_numbers" -> {
                                if (uri == null) return@launch
                                val pos = when (selectedOption) {
                                    1 -> PageNumberPosition.BOTTOM_RIGHT
                                    2 -> PageNumberPosition.TOP_CENTER
                                    else -> PageNumberPosition.BOTTOM_CENTER
                                }
                                val res = engine.addPageNumbers(uri, pos, "Page {n} of {total}", 1, "numbered", progressCallback)
                                processingProgress = null
                                when (res) {
                                    is EngineResult.Success -> resultFile = res.data
                                    is EngineResult.Error -> errorMessage = res.userMessage
                                }
                            }
                            "header_footer" -> {
                                if (uri == null) return@launch
                                val res = engine.addHeaderFooter(uri, paramText1, paramText2, "header_footer", progressCallback)
                                processingProgress = null
                                when (res) {
                                    is EngineResult.Success -> resultFile = res.data
                                    is EngineResult.Error -> errorMessage = res.userMessage
                                }
                            }
                            "stamp" -> {
                                if (uri == null) return@launch
                                val stamp = StandardStamp.entries.getOrElse(selectedOption) { StandardStamp.CONFIDENTIAL }
                                val res = engine.addStamp(uri, stamp, null, "stamped", progressCallback)
                                processingProgress = null
                                when (res) {
                                    is EngineResult.Success -> resultFile = res.data
                                    is EngineResult.Error -> errorMessage = res.userMessage
                                }
                            }
                            "crop" -> {
                                if (uri == null) return@launch
                                val res = engine.cropPages(uri, paramSlider, "cropped", progressCallback)
                                processingProgress = null
                                when (res) {
                                    is EngineResult.Success -> resultFile = res.data
                                    is EngineResult.Error -> errorMessage = res.userMessage
                                }
                            }
                            "nup" -> {
                                if (uri == null) return@launch
                                val res = engine.nUp(uri, 2, "2up_sheet", progressCallback)
                                processingProgress = null
                                when (res) {
                                    is EngineResult.Success -> resultFile = res.data
                                    is EngineResult.Error -> errorMessage = res.userMessage
                                }
                            }
                            "images_to_pdf" -> {
                                if (selectedMultiUris.isEmpty()) {
                                    errorMessage = "Please select at least 1 image."
                                    return@launch
                                }
                                val res = engine.imagesToPdf(selectedMultiUris, "photos_pdf", progressCallback)
                                processingProgress = null
                                when (res) {
                                    is EngineResult.Success -> resultFile = res.data
                                    is EngineResult.Error -> errorMessage = res.userMessage
                                }
                            }
                            "pdf_to_images" -> {
                                if (uri == null) return@launch
                                val res = engine.pdfToImages(uri, "PNG", "pdf_pages", progressCallback)
                                processingProgress = null
                                when (res) {
                                    is EngineResult.Success -> resultFiles = res.data
                                    is EngineResult.Error -> errorMessage = res.userMessage
                                }
                            }
                            "extract_images" -> {
                                if (uri == null) return@launch
                                val res = engine.extractImages(uri, "extracted_pic", progressCallback)
                                processingProgress = null
                                when (res) {
                                    is EngineResult.Success -> resultFiles = res.data
                                    is EngineResult.Error -> errorMessage = res.userMessage
                                }
                            }
                            "extract_text" -> {
                                if (uri == null) return@launch
                                val res = engine.extractText(uri, "extracted_text", progressCallback)
                                processingProgress = null
                                when (res) {
                                    is EngineResult.Success -> resultFile = res.data
                                    is EngineResult.Error -> errorMessage = res.userMessage
                                }
                            }
                            "text_to_pdf" -> {
                                if (paramText2.isBlank()) {
                                    errorMessage = "Please enter some content."
                                    return@launch
                                }
                                val title = if (paramText1.isBlank()) "Document" else paramText1
                                val res = engine.textToPdf(paramText2, title, "text_pdf", progressCallback)
                                processingProgress = null
                                when (res) {
                                    is EngineResult.Success -> resultFile = res.data
                                    is EngineResult.Error -> errorMessage = res.userMessage
                                }
                            }
                            "sign" -> {
                                if (uri == null || drawingPoints.isEmpty()) {
                                    errorMessage = "Please draw your signature above."
                                    return@launch
                                }
                                val signBmp = Bitmap.createBitmap(400, 160, Bitmap.Config.ARGB_8888)
                                val canvas = Canvas(signBmp)
                                val paint = Paint().apply {
                                    color = Color.BLACK
                                    strokeWidth = 6f
                                    strokeCap = Paint.Cap.ROUND
                                    isAntiAlias = true
                                }
                                for (i in 0 until drawingPoints.size - 1) {
                                    val p1 = drawingPoints[i]
                                    val p2 = drawingPoints[i + 1]
                                    if (p1 != null && p2 != null) {
                                        canvas.drawLine(p1.x, p1.y, p2.x, p2.y, paint)
                                    }
                                }
                                val res = engine.signPdf(uri, signBmp, 0, 0.6f, 0.15f, "signed", progressCallback)
                                processingProgress = null
                                when (res) {
                                    is EngineResult.Success -> resultFile = res.data
                                    is EngineResult.Error -> errorMessage = res.userMessage
                                }
                            }
                            "delete_pages" -> {
                                if (uri == null) return@launch
                                val delIndices = paramText1.split(",").mapNotNull { it.trim().toIntOrNull()?.minus(1) }
                                val total = engine.getPageCount(uri)
                                val keepIndices = (0 until total).filter { it !in delIndices }
                                val res = engine.reorderAndFilter(uri, keepIndices, emptyMap(), "pages_deleted", progressCallback)
                                processingProgress = null
                                when (res) {
                                    is EngineResult.Success -> resultFile = res.data
                                    is EngineResult.Error -> errorMessage = res.userMessage
                                }
                            }
                            "blank_pages" -> {
                                if (uri == null) return@launch
                                val posList = paramText1.split(",").mapNotNull { it.trim().toIntOrNull() }
                                val res = engine.insertBlankPages(uri, posList, "with_blank_pages", progressCallback)
                                processingProgress = null
                                when (res) {
                                    is EngineResult.Success -> resultFile = res.data
                                    is EngineResult.Error -> errorMessage = res.userMessage
                                }
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                enabled = (selectedUri != null || toolId == "images_to_pdf" || toolId == "text_to_pdf")
            ) {
                Text("Process Document")
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

        resultMessage?.let { msg ->
            AlertDialog(
                onDismissRequest = { resultMessage = null },
                title = { Text("Result") },
                text = { Text(msg) },
                confirmButton = { TextButton(onClick = { resultMessage = null }) { Text("OK") } }
            )
        }

        resultFile?.let { file ->
            val shareableUri = remember(file) { storageManager.getShareableUri(file) }
            val mime = if (file.name.endsWith(".txt")) "text/plain" else "application/pdf"
            AlertDialog(
                onDismissRequest = { resultFile = null },
                title = { Text("Completed! 🎉") },
                text = { Text("Output saved: ${file.name} (${file.length() / 1024} KB)") },
                confirmButton = {
                    Button(onClick = {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = mime
                            putExtra(Intent.EXTRA_STREAM, shareableUri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share Output File"))
                    }) {
                        Text("Share / Save")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { resultFile = null }) { Text("Done") }
                }
            )
        }

        resultFiles?.let { files ->
            AlertDialog(
                onDismissRequest = { resultFiles = null },
                title = { Text("Extraction Complete! 🖼️") },
                text = { Text("Extracted ${files.size} image files successfully.") },
                confirmButton = {
                    Button(onClick = {
                        val first = files.firstOrNull() ?: return@Button
                        val uri = storageManager.getShareableUri(first)
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "image/png"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share First Image"))
                    }) {
                        Text("Share First Image")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { resultFiles = null }) { Text("Done") }
                }
            )
        }
    }
}
