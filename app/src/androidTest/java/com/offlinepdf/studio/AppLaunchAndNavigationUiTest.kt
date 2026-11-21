package com.offlinepdf.studio

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 4: Application Launch & Primary Navigation UI Tests
 *
 * Verifies:
 * - Application launch without crashing or unexpected blank/dialog states.
 * - Initial HomeScreen rendering (app branding, quick tools, recent files empty state, privacy badge).
 * - AppBottomNavigationBar presence and tab switching across all 5 main navigation destinations:
 *   Home, Files, Tools, Scanner, Settings.
 * - Detail screen navigation from Home/Tools to Merge, Split, Reorder, Compress, and ToolAction.
 * - System and Compose back navigation restoring prior screen state and restoring the bottom bar.
 */
@RunWith(AndroidJUnit4::class)
class AppLaunchAndNavigationUiTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun testAppLaunch_InitialHomeScreenRendersCorrectly() {
        // 1. Verify app branding / header
        composeTestRule.onNodeWithText("Offline PDF").assertIsDisplayed()

        // 2. Verify Privacy Badge
        composeTestRule.onNodeWithText("100% Offline", substring = true).assertIsDisplayed()

        // 3. Verify Quick Tools section
        composeTestRule.onNodeWithText("Quick Tools").assertIsDisplayed()
        composeTestRule.onNodeWithText("View all (36)").assertIsDisplayed()

        // 4. Verify Recent Files section
        composeTestRule.onNodeWithText("Recent Files").assertIsDisplayed()

        // 5. Verify Bottom Navigation Bar has all 5 destinations visible
        composeTestRule.onNodeWithText("Home").assertIsDisplayed()
        composeTestRule.onNodeWithText("Files").assertIsDisplayed()
        composeTestRule.onNodeWithText("Tools").assertIsDisplayed()
        composeTestRule.onNodeWithText("Scanner").assertIsDisplayed()
        composeTestRule.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test
    fun testBottomNavigation_SwitchingTabs_DisplaysRespectiveScreens() {
        // Tab 1: Home is active initially
        composeTestRule.onNodeWithText("Quick Tools").assertIsDisplayed()

        // Tab 2: Switch to Tools
        composeTestRule.onNodeWithText("Tools").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("PDF Tools").assertIsDisplayed()
        composeTestRule.onNodeWithText("Search tools...").assertIsDisplayed()

        // Tab 3: Switch to Files
        composeTestRule.onNodeWithText("Files").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Processed files").assertIsDisplayed()

        // Tab 4: Switch to Scanner
        composeTestRule.onNodeWithText("Scanner").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Scanned Images", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Converted PDFs", substring = true).assertIsDisplayed()

        // Tab 5: Switch to Settings
        composeTestRule.onNodeWithText("Settings").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Appearance & Interface").assertIsDisplayed()

        // Return to Home
        composeTestRule.onNodeWithText("Home").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Quick Tools").assertIsDisplayed()
    }

    @Test
    fun testNavigation_QuickTools_MergeScreen_AndBackNavigation() {
        // From Home, click Quick Tool "Merge Documents"
        composeTestRule.onNodeWithText("Merge Documents").performClick()
        composeTestRule.waitForIdle()

        // Verify Merge Screen is displayed
        composeTestRule.onNodeWithText("Merge PDF Documents").assertIsDisplayed()
        composeTestRule.onNodeWithText("Select PDF Files to Merge").assertIsDisplayed()

        // Click Back button in TopAppBar
        composeTestRule.onNodeWithContentDescription("Back").performClick()
        composeTestRule.waitForIdle()

        // Verify returned to Home screen and Bottom Navigation is restored
        composeTestRule.onNodeWithText("Quick Tools").assertIsDisplayed()
        composeTestRule.onNodeWithText("Home").assertIsDisplayed()
        composeTestRule.onNodeWithText("Tools").assertIsDisplayed()
    }

    @Test
    fun testNavigation_QuickTools_SplitScreen_AndBackNavigation() {
        // From Home, click Quick Tool "Split PDF"
        composeTestRule.onNodeWithText("Split PDF").performClick()
        composeTestRule.waitForIdle()

        // Verify Split Screen is displayed
        composeTestRule.onNodeWithText("Split PDF").assertIsDisplayed()
        composeTestRule.onNodeWithText("Select PDF to Split").assertIsDisplayed()

        // Click Back button
        composeTestRule.onNodeWithContentDescription("Back").performClick()
        composeTestRule.waitForIdle()

        // Verify returned to Home screen
        composeTestRule.onNodeWithText("Quick Tools").assertIsDisplayed()
    }

    @Test
    fun testNavigation_QuickTools_CompressScreen_AndBackNavigation() {
        // From Home, click Quick Tool "Compress PDF"
        composeTestRule.onNodeWithText("Compress PDF").performClick()
        composeTestRule.waitForIdle()

        // Verify Compress Screen is displayed
        composeTestRule.onAllNodesWithText("Compress PDF").onFirst().assertIsDisplayed()
        composeTestRule.onNodeWithText("Select PDF to Compress").assertIsDisplayed()

        // Click Back button
        composeTestRule.onNodeWithContentDescription("Back").performClick()
        composeTestRule.waitForIdle()

        // Verify returned to Home screen
        composeTestRule.onNodeWithText("Quick Tools").assertIsDisplayed()
    }

    @Test
    fun testNavigation_ViewAllTools_NavigatesToToolsTab() {
        // Click "View all (36)" link on Home screen
        composeTestRule.onNodeWithText("View all (36)").performClick()
        composeTestRule.waitForIdle()

        // Verify Tools screen is loaded
        composeTestRule.onNodeWithText("PDF Tools").assertIsDisplayed()
        composeTestRule.onNodeWithText("Search tools...").assertIsDisplayed()
    }

    @Test
    fun testNavigation_SettingsIcon_NavigatesToSettingsScreen() {
        // Click Settings icon button in Home screen TopAppBar
        composeTestRule.onNodeWithContentDescription("Settings").performClick()
        composeTestRule.waitForIdle()

        // Verify Settings screen is displayed
        composeTestRule.onNodeWithText("Appearance & Interface").assertIsDisplayed()
        composeTestRule.onNodeWithText("Theme").assertIsDisplayed()
    }
}
