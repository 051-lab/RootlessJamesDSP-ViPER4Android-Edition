package me.timschneeberger.rootlessjamesdsp.preference

import org.junit.Assert.assertTrue
import org.junit.Test

class FileLibraryPreferenceTest {

    @Test
    fun recognizesLibraryExtensionsRegardlessOfCase() {
        assertTrue(FileLibraryPreference.hasIrsExtension("room.WAV"))
        assertTrue(FileLibraryPreference.hasLiveprogExtension("effect.EEL"))
        assertTrue(FileLibraryPreference.hasVdcExtension("device.VDC"))
        assertTrue(FileLibraryPreference.hasPresetExtension("preset.TAR"))
    }
}
