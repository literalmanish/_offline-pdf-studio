package com.offlinepdf.studio.ui.components

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.offlinepdf.studio.core.engine.model.ProcessingProgress
import com.offlinepdf.studio.core.engine.model.StudioTool
import com.offlinepdf.studio.core.engine.model.ToolCategory
import com.offlinepdf.studio.core.util.FileStorageManager
import com.offlinepdf.studio.ui.navigation.BottomNavItem
import com.offlinepdf.studio.ui.theme.*
import java.io.File

@Composable
fun AppBottomNavigationBar(
    currentRoute: String,
    onNavigate: (String) -> Unit
) {
    NavigationBar(
        containerColor = DarkBottomNav,
        tonalElevation = 8.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        BottomNavItem.entries.forEach { item ->
            val isSelected = currentRoute == item.route
            NavigationBarItem(
                selected = isSelected,
                onClick = { onNavigate(item.route) },
                icon = {
                    Icon(
                        imageVector = if (isSelected) item.selectedIcon else item.unselectedIcon,
                        contentDescription = item.title,
                        modifier = Modifier.size(22.dp)
                    )
                },
                label = {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = DarkMainBg,
                    selectedTextColor = TextPrimaryDark,
                    indicatorColor = PinkAccent,
                    unselectedIconColor = TextSecondaryDark,
                    unselectedTextColor = TextSecondaryDark
                )
            )
        }
    }
}

@Composable
fun PrivacyBadge(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = DarkSecondaryBg,
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = "Offline indicator",
                tint = PinkAccent,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = "100% Offline • Zero Data Leaves Device",
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimaryDark,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun ThreeColumnToolItem(
    tool: StudioTool,
    onClick: () -> Unit
) {
    val (icon, pastelColor) = getPastelToolIconAndTint(tool.iconName, tool.category)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(115.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkToolCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(pastelColor.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = tool.title,
                    tint = pastelColor,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = tool.title,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = TextPrimaryDark,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun StorageDashboardCard(
    title: String,
    count: Int,
    icon: ImageVector,
    iconColor: Color,
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
                    .size(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(iconColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = icon, contentDescription = title, tint = iconColor, modifier = Modifier.size(22.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = TextPrimaryDark)
                Text(text = "$count files", style = MaterialTheme.typography.bodySmall, color = TextSecondaryDark)
            }

            Icon(imageVector = Icons.Default.ChevronRight, contentDescription = null, tint = TextSecondaryDark)
        }
    }
}

@Composable
fun FileItemCard(
    file: File,
    isFavorite: Boolean = false,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = DarkToolCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark)
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(PinkAccent.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.PictureAsPdf, contentDescription = null, tint = PinkAccent, modifier = Modifier.size(22.dp))
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimaryDark,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${file.length() / 1024} KB",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondaryDark
                )
            }

            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (isFavorite) Icons.Default.Star else Icons.Outlined.StarBorder,
                    contentDescription = "Favorite",
                    tint = if (isFavorite) PastelYellow else TextSecondaryDark
                )
            }

            IconButton(onClick = onShare) {
                Icon(Icons.Default.Share, contentDescription = "Share", tint = TextSecondaryDark)
            }

            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = StatusError)
            }
        }
    }
}

@Composable
fun ProcessingDialog(
    progress: ProcessingProgress,
    onCancel: () -> Unit
) {
    Dialog(onDismissRequest = onCancel) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = DarkSecondaryBg,
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark),
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Processing PDF",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimaryDark
                )
                Spacer(modifier = Modifier.height(16.dp))

                LinearProgressIndicator(
                    progress = { progress.percentage },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = PinkAccent,
                    trackColor = PinkAccent.copy(alpha = 0.2f),
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = progress.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondaryDark
                )

                Spacer(modifier = Modifier.height(20.dp))

                OutlinedButton(
                    onClick = onCancel,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimaryDark)
                ) {
                    Text("Cancel")
                }
            }
        }
    }
}

