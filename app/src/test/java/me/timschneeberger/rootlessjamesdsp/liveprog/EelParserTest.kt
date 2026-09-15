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

    @Test
    fun discoveryCurrentlyExceedsTheReferenceForkLimit() {
        // Characterization: migration must deliberately change this to a 128-property cap.
        for (count in listOf(127, 128, 129, 256)) {
            val source = (1..count).joinToString("\n") { "p$it:.5<0,1,.1>Parameter $it" }
            assertEquals(count, parse(source).properties.size)
        }
    }

    @Test
    fun discoveryCurrentlyKeepsDuplicateAndPostSectionMetadata() {
        val parser = parse("gain:.5<0,1,.1>First\ngain:.2<0,1,.1>Duplicate\n@init\n// late:.3<0,1,.1>Late")
        assertEquals(listOf("gain", "gain", "late"), parser.properties.map { it.key })
    }

    @Test
    fun malformedRangesDoNotPreventLaterValidDiscovery() {
        val parser = parse("""
            broken:not-a-number<0,1,.1>Malformed
            overflow:1e99<0,1,.1>Overflow
            reversed:.5<1,0,.1>Reversed
            zero:.5<0,1,0>Zero step
            valid:.5<0,1,.1>Valid
        """.trimIndent())
        assertEquals(listOf("valid"), parser.properties.map { it.key })
    }

    @Test
    fun fourParserInstancesDoNotShareParameterObjects() {
        val parsers = List(4) { parse("gain:.5<0,1,.1>Gain") }
        @Suppress("UNCHECKED_CAST")
        val changed = parsers[3].properties.single() as EelNumberRangeProperty<Float>
        changed.value = .9f
        for (slot in 0..3) {
            val prop = parsers[slot].properties.single() as EelNumberRangeProperty<*>
            assertEquals(if (slot == 3) .9f else .5f, prop.value.toFloat(), 0f)
        }
        parsers[3].contents = "other:.1<0,1,.1>Other"
        parsers[3].parse()
        assertEquals("gain", parsers[0].properties.single().key)
        assertEquals("other", parsers[3].properties.single().key)
    }

    private fun parse(source: String) = EelParser().apply {
        contents = source
        parse()
    }
}
