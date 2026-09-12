package me.timschneeberger.rootlessjamesdsp.utils.storage

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID


object StorageUtils {

    fun importFile(context: Context, targetDir: String, uri: Uri): File? {
        val name = queryName(context, uri)
        if(name == null) {
            Timber.e("importFile: name is null")
            return null
        }

        val targetDirectory = File(targetDir)
        if (!targetDirectory.mkdirs() && !targetDirectory.isDirectory) {
            Timber.e("importFile: target directory could not be created")
            return null
        }

        val destinationFile = File(targetDirectory, name)
        val temporaryFile = File(targetDirectory, ".${name}.${UUID.randomUUID()}.part")
        try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            inputStream.use { ins ->
                if (!createFileFromStream(ins, temporaryFile)) return null
            }
            if (destinationFile.exists()) {
                Files.move(
                    temporaryFile.toPath(),
                    destinationFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            } else {
                try {
                    Files.move(
                        temporaryFile.toPath(),
                        destinationFile.toPath(),
                        StandardCopyOption.ATOMIC_MOVE
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporaryFile.toPath(), destinationFile.toPath())
                }
            }
        } catch (ex: Exception) {
            Timber.e(ex, "Failed to import file")
            return null
        } finally {
            temporaryFile.delete()
        }
        return destinationFile
    }

    fun openInputStreamSafe(context: Context, uri: Uri): InputStream? {
        return try {
            context.contentResolver.openInputStream(uri)
        } catch (ex: Exception) {
            Timber.e(ex.message)
            ex.printStackTrace()
            null
        }
    }

    private fun createFileFromStream(ins: InputStream, destination: File): Boolean {
        try {
            FileOutputStream(destination).use { os ->
                val buffer = ByteArray(4096)
                var length: Int
                while (ins.read(buffer).also { length = it } > 0) {
                    os.write(buffer, 0, length)
                }
                os.flush()
            }
        } catch (ex: Exception) {
            Timber.e(ex, "Failed to copy imported file")
            return false
        }
        return true
    }

    fun queryName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex < 0 || !cursor.moveToFirst() || cursor.isNull(nameIndex)) {
                    null
                } else {
                    safeDisplayName(cursor.getString(nameIndex))
                }
            }
        } catch (ex: Exception) {
            Timber.e(ex, "Failed to query imported file name")
            null
        }
    }
}

internal fun safeDisplayName(name: String): String? =
    name.takeIf {
        it.isNotBlank() &&
                it != "." &&
                it != ".." &&
                !it.contains('\u0000') &&
                File(it).name == it
    }
