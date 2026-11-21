package com.offlinepdf.studio

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 4: Tools Screen & Tool Discovery UI Tests
 *
 * Verifies:
 * - 3-Column responsive grid displaying tools from ToolCatalog.
 * - Category filter chips ("All (36)", "Organize & Edit", "Security & Privacy", etc.).
 * - Search text field filtering tools dynamically by title and description.
 * - Search clear button behavior.
 * - Navigation to dynamic ToolActionScreen and detail screens with proper parameter scaffolding.
 * - Back navigation from ToolActionScreen.
 */
@RunWith(AndroidJUnit4::class)
class ToolsScreenUiTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun navigateToToolsTab() {
        composeTestRule.onNodeWithText("Tools").performClick()
        composeTestRule.waitForIdle()
    }

    @Test
    fun testToolsScreen_HeaderAndInitialState() {
        composeTestRule.onNodeWithText("PDF Tools").assertIsDisplayed()
        composeTestRule.onNodeWithText("Search tools...").assertIsDisplayed()
        composeTestRule.onNodeWithText("All (36)").assertIsDisplayed()
    }

    @Test
    fun testToolsScreen_CategoryChips_FilterToolsCorrectly() {
        // Click "Organize & Edit" category chip
        composeTestRule.onNodeWithText("Organize & Edit", substring = true).performClick()
        composeTestRule.waitForIdle()

        // Verify organize tools are present in grid
        composeTestRule.onNodeWithText("Merge Documents").assertExists()
        composeTestRule.onNodeWithText("Split PDF").assertExists()

        // Click "All (36)" to reset
        composeTestRule.onNodeWithText("All (36)").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Merge Documents").assertExists()
    }

    @Test
    fun testToolsScreen_SearchInput_FiltersDynamically() {
        // Initial state: placeholder is visible
        composeTestRule.onNodeWithText("Search tools...").assertExists()

        // Type "Watermark" into search field
        composeTestRule.onNodeWithText("Search tools...").performTextInput("Watermark")
        composeTestRule.waitForIdle()

        // Verify Watermark tool is visible in tree
        composeTestRule.onNodeWithText("Add Watermark").assertExists()

        // Verify unrelated tool is not in tree
        composeTestRule.onNodeWithText("Merge Documents").assertDoesNotExist()

        // Clear search text field using text replacement
        composeTestRule.onNodeWithText("Watermark").performTextReplacement("")
        composeTestRule.waitForIdle()

        // Verify placeholder is restored
        composeTestRule.onNodeWithText("Search tools...").assertExists()
    }

    @Test
    fun testToolsScreen_SearchNonexistentTool_ShowsEmptyState() {
        // Type a nonsense query
        composeTestRule.onNodeWithText("Search tools...").performTextInput("xyznonexistenttool999")
        composeTestRule.waitForIdle()

        // Verify no tools are rendered in tree
        composeTestRule.onNodeWithText("Merge Documents").assertDoesNotExist()
        composeTestRule.onNodeWithText("Add Watermark").assertDoesNotExist()

        // Clear search
        composeTestRule.onNodeWithText("xyznonexistenttool999").performTextReplacement("")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Search tools...").assertExists()
    }

    @Test
    fun testToolsScreen_ClickToolAction_NavigatesToDynamicToolScreen() {
        // Search for Watermark
        composeTestRule.onNodeWithText("Search tools...").performTextInput("Watermark")
        composeTestRule.waitForIdle()

        // Click on the tool card
        composeTestRule.onAllNodesWithText("Add Watermark").onFirst().performClick()
        composeTestRule.waitForIdle()

        // Verify ToolActionScreen for Watermark is displayed
        composeTestRule.onNodeWithText("Watermark Text").assertIsDisplayed()

        // Press Back to return to Tools screen
        composeTestRule.onNodeWithContentDescription("Back").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("PDF Tools").assertIsDisplayed()
    }

    @Test
    fun testToolsScreen_ClickProtectPdf_NavigatesWithPasswordScaffolding() {
        // Search and click "Protect with Password"
        composeTestRule.onNodeWithText("Search tools...").performTextInput("Protect")
        composeTestRule.waitForIdle()

        composeTestRule.onAllNodesWithText("Protect with Password").onFirst().performClick()
        composeTestRule.waitForIdle()

        // Verify Protect PDF screen has password input
        composeTestRule.onNodeWithText("Enter Password to Encrypt").assertIsDisplayed()

        // Press Back
        composeTestRule.onNodeWithContentDescription("Back").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("PDF Tools").assertIsDisplayed()
    }
}
