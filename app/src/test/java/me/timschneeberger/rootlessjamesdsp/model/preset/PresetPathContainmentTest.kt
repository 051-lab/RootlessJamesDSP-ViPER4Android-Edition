package me.timschneeberger.rootlessjamesdsp.model.preset

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PresetPathContainmentTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun base(): File = tmp.newFolder("external")

    @Test
    fun acceptsScriptInsideLiveprogDirectory() {
        val base = base()
        File(base, "Liveprog").mkdirs()
        val script = File(base, "Liveprog/foo.eel").apply { writeText("@init\n@sample\n") }

        val result = Preset.Companion.containedLiveprogSource(base, "Liveprog/foo.eel")

        assertNotNull(result)
        assertEquals(script.canonicalFile, result)
    }

    @Test
    fun acceptsScriptInsideLiveprogSubdirectory() {
        val base = base()
        File(base, "Liveprog/nested").mkdirs()
        val script = File(base, "Liveprog/nested/foo.eel").apply { writeText("@init\n@sample\n") }

        val result = Preset.Companion.containedLiveprogSource(base, "Liveprog/nested/foo.eel")

        assertNotNull(result)
        assertEquals(script.canonicalFile, result)
    }

    @Test
    fun rejectsTraversalOutsideLiveprog() {
        val base = base()
        File(base, "Liveprog").mkdirs()
        File(base, "secret.eel").writeText("secret")

        val result = Preset.Companion.containedLiveprogSource(base, "Liveprog/../secret.eel")

        assertNull(result)
    }

    @Test
    fun rejectsSiblingDirectory() {
        val base = base()
        File(base, "Liveprog").mkdirs()
        File(base, "Other").mkdirs()
        File(base, "Other/foo.eel").writeText("@init\n@sample\n")

        val result = Preset.Companion.containedLiveprogSource(base, "Other/foo.eel")

        assertNull(result)
    }

    @Test
    fun rejectsAbsoluteOutsidePath() {
        val base = base()
        File(base, "Liveprog").mkdirs()
        val outside = tmp.newFile("outside.eel").apply { writeText("@init\n@sample\n") }

        val result = Preset.Companion.containedLiveprogSource(base, outside.absolutePath)

        assertNull(result)
    }

    @Test
    fun rejectsBlankValue() {
        val base = base()

        assertNull(Preset.Companion.containedLiveprogSource(base, ""))
        assertNull(Preset.Companion.containedLiveprogSource(base, "   "))
    }
}
