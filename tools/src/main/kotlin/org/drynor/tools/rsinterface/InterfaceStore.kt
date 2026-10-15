package org.drynor.tools.rsinterface

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.ReadOnlyCache
import world.gregs.voidps.cache.definition.data.InterfaceComponentDefinition
import world.gregs.voidps.cache.definition.data.InterfaceComponentHook
import world.gregs.voidps.cache.definition.data.InterfaceComponentSetting
import world.gregs.voidps.cache.definition.decoder.InterfaceDecoder
import world.gregs.voidps.cache.definition.encoder.InterfaceEncoder
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.lang.reflect.Field
import java.nio.BufferOverflowException

/**
 * Raised when a save would have to write to a cache that cannot be written to.
 *
 * Both cache implementations the tools use extend
 * [world.gregs.voidps.cache.ReadOnlyCache], whose `write` throws and whose
 * `update` returns false, so this is the normal outcome of [InterfaceStore.save]
 * today. It exists so the failure is loud: the alternative is a save button that
 * appears to work and changes nothing.
 */
class CacheNotWritableException(message: String) : IllegalStateException(message)

/** What [InterfaceStore.save] should do besides writing components. */
data class SaveOptions(
    /** Refuse to replace an interface that already exists when false. */
    val overwrite: Boolean = true,
    /** Dump the current archive bytes next to the JSON before writing. */
    val backup: Boolean = true,
    /** Write the JSON sidecar as well, which is the diffable source of truth. */
    val exportJson: Boolean = true,
    /**
     * Overwrite files left behind by deleted trailing components with an inert
     * placeholder, since the cache API has no way to remove a file from an archive.
     */
    val padStaleFiles: Boolean = true,
    /** Decode each encoded component back and report fields that changed. */
    val verify: Boolean = true
)

/**
 * One field that did not survive an encode followed by a decode.
 *
 * [derived] marks fields the decoder computes rather than reads - `id` comes from
 * the archive and file numbers, `hasScript` and `clickable` are set as a side
 * effect of reading hooks and key bindings - so a difference there is bookkeeping
 * rather than data loss.
 */
data class FieldDifference(
    val componentIndex: Int,
    val field: String,
    val written: String,
    val decoded: String,
    val derived: Boolean = false
) {
    override fun toString(): String = "[$componentIndex] $field: wrote $written, read back $decoded"
}

/** Outcome of [InterfaceStore.save]. */
data class SaveResult(
    val interfaceId: Int,
    /** Number of component files written. */
    val files: Int,
    /** Total encoded size in bytes. */
    val bytes: Int,
    /** Files that existed in the archive past the last component; see [SaveOptions.padStaleFiles]. */
    val staleFiles: List<Int>,
    val backupFile: File?,
    val jsonFile: File?,
    val differences: List<FieldDifference>,
    /** What [Cache.update] returned, i.e. whether the index was actually committed. */
    val committed: Boolean,
    val warnings: List<String>
)

/**
 * Reads interfaces out of cache index 3, writes them back, and keeps a JSON
 * sidecar so an interface can live in version control as source.
 *
 * ### Layout of an interface in the cache
 *
 * Index 3, archive = interface id, **one file per component**, file number =
 * component index. There is no per-interface header: the component count is the
 * archive's file count. That is what makes deletion awkward (see
 * [SaveOptions.padStaleFiles]) and what makes component index load bearing.
 *
 * ### Whether any of this reaches the cache
 *
 * [Cache.write] and [Cache.update] exist on the interface, but
 * [world.gregs.voidps.cache.FileCache] and
 * [world.gregs.voidps.cache.MemoryCache] both inherit
 * [world.gregs.voidps.cache.ReadOnlyCache]'s implementations, which throw and
 * return false respectively. So with the cache the tools load today, [save]
 * writes the JSON sidecar and the backup and then throws
 * [CacheNotWritableException]. [writable] says so up front; use it to grey out a
 * save button, and use [exportJson] as the working save path until a writable
 * cache exists.
 */
