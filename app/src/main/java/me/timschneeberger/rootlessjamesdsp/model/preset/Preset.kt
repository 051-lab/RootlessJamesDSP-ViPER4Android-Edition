package me.timschneeberger.rootlessjamesdsp.model.preset

import android.content.Context
import android.content.Intent
import android.system.ErrnoException
import me.timschneeberger.rootlessjamesdsp.BuildConfig
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.backup.BackupManager
import me.timschneeberger.rootlessjamesdsp.liveprog.EelParser
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.LiveprogSlots
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.broadcastPresetLoadEvent
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.toast
import me.timschneeberger.rootlessjamesdsp.utils.storage.Tar
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.xml.sax.SAXException
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

class Preset(val name: String, externalPath: File? = null): KoinComponent {
    private val ctx: Context by inject()
    private val externalPath = externalPath ?: File("${ctx.getExternalFilesDir(null)!!.path}/Presets")

    fun file(): File = File(externalPath, name)

    fun rename(newName: String): Boolean {
        return file().renameTo(File(externalPath, newName))
    }

    fun validate(): Boolean {
        return validate(FileInputStream(file()))
    }

    /**
     * @exception Exception if preset cannot be loaded
     */
    fun load(): PresetMetadata {
        val file = file()
        Timber.d("Loading preset from ${file.path}")
        return load(
            ctx,
            FileInputStream(file)
        )
    }

    fun save(): Boolean {
        val targetFile = file()
        if (targetFile.exists())
            targetFile.delete()

        try {
            externalPath.mkdirs()
        } catch (_: Exception) {}

        Timber.d("Saving preset $name to ${targetFile.path}")


        // Create a TarOutputStream
        try {
            Tar.Composer(targetFile).use { c ->
                c.metadata = mutableMapOf(
                    META_VERSION to PRESET_VERSION,
                    META_APP_VERSION to BuildConfig.VERSION_NAME,
                    META_APP_FLAVOR to BuildConfig.FLAVOR,
                    META_LIVEPROG_INCLUDED to false.toString(),
                    META_MIN_VERSION_CODE to MIN_VERSION_CODE
                )

                currentPath(ctx)
                    .listFiles()
                    ?.filter { it.name.startsWith("dsp_") || it.name == FILE_EFFECT_LAYOUT }
                    ?.filter { it.extension == "xml" }
                    ?.forEach(c::add)

                // Embed each occupied four-slot script under its own archive
                // entry. Only the file's name (not a traversable path) is
                // recorded in metadata, and the loader re-arms the working slot.
                var anyLiveprogIncluded = false
                LiveprogSlots.read(ctx).forEachIndexed { slot, value ->
                    if (value.isBlank())
                        return@forEachIndexed

                    val source = File(ctx.getExternalFilesDir(null), value)
                    if (!source.isFile) {
                        Timber.w("Skipping missing liveprog source for slot ${slot + 1}: $value")
                        return@forEachIndexed
                    }

                    c.metadata["liveprog_slot_${slot + 1}_name"] = source.name
                    c.add(source, liveprogEntryName(slot))
                    anyLiveprogIncluded = true
                    Timber.d("Saving included liveprog slot ${slot + 1} from '$value'")
                }
                if (anyLiveprogIncluded)
                    c.metadata[META_LIVEPROG_INCLUDED] = true.toString()
            }
        }
        catch (ex: ErrnoException) {
            Timber.d(ex)
            ex.localizedMessage?.let { ctx.toast(it) }
            return false
        }
        catch (ex: Exception) {
            Timber.d(ex)
            return false
        }

        return true
    }

