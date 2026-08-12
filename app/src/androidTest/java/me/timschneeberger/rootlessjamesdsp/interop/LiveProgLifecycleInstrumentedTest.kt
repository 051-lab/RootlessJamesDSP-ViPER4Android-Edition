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

    private fun resultCode(id: String): Int = callbacks.results.last { it.id == id }.code

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
        assertEquals(-2, resultCode("missing"))

        assertFalse(load(0, "duplicate", "@sample\nx=1;\n@sample\ny=2;"))
        assertEquals(-6, resultCode("duplicate"))
    }

    @Test
    fun reportsSyntaxErrorForTheCorrectLifecycleStage() {
        val badInit = """
            @init
            broken = ;
            @sample
            spl0 = spl0; spl1 = spl1;
        """.trimIndent()
        val badSlider = """
            @slider
            broken = ;
            @sample
            spl0 = spl0; spl1 = spl1;
        """.trimIndent()
        val badBlock = """
            @block
            broken = ;
            @sample
            spl0 = spl0; spl1 = spl1;
        """.trimIndent()
        val badSample = """
            @sample
            broken = ;
        """.trimIndent()

        assertFalse(load(0, "bad-init", badInit))
        assertEquals(-1, resultCode("bad-init"))
        assertFalse(load(0, "bad-slider", badSlider))
        assertEquals(-4, resultCode("bad-slider"))
        assertFalse(load(0, "bad-block", badBlock))
        assertEquals(-5, resultCode("bad-block"))
        assertFalse(load(0, "bad-sample", badSample))
        assertEquals(-3, resultCode("bad-sample"))
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

    @Test
    fun targetedSliderWriteOnlyExecutesTheSelectedSlot() {
        val script = """
            @init
            gain = 1;
            slider_count = 0;
            @slider
            slider_count += 1;
            @sample
            spl0 *= gain; spl1 *= gain;
        """.trimIndent()

        for (slot in 0..3) {
            assertTrue(load(slot, "slider-isolation-$slot", script))
            assertEquals(1f, value(slot, "slider_count"), 0.0001f)
        }

        assertTrue(JamesDspWrapper.manipulateEelVariableSlot(handle, 2, "gain", 0.6f))
        assertEquals(1f, value(0, "slider_count"), 0.0001f)
        assertEquals(1f, value(1, "slider_count"), 0.0001f)
        assertEquals(2f, value(2, "slider_count"), 0.0001f)
        assertEquals(1f, value(3, "slider_count"), 0.0001f)
    }

    @Test
    fun invalidReloadPreservesThePreviouslyRunningProgram() {
        val good = """
            @init
            gain = 0.25;
            processed = 0;
            @sample
            processed += 1;
            spl0 *= gain; spl1 *= gain;
        """.trimIndent()
        val bad = """
            @init
            gain = 0.75;
            @slider
            broken = ;
            @sample
            spl0 *= gain; spl1 *= gain;
        """.trimIndent()

        assertTrue(load(0, "good", good))
        assertFalse(load(0, "bad-reload", bad))
        assertEquals(-4, resultCode("bad-reload"))
        assertEquals(0.25f, value(0, "gain"), 0.0001f)

        val input = floatArrayOf(1f, 1f, 1f, 1f)
        val output = FloatArray(input.size)
        JamesDspWrapper.processFloat(handle, input, output, 0, input.size)
        assertEquals(2f, value(0, "processed"), 0.0001f)
    }

    @Test
    fun legacyInitSampleScriptStillProcesses() {
        val legacy = """
            @init
            gain = 0.5;
            @sample
            spl0 *= gain; spl1 *= gain;
        """.trimIndent()

        assertTrue(load(0, "legacy", legacy))
        val input = floatArrayOf(1f, -1f)
        val output = FloatArray(input.size)
        JamesDspWrapper.processFloat(handle, input, output, 0, input.size)
        assertEquals(0.5f, output[0], 0.0001f)
        assertEquals(-0.5f, output[1], 0.0001f)
    }

    @Test
    fun nonFiniteScriptOutputIsSanitized() {
        val script = """
            @sample
            spl0 = 0 / 0;
            spl1 = 0 / 0;
        """.trimIndent()

        assertTrue(load(0, "non-finite", script))
        val input = floatArrayOf(1f, -1f)
        val output = FloatArray(input.size)
        JamesDspWrapper.processFloat(handle, input, output, 0, input.size)
        assertTrue(output[0].isFinite())
        assertTrue(output[1].isFinite())
    }
}
