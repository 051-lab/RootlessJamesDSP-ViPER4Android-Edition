package me.timschneeberger.rootlessjamesdsp.utils.storage

import android.content.Context
import org.kamranzafar.jtar.TarEntry
import org.kamranzafar.jtar.TarInputStream
import org.kamranzafar.jtar.TarOutputStream
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

object Tar {
    private const val FILE_METADATA = "metadata"
    private const val MAX_METADATA_SIZE_BYTES = 64 * 1024

    /**
     * Create tar composer
     * @throws FileNotFoundException if file already exists as a directory or cannot be created for other reasons
     * @throws SecurityException if write access is denied
     */
    class Composer: AutoCloseable, KoinComponent {
        constructor(outputStream: OutputStream) {
            stream = TarOutputStream(outputStream)
        }

        constructor(file: File) {
            stream = TarOutputStream(file)
        }

        private val context: Context by inject()
        private val stream: TarOutputStream

        var metadata = mutableMapOf<String, String>()

        fun add(file: File, entryPath: String? = null): Boolean {
            if (!file.exists() || file.isDirectory) {
                Timber.e("addFile: ${file.absolutePath} is not valid")
                return false
            }

            stream.putNextEntry(TarEntry(file, (entryPath ?: file.name)))
            BufferedInputStream(FileInputStream(file)).use { origin ->
                var count: Int
                val data = ByteArray(2048)
                while (origin.read(data).also { count = it } != -1) {
                    stream.write(data, 0, count)
                }
                stream.flush()
            }
            return true
        }

        override fun close() {
            var metadataFile: File? = null
            try {
                metadataFile = File.createTempFile("archive-metadata-", ".tmp", context.cacheDir)
                metadataFile.writeText(
                    metadata
                        .map { "${it.key}=${it.value}" }
                        .joinToString("\n")
                )
                add(metadataFile, FILE_METADATA)
            } finally {
                metadataFile?.delete()
                stream.close()
            }
        }
    }

    /** Create tar reader */
    class Reader(
        private val inStream: InputStream,
        private val shouldExtract: ((entryName: String) -> Boolean) = { true }
    ) {
        private fun process(onNextEntry: (tis: TarInputStream, entryName: String) -> Unit) {
            TarInputStream(BufferedInputStream(inStream)).use { tis ->
                var entry: TarEntry?
                while (tis.nextEntry.also { entry = it } != null) {
                    val entryName = entry?.name
                    entryName ?: break

                    if (!shouldExtract(entryName) && entryName != FILE_METADATA) {
                        Timber.w("Entry name ignored: $entryName")
                        continue
                    }

                    onNextEntry(tis, entryName)
                }
            }
        }

        fun validate(): Boolean {
            Timber.d("Validating preset")

            var knownCount = 0
            try {
                process { _, name ->
                    if (name != FILE_METADATA)
                        knownCount++
                }
            }
            catch(ex: Exception) {
                Timber.e("Validation failed due to exception")
                Timber.w(ex)
                return false
            }

            if (knownCount < 1) {
                Timber.e("Archive did not contain any useful data")
                return false
            }

            return true
        }

        fun extract(targetFolder: File) : Map<String, String>? {
            if (targetFolder.exists() && !targetFolder.deleteRecursively()) {
                Timber.e("Failed to clear extraction directory")
                return null
            }
            if (!targetFolder.mkdirs() && !targetFolder.isDirectory) {
                Timber.e("Failed to create extraction directory")
                return null
            }

            val metadataBytes = ByteArrayOutputStream()
            try {
                val canonicalTarget = targetFolder.canonicalFile
                process { stream, name ->
                    var count: Int
                    val data = ByteArray(2048)

                    if (name == FILE_METADATA) {
                        while (stream.read(data).also { count = it } != -1) {
                            if (metadataBytes.size() > MAX_METADATA_SIZE_BYTES - count) {
                                throw IOException("Archive metadata exceeds the size limit")
                            }
                            metadataBytes.write(data, 0, count)
                        }
                        return@process
                    }

                    val output = containedArchiveEntry(canonicalTarget, name)
                        ?: throw IOException("Archive entry escapes extraction directory: $name")
                    val parent = output.parentFile
                    if (parent != null && !parent.mkdirs() && !parent.isDirectory) {
                        throw IOException("Failed to create directory for archive entry: $name")
                    }
                    BufferedOutputStream(FileOutputStream(output)).use { dest ->
                        while (stream.read(data).also { count = it } != -1) {
                            dest.write(data, 0, count)
                        }
                        dest.flush()
                    }
                }
                metadataBytes.flush()
            }
            catch(ex: Exception) {
                Timber.e("Extraction failed")
                Timber.w(ex)
                targetFolder.deleteRecursively()
                return null
            }

            return mutableMapOf<String, String>().apply {
                metadataBytes.toString().lines().forEach {
                    putAll(parseMetadata(it))
                }
            }
        }
    }

}

/** Resolves an archive entry only when it is a relative child of [targetFolder]. */
internal fun containedArchiveEntry(targetFolder: File, entryName: String): File? {
    if (entryName.isBlank() || File(entryName).isAbsolute ||
        entryName.split('/').any { it.isBlank() || it == "." || it == ".." }) return null

    return try {
        val canonicalTarget = targetFolder.canonicalFile
        val candidate = File(canonicalTarget, entryName).canonicalFile
        val targetPrefix = canonicalTarget.path + File.separator
        candidate.takeIf { it.path.startsWith(targetPrefix) }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }
}

/**
 * Parses one "key=value" metadata line, splitting on the first '=' only so a
 * value may itself contain '='. An empty line, a blank key, or a line with no
 * delimiter contributes nothing to the resulting map.
 */
internal fun parseMetadata(line: String): Map<String, String> {
    val parts = line.split("=", limit = 2)
    return if (parts.size == 2 && parts[0].isNotBlank()) {
        mapOf(parts[0] to parts[1].trim())
    } else {
        emptyMap()
    }
}
