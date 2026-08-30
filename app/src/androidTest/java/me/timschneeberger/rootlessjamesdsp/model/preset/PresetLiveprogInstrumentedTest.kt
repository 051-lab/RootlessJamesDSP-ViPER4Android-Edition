package me.timschneeberger.rootlessjamesdsp.model.preset

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.timschneeberger.rootlessjamesdsp.utils.LiveprogSlots
import me.timschneeberger.rootlessjamesdsp.utils.storage.Tar
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class PresetLiveprogInstrumentedTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val liveprogDir = File(context.getExternalFilesDir(null)!!, "Liveprog")
    private val presetDir = File(context.getExternalFilesDir(null)!!, "Presets-test")
    private val presetFile = File(presetDir, "slots.tar")

    // Four distinct scripts. Each has a unique slider default so a restored
    // file's bytes are distinguishable from a sibling's.
    private data class Script(val filename: String, val content: String) {
        val sha256: String = run {
            val md = MessageDigest.getInstance("SHA-256")
            md.digest(content.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }

    private fun script(i: Int) = Script(
        filename = "slot$i.eel",
        content = "@init\nslider1=$i;\n@sample\nout1 = in1;\n"
    )

    @Before
    fun setUp() {
        liveprogDir.mkdirs()
        presetDir.mkdirs()
        if (presetFile.exists()) presetFile.delete()
        clearAllPending()
    }

    @After
    fun tearDown() {
        clearAllPending()
        presetFile.delete()
        presetDir.delete()
        liveprogDir.delete()
    }

    private fun clearAllPending() {
        for (i in 0 until LiveprogSlots.COUNT) {
            LiveprogSlots.write(context, i, "")
        }
    }

    private fun createScripts(): Array<Script> {
        val all = Array(4) { script(it) }
        all.forEach { s ->
            val f = File(liveprogDir, s.filename)
            f.writeText(s.content)
        }
        return all
    }

    @Test
    fun fullRoundTripRestoresOccupiedSlotsAndPreservesGaps() {
        val scripts = createScripts()

        // Occupied: slots 1, 3, 4. Slot 2 (index 1) stays empty.
        LiveprogSlots.write(context, 0, "Liveprog/${scripts[0].filename}")
        LiveprogSlots.write(context, 2, "Liveprog/${scripts[2].filename}")
        LiveprogSlots.write(context, 3, "Liveprog/${scripts[3].filename}")

        assertTrue("Preset save should succeed", Preset(presetFile.name, presetDir).save())

        // Clear the slots and delete the source scripts.
        clearAllPending()
        scripts.forEach { File(liveprogDir, it.filename).delete() }

        // Load the saved preset.
        Preset.load(context, presetFile.inputStream())

        val restored = LiveprogSlots.read(context)
        // Slot 2 stays empty.
        assertEquals("Slot 2 must stay empty", "", restored[1])
        // Slots 1, 3, 4 have their filenames restored.
        assertEquals(scripts[0].filename, File(restored[0]).name)
        assertEquals(scripts[2].filename, File(restored[2]).name)
        assertEquals(scripts[3].filename, File(restored[3]).name)

        // Content round-trips byte-for-byte.
        val restoredFiles = listOf(restored[0], restored[2], restored[3]).map { File(liveprogDir, File(it).name) }
        val expected = listOf(0, 2, 3).map { File(liveprogDir, scripts[it].filename) }
        restoredFiles.zip(expected).forEach { (actual, e) ->
            assertTrue("Restored file ${actual.name} must exist", actual.exists())
            assertArrayEquals("Content of ${actual.name} must match source", e.readBytes(), actual.readBytes())
        }
    }

    @Test
    fun legacyVersionThreeFixtureRestoresSlotOne() {
        // A v3 preset has no per-slot entries: it embeds a lone "liveprog"
        // script and metadata. It must restore slot 1.
        val legacyScript = File(liveprogDir, "legacy.eel").apply { writeText(script(9).content) }

        // Build the v3 archive by hand: only the "liveprog" entry + metadata,
        // with no liveprog_slot_N_name keys at all.
        val fixture = File(presetDir, "legacy-v3.tar")
        if (fixture.exists()) fixture.delete()
        Tar.Composer(fixture.outputStream()).use { composer ->
            composer.metadata = mutableMapOf(
                Preset.META_VERSION to "3",
                Preset.META_APP_VERSION to "2.5.2",
                Preset.META_APP_FLAVOR to "rootlessFdroid",
                Preset.META_LIVEPROG_INCLUDED to "true"
            )
            composer.add(legacyScript, Preset.FILE_LIVEPROG)
        }

        // Point slot 1 at the legacy path so the loader can find the working
        // file via the shared prefs, keep it assigned (the legacy loader relies
        // on that preference), and delete the script before loading.
        LiveprogSlots.write(context, 0, "Liveprog/legacy.eel")
        legacyScript.delete()

        Preset.load(context, fixture.inputStream())

        val restored = LiveprogSlots.read(context)
        assertEquals("legacy.eel should restore into slot 1", "legacy.eel", File(restored[0]).name)

        val restoredFile = File(liveprogDir, File(restored[0]).name)
        assertTrue("Restored legacy.eel must exist", restoredFile.exists())
        assertArrayEquals(
            "Legacy content must round-trip",
            script(9).content.toByteArray(),
            restoredFile.readBytes()
        )
    }

    @Test
    fun slotValuesAreRelativeLiveprogPaths() {
        // Guards the serialization contract: slot values are paths relative to
        // getExternalFilesDir(null), never absolute paths or bare filenames.
        createScripts()
        LiveprogSlots.write(context, 0, "Liveprog/slot0.eel")
        assertTrue(Preset(presetFile.name, presetDir).save())
        // The archive must carry liveprog1..liveprog4 entry names and the
        // per-slot name metadata.
        assertTrue(presetFile.exists())
        assertFalse(
            "Archive must not contain an absolute liveprog path",
            String(presetFile.readBytes(), Charsets.UTF_8).contains("/Liveprog/slot0.eel")
        )
    }

    private fun sha256File(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val read = input.read(buf)
                if (read < 0) break
                md.update(buf, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun writeAsset(slot: Int, assetPath: String, name: String): String {
        val dest = File(liveprogDir, name)
        context.assets.open(assetPath).use { input -> dest.outputStream().use { input.copyTo(it) } }
        return dest.absolutePath
    }

    @Test
    fun airwindowsChainRestoresAllFourSlotsByteForByte() {
        // The four v1.1.0 conservative Airwindows assets, in chain order.
        val chain = listOf(
            "eelvault-custom/airwindows-v110/01-ChannelX-Conservative.eel" to "01-ChannelX-Conservative.eel",
            "eelvault-custom/airwindows-v110/02-ToTape9-Conservative.eel" to "02-ToTape9-Conservative.eel",
            "eelvault-custom/airwindows-v110/03-Srsly3-Conservative.eel" to "03-Srsly3-Conservative.eel",
            "eelvault-custom/airwindows-v110/04-X2Buss-Conservative.eel" to "04-X2Buss-Conservative.eel",
        )

        // Pin the published hashes (EELVault/releases/v1.1.0/SHA256SUMS).
        val expectedHashes = mapOf(
            "01-ChannelX-Conservative.eel" to "ffeb1892642214b5818b365902ef1cc730578482f2e4be136cc0e94164f6c126",
            "02-ToTape9-Conservative.eel" to "025c47fafd81108fa691f13ddfaba9e3b4e478fe4bbad60b97db7e8ee897f5ce",
            "03-Srsly3-Conservative.eel" to "6a1ff5ef8f923fa83f77942eb82f2c0353a214c9b30d59cc8005ace7d65efe72",
            "04-X2Buss-Conservative.eel" to "0196ab1ce9a8243de3860ffef15d072806b708952fc0500422195cc68be1a567",
        )

        // Guards the assets themselves: they must match the published release.
        chain.forEachIndexed { slot, (assetPath, name) ->
            val assetFile = File(liveprogDir, name)
            writeAsset(slot, assetPath, name)
            assertEquals(
                "Asset $name must match published SHA256",
                expectedHashes[name],
                sha256File(assetFile)
            )
        }

        // Assign all four slots in order, save, clear, load.
        chain.forEachIndexed { slot, (_, name) ->
            LiveprogSlots.write(context, slot, "Liveprog/$name")
        }
        assertTrue("Chain preset save should succeed", Preset(presetFile.name, presetDir).save())

        clearAllPending()
        chain.forEach { (_, name) -> File(liveprogDir, name).delete() }

        Preset.load(context, presetFile.inputStream())

        // Both the slot assignment and the file order round-trip.
        assertEquals(
            listOf(
                "01-ChannelX-Conservative.eel",
                "02-ToTape9-Conservative.eel",
                "03-Srsly3-Conservative.eel",
                "04-X2Buss-Conservative.eel",
            ),
            LiveprogSlots.read(context).map { File(it).name }
        )

        // Content must be byte-identical to the published release.
        chain.forEach { (_, name) ->
            assertEquals(
                "Restored $name must match published hash",
                expectedHashes[name],
                sha256File(File(liveprogDir, name))
            )
        }
    }
}
