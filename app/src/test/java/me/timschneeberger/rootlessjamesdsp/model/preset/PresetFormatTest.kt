package me.timschneeberger.rootlessjamesdsp.model.preset

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PresetFormatTest {

    @Test
    fun v4EntryNamesPreserveSlotIdentity() {
        assertEquals(
            listOf("liveprog1", "liveprog2", "liveprog3", "liveprog4"),
            (0 until 4).map(::liveprogEntryName)
        )
    }

    @Test
    fun loaderAcceptsLegacyAndV4Names() {
        assertEquals(0, liveprogSlotForEntry("liveprog"))
        assertEquals(0, liveprogSlotForEntry("liveprog1"))
        assertEquals(1, liveprogSlotForEntry("liveprog2"))
        assertEquals(2, liveprogSlotForEntry("liveprog3"))
        assertEquals(3, liveprogSlotForEntry("liveprog4"))
        assertNull(liveprogSlotForEntry("liveprog5"))
        assertNull(liveprogSlotForEntry("liveprog1/escape"))
    }

    @Test
    fun unknownEntryNamesRejected() {
        assertNull(liveprogSlotForEntry("liveprog0"))
        assertNull(liveprogSlotForEntry("Liveprog1"))
        assertNull(liveprogSlotForEntry(""))
    }
}