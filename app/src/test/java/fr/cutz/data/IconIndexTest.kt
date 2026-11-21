package fr.cutz.data

import fr.cutz.data.icons.IconCategory
import fr.cutz.data.icons.IconIndex
import fr.cutz.data.icons.IconPack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class IconIndexTest {

    private val json = """
        {"version":1,"categories":[],"icons":[
          {"id":"tabler/home","source":"tabler","name":"home","title":"home","tags":["house","maison"],"category":"home"},
          {"id":"lucide/house","source":"lucide","name":"house","title":"house","tags":["home","living"],"category":"home"},
          {"id":"tabler/smart-home-off","source":"tabler","name":"smart-home-off","title":"smart home off","tags":[],"category":"home"},
          {"id":"simpleicons/signal","source":"simpleicons","name":"signal","title":"Signal","tags":[],"category":"communication","color":"#3B45FD"},
          {"id":"tabler/lock","source":"tabler","name":"lock","title":"lock","tags":["sécurité"],"category":"security"},
          {"id":"bogus/x","source":"bogus","name":"x","title":"x","tags":[],"category":"ui"},
          {"id":"tabler/weird","source":"tabler","name":"weird","title":"weird","tags":[],"category":"not-a-category"}
        ]}
    """.trimIndent()

    private val index = IconIndex.parse(json)

    @Test fun `unknown sources are skipped and unknown categories fall back to OTHER`() {
        assertEquals(6, index.icons.size)
        assertNull(index.get("bogus/x"))
        assertEquals(IconCategory.OTHER, index.get("tabler/weird")!!.category)
    }

    @Test fun `brand colour is parsed as opaque ARGB`() {
        assertEquals(0xFF3B45FD.toInt(), index.get("simpleicons/signal")!!.brandColor)
        assertNull(index.get("tabler/home")!!.brandColor)
    }

    @Test fun `search matches name and tags, exact name ranked first`() {
        val r = index.search("home").map { it.id }
        assertEquals("tabler/home", r.first())
        assertTrue("lucide/house" in r) // via tag
        assertTrue("tabler/smart-home-off" in r)
    }

    @Test fun `search ignores accents and case`() {
        assertEquals(listOf("tabler/lock"), index.search("SECURITE").map { it.id })
    }

    @Test fun `all terms must match`() {
        assertEquals(listOf("tabler/smart-home-off"), index.search("smart off").map { it.id })
    }

    @Test fun `category and pack filters combine`() {
        assertEquals(3, index.search("", IconCategory.HOME).size)
        assertEquals(listOf("lucide/house"), index.search("", IconCategory.HOME, setOf(IconPack.LUCIDE)).map { it.id })
    }

    @Test fun `same name in two packs is kept and distinguished by id`() {
        val idx = IconIndex.parse(
            """{"icons":[
              {"id":"tabler/x","source":"tabler","name":"x","category":"ui"},
              {"id":"lucide/x","source":"lucide","name":"x","category":"ui"}]}"""
        )
        assertEquals(setOf(IconPack.TABLER, IconPack.LUCIDE), idx.search("x").map { it.pack }.toSet())
    }

    /** Guards the generated asset: every icon has a known pack and a valid category. */
    @Test fun `bundled index is valid`() {
        val file = File("src/main/assets/icons/index.json")
        if (!file.exists()) return // assets not generated in this checkout
        val idx = IconIndex.parse(file.readText())
        assertTrue(idx.icons.size > 10_000)
        val categories = idx.countByCategory()
        assertEquals("every unified category is used", IconCategory.entries.toSet(), categories.keys)
        assertEquals(idx.icons.size, idx.icons.map { it.id }.toSet().size)
        idx.icons.take(50).forEach { assertTrue(File("src/main/assets/${it.assetPath}").exists()) }
    }
}
