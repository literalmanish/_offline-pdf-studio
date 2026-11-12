package com.offlinepdf.studio.ui.screens.viewer

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.NavigateBefore
import androidx.compose.material.icons.filled.NavigateNext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.offlinepdf.studio.core.renderer.PdfRendererHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    initialUri: Uri? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var activeUri by remember { mutableStateOf(initialUri) }
    var rendererHelper by remember { mutableStateOf<PdfRendererHelper?>(null) }
    var totalPages by remember { mutableIntStateOf(0) }
    var currentPageIndex by remember { mutableIntStateOf(0) }
    var currentBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    fun loadPage(index: Int) {
        val helper = rendererHelper ?: return
        if (index !in 0 until totalPages) return
        isLoading = true
        coroutineScope.launch {
            val bmp = helper.renderPage(index, destWidth = 1080)
            currentBitmap = bmp
            currentPageIndex = index
            isLoading = false
        }
    }

    fun openDocument(uri: Uri) {
        activeUri = uri
        coroutineScope.launch {
            try {
                rendererHelper?.close()
                val helper = PdfRendererHelper.fromUri(context, uri)
                rendererHelper = helper
                totalPages = helper.pageCount
                currentPageIndex = 0
                loadPage(0)
            } catch (e: Exception) {
                // handle load error
            }
        }
    }

    LaunchedEffect(initialUri) {
        initialUri?.let { openDocument(it) }
    }

    DisposableEffect(Unit) {
        onDispose {
            rendererHelper?.close()
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) openDocument(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (totalPages > 0) "Page ${currentPageIndex + 1} of $totalPages" else "PDF Viewer",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { filePickerLauncher.launch(arrayOf("application/pdf")) }) {
                        Icon(Icons.Default.FileOpen, contentDescription = "Open PDF")
                    }
                }
            )
        },
        bottomBar = {
            if (totalPages > 1) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { loadPage(currentPageIndex - 1) },
                            enabled = currentPageIndex > 0
                        ) {
                            Icon(Icons.Default.NavigateBefore, contentDescription = "Previous Page")
                        }

                        Text(
                            text = "${currentPageIndex + 1} / $totalPages",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )

                        IconButton(
                            onClick = { loadPage(currentPageIndex + 1) },
                            enabled = currentPageIndex < totalPages - 1
                        ) {
                            Icon(Icons.Default.NavigateNext, contentDescription = "Next Page")
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color(0xFFE0E0E0)),
            contentAlignment = Alignment.Center
        ) {
            if (activeUri == null) {
                Button(
                    onClick = { filePickerLauncher.launch(arrayOf("application/pdf")) },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.FileOpen, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Select PDF Document to View")
                }
            } else if (isLoading) {
                CircularProgressIndicator()
            } else if (currentBitmap != null) {
                Image(
                    bitmap = currentBitmap!!.asImageBitmap(),
                    contentDescription = "PDF Page View",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                )
            }
        }
    }
}
