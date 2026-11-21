package com.offlinepdf.studio

import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.offlinepdf.studio.ui.screens.viewer.ViewerScreen
import com.offlinepdf.studio.ui.theme.OfflinePdfStudioTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase 4: PDF Viewer Screen & Interaction UI Tests
 *
 * Verifies:
 * - Empty state when no document is active ("Select PDF Document to View").
 * - Dynamic page rendering via PdfRendererHelper.
 * - Multi-page navigation: Next / Previous buttons, page counter ("1 / 3", "2 / 3", "3 / 3").
 * - Bound checking: Previous button disabled on page 1, Next button disabled on last page.
 * - TopAppBar back navigation triggering the onBack callback.
 */
@RunWith(AndroidJUnit4::class)
class PdfViewerUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testViewerScreen_EmptyState_PromptShown() {
        var backPressed = false
        composeTestRule.setContent {
            OfflinePdfStudioTheme {
                ViewerScreen(
                    initialUri = null,
                    onBack = { backPressed = true }
                )
            }
        }

        // Verify empty state button is visible
        composeTestRule.onNodeWithText("Select PDF Document to View").assertIsDisplayed()
        composeTestRule.onNodeWithText("PDF Viewer").assertIsDisplayed()

        // Test Back button
        composeTestRule.onNodeWithContentDescription("Back").performClick()
        assertTrue("onBack callback must be invoked", backPressed)
    }

    @Test
    fun testViewerScreen_SinglePageDocument_RendersPageAndDisablesBottomPaging() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val testPdf = File(context.cacheDir, "test_viewer_single.pdf")
        PdfEngineTestFixtures.createMultiPagePdf(testPdf, 1)

        composeTestRule.setContent {
            OfflinePdfStudioTheme {
                ViewerScreen(
                    initialUri = Uri.fromFile(testPdf),
                    onBack = {}
                )
            }
        }

        composeTestRule.waitForIdle()

        // Top bar shows page title
        composeTestRule.onNodeWithText("Page 1 of 1").assertIsDisplayed()

        // For a single page document, bottom navigation bar is not displayed
        composeTestRule.onNodeWithContentDescription("Next Page").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Previous Page").assertDoesNotExist()

        testPdf.delete()
    }

    @Test
    fun testViewerScreen_MultiPageDocument_NavigationNextAndPrevious() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val testPdf = File(context.cacheDir, "test_viewer_multipage.pdf")
        PdfEngineTestFixtures.createMultiPagePdf(testPdf, 3)

        composeTestRule.setContent {
            OfflinePdfStudioTheme {
                ViewerScreen(
                    initialUri = Uri.fromFile(testPdf),
                    onBack = {}
                )
            }
        }

        composeTestRule.waitForIdle()

        // Page 1 of 3
        composeTestRule.onNodeWithText("Page 1 of 3").assertIsDisplayed()
        composeTestRule.onNodeWithText("1 / 3").assertIsDisplayed()

        // Previous Page is disabled on page 1
        composeTestRule.onNodeWithContentDescription("Previous Page").assertIsNotEnabled()
        // Next Page is enabled
        composeTestRule.onNodeWithContentDescription("Next Page").assertIsEnabled()

        // Click Next Page -> Page 2
        composeTestRule.onNodeWithContentDescription("Next Page").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Page 2 of 3").assertIsDisplayed()
        composeTestRule.onNodeWithText("2 / 3").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Previous Page").assertIsEnabled()
        composeTestRule.onNodeWithContentDescription("Next Page").assertIsEnabled()

        // Click Next Page -> Page 3
        composeTestRule.onNodeWithContentDescription("Next Page").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Page 3 of 3").assertIsDisplayed()
        composeTestRule.onNodeWithText("3 / 3").assertIsDisplayed()
        // On last page, Next Page is disabled
        composeTestRule.onNodeWithContentDescription("Next Page").assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription("Previous Page").assertIsEnabled()

        // Click Previous Page -> Page 2
        composeTestRule.onNodeWithContentDescription("Previous Page").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Page 2 of 3").assertIsDisplayed()
        composeTestRule.onNodeWithText("2 / 3").assertIsDisplayed()

        testPdf.delete()
    }
}
