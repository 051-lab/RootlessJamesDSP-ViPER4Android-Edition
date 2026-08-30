package me.timschneeberger.rootlessjamesdsp.utils.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TarMetadataTest {

    @Test
    fun valuesContainingEqualsSignsArePreserved() {
        assertEquals(
            "mix=final.eel",
            parseMetadata("slot=mix=final.eel")["slot"]
        )
    }

    @Test
    fun blankKeysAreDropped() {
        assertFalse(parseMetadata("=bad").containsKey(""))
    }

    @Test
    fun linesWithoutDelimiterAreIgnored() {
        assertTrue(parseMetadata("broken").isEmpty())
    }

    @Test
    fun whitespaceAroundValueIsTrimmed() {
        assertEquals("hello", parseMetadata(" k = hello ")[" k "])
    }
}