    companion object {
        /* Update constants as needed */
        const val PRESET_VERSION = "4"
        const val MIN_VERSION_CODE = "90"

        // Four-slot presets store each embedded script as liveprog1..liveprog4.
        // The legacy single-script archive entry is retained for preset
        // versions 1..3 and maps to slot 0 while loading.
        const val FILE_LIVEPROG = "liveprog"

        /** First preset version that can carry one script per slot. */
        const val FOUR_SLOT_VERSION = 4

        const val META_VERSION = "version"
        const val META_APP_VERSION = "app_version"
        const val META_APP_FLAVOR = "app_flavor"
        const val META_LIVEPROG_INCLUDED = "liveprog_included" /* version 2+ */
        const val META_MIN_VERSION_CODE = "min_version_code" /* version 3+ */

        private fun currentPath(ctx: Context) = File(ctx.applicationInfo.dataDir + "/shared_prefs")
        // "dsp_*" covers every effect namespace and the processing chain order;
        // the visual layout (card order, hidden cards, custom groups) lives in
        // its own file and must be listed explicitly.
        private const val FILE_EFFECT_LAYOUT = "effect_layout.xml"

        private fun isKnownEntry(n: String) =
            (n.startsWith("dsp_") && n.endsWith("xml")) ||
                    n == FILE_EFFECT_LAYOUT ||
                    liveprogSlotForEntry(n) != null

        fun validate(inputStream: InputStream) = Tar.Reader(inputStream, ::isKnownEntry).validate()

        /**
         * @exception Exception if preset cannot be loaded
         */
        fun load(ctx: Context, stream: InputStream): PresetMetadata {
            Timber.d("Loading preset from stream")

            val targetFolder = File(ctx.cacheDir, "preset")
            val metadata = Tar.Reader(stream, ::isKnownEntry).extract(targetFolder)
            metadata ?: throw Exception(ctx.getString(R.string.filelibrary_corrupted))

            if(metadata[BackupManager.META_IS_BACKUP]?.toBoolean() == true) {
                Timber.e("This is a backup file, not a preset file")
                targetFolder.deleteRecursively()
                throw Exception(ctx.getString(R.string.filelibrary_is_backup_not_preset))
            }

            val version = metadata[META_VERSION]?.toIntOrNull() ?: 2
            Timber.d("Loaded preset file version $version")

            val minVersionCode = metadata[META_MIN_VERSION_CODE]?.toIntOrNull() ?: 0
            if(BuildConfig.VERSION_CODE < minVersionCode) {
                Timber.w("Preset too new. Version code $minVersionCode or later required")
                targetFolder.deleteRecursively()
                throw Exception(ctx.getString(R.string.filelibrary_file_too_new))
            }

            val files = targetFolder.listFiles()
            if(files == null || files.isEmpty()) {
                Timber.e("Preset archive did not contain any useful data")
                targetFolder.deleteRecursively()
                throw Exception(ctx.getString(R.string.filelibrary_corrupted))
            }

            files.forEach next@ { f ->
                if(!isKnownEntry(f.name))
                    return@next
                // Multi-slot embedded scripts are restored separately below and
                // must not be copied into the shared prefs directory. The
                // legacy FILE_LIVEPROG entry is left in shared prefs so the
                // v1..3 branch below can read it back out.
                if (liveprogSlotForEntry(f.name) != null && f.name != FILE_LIVEPROG)
                    return@next

                val target = File(currentPath(ctx), f.name)
                f.copyTo(target, overwrite = true)
                Timber.d("Copying to ${target.absolutePath}")
            }

            if (version >= FOUR_SLOT_VERSION) {
                restoreFourSlots(ctx, files, metadata)
            }
            else if (files.any { it.name == FILE_LIVEPROG }) {
                findLiveprogScriptPath(ctx)?.let {
                    val originalFile = File(it)
                    val targetFile =
                        File("${ctx.getExternalFilesDir(null)!!.path}/Liveprog", originalFile.name)
                    val tempPath = File(currentPath(ctx), FILE_LIVEPROG)

                    if(metadata[META_LIVEPROG_INCLUDED].toBoolean()) {
                        if(!targetFile.exists()) {
                            Timber.d("Extracting embedded liveprog file to '${targetFile.absolutePath}'")
                            tempPath.copyTo(targetFile, overwrite = true)
                            tempPath.delete()
                        }
                        else {
                            Timber.d("Copying parameters of embedded liveprog file to '${targetFile.absolutePath}'")
                            val parser = EelParser()
                            val parserNew = EelParser()
                            parser.load(tempPath.absolutePath)
                            parserNew.load(targetFile.absolutePath)
                            parser.properties.forEach(parserNew::manipulateProperty)
                            parserNew.save()
                            tempPath.delete()
                        }
                        ctx.sendLocalBroadcast(Intent(Constants.ACTION_SERVICE_RELOAD_LIVEPROG))
                    }
                }
            }

            // clean up
            targetFolder.deleteRecursively()

            ctx.broadcastPresetLoadEvent()

            return metadata.toMutableMap()
        }

        private fun findLiveprogScriptPath(ctx: Context): String? {
            val xmlFile = File(currentPath(ctx), "${Constants.PREF_LIVEPROG}.xml")
            if (!xmlFile.exists())
                return null
            try {
                val factory = DocumentBuilderFactory.newInstance()
                val builder = factory.newDocumentBuilder()
                val doc = builder.parse(FileInputStream(xmlFile))
                val nodes = doc.getElementsByTagName("string")

                for(i in 0 until nodes.length) {
                    val node = nodes.item(i)
                    if(node.attributes.getNamedItem("name").nodeValue ==
                        ctx.getString(R.string.key_liveprog_file)) {
                        return node.textContent.let {
                            ctx.getExternalFilesDir(null)!!.absolutePath + "/" + it
                        }.also {
                            Timber.d("Found liveprog file path: $it")
                        }
                    }
                }
            } catch (e: SAXException) {
                Timber.w(e)
            }
            catch (e: IOException) {
                Timber.w(e)
            }
            return null
        }

        /**
         * Restores each embedded four-slot script into the external Liveprog
         * directory and re-arms the working slot. Names come from metadata and
         * are reduced to their file name; blank names or names that differ from
         * the raw value (i.e. any that embed separators or traversal) are
         * rejected. Existing destinations are merged via [EelParser] so newer
         * script code is preserved while the save-time controls are applied.
         */
        private fun restoreFourSlots(
            ctx: Context,
            files: Array<File>,
            metadata: Map<String, String>
        ) {
            val liveprogDir =
                File("${ctx.getExternalFilesDir(null)!!.path}/Liveprog").also { it.mkdirs() }
            val canonicalLiveprog = liveprogDir.canonicalFile
            var reloaded = false

            files.forEach next@ { f ->
                val slot = liveprogSlotForEntry(f.name) ?: return@next
                if (f.name == FILE_LIVEPROG || slot < 0 || slot >= LiveprogSlots.COUNT)
                    return@next

                val rawName = metadata["liveprog_slot_${slot + 1}_name"] ?: return@next
                val filename = File(rawName).name
                if (filename.isBlank() || filename != rawName) {
                    Timber.w("Rejecting liveprog name '$rawName' for slot ${slot + 1}")
                    return@next
                }

                val targetFile = File(liveprogDir, filename)
                if (targetFile.parentFile?.canonicalFile != canonicalLiveprog) {
                    Timber.w("Refusing to write outside Liveprog: '${targetFile.absolutePath}'")
                    return@next
                }

                if (!targetFile.exists()) {
                    Timber.d("Extracting embedded liveprog ${slot + 1} to '${targetFile.absolutePath}'")
                    f.copyTo(targetFile, overwrite = true)
                    f.delete()
                }
                else {
                    Timber.d("Merging params of embedded liveprog ${slot + 1} into '${targetFile.absolutePath}'")
                    val parser = EelParser()
                    val parserNew = EelParser()
                    parser.load(f.absolutePath)
                    parserNew.load(targetFile.absolutePath)
                    parser.properties.forEach(parserNew::manipulateProperty)
                    parserNew.save()
                    f.delete()
                }

                // Re-arm the slot with the restored path so gaps are preserved.
                LiveprogSlots.write(ctx, slot, "Liveprog/$filename")
                reloaded = true
            }

            // Broadcast once after all entries, not once per slot.
            if (reloaded)
                ctx.sendLocalBroadcast(Intent(Constants.ACTION_SERVICE_RELOAD_LIVEPROG))
        }
    }
}

/** Archive entry name for an embedded four-slot script (slots 0..3). */
internal fun liveprogEntryName(slot: Int): String = when (slot) {
    0 -> "liveprog1"
    1 -> "liveprog2"
    2 -> "liveprog3"
    3 -> "liveprog4"
    else -> throw IllegalArgumentException("Unknown LiveProg slot: $slot")
}

/**
 * Maps a four-slot archive entry name back to its slot, or a legacy
 * [Preset.Companion.FILE_LIVEPROG_LEGACY] entry to slot 0. Returns null for
 * any other name.
 */
internal fun liveprogSlotForEntry(name: String): Int? = when (name) {
    Preset.FILE_LIVEPROG -> 0
    else -> when (name) {
        "liveprog1" -> 0
        "liveprog2" -> 1
        "liveprog3" -> 2
        "liveprog4" -> 3
        else -> null
    }
}


typealias PresetMetadata = MutableMap<String, String>