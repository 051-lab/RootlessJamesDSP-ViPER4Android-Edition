package me.timschneeberger.rootlessjamesdsp.utils.extensions

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.ResponseBody
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

object ApiExtensions {
    sealed class DownloadState {
        data class Downloading(val progress: Int, val currentBytes: Long, val totalBytes: Long) : DownloadState()
        data class Finished(val file: File) : DownloadState()
        data class Failed(val error: Throwable? = null) : DownloadState()
    }

    fun ResponseBody.save(destinationFile: File): Flow<DownloadState> {
        return flow {
            val totalBytes = contentLength()
            emit(DownloadState.Downloading(0, 0, totalBytes))

            var temporaryFile: File? = null

            try {
                val parent = destinationFile.absoluteFile.parentFile
                    ?: throw IOException("Download destination has no parent directory")
                if (!parent.mkdirs() && !parent.isDirectory)
                    throw IOException("Failed to create download directory")
                val downloadFile = File(parent, ".${destinationFile.name}.${UUID.randomUUID()}.part")
                temporaryFile = downloadFile

                byteStream().use { inputStream ->
                    downloadFile.outputStream().use { outputStream ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var progressBytes = 0L

                        var bytes = inputStream.read(buffer)
                        while (bytes >= 0) {
                            outputStream.write(buffer, 0, bytes)
                            progressBytes += bytes
                            bytes = inputStream.read(buffer)
                            emit(
                                DownloadState.Downloading(
                                    if (totalBytes > 0)
                                        ((progressBytes * 100) / totalBytes).coerceIn(0, 100).toInt()
                                    else
                                        0,
                                    progressBytes,
                                    totalBytes
                                )
                            )
                        }
                    }
                }
                if (destinationFile.exists()) {
                    Files.move(
                        downloadFile.toPath(),
                        destinationFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                } else {
                    try {
                        Files.move(
                            downloadFile.toPath(),
                            destinationFile.toPath(),
                            StandardCopyOption.ATOMIC_MOVE
                        )
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(downloadFile.toPath(), destinationFile.toPath())
                    }
                }
                emit(DownloadState.Finished(destinationFile))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emit(DownloadState.Failed(e))
            } finally {
                temporaryFile?.delete()
            }
        }
            .flowOn(Dispatchers.IO)
            .distinctUntilChanged()
    }
}
