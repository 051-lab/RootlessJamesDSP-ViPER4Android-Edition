package me.timschneeberger.rootlessjamesdsp.interop

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.timschneeberger.rootlessjamesdsp.liveprog.EelParser
import me.timschneeberger.rootlessjamesdsp.model.ProcessorMessage
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EelVaultCompatibilityInstrumentedTest {
    private class Callbacks : JamesDspWrapper.JamesDspCallbacks {
        var lastResult: Result? = null

        data class Result(val code: Int, val id: String, val error: String?)

        override fun onLiveprogOutput(message: String) = Unit
        override fun onLiveprogExec(id: String) = Unit
        override fun onLiveprogResult(resultCode: Int, id: String, errorMessage: String?) {
            lastResult = Result(resultCode, id, errorMessage)
        }
        override fun onVdcParseError() = Unit
        override fun onConvolverParseError(errorCode: ProcessorMessage.ConvolverErrorCode) = Unit
    }

    private lateinit var callbacks: Callbacks
    private var handle: JamesDspHandle = 0L
    private val context: Context = InstrumentationRegistry.getInstrumentation().context

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

    @Test
    fun materialMemoryOutputControlChangesProcessedLevel() {
        val source = context.assets.open(
            "eelvault-custom/materialmemory/materialmemory.eel"
        ).bufferedReader().use { it.readText() }

        fun processWithOutput(outputDb: String): Double {
            val script = source.replace(
                Regex("(?m)^slider4\\s*=.*$"),
                "slider4 = $outputDb;"
            )
            assertTrue(JamesDspWrapper.setLiveprog(handle, true, "material-$outputDb", script))
            val input = FloatArray(4096) { 0.1f }
            val output = FloatArray(input.size)
            JamesDspWrapper.processFloat(handle, input, output, 0, input.size)
            val start = output.size / 2
            return output.copyOfRange(start, output.size)
                .map { it.toDouble() * it }
                .average()
        }

        val quietPower = processWithOutput("-12.0")
        val loudPower = processWithOutput("6.0")
        assertTrue(
            "Material Memory output control did not change processed level: quiet=$quietPower loud=$loudPower",
            loudPower > quietPower * 8.0
        )
    }

    @Test
    fun stillRoomWetControlChangesProcessedSignal() {
        val source = context.assets.open(
            "eelvault-custom/stillroom/stillroom.eel"
        ).bufferedReader().use { it.readText() }

        fun processWithWet(wet: String): FloatArray {
            val script = source.replace(
                Regex("(?m)^wetPct\\s*=.*$"),
                "wetPct = $wet;"
            )
            assertTrue(JamesDspWrapper.setLiveprog(handle, true, "stillroom-$wet", script))
            val input = FloatArray(4096)
            input[0] = 0.5f
            input[1] = 0.5f
            val output = FloatArray(input.size)
            JamesDspWrapper.processFloat(handle, input, output, 0, input.size)
            return output
        }

        val dry = processWithWet("0")
        val wet = processWithWet("100")
        val difference = dry.indices
            .map { kotlin.math.abs(dry[it] - wet[it]).toDouble() }
            .average()
        assertTrue(
            "StillRoom wet control did not change the processed signal: difference=$difference",
            difference > 0.00001
        )
    }

    @Test
    fun loadsAllCustomEelVaultScripts() {
        val failures = mutableListOf<String>()
        val files = assetFiles("eelvault-custom")

        files.forEachIndexed { index, path ->
            val id = "eelvault-$index"
            val script = context.assets.open(path).bufferedReader().use { it.readText() }
            val expectedProperties = Regex("(?m)^\\s*\\w+\\s*:[^\\n<]*<").findAll(script).count()
            val parser = EelParser().apply {
                contents = script
                parse()
            }
            if (parser.properties.size != expectedProperties) {
                failures += "$path: parser found ${parser.properties.size} of $expectedProperties declared properties"
            }

            callbacks.lastResult = null
            val loaded = JamesDspWrapper.setLiveprog(handle, true, id, script)
            if (!loaded) {
                val result = callbacks.lastResult
                failures += "$path: code=${result?.code} error=${result?.error}"
            } else {
                try {
                    val input = FloatArray(256) { index ->
                        if (index % 2 == 0) 0.1f else -0.1f
                    }
                    val output = FloatArray(input.size)
                    JamesDspWrapper.processFloat(handle, input, output, 0, input.size)
                } catch (error: Throwable) {
                    failures += "$path: processing ${error::class.simpleName}: ${error.message}"
                }
            }
        }

        assertTrue("Failed EELVault scripts:\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    private fun assetFiles(path: String): List<String> {
        val children = context.assets.list(path).orEmpty()
        if (children.isEmpty()) return listOf(path)

        return children.flatMap { child ->
            assetFiles("$path/$child")
        }.filter { it.endsWith(".eel") }
    }
}
