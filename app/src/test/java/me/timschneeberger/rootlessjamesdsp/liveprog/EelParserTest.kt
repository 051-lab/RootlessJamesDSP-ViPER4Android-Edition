package me.timschneeberger.rootlessjamesdsp.liveprog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EelParserTest {
    @Test
    fun parsesMetadataDefaultsWithoutAssignments() {
        val parser = parse(
            """
            slider1:.5<0,1,.1>Gain
            mode:2<0,2,1{Off, Normal, Wide}>Mode
            @init
            gain = slider1;
            """.trimIndent()
        )

        assertEquals(0.5f, (parser.properties[0] as EelNumberRangeProperty<*>).value.toFloat(), 0f)
        assertEquals(2, (parser.properties[1] as EelListProperty).value)
    }

    @Test
    fun existingAssignmentsOverrideMetadataDefaults() {
        val parser = parse(
            """
            gain:.5<0,1,.1>Gain
            mode:2<0,2,1{Off, Normal, Wide}>Mode
            @init
            gain = 0.75;
            mode = 1;
            """.trimIndent()
        )

        assertEquals(0.75f, (parser.properties[0] as EelNumberRangeProperty<*>).value.toFloat(), 0f)
        assertEquals(1, (parser.properties[1] as EelListProperty).value)
    }

    @Test
    fun insertsMissingAssignmentAtStartOfInit() {
        val source = """
            slider1:.5<0,1,.1>Gain
            @init
            state = 1;
            @sample
            spl0 *= slider1;
        """.trimIndent()
        val property = parse(source).properties.single() as EelNumberRangeProperty<Float>
        property.value = 0.75f

        val updated = property.manipulateProperty(source)!!

        assertTrue(updated.contains("@init\nslider1 = 0.75;\nstate = 1;"))
    }

    @Test
    fun createsInitBeforeSliderWhenScriptHasNoInit() {
        val source = """
            slider1:.5<0,1,.1>Gain
            @slider
            gain = slider1;
            @sample
            spl0 *= gain;
        """.trimIndent()
        val property = parse(source).properties.single() as EelNumberRangeProperty<Float>
        property.value = 0.25f

        val updated = property.manipulateProperty(source)!!

        assertTrue(updated.contains("@init\nslider1 = 0.25;\n@slider"))
        assertTrue(updated.indexOf("@init") < updated.indexOf("@slider"))
    }

    @Test
    fun secondEditReplacesInsertedAssignment() {
        val source = """
            slider1:.5<0,1,.1>Gain
            @init
            gain = slider1;
        """.trimIndent()
        val property = parse(source).properties.single() as EelNumberRangeProperty<Float>

        property.value = 0.75f
        val first = property.manipulateProperty(source)!!
        property.value = 0.25f
        val second = property.manipulateProperty(first)!!

        assertEquals(1, Regex("slider1 =").findAll(second).count())
        assertTrue(second.contains("slider1 = 0.25;"))
    }

    private fun parse(source: String) = EelParser().apply {
        contents = source
        parse()
    }
}
