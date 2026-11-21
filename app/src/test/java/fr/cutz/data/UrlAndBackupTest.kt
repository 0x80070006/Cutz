package fr.cutz.data

import fr.cutz.data.backup.BackupCodec
import fr.cutz.data.db.DisabledReason
import fr.cutz.data.db.IconSourceType
import fr.cutz.data.db.ShortcutStatus
import fr.cutz.data.db.TargetType
import fr.cutz.shortcut
import fr.cutz.util.UrlValidator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlValidatorTest {
    @Test fun `accepts http and https with a host`() {
        assertEquals("https://example.org/path?q=1", UrlValidator.normalize("https://example.org/path?q=1"))
        assertEquals("http://192.168.1.10:8123", UrlValidator.normalize("http://192.168.1.10:8123"))
        assertEquals("http://localhost:3000", UrlValidator.normalize("http://localhost:3000"))
    }

    @Test fun `adds https when the scheme is missing`() {
        assertEquals("https://grapheneos.org", UrlValidator.normalize("  grapheneos.org "))
    }

    @Test fun `rejects other schemes and garbage`() {
        listOf("javascript:alert(1)", "file:///sdcard/x", "intent://x#Intent;end", "ftp://a.b", "https://", "hello world", "", "https://nodot")
            .forEach { assertNull(it, UrlValidator.normalize(it)) }
    }
}

class BackupCodecTest {
    @Test fun `round trip keeps appearance and resets status to not pinned`() {
        val a = shortcut(id = "a").copy(backgroundColor = 0xFFFF0000.toInt(), glyphColor = 0x80123456.toInt(), scale = 0.75f)
        val b = shortcut(id = "b", type = TargetType.APP, target = "org.example.app")
        val json = BackupCodec.encode(listOf(a, b), { null }, now = 1)
        val decoded = BackupCodec.decode(json, now = 2)
        assertEquals(2, decoded.size)
        val da = decoded.first { it.entity.id == "a" }.entity
        assertEquals(a.backgroundColor, da.backgroundColor)
        assertEquals(a.glyphColor, da.glyphColor) // negative ARGB ints survive the JSON trip
        assertEquals(0.75f, da.scale)
        assertEquals(ShortcutStatus.DESACTIVE, da.status)
        assertEquals(DisabledReason.NOT_PINNED, da.disabledReason)
        assertEquals(2L, da.updatedAt)
    }

    @Test fun `gallery image is embedded`() {
        val png = byteArrayOf(1, 2, 3, 4)
        val g = shortcut(id = "g").copy(iconSource = IconSourceType.GALLERY, iconRef = "x.png")
        val decoded = BackupCodec.decode(BackupCodec.encode(listOf(g), { png }, 0), 0).single()
        assertArrayEquals(png, decoded.galleryPng)
    }

    @Test fun `invalid entries are skipped, not fatal`() {
        val json = """{"format":"cutz-backup","version":1,"shortcuts":[
            {"id":"1","type":"URL","target":"javascript:alert(1)","name":"x","iconSource":"BUNDLED","iconRef":"a","backgroundColor":0,"glyphColor":0,"shape":"SQUARE"},
            {"id":"2","type":"APP","target":"../../etc","name":"x","iconSource":"BUNDLED","iconRef":"a","backgroundColor":0,"glyphColor":0,"shape":"SQUARE"},
            {"id":"3","type":"NOPE","target":"x","name":"x","iconSource":"BUNDLED","iconRef":"a","backgroundColor":0,"glyphColor":0},
            {"id":"4","type":"URL","target":"example.org","name":"ok","iconSource":"BUNDLED","iconRef":"a","backgroundColor":0,"glyphColor":0,"shape":"BLOB"}
        ]}"""
        val decoded = BackupCodec.decode(json, 0)
        assertEquals(listOf("4"), decoded.map { it.entity.id })
        assertEquals("https://example.org", decoded.single().entity.target)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `foreign json is rejected`() {
        BackupCodec.decode("""{"shortcuts":[]}""", 0)
    }

    @Test fun `names are truncated`() {
        val long = shortcut().copy(name = "x".repeat(100))
        val d = BackupCodec.decode(BackupCodec.encode(listOf(long), { null }, 0), 0).single()
        assertTrue(d.entity.name.length <= BackupCodec.MAX_NAME)
    }
}
