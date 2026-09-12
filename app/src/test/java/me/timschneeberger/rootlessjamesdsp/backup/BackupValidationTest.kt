package me.timschneeberger.rootlessjamesdsp.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupValidationTest {

    @Test
    fun requiresExplicitBackupMarkerBeforeRestore() {
        assertTrue(BackupManager.isBackupMetadata(mapOf(BackupManager.META_IS_BACKUP to "true")))
        assertFalse(BackupManager.isBackupMetadata(emptyMap()))
        assertFalse(BackupManager.isBackupMetadata(mapOf(BackupManager.META_IS_BACKUP to "false")))
    }

    @Test
    fun acceptsOnlyExpectedArchiveLayouts() {
        assertTrue(BackupManager.isKnownFile("shared_prefs/dsp_main.xml"))
        assertTrue(BackupManager.isKnownFile("profiles/headphones/profile.json"))
        assertTrue(BackupManager.isKnownFile("Convolver/room.WAV"))

        assertFalse(BackupManager.isKnownFile("../shared_prefs/dsp_main.xml"))
        assertFalse(BackupManager.isKnownFile("profiles/../shared_prefs/dsp_main.xml"))
        assertFalse(BackupManager.isKnownFile("shared_prefs/nested/dsp_main.xml"))
        assertFalse(BackupManager.isKnownFile("ConvolverEvil/room.wav"))
        assertFalse(BackupManager.isKnownFile("Convolver/nested/room.wav"))
    }
}
