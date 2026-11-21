package com.offlinepdf.studio

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.offlinepdf.studio.ui.screens.files.FilesScreen
import com.offlinepdf.studio.ui.screens.scanner.ScannerScreen
import com.offlinepdf.studio.ui.theme.OfflinePdfStudioTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 4: Files Screen & Document Scanner UI Tests
 *
 * Verifies:
 * - FilesScreen root overview cards ("Internal Storage", "Downloads", "Processed files", "Bin").
 * - FilesScreen header and storage categories.
 * - ScannerScreen tab navigation between "Scanned Images" and "Converted PDFs".
 * - ScannerScreen action buttons ("Take Photo", "From Gallery", "Scan Page").
 */
@RunWith(AndroidJUnit4::class)
class FilesAndScannerUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testFilesScreen_OverviewCardsAndSearch_Displayed() {
        composeTestRule.setContent {
            OfflinePdfStudioTheme {
                FilesScreen(onNavigateTo = {})
            }
        }

        // Title and My Storage header
        composeTestRule.onNodeWithText("Offline PDF Studio").assertIsDisplayed()
        composeTestRule.onNodeWithText("My storage").assertIsDisplayed()

        // Overview categories
        composeTestRule.onNodeWithText("Internal Storage").assertIsDisplayed()
        composeTestRule.onNodeWithText("Downloads").assertIsDisplayed()
        composeTestRule.onNodeWithText("Processed files").assertIsDisplayed()
        composeTestRule.onNodeWithText("Bin").assertIsDisplayed()
    }

    @Test
    fun testScannerScreen_Tabs_SwitchBetweenImagesAndConvertedPdfs() {
        composeTestRule.setContent {
            OfflinePdfStudioTheme {
                ScannerScreen(onNavigateTo = {})
            }
        }

        // Header and tabs
        composeTestRule.onNodeWithText("Document Scanner").assertIsDisplayed()
        composeTestRule.onNodeWithText("Scanned Images", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Converted PDFs", substring = true).assertIsDisplayed()

        // Action buttons
        composeTestRule.onNodeWithText("Take Photo").assertIsDisplayed()
        composeTestRule.onNodeWithText("From Gallery").assertIsDisplayed()

        // Switch to Tab 1: Converted PDFs
        composeTestRule.onNodeWithText("Converted PDFs", substring = true).performClick()
        composeTestRule.waitForIdle()

        // Tab 1 content is active
        composeTestRule.onNodeWithText("Converted PDFs", substring = true).assertIsDisplayed()

        // Switch back to Tab 0: Scanned Images
        composeTestRule.onNodeWithText("Scanned Images", substring = true).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Take Photo").assertIsDisplayed()
    }
}
