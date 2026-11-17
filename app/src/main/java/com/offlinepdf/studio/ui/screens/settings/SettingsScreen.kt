package com.offlinepdf.studio.ui.screens.settings

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
import com.offlinepdf.studio.core.util.FileStorageManager
import com.offlinepdf.studio.ui.navigation.Screen
import com.offlinepdf.studio.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateTo: (Screen) -> Unit
) {
    val context = LocalContext.current
    val storageManager = remember { FileStorageManager(context) }
    var cacheSizeKb by remember { mutableLongStateOf(0L) }
    var showAboutDialog by remember { mutableStateOf(false) }

    fun calculateCache() {
        var total = 0L
        context.cacheDir.walkTopDown().forEach { total += it.length() }
        cacheSizeKb = total / 1024
    }

    LaunchedEffect(Unit) {
        calculateCache()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimaryDark
                    )
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
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Section: Appearance
            SettingsSectionHeader(title = "Appearance & Interface")
            SettingsItemCard(
                title = "Theme",
                subtitle = "Dark Mode (iLovePDF Style)",
                icon = Icons.Outlined.DarkMode,
                iconTint = PastelPurple,
                onClick = {}
            )
            SettingsItemCard(
                title = "Language",
                subtitle = "English (System Default)",
                icon = Icons.Outlined.Language,
                iconTint = PastelBlue,
                onClick = {}
            )

            // Section: Storage
            SettingsSectionHeader(title = "Storage & Downloads")
            SettingsItemCard(
                title = "Default Save Location",
                subtitle = "Downloads / OfflinePDFStudio",
                icon = Icons.Outlined.Folder,
                iconTint = PastelGreen,
                onClick = {
                    Toast.makeText(context, "Saved files go to phone Downloads/OfflinePDFStudio", Toast.LENGTH_SHORT).show()
                }
            )
            SettingsItemCard(
                title = "Clear Temporary Cache",
                subtitle = "$cacheSizeKb KB temporary files",
                icon = Icons.Outlined.CleaningServices,
                iconTint = PastelOrange,
                trailingText = "Clear",
                onClick = {
                    storageManager.clearTempFiles()
                    calculateCache()
                    Toast.makeText(context, "Cache cleaned successfully!", Toast.LENGTH_SHORT).show()
                }
            )

            // Section: Privacy & Security
            SettingsSectionHeader(title = "Privacy & Security")
            SettingsItemCard(
                title = "100% On-Device Guarantee",
                subtitle = "No internet permissions requested. Zero data leaves your phone.",
                icon = Icons.Outlined.Shield,
                iconTint = PinkAccent,
                onClick = {}
            )
            SettingsItemCard(
                title = "Metadata Sanitizer",
                subtitle = "Strip author tracking & revision history from documents",
                icon = Icons.Outlined.Lock,
                iconTint = PastelRed,
                onClick = { onNavigateTo(Screen.ToolAction("sanitize")) }
            )

            // Section: About
            SettingsSectionHeader(title = "About Application")
            SettingsItemCard(
                title = "Offline PDF Studio",
                subtitle = "Version 2.1.0 (Native Android)",
                icon = Icons.Outlined.Info,
                iconTint = PastelTeal,
                onClick = { showAboutDialog = true }
            )

            Spacer(modifier = Modifier.height(24.dp))
        }

        if (showAboutDialog) {
            AlertDialog(
                onDismissRequest = { showAboutDialog = false },
                containerColor = DarkSecondaryBg,
                titleContentColor = TextPrimaryDark,
                textContentColor = TextSecondaryDark,
                title = { Text("About Offline PDF Studio", fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("• 36 Native PDF Utilities")
                        Text("• Built with Kotlin & Jetpack Compose")
                        Text("• Powered by Apache PDFBox-Android & Android PdfRenderer")
                        Text("• 100% Offline with Zero Network Access")
                        Text("• Version 2.1.0 (API 36)")
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showAboutDialog = false }) {
                        Text("Close", color = PinkAccent)
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
                Text(text = trailingText, color = PinkAccent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            } else {
                Icon(imageVector = Icons.Default.ChevronRight, contentDescription = null, tint = TextSecondaryDark)
            }
        }
    }
}
