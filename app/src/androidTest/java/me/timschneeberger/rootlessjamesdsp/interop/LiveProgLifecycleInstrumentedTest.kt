package me.timschneeberger.rootlessjamesdsp.interop

import androidx.test.ext.junit.runners.AndroidJUnit4
import me.timschneeberger.rootlessjamesdsp.model.ProcessorMessage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveProgLifecycleInstrumentedTest {
    private class Callbacks : JamesDspWrapper.JamesDspCallbacks {
        data class Result(val code: Int, val id: String, val error: String?)

        val results = mutableListOf<Result>()

        override fun onLiveprogOutput(message: String) = Unit
        override fun onLiveprogExec(id: String) = Unit
        override fun onLiveprogResult(resultCode: Int, id: String, errorMessage: String?) {
            results += Result(resultCode, id, errorMessage)
        }
        override fun onVdcParseError() = Unit
        override fun onConvolverParseError(errorCode: ProcessorMessage.ConvolverErrorCode) = Unit
    }

    private lateinit var callbacks: Callbacks
    private var handle: JamesDspHandle = 0L

    @Before
    fun setUp() {
        callbacks = Callbacks()
        handle = JamesDspWrapper.alloc(callbacks)
        assertTrue(handle != 0L)
        JamesDspWrapper.setSamplingRate(handle, 48_000f, false)
    }

    @After
    fun tearDown() {
        if (handle != 0L) {
            JamesDspWrapper.free(handle)
            handle = 0L
        }
    }

    private fun variables(slot: Int) = JamesDspWrapper.enumerateEelVariablesSlot(handle, slot)

    private fun value(slot: Int, name: String): Float =
        variables(slot).first { it.name == name }.value.toFloat()

    private fun load(slot: Int, id: String, script: String): Boolean =
        if (slot == 0) JamesDspWrapper.setLiveprog(handle, true, id, script)
        else JamesDspWrapper.setLiveprogSlot(handle, slot, true, id, script)

    @Test
    fun slotVariableWritesAreIsolated() {
        val script0 = """
            @init
            gain = 0.25;
            @sample
            spl0 *= gain; spl1 *= gain;
        """.trimIndent()
        val script1 = """
            @init
            gain = 0.75;
            @sample
            spl0 *= gain; spl1 *= gain;
        """.trimIndent()

        assertTrue(load(0, "slot0", script0))
        assertTrue(load(1, "slot1", script1))
        assertEquals(0.25f, value(0, "gain"), 0.0001f)
        assertEquals(0.75f, value(1, "gain"), 0.0001f)

        assertTrue(JamesDspWrapper.manipulateEelVariableSlot(handle, 1, "gain", 0.5f))
        assertEquals(0.25f, value(0, "gain"), 0.0001f)
        assertEquals(0.5f, value(1, "gain"), 0.0001f)
    }

    @Test
    fun acceptsSampleOnlyAndFullLifecycleInEverySlot() {
        val sampleOnly = """
            @sample
            spl0 *= 0.5; spl1 *= 0.5;
        """.trimIndent()

        val full = """
            @init
            gain = 0.5;
            @slider
            derived = gain * 2;
            @block
            block_seen = samplesblock;
            @sample
            spl0 *= gain; spl1 *= gain;
        """.trimIndent()

        assertTrue(load(0, "sampleOnly", sampleOnly))
        for (slot in 1..3) {
            assertTrue(load(slot, "full$slot", full))
        }
    }

    @Test
    fun rejectsMissingSampleAndDuplicateSections() {
        assertFalse(load(0, "missing", "@init\nx=1;"))
        assertFalse(load(0, "duplicate", "@sample\nx=1;\n@sample\ny=2;"))
    }

    @Test
    fun sectionNamesInsideCommentsDoNotSplitSource() {
        val script = """
            @init
            x = 1; // @block is text here, not a section marker
            @sample
            spl0 *= x; spl1 *= x;
        """.trimIndent()
        assertTrue(load(0, "comment-marker", script))
    }

    @Test
    fun fullLifecycleRunsAtExpectedRatesInEverySlot() {
        val script = """
            @init
            init_count = 0; slider_count = 0; block_count = 0; sample_count = 0;
            gain = 1;
            init_count += 1;

            @slider
            slider_count += 1;
            derived_gain = gain * 0.5;

            @block
            block_count += 1;
            seen_block_size = samplesblock;

            @sample
            sample_count += 1;
            spl0 *= derived_gain;
            spl1 *= derived_gain;
        """.trimIndent()

        for (slot in 0..3) {
            assertTrue(load(slot, "lifecycle$slot", script))
            assertEquals(1f, value(slot, "init_count"), 0.0001f)
            assertEquals(1f, value(slot, "slider_count"), 0.0001f)
            assertTrue(JamesDspWrapper.manipulateEelVariableSlot(handle, slot, "gain", 0.8f))
            assertEquals(2f, value(slot, "slider_count"), 0.0001f)
        }

        val frames = 16
        val input = FloatArray(frames * 2) { 1f }
        val output = FloatArray(input.size)
        JamesDspWrapper.processFloat(handle, input, output, 0, input.size)

        for (slot in 0..3) {
            assertEquals(1f, value(slot, "block_count"), 0.0001f)
            assertEquals(frames.toFloat(), value(slot, "seen_block_size"), 0.0001f)
            assertEquals(frames.toFloat(), value(slot, "sample_count"), 0.0001f)
        }
    }
}
