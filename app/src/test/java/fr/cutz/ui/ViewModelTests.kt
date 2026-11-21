package fr.cutz.ui

import fr.cutz.FakePublisher
import fr.cutz.FakeRepository
import fr.cutz.data.db.DisabledReason
import fr.cutz.data.db.IconSourceType
import fr.cutz.data.db.ShortcutStatus
import fr.cutz.data.db.TargetType
import fr.cutz.data.icons.IconCategory
import fr.cutz.data.icons.IconMeta
import fr.cutz.data.icons.IconPack
import fr.cutz.shortcut
import fr.cutz.shortcut.PublishResult
import fr.cutz.ui.create.CreateEvent
import fr.cutz.ui.create.CreateViewModel
import fr.cutz.ui.shortcuts.ShortcutsEvent
import fr.cutz.ui.shortcuts.ShortcutsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
abstract class MainDispatcherTest {
    @Before fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun resetMain() = Dispatchers.resetMain()
}

private val HOME_ICON = IconMeta("tabler/home", IconPack.TABLER, "home", "home", emptyList(), IconCategory.HOME, null)
private val SIGNAL_ICON = IconMeta("simpleicons/signal", IconPack.SIMPLE_ICONS, "signal", "Signal", emptyList(), IconCategory.COMMUNICATION, 0xFF3B45FD.toInt())

class CreateViewModelTest : MainDispatcherTest() {
    private val repo = FakeRepository()
    private val publisher = FakePublisher()
    private var pinCallbacks = 0
    private fun vm() = CreateViewModel(
        repo, publisher, renderPreview = { null }, onPinRequested = { pinCallbacks++ },
        clock = { 1_000 }, newId = { "new-id" },
    )

    @Test fun `cannot submit without target, name and icon`() {
        val vm = vm()
        assertFalse(vm.state.value.canSubmit)
        vm.setType(TargetType.URL)
        vm.setUrl("not a url")
        assertTrue(vm.state.value.showUrlError)
        vm.setUrl("https://grapheneos.org")
        assertEquals("Grapheneos", vm.state.value.name) // suggested from host
        assertFalse(vm.state.value.canSubmit) // still no icon
        vm.selectBundledIcon(HOME_ICON)
        assertTrue(vm.state.value.canSubmit)
    }

    @Test fun `user-typed name is never overwritten by suggestions`() {
        val vm = vm()
        vm.setName("Mon site")
        vm.setType(TargetType.URL)
        vm.setUrl("https://example.org")
        assertEquals("Mon site", vm.state.value.name)
        vm.selectApp("org.example", "Example")
        assertEquals("Mon site", vm.state.value.name)
    }

    @Test fun `selecting an app defaults to its own icon, switching to URL drops it`() {
        val vm = vm()
        vm.selectApp("org.example", "Example")
        assertEquals(IconSourceType.APP_ICON, vm.state.value.iconSource)
        assertEquals("org.example", vm.state.value.iconRef)
        vm.setType(TargetType.URL)
        assertNull(vm.state.value.iconSource)
    }

    @Test fun `brand icon applies brand colour and a contrasting glyph`() {
        val vm = vm()
        vm.selectBundledIcon(SIGNAL_ICON)
        assertEquals(0xFF3B45FD.toInt(), vm.state.value.backgroundColor)
        assertEquals(0xFFFFFFFF.toInt(), vm.state.value.glyphColor)
        assertEquals(0xFF1B1B1F.toInt(), CreateViewModel.contrastingGlyph(0xFFFFEB3B.toInt()))
    }

    private fun filled(vm: CreateViewModel) = vm.apply {
        setType(TargetType.URL); setUrl("https://example.org"); selectBundledIcon(HOME_ICON)
    }

    @Test fun `successful pin saves an ACTIF row and resets the form`() = runTest {
        val vm = filled(vm())
        val event = async { vm.events.first() }
        vm.submit()
        assertEquals(CreateEvent.PinRequested, event.await())
        val saved = repo.get("new-id")!!
        assertEquals(ShortcutStatus.ACTIF, saved.status)
        assertEquals("https://example.org", saved.target)
        assertEquals(1, pinCallbacks)
        assertEquals("", vm.state.value.url)
    }

    @Test fun `unsupported launcher leaves no row behind`() = runTest {
        publisher.pinSupported = false
        val vm = filled(vm())
        val event = async { vm.events.first() }
        vm.submit()
        assertEquals(CreateEvent.PinUnsupported, event.await())
        assertTrue(repo.getAll().isEmpty())
    }

    @Test fun `pin error rolls back the row`() = runTest {
        publisher.pinResult = PublishResult.Error("boom")
        val vm = filled(vm())
        val event = async { vm.events.first() }
        vm.submit()
        assertEquals(CreateEvent.Error("boom"), event.await())
        assertTrue(repo.getAll().isEmpty())
    }

    @Test fun `editing an active shortcut pushes an update`() = runTest {
        repo.save(shortcut(id = "e1"))
        val vm = vm()
        vm.loadForEdit(repo.get("e1")!!, null)
        vm.setName("Renamed")
        val event = async { vm.events.first() }
        vm.submit()
        assertEquals(CreateEvent.Saved, event.await())
        assertEquals("Renamed", publisher.updates.single().name)
        assertEquals("Renamed", repo.get("e1")!!.name)
        assertFalse(repo.get("e1")!!.pendingPush)
    }

