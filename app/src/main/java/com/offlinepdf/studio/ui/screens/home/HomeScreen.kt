package com.offlinepdf.studio.ui.screens.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.offlinepdf.studio.ui.components.PrivacyBadge
import com.offlinepdf.studio.ui.components.ToolCard
import com.offlinepdf.studio.ui.navigation.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateTo: (Screen) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Offline PDF Studio",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
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
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            PrivacyBadge(modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))

            ToolCard(
                title = "Merge Documents",
                description = "Combine multiple PDF files into one clean document",
                icon = Icons.Default.CallMerge,
                iconTint = Color(0xFF1976D2),
                onClick = { onNavigateTo(Screen.Merge) }
            )

            ToolCard(
                title = "Split PDF",
                description = "Extract pages or separate specific chapter ranges",
                icon = Icons.Default.CallSplit,
                iconTint = Color(0xFF00796B),
                onClick = { onNavigateTo(Screen.Split) }
            )

            ToolCard(
                title = "Reorder & Delete Pages",
                description = "Visual thumbnail grid to reorganize or remove pages",
                icon = Icons.Default.ViewAgenda,
                iconTint = Color(0xFFF57C00),
                onClick = { onNavigateTo(Screen.Reorder) }
            )

            ToolCard(
                title = "Compress PDF",
                description = "On-device image downsampling to reduce file size",
                icon = Icons.Default.Compress,
                iconTint = Color(0xFF7B1FA2),
                onClick = { onNavigateTo(Screen.Compress) }
            )

            ToolCard(
                title = "View PDF",
                description = "Fast, hardware-accelerated offline document viewer",
                icon = Icons.Default.Visibility,
                iconTint = Color(0xFF388E3C),
                onClick = { onNavigateTo(Screen.Viewer) }
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
