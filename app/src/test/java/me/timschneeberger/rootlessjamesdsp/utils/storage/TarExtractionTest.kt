package me.timschneeberger.rootlessjamesdsp.utils.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.kamranzafar.jtar.TarEntry
import org.kamranzafar.jtar.TarOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class TarExtractionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun metadataOnlyArchiveIsNotValid() {
        val archive = archiveOf("metadata" to "is_backup=true")

        assertFalse(Tar.Reader(ByteArrayInputStream(archive)).validate())
    }

    @Test
    fun traversalEntryIsRejectedWithoutWritingOutsideTarget() {
        val archive = archiveOf("../escaped.txt" to "not safe")
        val target = tmp.newFolder("target")
        val escaped = File(target.parentFile, "escaped.txt")

        assertNull(Tar.Reader(ByteArrayInputStream(archive)).extract(target))
        assertFalse(escaped.exists())
    }

    @Test
    fun extractionClearsFilesLeftByAnEarlierAttempt() {
        val archive = archiveOf("dsp_test.xml" to "new")
        val target = tmp.newFolder("target")
        File(target, "stale.xml").writeText("stale")

        val metadata = Tar.Reader(ByteArrayInputStream(archive)).extract(target)

        assertEquals(emptyMap<String, String>(), metadata)
        assertFalse(File(target, "stale.xml").exists())
        assertEquals("new", File(target, "dsp_test.xml").readText())
    }

    @Test
    fun oversizedMetadataIsRejected() {
        val archive = archiveOf(
            "dsp_test.xml" to "data",
            "metadata" to "x".repeat(64 * 1024 + 1)
        )
        val target = tmp.newFolder("target")

        assertNull(Tar.Reader(ByteArrayInputStream(archive)).extract(target))
        assertFalse(target.exists())
    }

    @Test
    fun containmentAcceptsOnlyRelativeChildren() {
        val target = tmp.newFolder("containment")

        assertEquals(File(target, "nested/file").canonicalFile,
            containedArchiveEntry(target, "nested/file"))
        assertNull(containedArchiveEntry(target, "../file"))
        assertNull(containedArchiveEntry(target, File(target.parentFile, "file").absolutePath))
        assertNull(containedArchiveEntry(target, "nested/../file"))
    }

    private fun archiveOf(vararg entries: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        TarOutputStream(output).use { tar ->
            entries.forEachIndexed { index, (name, contents) ->
                val source = tmp.newFile("source-$index").apply { writeText(contents) }
                tar.putNextEntry(TarEntry(source, name))
                source.inputStream().use { it.copyTo(tar) }
            }
        }
        return output.toByteArray()
    }
}
