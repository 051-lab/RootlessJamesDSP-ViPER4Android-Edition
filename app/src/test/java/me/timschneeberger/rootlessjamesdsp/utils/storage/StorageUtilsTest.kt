package me.timschneeberger.rootlessjamesdsp.utils.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StorageUtilsTest {

    @Test
    fun acceptsPlainDisplayNames() {
        assertEquals("profile.irs", safeDisplayName("profile.irs"))
    }

    @Test
    fun rejectsDisplayNamesContainingPaths() {
        assertNull(safeDisplayName("../profile.irs"))
        assertNull(safeDisplayName("folder/profile.irs"))
        assertNull(safeDisplayName("/profile.irs"))
    }

    @Test
    fun rejectsInvalidSpecialNames() {
        assertNull(safeDisplayName(""))
        assertNull(safeDisplayName("."))
        assertNull(safeDisplayName(".."))
        assertNull(safeDisplayName("bad\u0000name.irs"))
    }
}