class InterfaceStore(
    private val cache: Cache,
    /** Where JSON sidecars are written; created on demand. */
    val directory: File = File(DEFAULT_DIRECTORY),
    /** Where pre-overwrite archive dumps go. */
    val backupDirectory: File = File(directory, "backups")
) {

    private val decoder = InterfaceDecoder()
    private val encoder = InterfaceEncoder()
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    /** False when [save] cannot reach the cache; [readOnlyReason] says why. */
    val writable: Boolean = cache !is ReadOnlyCache

    val readOnlyReason: String? = if (writable) {
        null
    } else {
        "${cache.javaClass.simpleName} extends ReadOnlyCache: write() throws " +
            "UnsupportedOperationException and update() returns false, so nothing can be " +
            "committed to cache index ${Index.INTERFACES}"
    }

    // --- Reading -----------------------------------------------------------

    /** Whether archive [id] of index 3 holds any component files. */
    fun exists(id: Int): Boolean = id >= 0 && cache.files(Index.INTERFACES, id).isNotEmpty()

    /** Highest interface id present in the cache. */
    fun lastInterfaceId(): Int = cache.lastArchiveId(Index.INTERFACES)

    /** Decodes interface [id] into an editable document. */
    fun open(id: Int): InterfaceDocument = InterfaceDocument.decode(cache, id)

    /**
     * A blank document for a new interface.
     *
     * @throws IllegalStateException when [id] already exists and [overwrite] is false.
     */
    fun newInterface(id: Int, overwrite: Boolean = false): InterfaceDocument {
        require(id >= 0) { "Interface id must not be negative" }
        check(overwrite || !exists(id)) {
            "Interface $id already exists with ${cache.files(Index.INTERFACES, id).size} components; " +
                "pass overwrite = true to start from scratch anyway"
        }
        return InterfaceDocument.empty(id)
    }

    // --- Encoding ----------------------------------------------------------

    /**
     * Encodes one component to its if3 file bytes.
     *
     * The buffer is grown and the encode retried on overflow, because
     * [BufferWriter] is fixed capacity and a component's size depends on its
     * strings and hooks.
     */
    fun encode(definition: InterfaceComponentDefinition): ByteArray {
        check(definition.id != -1) {
            "Component id is -1; InterfaceEncoder writes nothing for an unassigned id, which " +
                "would silently produce an empty cache file"
        }
        var capacity = estimateSize(definition)
        while (true) {
            try {
                val writer = BufferWriter(capacity)
                encoder.run { writer.encode(definition) }
                return writer.toArray()
            } catch (_: BufferOverflowException) {
                check(capacity < MAX_COMPONENT_BYTES) {
                    "Component ${definition.id} did not fit in $MAX_COMPONENT_BYTES bytes"
                }
                capacity *= 2
            }
        }
    }

    /** Encodes every component, keyed by the file number it belongs in. */
    fun encode(document: InterfaceDocument): Map<Int, ByteArray> {
        val files = LinkedHashMap<Int, ByteArray>(document.componentCount)
        document.components.forEachIndexed { index, definition ->
            files[index] = encode(definition)
        }
        return files
    }

    // --- Round-trip verification -------------------------------------------

    /**
     * Encodes [definition], decodes the bytes straight back, and reports every
     * field that changed.
     *
     * This is the honest answer to "will a save lose anything": rather than
     * trusting a list of known lossy fields, it compares all of them reflectively,
     * so a codec change shows up here rather than in a corrupted interface.
     */
    fun verifyRoundTrip(definition: InterfaceComponentDefinition, componentIndex: Int): List<FieldDifference> {
        val bytes = try {
            encode(definition)
        } catch (e: Exception) {
            return listOf(FieldDifference(componentIndex, "<encode>", "", e.toString()))
        }
        val fresh = InterfaceComponentDefinition(id = definition.id)
        try {
            decoder.run { fresh.read(BufferReader(bytes)) }
        } catch (e: Exception) {
            return listOf(
                FieldDifference(componentIndex, "<decode>", "${bytes.size} bytes", e.toString())
            )
        }
        val differences = ArrayList<FieldDifference>()
        for (field in ComponentFields.fields) {
            if (field.name in ComponentFields.transientFields) {
                continue
            }
            val written = ComponentFields.get(definition, field)
            val decoded = ComponentFields.get(fresh, field)
            if (!ComponentFields.same(written, decoded)) {
                differences.add(
                    FieldDifference(
                        componentIndex,
                        field.name,
                        ComponentFields.describe(written),
                        ComponentFields.describe(decoded),
                        field.name in ComponentFields.derivedFields
                    )
                )
            }
        }
        return differences
    }

    /** [verifyRoundTrip] across a whole document. */
    fun verifyRoundTrip(document: InterfaceDocument): List<FieldDifference> {
        val differences = ArrayList<FieldDifference>()
        document.components.forEachIndexed { index, definition ->
            differences.addAll(verifyRoundTrip(definition, index))
        }
        return differences
    }

    // --- Saving ------------------------------------------------------------

    /**
     * Encodes [document] and writes it to index 3 as archive = interface id,
     * file = component index.
     *
     * The order matters: everything that cannot lose work - the JSON sidecar and
     * the backup - happens first, so a read-only cache still leaves the developer
     * with their interface on disk before this throws.
     *
     * @throws CacheNotWritableException when the cache cannot be written to.
     * @throws IllegalStateException when the interface exists and
     *   [SaveOptions.overwrite] is false.
     */
    fun save(document: InterfaceDocument, options: SaveOptions = SaveOptions()): SaveResult {
        check(options.overwrite || !exists(document.id)) {
            "Interface ${document.id} already exists; pass SaveOptions(overwrite = true) to replace it"
        }
        val warnings = ArrayList<String>()
        val files = encode(document)
        val differences = if (options.verify) verifyRoundTrip(document) else emptyList()
        val lossy = differences.filter { !it.derived }
        if (lossy.isNotEmpty()) {
            warnings.add(
                "${lossy.size} field(s) across ${lossy.map { it.componentIndex }.distinct().size} " +
                    "component(s) do not survive an encode/decode round-trip"
            )
        }
        val existing = cache.files(Index.INTERFACES, document.id)
        val stale = existing.filter { it >= document.componentCount }.sorted()
        val backupFile = if (options.backup && existing.isNotEmpty()) backup(document.id) else null
        val jsonFile = if (options.exportJson) exportJson(document) else null
        if (stale.isNotEmpty()) {
            warnings.add(
                "${stale.size} file(s) past the last component (${stale.joinToString()}) cannot be " +
                    "removed - the cache API only writes files, it never deletes them" +
                    if (options.padStaleFiles) "; overwriting them with inert placeholders" else ""
            )
        }
        if (!writable) {
            throw CacheNotWritableException(
                "Cannot write interface ${document.id}: $readOnlyReason. " +
                    (jsonFile?.let { "The JSON sidecar was written to $it. " } ?: "") +
                    (backupFile?.let { "A backup of the current archive is at $it." } ?: "")
            )
        }
        var bytes = 0
        for ((file, data) in files) {
            cache.write(Index.INTERFACES, document.id, file, data)
            bytes += data.size
        }
        if (options.padStaleFiles) {
            for (file in stale) {
                val placeholder = encode(inertPlaceholder(document.id, file))
                cache.write(Index.INTERFACES, document.id, file, placeholder)
                bytes += placeholder.size
            }
        }
        val committed = cache.update()
        if (!committed) {
            warnings.add("Cache.update() returned false; the index was not committed")
        } else {
            document.markSaved()
        }
        return SaveResult(
            interfaceId = document.id,
            files = files.size,
            bytes = bytes,
            staleFiles = stale,
            backupFile = backupFile,
            jsonFile = jsonFile,
            differences = differences,
            committed = committed,
            warnings = warnings
        )
    }

    /**
     * Saves [document] under a different interface id.
     *
     * The copy's component hashes are repacked onto [newId] by
     * [InterfaceDocument.copyAs], so the duplicate drives its own components.
     */
    fun saveAs(document: InterfaceDocument, newId: Int, options: SaveOptions = SaveOptions()): SaveResult =
        save(document.copyAs(newId), options)

    /**
     * The safe half of [save]: JSON sidecar plus a backup of whatever is in the
     * cache, with nothing written to the cache itself.
     */
    fun saveSidecar(document: InterfaceDocument): File {
        if (exists(document.id)) {
            backup(document.id)
        }
        return exportJson(document)
    }

    /**
     * A component that decodes cleanly and draws nothing, used to neutralise files
     * left over from deleted components.
     *
     * Hidden, parentless, zero sized and type 0, so it is never laid out, never
     * drawn and never hit tested even though the file still exists.
     */
    fun inertPlaceholder(interfaceId: Int, index: Int): InterfaceComponentDefinition {
        val placeholder = ComponentDefinitions.create(interfaceId, index, ComponentType.CONTAINER)
        placeholder.hidden = true
        placeholder.parent = ComponentDefinitions.ROOT
        placeholder.name = "deleted"
        return placeholder
    }

    // --- Backups -----------------------------------------------------------

    fun backupFile(id: Int): File = File(backupDirectory, "interface-$id-${System.currentTimeMillis()}.bin")

    /**
     * Dumps every existing file of archive [id] to a single container so a bad save
     * can be undone.
     *
     * Format: the ascii magic `IFBK`, a version int, the interface id, the file
     * count, then per file its number, its length and its bytes. Decompressed
     * per-file data rather than the raw sector, because that is the shape
     * [Cache.write] takes when restoring.
     *
     * @return the file written, or null when the archive is empty.
     */
    fun backup(id: Int, file: File = backupFile(id)): File? {
        val fileIds = cache.files(Index.INTERFACES, id)
        if (fileIds.isEmpty()) {
            return null
        }
        file.parentFile?.mkdirs()
        DataOutputStream(file.outputStream().buffered()).use { out ->
            out.writeInt(BACKUP_MAGIC)
            out.writeInt(BACKUP_VERSION)
            out.writeInt(id)
            out.writeInt(fileIds.size)
            for (fileId in fileIds) {
                val data = cache.data(Index.INTERFACES, id, fileId) ?: ByteArray(0)
                out.writeInt(fileId)
                out.writeInt(data.size)
                out.write(data)
            }
        }
        return file
    }

    /** Reads a container written by [backup] back into file number to bytes. */
    fun readBackup(file: File): Map<Int, ByteArray> {
        DataInputStream(file.inputStream().buffered()).use { input ->
            check(input.readInt() == BACKUP_MAGIC) { "$file is not an interface backup" }
            val version = input.readInt()
            check(version == BACKUP_VERSION) { "Unsupported backup version $version in $file" }
            input.readInt()
            val count = input.readInt()
            val files = LinkedHashMap<Int, ByteArray>(count)
            repeat(count) {
                val fileId = input.readInt()
                val data = ByteArray(input.readInt())
                input.readFully(data)
                files[fileId] = data
            }
            return files
        }
    }

    // --- JSON sidecar ------------------------------------------------------

    fun jsonFile(id: Int): File = File(directory, "interface-$id.json")

    /** Writes [document] as JSON and returns the file. */
    fun exportJson(document: InterfaceDocument, file: File = jsonFile(document.id)): File {
        file.parentFile?.mkdirs()
        file.writeText(toJson(document))
        return file
    }

    /** Reads a document back out of a file written by [exportJson]. */
    fun importJson(file: File): InterfaceDocument = fromJson(file.readText())

    /**
     * The document as JSON.
     *
     * Fields equal to the `InterfaceComponentDefinition` constructor default are
     * omitted, which keeps a diff to the fields an author actually touched. `id` is
     * omitted too: it is always `componentIndex or (interfaceId shl 16)` and is
     * rebuilt on import, so it can never drift out of sync in a hand edited file.
     * `clientParams`, `stringId` and `extras` are omitted because no cache byte
     * carries them.
     */
    fun toJson(document: InterfaceDocument): String {
        val defaults = InterfaceComponentDefinition()
        val components = document.components.mapIndexed { index, definition ->
            val fields = LinkedHashMap<String, JsonElement>()
            fields["index"] = JsonPrimitive(index)
            for (field in ComponentFields.fields) {
                if (field.name in ComponentFields.transientFields || field.name == "id") {
                    continue
                }
                val value = ComponentFields.get(definition, field)
                if (field.name != "type" && field.name != "parent" &&
                    ComponentFields.same(value, ComponentFields.get(defaults, field))
                ) {
                    continue
                }
                fields[field.name] = encodeField(field, value)
            }
            JsonObject(fields)
        }
        val root = JsonObject(
            linkedMapOf(
                "format" to JsonPrimitive(JSON_FORMAT),
                "interfaceId" to JsonPrimitive(document.id),
                "components" to JsonArray(components)
            )
        )
        return json.encodeToString(JsonObject.serializer(), root)
    }

    /**
     * Parses [text] written by [toJson].
     *
     * Unknown keys are ignored so an older sidecar still loads; a known key with
     * the wrong shape throws, because silently dropping it would lose data the file
     * clearly meant to carry.
     */
    fun fromJson(text: String): InterfaceDocument {
        val root = json.parseToJsonElement(text).jsonObject
        val format = root["format"]?.jsonPrimitive?.int ?: JSON_FORMAT
        check(format <= JSON_FORMAT) { "Sidecar format $format is newer than $JSON_FORMAT" }
        val id = root["interfaceId"]?.jsonPrimitive?.int
            ?: error("Sidecar has no interfaceId")
        val array = root["components"]?.jsonArray ?: JsonArray(emptyList())
        val components = array.mapIndexed { index, element ->
            val definition = InterfaceComponentDefinition(id = ComponentDefinitions.pack(id, index))
            for ((name, value) in element.jsonObject) {
                if (name == "index" || name == "id") {
                    continue
                }
                val field = ComponentFields.field(name) ?: continue
                if (name in ComponentFields.transientFields) {
                    continue
                }
                ComponentFields.set(definition, field, decodeField(field, value))
            }
            definition
        }
        return InterfaceDocument(id, components)
    }

    private fun encodeField(field: Field, value: Any?): JsonElement = when {
        value == null -> JsonNull
        field.type == Int::class.javaPrimitiveType -> JsonPrimitive(value as Int)
        field.type == Byte::class.javaPrimitiveType -> JsonPrimitive((value as Byte).toInt())
        field.type == Boolean::class.javaPrimitiveType -> JsonPrimitive(value as Boolean)
        field.type == String::class.java -> JsonPrimitive(value as String)
        field.type == IntArray::class.java -> JsonArray((value as IntArray).map { JsonPrimitive(it) })
        field.type == ByteArray::class.java -> JsonArray((value as ByteArray).map { JsonPrimitive(it.toInt()) })
        field.type == Array<String>::class.java ->
            JsonArray((value as Array<*>).map { JsonPrimitive(it as String) })
        field.type == InterfaceComponentSetting::class.java -> {
            val setting = value as InterfaceComponentSetting
            JsonObject(
                linkedMapOf(
                    "setting" to JsonPrimitive(setting.setting),
                    "data" to JsonPrimitive(setting.anInt7413)
                )
            )
        }
        field.type == InterfaceComponentHook::class.java -> {
            val hook = value as InterfaceComponentHook
            JsonObject(
                linkedMapOf(
                    "script" to JsonPrimitive(hook.script),
                    "args" to JsonArray(
                        hook.args.map {
                            when (it) {
                                is Int -> JsonPrimitive(it)
                                is String -> JsonPrimitive(it)
                                else -> throw IllegalArgumentException(
                                    "Hook argument of unsupported type ${it.javaClass.name}"
                                )
                            }
                        }
                    )
                )
            )
        }
        else -> throw IllegalArgumentException(
            "No JSON mapping for ${field.name} of type ${field.type.name}"
        )
    }

    private fun decodeField(field: Field, element: JsonElement): Any? = when {
        element is JsonNull -> null
        field.type == Int::class.javaPrimitiveType -> element.jsonPrimitive.int
        field.type == Byte::class.javaPrimitiveType -> element.jsonPrimitive.int.toByte()
        field.type == Boolean::class.javaPrimitiveType -> element.jsonPrimitive.boolean
        field.type == String::class.java -> element.jsonPrimitive.content
        field.type == IntArray::class.java -> {
            val array = element.jsonArray
            IntArray(array.size) { array[it].jsonPrimitive.int }
        }
        field.type == ByteArray::class.java -> {
            val array = element.jsonArray
            ByteArray(array.size) { array[it].jsonPrimitive.int.toByte() }
        }
        field.type == Array<String>::class.java ->
            element.jsonArray.map { it.jsonPrimitive.content }.toTypedArray()
        field.type == InterfaceComponentSetting::class.java -> {
            val object_ = element.jsonObject
            InterfaceComponentSetting(
                object_["setting"]?.jsonPrimitive?.int ?: 0,
                object_["data"]?.jsonPrimitive?.int ?: -1
            )
        }
        field.type == InterfaceComponentHook::class.java -> {
            val object_ = element.jsonObject
            val args = object_["args"]?.jsonArray ?: JsonArray(emptyList())
            InterfaceComponentHook(
                object_["script"]?.jsonPrimitive?.int ?: -1,
                Array(args.size) { position ->
                    val primitive = args[position].jsonPrimitive
                    if (primitive.isString) primitive.content else primitive.int
                }
            )
        }
        else -> throw IllegalArgumentException(
            "No JSON mapping for ${field.name} of type ${field.type.name}"
        )
    }

    /**
     * A starting buffer size for [encode]. Over-estimating costs one short lived
     * allocation; under-estimating costs a retry, so this leans generous.
     */
    private fun estimateSize(definition: InterfaceComponentDefinition): Int {
        var size = BASE_COMPONENT_BYTES
        size += (definition.unknown?.length ?: 0) + definition.text.length +
            definition.name.length + definition.useOption.length +
            (definition.optionOverride?.length ?: 0) + STRING_SLACK * 5
        definition.options?.forEach { size += it.length + STRING_SLACK }
        definition.mouseIcon?.let { size += it.size * 2 + STRING_SLACK }
        for (hook in definition.hooks().values) {
            size += HOOK_BYTES
            for (arg in hook.args) {
                size += if (arg is String) arg.length + STRING_SLACK else 5
            }
        }
        size += ((definition.varps?.size ?: 0) + (definition.mouseLeaveArrayParams?.size ?: 0) +
            (definition.statTransmitFilter?.size ?: 0) + (definition.keyPressArray?.size ?: 0) +
            (definition.mouseWheelArray?.size ?: 0)) * 4
        return size
    }

    companion object {
        /** Bumped when the sidecar shape changes incompatibly. */
        const val JSON_FORMAT = 1

        const val DEFAULT_DIRECTORY = "data/interfaces"

        private const val BACKUP_MAGIC = 0x4946424b // "IFBK"
        private const val BACKUP_VERSION = 1

        private const val BASE_COMPONENT_BYTES = 256
        private const val STRING_SLACK = 8
        private const val HOOK_BYTES = 16
        private const val MAX_COMPONENT_BYTES = 1 shl 20
    }
}