@Composable
fun ProcessCompleteDownloadDialog(
    file: File,
    title: String = "Processing Complete! 🎉",
    description: String? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val storageManager = remember { FileStorageManager(context) }
    var isSavedToDownloads by remember { mutableStateOf(false) }

    val createDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { destUri ->
        if (destUri != null) {
            val ok = storageManager.copyFileToUri(file, destUri)
            if (ok) {
                Toast.makeText(context, "Saved successfully!", Toast.LENGTH_SHORT).show()
                isSavedToDownloads = true
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSecondaryBg,
        titleContentColor = TextPrimaryDark,
        textContentColor = TextSecondaryDark,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    description ?: "Your document is ready: ${file.name} (${file.length() / 1024} KB)",
                    color = TextSecondaryDark
                )

                if (isSavedToDownloads) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = StatusSuccess.copy(alpha = 0.15f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = StatusSuccess)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Saved to device Downloads folder!",
                                color = StatusSuccess,
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                // 1-Click Direct Download to Phone
                Button(
                    onClick = {
                        val uri = storageManager.saveToPublicDownloads(file)
                        if (uri != null) {
                            isSavedToDownloads = true
                            Toast.makeText(context, "Downloaded to phone: Downloads/OfflinePDFStudio", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(context, "Could not save directly. Try 'Save As...'", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PinkAccent,
                        contentColor = DarkMainBg
                    )
                ) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (isSavedToDownloads) "Downloaded ✓" else "Direct Download to Phone",
                        fontWeight = FontWeight.Bold
                    )
                }

                // Choose folder Save As
                OutlinedButton(
                    onClick = { createDocLauncher.launch(file.name) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimaryDark)
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Save As (Choose Folder)...")
                }

                // Share
                OutlinedButton(
                    onClick = {
                        val shareable = storageManager.getShareableUri(file)
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = if (file.name.endsWith(".txt")) "text/plain" else "application/pdf"
                            putExtra(Intent.EXTRA_STREAM, shareable)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "Share Document"))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimaryDark)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Share to Apps")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done", color = PinkAccent)
            }
        }
    )
}

fun getPastelToolIconAndTint(name: String, cat: ToolCategory): Pair<ImageVector, Color> {
    val tint = when (cat) {
        ToolCategory.ORGANIZE -> PastelBlue
        ToolCategory.CONVERT -> PastelGreen
        ToolCategory.SECURITY -> PastelRed
        ToolCategory.BRANDING -> PastelPurple
        ToolCategory.OPTIMIZE -> PastelOrange
        ToolCategory.VIEW -> PastelTeal
    }

    val icon = when (name) {
        "CallMerge" -> Icons.Default.CallMerge
        "CallSplit" -> Icons.Default.CallSplit
        "ViewAgenda" -> Icons.Default.ViewAgenda
        "RotateRight" -> Icons.Default.RotateRight
        "SwapVert" -> Icons.Default.SwapVert
        "ContentCopy" -> Icons.Default.ContentCopy
        "NoteAdd" -> Icons.Default.NoteAdd
        "Crop" -> Icons.Default.Crop
        "Dashboard" -> Icons.Default.Dashboard
        "LayersClear" -> Icons.Default.LayersClear
        "PhotoLibrary" -> Icons.Default.PhotoLibrary
        "Image" -> Icons.Default.Image
        "Filter" -> Icons.Default.Filter
        "Article" -> Icons.Default.Article
        "TextFields" -> Icons.Default.TextFields
        "Lock" -> Icons.Default.Lock
        "LockOpen" -> Icons.Default.LockOpen
        "Layers" -> Icons.Default.Layers
        "Badge" -> Icons.Default.Badge
        "CleaningServices" -> Icons.Default.CleaningServices
        "Build" -> Icons.Default.Build
        "BrandingWatermark" -> Icons.Default.BrandingWatermark
        "FormatListNumbered" -> Icons.Default.FormatListNumbered
        "BorderLeft" -> Icons.Default.BorderLeft
        "Title" -> Icons.Default.Title
        "Approval" -> Icons.Default.Approval
        "Draw" -> Icons.Default.Draw
        "Compress" -> Icons.Default.Compress
        "Contrast" -> Icons.Default.Contrast
        "DarkMode" -> Icons.Default.DarkMode
        "DeleteSweep" -> Icons.Default.DeleteSweep
        "Assessment" -> Icons.Default.Assessment
        "Visibility" -> Icons.Default.Visibility
        "Bookmarks" -> Icons.Default.Bookmarks
        "Compare" -> Icons.Default.Compare
        else -> Icons.Default.PictureAsPdf
    }
    return Pair(icon, tint)
}
