package me.timschneeberger.rootlessjamesdsp.interop

import androidx.test.ext.junit.runners.AndroidJUnit4
import me.timschneeberger.rootlessjamesdsp.model.ProcessorMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real JNI/audio-chain checks. Building this APK does not execute these tests. */
@RunWith(AndroidJUnit4::class)
class LiveProgLifecycleInstrumentedTest {
    @Test
    fun reloadFailureAndEmptyReplacementPreserveUnrelatedSlots() {
        for (target in 0..3) withEngine { handle ->
            for (slot in 0..3) assertTrue(load(handle, slot, COUNTER))
            assertEquals(.032f, process(handle).last(), .00001f)

            assertTrue(load(handle, target, COUNTER))
            val reloaded = process(handle)
            assertEquals(.028f, reloaded.first(), .00001f)
            assertEquals(.056f, reloaded.last(), .00001f)

            assertFalse(load(handle, target, "@sample\nspl0 = ;"))
            val preserved = process(handle)
            assertEquals(.060f, preserved.first(), .00001f)
            assertEquals(.088f, preserved.last(), .00001f)

            assertTrue(load(handle, target, ""))
            assertEquals(.075f, process(handle).first(), .00001f)
        }
    }

    @Test
    fun gapsAndReorderingKeepFixedSlotIdentity() = withEngine { handle ->
        assertTrue(load(handle, 0, "@init\ngain = .1;\n@sample\nspl0 += gain; spl1 += gain;"))
        assertTrue(load(handle, 3, "@sample\nspl0 *= 2; spl1 *= 2;"))
        // JdspEffectId LIVEPROG..LIVEPROG4 are fixed IDs 13..16, not chain positions.
        assertTrue(JamesDspWrapper.setChainOrder(handle, intArrayOf(13, 14, 15, 16)))
        assertEquals(.4f, process(handle, .1f).first(), .00001f)
        assertTrue(JamesDspWrapper.setChainOrder(handle, intArrayOf(16, 15, 14, 13)))
        assertEquals(.3f, process(handle, .1f).first(), .00001f)
        assertTrue(JamesDspWrapper.manipulateEelVariable(handle, "gain", .2f))
        assertEquals(.4f, process(handle, .1f).first(), .00001f)
    }

    @Test
    fun sampleRateSetterCurrentlyLeavesEachSlotStale() {
        // Explicit characterization of an OPEN acceptance defect, not desired behavior.
        for (slot in 0..3) withEngine { handle ->
            assertTrue(load(handle, slot, "@sample\nspl0 = srate / 100000; spl1 = spl0;"))
            assertEquals(.48f, process(handle).first(), .00001f)
            JamesDspWrapper.setSamplingRate(handle, 44100f, false)
            assertEquals(.48f, process(handle).first(), .00001f)
        }
    }

    private fun load(handle: Long, slot: Int, script: String) =
        if (slot == 0) JamesDspWrapper.setLiveprog(handle, true, "slot-$slot", script)
        else JamesDspWrapper.setLiveprogSlot(handle, slot, true, "slot-$slot", script)

    private fun process(handle: Long, input: Float = 0f): FloatArray = FloatArray(16).also {
        JamesDspWrapper.processFloat(handle, FloatArray(16) { input }, it)
    }

    private fun withEngine(test: (Long) -> Unit) {
        val callbacks = object : JamesDspWrapper.JamesDspCallbacks {
            override fun onLiveprogOutput(message: String) = Unit
            override fun onLiveprogExec(id: String) = Unit
            override fun onLiveprogResult(resultCode: Int, id: String, errorMessage: String?) = Unit
            override fun onVdcParseError() = Unit
            override fun onConvolverParseError(errorCode: ProcessorMessage.ConvolverErrorCode) = Unit
        }
        val handle = JamesDspWrapper.alloc(callbacks)
        assertNotEquals(0L, handle)
        try {
            assertTrue(JamesDspWrapper.setLimiterMode(handle, 2))
            JamesDspWrapper.setSamplingRate(handle, 48000f, false)
            test(handle)
        } finally {
            JamesDspWrapper.free(handle)
        }
    }

    companion object {
        private const val COUNTER = "@init\nticks = 0;\n@sample\nticks += 1; spl0 += ticks * .001; spl1 += ticks * .001;"
    }
}