    @Test fun `rate-limited edit is saved with pendingPush for a later retry`() = runTest {
        repo.save(shortcut(id = "e1"))
        publisher.updateResult = PublishResult.RateLimited
        val vm = vm()
        vm.loadForEdit(repo.get("e1")!!, null)
        vm.setName("Later")
        val event = async { vm.events.first() }
        vm.submit()
        assertEquals(CreateEvent.SavedPending, event.await())
        assertTrue(repo.get("e1")!!.pendingPush)
        assertEquals("Later", repo.get("e1")!!.name)
    }

    @Test fun `duplicate becomes a new shortcut`() {
        val vm = vm()
        vm.loadAsCopy(shortcut(id = "orig"), null, " (copie)")
        assertNull(vm.state.value.editingId)
        assertEquals("Example (copie)", vm.state.value.name)
    }

    private fun <T> kotlinx.coroutines.test.TestScope.async(block: suspend () -> T) =
        backgroundScope.let { scope -> kotlinx.coroutines.CompletableDeferred<T>().also { d -> scope.launch { d.complete(block()) } } }
}

class ShortcutsViewModelTest : MainDispatcherTest() {
    private val repo = FakeRepository()
    private val publisher = FakePublisher()
    private val pinnedFlow = MutableStateFlow<Set<String>>(emptySet())
    private val deletedFiles = mutableListOf<String>()
    private fun vm() = ShortcutsViewModel(
        repo, publisher, pinnedFlow,
        refreshPinned = { pinnedFlow.value = publisher.pinnedIds() },
        deletedMessage = "Raccourci supprimé",
        deleteGalleryFile = { deletedFiles += it },
        clock = { 5 },
    )

    @Test fun `deleting a pinned shortcut disables it and parks it for manual removal`() = runTest {
        repo.save(shortcut(id = "p"))
        publisher.pinned += "p"
        val vm = vm()
        vm.delete(repo.get("p")!!)
        advanceUntilIdle()
        assertEquals("Raccourci supprimé", publisher.disabled["p"])
        val e = repo.get("p")!!
        assertEquals(ShortcutStatus.DESACTIVE, e.status)
        assertEquals(DisabledReason.DELETED_BY_USER, e.disabledReason)
        val toRemove = vm.state.first { !it.loading }.toRemove.single()
        assertTrue(toRemove.stillOnHome)
    }

    @Test fun `once the icon is gone from home, the entry is flagged as removed`() = runTest {
        repo.save(shortcut(id = "p", status = ShortcutStatus.DESACTIVE, reason = DisabledReason.DELETED_BY_USER))
        val vm = vm()
        vm.refresh()
        assertFalse(vm.state.first { !it.loading }.toRemove.single().stillOnHome)
    }

    @Test fun `deleting a shortcut that is not pinned purges it directly`() = runTest {
        repo.save(shortcut(id = "np", status = ShortcutStatus.DESACTIVE, reason = DisabledReason.NOT_PINNED)
            .copy(iconSource = IconSourceType.GALLERY, iconRef = "img.png"))
        val vm = vm()
        vm.delete(repo.get("np")!!)
        advanceUntilIdle()
        assertNull(repo.get("np"))
        assertTrue(publisher.disabled.isEmpty())
        assertEquals(listOf("img.png"), deletedFiles)
    }

    @Test fun `restore re-enables a shortcut still on home`() = runTest {
        repo.save(shortcut(id = "p", status = ShortcutStatus.DESACTIVE, reason = DisabledReason.DELETED_BY_USER))
        publisher.pinned += "p"
        val vm = vm()
        vm.restore(repo.get("p")!!)
        advanceUntilIdle()
        assertEquals(listOf("p"), publisher.enabled)
        assertEquals(ShortcutStatus.ACTIF, repo.get("p")!!.status)
        assertNull(repo.get("p")!!.disabledReason)
    }

    @Test fun `purge removes the row`() = runTest {
        repo.save(shortcut(id = "p", status = ShortcutStatus.DESACTIVE, reason = DisabledReason.DELETED_BY_USER))
        val vm = vm()
        val event = kotlinx.coroutines.CompletableDeferred<ShortcutsEvent>()
        backgroundScope.launch { event.complete(vm.events.first()) }
        vm.purge(repo.get("p")!!)
        assertEquals(ShortcutsEvent.Purged("Example"), event.await())
        assertNull(repo.get("p"))
    }

    @Test fun `pin again marks the shortcut active`() = runTest {
        repo.save(shortcut(id = "np", status = ShortcutStatus.DESACTIVE, reason = DisabledReason.NOT_PINNED))
        val vm = vm()
        vm.pin(repo.get("np")!!)
        advanceUntilIdle()
        assertEquals("np", publisher.pinRequests.single().id)
        assertEquals(ShortcutStatus.ACTIF, repo.get("np")!!.status)
    }

    @Test fun `sections are split by status and reason`() = runTest {
        repo.saveAll(listOf(
            shortcut(id = "a"),
            shortcut(id = "n", status = ShortcutStatus.DESACTIVE, reason = DisabledReason.NOT_PINNED),
            shortcut(id = "u", status = ShortcutStatus.DESACTIVE, reason = DisabledReason.APP_UNINSTALLED),
            shortcut(id = "d", status = ShortcutStatus.DESACTIVE, reason = DisabledReason.DELETED_BY_USER),
        ))
        val s = vm().state.first { !it.loading }
        assertEquals(listOf("a"), s.active.map { it.id })
        assertEquals(listOf("n"), s.notPinned.map { it.id })
        assertEquals(listOf("u"), s.appMissing.map { it.id })
        assertEquals(listOf("d"), s.toRemove.map { it.entity.id })
    }
}
