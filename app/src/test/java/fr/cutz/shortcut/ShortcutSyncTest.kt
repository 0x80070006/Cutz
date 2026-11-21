package fr.cutz.shortcut

import fr.cutz.FakePublisher
import fr.cutz.FakeRepository
import fr.cutz.data.db.DisabledReason
import fr.cutz.data.db.IconShape
import fr.cutz.data.db.ShortcutStatus
import fr.cutz.data.db.TargetType
import fr.cutz.shortcut
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class ShortcutSyncTest {
    private val repo = FakeRepository()
    private val publisher = FakePublisher()
    private val installed = mutableSetOf("org.app")
    private var now = 100_000L
    private val sync = ShortcutSync(repo, publisher, { it in installed }, { "Application désinstallée" }, { now }, graceMs = 4_000)

    @Test fun `active shortcut missing from home becomes NOT_PINNED`() = runTest {
        repo.save(shortcut(id = "a", updatedAt = 0))
        sync.sync()
        assertEquals(DisabledReason.NOT_PINNED, repo.get("a")!!.disabledReason)
        assertEquals(ShortcutStatus.DESACTIVE, repo.get("a")!!.status)
    }

    @Test fun `grace period protects a shortcut whose pin dialog may still be open`() = runTest {
        repo.save(shortcut(id = "a", updatedAt = now - 1_000))
        sync.sync()
        assertEquals(ShortcutStatus.ACTIF, repo.get("a")!!.status)
    }

    @Test fun `NOT_PINNED shortcut found on home becomes ACTIF`() = runTest {
        repo.save(shortcut(id = "a", status = ShortcutStatus.DESACTIVE, reason = DisabledReason.NOT_PINNED))
        publisher.pinned += "a"
        sync.sync()
        assertEquals(ShortcutStatus.ACTIF, repo.get("a")!!.status)
        assertNull(repo.get("a")!!.disabledReason)
    }

    @Test fun `uninstalled target app disables the pinned shortcut`() = runTest {
        repo.save(shortcut(id = "a", type = TargetType.APP, target = "org.gone"))
        publisher.pinned += "a"
        sync.sync()
        assertEquals("Application désinstallée", publisher.disabled["a"])
        assertEquals(DisabledReason.APP_UNINSTALLED, repo.get("a")!!.disabledReason)
    }

    @Test fun `reinstalled app re-enables the shortcut`() = runTest {
        repo.save(shortcut(id = "a", type = TargetType.APP, target = "org.app", status = ShortcutStatus.DESACTIVE, reason = DisabledReason.APP_UNINSTALLED))
        publisher.pinned += "a"
        sync.sync()
        assertEquals(listOf("a"), publisher.enabled)
        assertEquals(ShortcutStatus.ACTIF, repo.get("a")!!.status)
    }

    @Test fun `user-deleted shortcuts are never touched`() = runTest {
        val e = shortcut(id = "d", status = ShortcutStatus.DESACTIVE, reason = DisabledReason.DELETED_BY_USER)
        repo.save(e)
        sync.sync()
        assertEquals(e, repo.get("d"))
        assertTrue(publisher.enabled.isEmpty())
    }

    @Test fun `pending pushes are retried and cleared`() = runTest {
        repo.save(shortcut(id = "a", pendingPush = true))
        publisher.pinned += "a"
        sync.sync()
        assertEquals("a", publisher.updates.single().id)
        assertFalse(repo.get("a")!!.pendingPush)
    }

    @Test fun `pinnedIds is exposed for the UI`() = runTest {
        publisher.pinned += setOf("x", "y")
        sync.sync()
        assertEquals(setOf("x", "y"), sync.pinnedIds.value)
    }
}

class IconGeometryTest {
    @Test fun `custom shapes always fit the 66dp safe circle`() {
        // Farthest point of each shape, in dp, must not exceed the safe radius (33 dp).
        for (shape in IconShape.entries.filter { it != IconShape.SYSTEM }) {
            val half = IconGeometry.SAFE_DP / 2f / IconGeometry.maxRadius(shape)
            assertTrue("$shape", half * IconGeometry.maxRadius(shape) <= IconGeometry.SAFE_DP / 2f + 1e-4f)
        }
        assertEquals(sqrt(2f), IconGeometry.maxRadius(IconShape.SQUARE), 1e-6f)
        assertTrue(IconGeometry.maxRadius(IconShape.SQUIRCLE) in 1f..sqrt(2f))
    }
}
