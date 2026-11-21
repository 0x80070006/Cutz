package fr.cutz.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.util.UUID

/**
 * Cropped gallery images, copied into app-private storage.
 *
 * Why copy instead of keeping the Photo Picker URI: picker grants are temporary (they
 * die with the process), so the icon must not depend on the original file. A 512 px
 * PNG is plenty for a 108 dp adaptive icon.
 */
class GalleryStore(context: Context) {
    private val dir = File(context.filesDir, "gallery").apply { mkdirs() }

    fun save(bitmap: Bitmap): String {
        val name = "${UUID.randomUUID()}.png"
        File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return name
    }

    fun saveBytes(bytes: ByteArray): String {
        val name = "${UUID.randomUUID()}.png"
        File(dir, name).writeBytes(bytes)
        return name
    }

    fun load(name: String): Bitmap? = file(name)?.let { BitmapFactory.decodeFile(it.path) }

    fun bytes(name: String): ByteArray? = file(name)?.readBytes()

    fun delete(name: String) {
        file(name)?.delete()
    }

    /** Guards against path traversal: only plain file names inside [dir] are accepted. */
    private fun file(name: String): File? {
        if (name.contains('/') || name.contains('\\') || name.startsWith(".")) return null
        return File(dir, name).takeIf { it.exists() }
    }
}
