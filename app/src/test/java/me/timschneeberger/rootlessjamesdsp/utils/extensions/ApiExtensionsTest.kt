package me.timschneeberger.rootlessjamesdsp.utils.extensions

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType
import okhttp3.ResponseBody
import okio.BufferedSource
import okio.buffer
import okio.source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.io.InputStream

class ApiExtensionsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun interruptedDownloadDoesNotReplaceExistingFile() = runBlocking {
        val destination = tmp.newFile("update.apk").apply { writeText("existing") }
        val response = failingResponseBody()

        val states = with(ApiExtensions) { response.save(destination).toList() }

        assertTrue(states.last() is ApiExtensions.DownloadState.Failed)
        assertEquals("existing", destination.readText())
        assertTrue(destination.parentFile!!.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    @Test
    fun unknownContentLengthDoesNotDivideByZero() = runBlocking {
        val destination = tmp.newFile("update.apk").apply { delete() }
        val response = responseBody("complete", -1)

        val states = with(ApiExtensions) { response.save(destination).toList() }

        assertTrue(states.last() is ApiExtensions.DownloadState.Finished)
        assertEquals("complete", destination.readText())
    }

    @Test
    fun successfulDownloadReplacesExistingFile() = runBlocking {
        val destination = tmp.newFile("update.apk").apply { writeText("old") }
        val response = responseBody("new", 3)

        val states = with(ApiExtensions) { response.save(destination).toList() }

        assertTrue(states.last() is ApiExtensions.DownloadState.Finished)
        assertEquals("new", destination.readText())
    }

    private fun responseBody(contents: String, length: Long) = object : ResponseBody() {
        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = length
        override fun source(): BufferedSource = contents.byteInputStream().source().buffer()
    }

    private fun failingResponseBody() = object : ResponseBody() {
        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = 10
        override fun source(): BufferedSource = object : InputStream() {
            private var reads = 0

            override fun read(): Int {
                if (reads++ < 3) return 'x'.code
                throw IOException("simulated interruption")
            }
        }.source().buffer()
    }
}
