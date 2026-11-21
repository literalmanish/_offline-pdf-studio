package com.offlinepdf.studio

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.offlinepdf.studio.ui.screens.compress.CompressScreen
import com.offlinepdf.studio.ui.screens.merge.MergeScreen
import com.offlinepdf.studio.ui.screens.split.SplitScreen
import com.offlinepdf.studio.ui.screens.tool.ToolActionScreen
import com.offlinepdf.studio.ui.theme.OfflinePdfStudioTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 4: PDF Operations & Scaffolding UI Tests
 *
 * Verifies:
 * - CompressScreen UI controls, button state, and back navigation.
 * - MergeScreen empty state, file selection prompt, and back navigation.
 * - SplitScreen prompt, file selection button, and back navigation.
 * - ToolActionScreen dynamic parameter controls for Watermark, Rotate, and Protect.
 */
@RunWith(AndroidJUnit4::class)
class PdfOperationsUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testCompressScreen_ControlsAndLevels() {
        var backPressed = false
        composeTestRule.setContent {
            OfflinePdfStudioTheme {
                CompressScreen(onBack = { backPressed = true })
            }
        }

        // Title and file picker button
        composeTestRule.onNodeWithText("Compress PDF").assertIsDisplayed()
        composeTestRule.onNodeWithText("Select PDF to Compress").assertIsDisplayed()

        // Back button
        composeTestRule.onNodeWithContentDescription("Back").performClick()
        assertTrue(backPressed)
    }

    @Test
    fun testMergeScreen_EmptyStateAndControls() {
        var backPressed = false
        composeTestRule.setContent {
            OfflinePdfStudioTheme {
                MergeScreen(onBack = { backPressed = true })
            }
        }

        composeTestRule.onNodeWithText("Merge PDF Documents").assertIsDisplayed()
        composeTestRule.onNodeWithText("Select PDF Files to Merge").assertIsDisplayed()
        composeTestRule.onNodeWithText("No PDFs selected yet.\nTap the button above to add at least 2 documents.").assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription("Back").performClick()
        assertTrue(backPressed)
    }

    @Test
    fun testSplitScreen_EmptyStateAndControls() {
        var backPressed = false
        composeTestRule.setContent {
            OfflinePdfStudioTheme {
                SplitScreen(onBack = { backPressed = true })
            }
        }

        composeTestRule.onNodeWithText("Split PDF").assertIsDisplayed()
        composeTestRule.onNodeWithText("Select PDF to Split").assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription("Back").performClick()
        assertTrue(backPressed)
    }

    @Test
    fun testToolAction_Watermark_InputParameters() {
        var backPressed = false
        composeTestRule.setContent {
            OfflinePdfStudioTheme {
                ToolActionScreen(
                    toolId = "watermark",
                    onBack = { backPressed = true }
                )
            }
        }

        composeTestRule.onAllNodesWithText("Add Watermark").onFirst().assertIsDisplayed()
        composeTestRule.onNodeWithText("Watermark Text").assertIsDisplayed()

        // Input custom watermark text
        composeTestRule.onNodeWithText("Watermark Text").performTextInput("CONFIDENTIAL")
        composeTestRule.waitForIdle()

        // Back navigation
        composeTestRule.onNodeWithContentDescription("Back").performClick()
        assertTrue(backPressed)
    }

    @Test
    fun testToolAction_ProtectPdf_PasswordFields() {
        var backPressed = false
        composeTestRule.setContent {
            OfflinePdfStudioTheme {
                ToolActionScreen(
                    toolId = "protect",
                    onBack = { backPressed = true }
                )
            }
        }

        composeTestRule.onAllNodesWithText("Protect with Password").onFirst().assertIsDisplayed()
        composeTestRule.onNodeWithText("Enter Password to Encrypt").assertIsDisplayed()

        composeTestRule.onNodeWithText("Enter Password to Encrypt").performTextInput("SecretPass123")
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Back").performClick()
        assertTrue(backPressed)
    }

    @Test
    fun testToolAction_RotatePages_Controls() {
        var backPressed = false
        composeTestRule.setContent {
            OfflinePdfStudioTheme {
                ToolActionScreen(
                    toolId = "rotate",
                    onBack = { backPressed = true }
                )
            }
        }

        composeTestRule.onAllNodesWithText("Rotate Pages").onFirst().assertIsDisplayed()
        composeTestRule.onNodeWithText("Select Rotation Angle:").assertIsDisplayed()
        composeTestRule.onNodeWithText("90°").assertIsDisplayed()
        composeTestRule.onNodeWithText("180°").assertIsDisplayed()
        composeTestRule.onNodeWithText("270°").assertIsDisplayed()

        // Select 180 degrees
        composeTestRule.onNodeWithText("180°").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Back").performClick()
        assertTrue(backPressed)
    }
}
