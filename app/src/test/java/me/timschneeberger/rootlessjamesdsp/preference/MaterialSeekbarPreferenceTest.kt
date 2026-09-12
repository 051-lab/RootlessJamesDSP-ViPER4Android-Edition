package me.timschneeberger.rootlessjamesdsp.preference

import org.junit.Assert.assertEquals
import org.junit.Test

class MaterialSeekbarPreferenceTest {

    @Test
    fun snapsRelativeToMinimumValue() {
        assertEquals(3f, snapSliderValue(2.8f, 1f, 9f, 2f), 0f)
    }

    @Test
    fun clampsValuesBeforeSnapping() {
        assertEquals(128f, snapSliderValue(-1f, 128f, 16_384f, 128f), 0f)
        assertEquals(16_384f, snapSliderValue(20_000f, 128f, 16_384f, 128f), 0f)
    }

    @Test
    fun preservesContinuousValuesWithinRange() {
        assertEquals(1.25f, snapSliderValue(1.25f, 0f, 2f, 0f), 0f)
    }

    @Test
    fun preservesStepsThatEvenlyDivideTheRange() {
        assertEquals(2f, compatibleSliderIncrement(1f, 9f, 2f), 0f)
    }

    @Test
    fun disablesStepsThatDoNotEvenlyDivideTheRange() {
        assertEquals(0f, compatibleSliderIncrement(0f, 10f, 6f), 0f)
    }
}
