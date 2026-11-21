package fr.cutz

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import fr.cutz.data.db.ShortcutStatus
import fr.cutz.ui.MainActivity
import fr.cutz.ui.create.CreateTags
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * End-to-end: fill the Create form for a web link, pick a bundled icon, request the pin,
 * accept the launcher's system dialog with UiAutomator, then check that the shortcut is
 * pinned and stored as ACTIF.
 *
 * Needs an emulator/device whose default launcher supports pinning (AOSP Launcher3,
 * Pixel Launcher, GrapheneOS launcher). Skipped otherwise.
 */
@RunWith(AndroidJUnit4::class)
class CreateAndPinTest {

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = (context.applicationContext as CutzApp).container

    @Test
    fun createWebLinkAndPinIt() {
        assumeTrue("launcher cannot pin", ShortcutManagerCompat.isRequestPinShortcutSupported(context))
        val name = "Test ${System.currentTimeMillis() % 100000}"

        compose.onNodeWithTag(CreateTags.TYPE_URL).performClick()
        compose.onNodeWithTag(CreateTags.URL_FIELD).performTextInput("https://grapheneos.org")
        // Replace (not append): the URL already suggested a name from the host.
        compose.onNodeWithTag(CreateTags.NAME_FIELD).performTextReplacement(name)

        compose.onNodeWithTag(CreateTags.ICON_BANK).performScrollTo().performClick()
        compose.onNodeWithTag(CreateTags.ICON_SEARCH).performTextInput("house")
        // The index loads asynchronously.
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("icon_lucide/house").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("icon_lucide/house").performClick()

        compose.onNodeWithTag(CreateTags.SUBMIT).performScrollTo().assertIsEnabled().performClick()

        // Launcher3-based dialogs: "Add to home screen" / "Add automatically" / "Ajouter"...
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val add = Pattern.compile("(?i)(add( to home screen| automatically)?|ajouter.*)")
        val button = device.wait(Until.findObject(By.text(add).clickable(true)), 10_000)
        assumeTrue("launcher pin dialog not found", button != null)
        button.click()

        // The callback / resume-time sync marks it ACTIF; read the source of truth directly.
        compose.waitUntil(15_000) {
            ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED).any { it.shortLabel == name }
        }
        val pinned = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED).first { it.shortLabel == name }
        val row = runBlocking { container.repository.get(pinned.id) }
        assertTrue("row saved", row != null)
        assertEquals(ShortcutStatus.ACTIF, row!!.status)
        assertEquals("https://grapheneos.org", row.target)
    }
}
