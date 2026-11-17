package com.offlinepdf.studio.ui.screens.settings

import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import com.offlinepdf.studio.core.util.AppPreferencesManager
import com.offlinepdf.studio.core.util.FileStorageManager
import com.offlinepdf.studio.ui.navigation.Screen
import com.offlinepdf.studio.ui.theme.*
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateTo: (Screen) -> Unit
) {
    val context = LocalContext.current
    val storageManager = remember { FileStorageManager(context) }
    val prefsManager = remember { AppPreferencesManager(context) }

    var cacheSizeKb by remember { mutableLongStateOf(0L) }
    var documentsCount by remember { mutableIntStateOf(0) }

    // Dialog states
    var showThemeDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showCompressionDialog by remember { mutableStateOf(false) }
    var showPageSizeDialog by remember { mutableStateOf(false) }
    var showPrivacyDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showClearCacheConfirm by remember { mutableStateOf(false) }
    var showClearDocsConfirm by remember { mutableStateOf(false) }

    // State bindings
    var currentTheme by remember { mutableStateOf(prefsManager.themeMode) }
    var currentLanguage by remember { mutableStateOf(prefsManager.language) }
    var currentCompression by remember { mutableStateOf(prefsManager.defaultCompression) }
    var currentPageSize by remember { mutableStateOf(prefsManager.defaultPageSize) }
    var directDownloadEnabled by remember { mutableStateOf(prefsManager.directDownloadEnabled) }

    fun refreshMetrics() {
        var total = 0L
        context.cacheDir.walkTopDown().forEach { total += it.length() }
        cacheSizeKb = total / 1024

        val docsDir = File(context.filesDir, "pdf_documents")
        documentsCount = docsDir.listFiles()?.size ?: 0
    }

    LaunchedEffect(Unit) {
        refreshMetrics()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                actions = {
                    val themeController = LocalThemeController.current
                    IconButton(onClick = { themeController.toggleTheme() }) {
                        Icon(
                            imageVector = if (themeController.isDark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
                            contentDescription = if (themeController.isDark) "Switch to Light Mode" else "Switch to Dark Mode",
                            tint = if (themeController.isDark) PastelYellow else PinkAccent
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Section 1: Appearance & Display
            SettingsSectionHeader(title = "Appearance & Interface")

            val themeLabel = when (currentTheme) {
                "AMOLED" -> "AMOLED Black (Pure Dark)"
                "LIGHT" -> "Clean Light Mode"
                "SYSTEM" -> "System Default"
                else -> "Studio Dark Mode"
            }
            SettingsItemCard(
                title = "Theme",
                subtitle = themeLabel,
                icon = Icons.Outlined.DarkMode,
                iconTint = PastelPurple,
                onClick = { showThemeDialog = true }
            )

            val languageLabel = when (currentLanguage) {
                "es" -> "Español (Spanish)"
                "fr" -> "Français (French)"
                "de" -> "Deutsch (German)"
                "hi" -> "हिन्दी (Hindi)"
                else -> "English (System Default)"
            }
            SettingsItemCard(
                title = "Language",
                subtitle = languageLabel,
                icon = Icons.Outlined.Language,
                iconTint = PastelBlue,
                onClick = { showLanguageDialog = true }
            )

            // Section 2: PDF Tool Defaults
            SettingsSectionHeader(title = "PDF Processing Defaults")

            val compressionLabel = when (currentCompression) {
                "LIGHT" -> "Light Compression (200 DPI - High Quality)"
                "AGGRESSIVE" -> "Maximum Compression (96 DPI - Smallest Size)"
                else -> "Balanced Compression (150 DPI - Standard)"
            }
            SettingsItemCard(
                title = "Default Compression Level",
                subtitle = compressionLabel,
                icon = Icons.Outlined.Compress,
                iconTint = PastelOrange,
                onClick = { showCompressionDialog = true }
            )

            val pageSizeLabel = when (currentPageSize) {
                "LETTER" -> "US Letter (8.5 × 11 in)"
                else -> "A4 Standard (210 × 297 mm)"
            }
            SettingsItemCard(
                title = "Default Document Format",
                subtitle = pageSizeLabel,
                icon = Icons.Outlined.Description,
                iconTint = PastelYellow,
                onClick = { showPageSizeDialog = true }
            )

            // Section 3: Storage & Downloads
            SettingsSectionHeader(title = "Storage & Downloads")

            SettingsItemCard(
                title = "Default Save Location",
                subtitle = "Downloads / OfflinePDFStudio",
                icon = Icons.Outlined.Folder,
                iconTint = PastelGreen,
                trailingText = "Open",
                onClick = {
                    try {
                        val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    } catch (_: Exception) {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(Uri.parse(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path), "*/*")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            Toast.makeText(context, "Saved files are in Downloads/OfflinePDFStudio", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            )

            SettingsItemCard(
                title = "Direct Download Prompt",
                subtitle = if (directDownloadEnabled) "Enabled: Save button offers 1-click download" else "Disabled: Manual save only",
                icon = Icons.Outlined.Download,
                iconTint = PastelTeal,
                trailingText = if (directDownloadEnabled) "ON" else "OFF",
                onClick = {
                    directDownloadEnabled = !directDownloadEnabled
                    prefsManager.directDownloadEnabled = directDownloadEnabled
                    Toast.makeText(context, "Direct download ${if (directDownloadEnabled) "Enabled" else "Disabled"}", Toast.LENGTH_SHORT).show()
                }
            )

            SettingsItemCard(
                title = "Clear Temporary Cache",
                subtitle = "$cacheSizeKb KB temporary files",
                icon = Icons.Outlined.CleaningServices,
                iconTint = PastelRed,
                trailingText = "Clean",
                onClick = {
                    showClearCacheConfirm = true
                }
            )

            SettingsItemCard(
                title = "Internal Staged Documents",
                subtitle = "$documentsCount documents stored in app sandbox",
                icon = Icons.Outlined.FolderDelete,
                iconTint = PastelPurple,
                trailingText = if (documentsCount > 0) "Clear" else null,
                onClick = {
                    if (documentsCount > 0) {
                        showClearDocsConfirm = true
                    } else {
                        Toast.makeText(context, "No internal documents to clear.", Toast.LENGTH_SHORT).show()
                    }
                }
            )

            // Section 4: Privacy & Security
            SettingsSectionHeader(title = "Privacy & Security")

            SettingsItemCard(
                title = "100% On-Device Guarantee",
                subtitle = "Zero internet permissions. Tap to inspect security status.",
                icon = Icons.Outlined.Shield,
                iconTint = PinkAccent,
                trailingText = "Verified",
                onClick = { showPrivacyDialog = true }
            )

            SettingsItemCard(
                title = "Metadata Sanitizer",
                subtitle = "Strip author tracking & revision history from documents",
                icon = Icons.Outlined.Lock,
                iconTint = PastelCyan,
                onClick = { onNavigateTo(Screen.ToolAction("sanitize")) }
            )

            // Section 5: About
            SettingsSectionHeader(title = "About Application")

            SettingsItemCard(
                title = "Offline PDF Studio",
                subtitle = "Version 2.1.0 (Native Android) • All 36 Tools Active",
                icon = Icons.Outlined.Info,
                iconTint = PastelTeal,
                onClick = { showAboutDialog = true }
            )

            Spacer(modifier = Modifier.height(30.dp))
        }

        // --- Dialogs ---

        // Theme Dialog
        if (showThemeDialog) {
            AlertDialog(
                onDismissRequest = { showThemeDialog = false },
                containerColor = DarkSecondaryBg,
                title = { Text("Choose Theme", fontWeight = FontWeight.Bold, color = TextPrimaryDark) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "DARK" to "Studio Dark Mode",
                            "LIGHT" to "Clean Light Mode",
                            "AMOLED" to "AMOLED Black (Pure Dark)",
                            "SYSTEM" to "System Default"
                        ).forEach { (code, label) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        currentTheme = code
                                        prefsManager.themeMode = code
                                        showThemeDialog = false
                                        Toast.makeText(context, "Theme set to $label", Toast.LENGTH_SHORT).show()
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = (currentTheme == code),
                                    onClick = {
                                        currentTheme = code
                                        prefsManager.themeMode = code
                                        showThemeDialog = false
                                    },
                                    colors = RadioButtonDefaults.colors(selectedColor = PinkAccent, unselectedColor = TextSecondaryDark)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(label, color = TextPrimaryDark, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showThemeDialog = false }) { Text("Cancel", color = PinkAccent) }
                }
            )
        }

        // Language Dialog
        if (showLanguageDialog) {
            AlertDialog(
                onDismissRequest = { showLanguageDialog = false },
                containerColor = DarkSecondaryBg,
                title = { Text("Choose Language", fontWeight = FontWeight.Bold, color = TextPrimaryDark) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "en" to "English (Default)",
                            "es" to "Español (Spanish)",
                            "fr" to "Français (French)",
                            "de" to "Deutsch (German)",
                            "hi" to "हिन्दी (Hindi)"
                        ).forEach { (code, label) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        currentLanguage = code
                                        prefsManager.language = code
                                        showLanguageDialog = false
                                        Toast.makeText(context, "Language set to $label", Toast.LENGTH_SHORT).show()
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = (currentLanguage == code),
                                    onClick = {
                                        currentLanguage = code
                                        prefsManager.language = code
                                        showLanguageDialog = false
                                    },
                                    colors = RadioButtonDefaults.colors(selectedColor = PinkAccent, unselectedColor = TextSecondaryDark)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(label, color = TextPrimaryDark, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showLanguageDialog = false }) { Text("Cancel", color = PinkAccent) }
                }
            )
        }

        // Compression Dialog
        if (showCompressionDialog) {
            AlertDialog(
                onDismissRequest = { showCompressionDialog = false },
                containerColor = DarkSecondaryBg,
                title = { Text("Default Compression", fontWeight = FontWeight.Bold, color = TextPrimaryDark) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "MEDIUM" to "Balanced (150 DPI) • Recommended",
                            "LIGHT" to "Light (200 DPI) • High Visual Quality",
                            "AGGRESSIVE" to "Maximum (96 DPI) • Smallest File Size"
                        ).forEach { (code, label) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        currentCompression = code
                                        prefsManager.defaultCompression = code
                                        showCompressionDialog = false
                                        Toast.makeText(context, "Default compression updated", Toast.LENGTH_SHORT).show()
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = (currentCompression == code),
                                    onClick = {
                                        currentCompression = code
                                        prefsManager.defaultCompression = code
                                        showCompressionDialog = false
                                    },
                                    colors = RadioButtonDefaults.colors(selectedColor = PinkAccent, unselectedColor = TextSecondaryDark)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(label, color = TextPrimaryDark, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showCompressionDialog = false }) { Text("Cancel", color = PinkAccent) }
                }
            )
        }

        // Page Size Dialog
        if (showPageSizeDialog) {
            AlertDialog(
                onDismissRequest = { showPageSizeDialog = false },
                containerColor = DarkSecondaryBg,
                title = { Text("Default Page Format", fontWeight = FontWeight.Bold, color = TextPrimaryDark) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "A4" to "A4 Standard (210 × 297 mm)",
                            "LETTER" to "US Letter (8.5 × 11 in)"
                        ).forEach { (code, label) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        currentPageSize = code
                                        prefsManager.defaultPageSize = code
                                        showPageSizeDialog = false
                                        Toast.makeText(context, "Page size set to $label", Toast.LENGTH_SHORT).show()
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = (currentPageSize == code),
                                    onClick = {
                                        currentPageSize = code
                                        prefsManager.defaultPageSize = code
                                        showPageSizeDialog = false
                                    },
                                    colors = RadioButtonDefaults.colors(selectedColor = PinkAccent, unselectedColor = TextSecondaryDark)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(label, color = TextPrimaryDark, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showPageSizeDialog = false }) { Text("Cancel", color = PinkAccent) }
                }
            )
        }

        // Clear Cache Confirmation
        if (showClearCacheConfirm) {
            AlertDialog(
                onDismissRequest = { showClearCacheConfirm = false },
                containerColor = DarkSecondaryBg,
                title = { Text("Clear Cache?", fontWeight = FontWeight.Bold, color = TextPrimaryDark) },
                text = { Text("This will delete all $cacheSizeKb KB of temporary rendering files. Your saved PDF files will not be touched.", color = TextSecondaryDark) },
                confirmButton = {
                    TextButton(onClick = {
                        storageManager.clearTempFiles()
                        refreshMetrics()
                        showClearCacheConfirm = false
                        Toast.makeText(context, "Temporary cache cleared successfully!", Toast.LENGTH_SHORT).show()
                    }) {
                        Text("Clear Now", color = PinkAccent, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearCacheConfirm = false }) { Text("Cancel", color = TextSecondaryDark) }
                }
            )
        }

        // Clear Internal Documents Confirmation
        if (showClearDocsConfirm) {
            AlertDialog(
                onDismissRequest = { showClearDocsConfirm = false },
                containerColor = DarkSecondaryBg,
                title = { Text("Clear Internal Documents?", fontWeight = FontWeight.Bold, color = TextPrimaryDark) },
                text = { Text("This will remove $documentsCount files from the app's internal staging area. Files saved to your public phone Downloads folder will remain intact.", color = TextSecondaryDark) },
                confirmButton = {
                    TextButton(onClick = {
                        val docsDir = File(context.filesDir, "pdf_documents")
                        docsDir.listFiles()?.forEach { it.delete() }
                        refreshMetrics()
                        showClearDocsConfirm = false
                        Toast.makeText(context, "Internal staged documents removed", Toast.LENGTH_SHORT).show()
                    }) {
                        Text("Delete", color = PastelRed, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearDocsConfirm = false }) { Text("Cancel", color = TextSecondaryDark) }
                }
            )
        }

        // Privacy & Security Inspector Dialog
        if (showPrivacyDialog) {
            AlertDialog(
                onDismissRequest = { showPrivacyDialog = false },
                containerColor = DarkSecondaryBg,
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.VerifiedUser, contentDescription = null, tint = PinkAccent)
                        Text("100% Offline Guarantee", fontWeight = FontWeight.Bold, color = TextPrimaryDark)
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "Offline PDF Studio operates strictly on-device without any cloud backends.",
                            color = TextPrimaryDark,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        HorizontalDivider(color = BorderDark)
                        Text("🔒 INTERNET Permission: Not Declared in Manifest", fontWeight = FontWeight.SemiBold, color = StatusSuccess)
                        Text("🔒 Remote Analytics / Trackers: 0 Installed", fontWeight = FontWeight.SemiBold, color = StatusSuccess)
                        Text("🔒 Execution: Local JVM on Dispatchers.IO", fontWeight = FontWeight.SemiBold, color = StatusSuccess)
                        Text("🔒 File Storage: MediaStore Downloads & Local App Sandbox", fontWeight = FontWeight.SemiBold, color = StatusSuccess)
                        Text(
                            "Your documents, signatures, scanned photos, and credentials never leave this hardware.",
                            color = TextSecondaryDark,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showPrivacyDialog = false }) {
                        Text("Close", color = PinkAccent, fontWeight = FontWeight.Bold)
                    }
                }
            )
        }

        // About Application Dialog
        if (showAboutDialog) {
            AlertDialog(
                onDismissRequest = { showAboutDialog = false },
                containerColor = DarkSecondaryBg,
                titleContentColor = TextPrimaryDark,
                textContentColor = TextSecondaryDark,
                title = { Text("About Offline PDF Studio", fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("• Version: 2.1.0 (Production Ready)", color = TextPrimaryDark, fontWeight = FontWeight.SemiBold)
                        Text("• 36 Native PDF Utilities & Direct Downloads")
                        Text("• Built with Kotlin 2.1 & Jetpack Compose (Material 3)")
                        Text("• Engine: Apache PDFBox-Android & Android PdfRenderer")
                        Text("• 100% Offline with Zero Network Access")
                        Text("• Target SDK: API 36 (Android 16)")
                        Text("• License: Open Source (Apache 2.0)")
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showAboutDialog = false }) {
                        Text("Close", color = PinkAccent, fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = PinkAccent,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    )
}

@Composable
private fun SettingsItemCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconTint: Color,
    trailingText: String? = null,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = DarkToolCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark)
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
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(iconTint.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = icon, contentDescription = title, tint = iconTint, modifier = Modifier.size(22.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = TextPrimaryDark)
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondaryDark)
            }

            if (trailingText != null) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = DarkSecondaryBg,
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark)
                ) {
                    Text(
                        text = trailingText,
                        color = PinkAccent,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            } else {
                Icon(imageVector = Icons.Default.ChevronRight, contentDescription = null, tint = TextSecondaryDark)
            }
        }
    }
}
