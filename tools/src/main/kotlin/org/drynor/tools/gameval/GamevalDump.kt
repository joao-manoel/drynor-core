package org.drynor.tools.gameval

import org.drynor.cs2.Cs2Cache
import org.drynor.cs2.Cs2Codec
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Config
import world.gregs.voidps.cache.Definition
import world.gregs.voidps.cache.DefinitionDecoder
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.config.decoder.ClientVariableParameterDecoder
import world.gregs.voidps.cache.config.decoder.HitSplatDecoder
import world.gregs.voidps.cache.config.decoder.IdentityKitDecoder
import world.gregs.voidps.cache.config.decoder.InventoryDecoder
import world.gregs.voidps.cache.config.decoder.MapSceneDecoder
import world.gregs.voidps.cache.config.decoder.OverlayDecoder
import world.gregs.voidps.cache.config.decoder.PlayerVariableParameterDecoder
import world.gregs.voidps.cache.config.decoder.QuestDecoder
import world.gregs.voidps.cache.config.decoder.UnderlayDecoder
import world.gregs.voidps.cache.config.decoder.WorldMapInfoDecoder
import world.gregs.voidps.cache.definition.data.InterfaceDefinition
import world.gregs.voidps.cache.definition.decoder.AnimationDecoder
import world.gregs.voidps.cache.definition.decoder.BASDecoder
import world.gregs.voidps.cache.definition.decoder.ClanVarDecoder
import world.gregs.voidps.cache.definition.decoder.EnumDecoder
import world.gregs.voidps.cache.definition.decoder.FontDecoder
import world.gregs.voidps.cache.definition.decoder.InterfaceDecoder
import world.gregs.voidps.cache.definition.decoder.ItemDecoder
import world.gregs.voidps.cache.definition.decoder.MaterialDecoder
import world.gregs.voidps.cache.definition.decoder.NPCDecoder
import world.gregs.voidps.cache.definition.decoder.ObjectDecoder
import world.gregs.voidps.cache.definition.decoder.ParamDecoder
import world.gregs.voidps.cache.definition.decoder.QuickChatOptionDecoder
import world.gregs.voidps.cache.definition.decoder.QuickChatPhraseDecoder
import world.gregs.voidps.cache.definition.decoder.SpotAnimDecoder
import world.gregs.voidps.cache.definition.decoder.StructDecoder
import world.gregs.voidps.cache.definition.decoder.VarBitDecoder
import java.io.File

/**
 * Dumps the whole 727 cache to one JSONL file per gameval type.
 *
 * The corpus exists to be diffed against other revisions' dumps, so it is written for a differ and
 * not for a human: one JSON object per line, ascending id, every array inline and complete, and
 * one file per type so a type can be reloaded on its own. Types are decoded one at a time and
 * streamed straight out, so peak memory is one type's definitions rather than the whole cache.
 *
 * Usage: `gameval dump <outDir>`
 */
object GamevalDump {

    /**
     * Config-index archives the 727 tree has no decoder for. They are still dumped, as raw
     * bytes, because an id-to-bytes listing is enough to line the ids up with an RS3 name list.
     */
    private val RAW_CONFIG_ARCHIVES = listOf(
        "cursor" to Config.CURSORS,
        "headbar" to Config.HIT_BARS,
        "seqconfig" to Config.SEQUENCES,
        "varcstring" to Config.VARC_STRINGS,
        "skybox" to Config.SKY_BOXES,
        "lightintensity" to Config.LIGHT_INTENSITY,
        "varclansetting" to Config.VAR_CLAN_SETTINGS,
        "varplayer" to Config.VAR_PLAYER,
        "varnpc" to Config.VAR_NPC,
        "varclient" to Config.VAR_CLIENT,
        "varbitconfig" to Config.VAR_BIT,
        "animflowcontrol" to Config.ANIM_FLOW_CONTROL,
        "vargroup" to Config.VAR_GROUP
    )

    /** Above this size a raw config file is identified by digest only, not spelled out in hex. */
    private const val MAX_HEX_BYTES = 4096

    /**
     * Where a dump lands when no directory is given.
     *
     * The catalogs the dump is evidence for live in the cache source tree
     * (`cache-source/gamevals`, see `docs/cache-source.md`), so the working files belong beside
     * them rather than in a `gamevals/` directory that no longer exists at the repository root.
     */
    private const val DEFAULT_OUTPUT = "cache-source/gamevals/work/727"

    fun run(cache: Cache, args: List<String>) {
        val mode = args.firstOrNull()?.lowercase() ?: "dump"
        if (mode != "dump") {
            println("Unknown gameval mode: $mode")
            printUsage()
            return
        }
        val directory = File(args.getOrNull(1) ?: DEFAULT_OUTPUT)
        if (!directory.exists() && !directory.mkdirs()) {
            println("Could not create output directory ${directory.absolutePath}")
            return
        }
        Dumper(cache, directory).dumpAll()
    }

    fun printUsage() {
        println("  gameval dump [outDir]    Dump every decoded cache type as JSONL (default $DEFAULT_OUTPUT)")
    }

    /** What one type's dump produced, for the manifest. */
    private class TypeReport(
        val type: String,
        val fileName: String,
        val source: String
    ) {
        var records: Int = 0
        var bytes: Long = 0
        var durationMs: Long = 0
        var error: String? = null
        var note: String? = null
    }

    private class Dumper(val cache: Cache, val directory: File) {

        private val reports = ArrayList<TypeReport>()

        fun dumpAll() {
            val start = System.currentTimeMillis()

            clientScripts()
            definitions("obj", "index ${Index.ITEMS}", ItemDecoder())
            definitions("npc", "index ${Index.NPCS}", NPCDecoder())
            definitions("loc", "index ${Index.OBJECTS}", ObjectDecoder())
            definitions("seq", "index ${Index.ANIMATIONS}", AnimationDecoder())
            definitions("spotanim", "index ${Index.SPOTANIMS}", SpotAnimDecoder())
            definitions("enum", "index ${Index.ENUMS}", EnumDecoder())
            definitions("struct", "config archive ${Config.STRUCTS}", StructDecoder())
            definitions("param", "config archive ${Config.PARAMS}", ParamDecoder())
            definitions("inv", "config archive ${Config.INVENTORIES}", InventoryDecoder())
            definitions("varbit", "index ${Index.VAR_BIT}", VarBitDecoder())
            definitions("varp", "config archive ${Config.VARP}", PlayerVariableParameterDecoder())
            definitions("varc", "config archive ${Config.VARC}", ClientVariableParameterDecoder())
            definitions("varclan", "config archive ${Config.VAR_CLAN}", ClanVarDecoder())
            definitions("bas", "config archive ${Config.RENDER_ANIMATIONS}", BASDecoder())
            definitions("hitmark", "config archive ${Config.HIT_SPLATS}", HitSplatDecoder())
            definitions("mapelement", "config archive ${Config.WORLD_MAP_INFO}", WorldMapInfoDecoder())
            definitions("idk", "config archive ${Config.IDENTITY_KIT}", IdentityKitDecoder())
            definitions("overlay", "config archive ${Config.FLOOR_OVERLAY}", OverlayDecoder())
            definitions("underlay", "config archive ${Config.FLOOR_UNDERLAY}", UnderlayDecoder())
            definitions("mapscene", "config archive ${Config.MAP_SCENES}", MapSceneDecoder())
            definitions("quest", "config archive ${Config.QUESTS}", QuestDecoder())
            definitions("quickchatoption", "index ${Index.QUICK_CHAT_MESSAGES} archive 0", QuickChatOptionDecoder(), present = { true })
            definitions("quickchatphrase", "index ${Index.QUICK_CHAT_MESSAGES} archive 1", QuickChatPhraseDecoder(), present = { true })
            definitions("material", "index ${Index.TEXTURE_DEFINITIONS}", MaterialDecoder(), present = { true })

            fontMetrics()
            interfaces()
            models()
            graphics()
            rawArchives("sound", Index.SYNTH_SOUNDS)
            rawArchives("midi", Index.MIDI_SONGS)
            for ((name, archive) in RAW_CONFIG_ARCHIVES) {
                rawConfigArchive(name, archive)
            }

            indexNameHashes()
            manifest(System.currentTimeMillis() - start)
        }

        /**
         * Decode a whole type through its own decoder and write it out, id ascending.
         *
         * A decoder that throws part-way through takes the whole type down with it, and the 727
         * tree has decoders that do - the identity kit files this revision writes are read past
         * their end. So a failed bulk load is retried id by id: every id that does decode is
         * still written, and the ones that do not carry a `decodeError` field instead of
         * silently vanishing from the corpus.
         */
        private fun <T : Definition> definitions(
            type: String,
            source: String,
            decoder: DefinitionDecoder<T>,
            present: (Int) -> Boolean = { id -> hasFile(decoder, id) },
            extras: (Int, T) -> List<Pair<String, Any?>> = { _, _ -> emptyList() }
        ) {
            write(type, source) { lines, report ->
                val errors = HashMap<Int, String>()
                val loaded = try {
                    decoder.load(cache)
                } catch (e: Throwable) {
                    report.note = "bulk load failed (${describe(e)}), retried id by id"
                    perId(decoder, errors)
                }
                for (id in loaded.indices) {
                    if (!present(id)) {
                        continue
                    }
                    val definition = loaded[id]
                    val error = errors[id]
                    val fields = if (error == null) extras(id, definition) else extras(id, definition) + ("decodeError" to error)
                    lines.write(id, definition, fields)
                }
                if (errors.isNotEmpty()) {
                    report.note = "${report.note}; ${errors.size} ids failed to decode"
                }
            }
        }

        /**
         * Whether the cache holds a file for [id] under [decoder]'s archive/file layout.
         *
         * The decoders size their arrays one past the last file, so a bulk load always ends on
         * an id that has no file, and some archives have holes (`obj`, `varp`, `varc`). Those ids
         * decode to a default definition; without this check they would be dumped as if the
         * cache had them, and a consumer would take placeholders for ids that do not exist.
         */
        private fun hasFile(decoder: DefinitionDecoder<*>, id: Int): Boolean =
            cache.files(decoder.index, decoder.getArchive(id)).contains(decoder.getFile(id))

        /** Decode every id of a type separately, recording rather than propagating failures. */
        private fun <T : Definition> perId(decoder: DefinitionDecoder<T>, errors: MutableMap<Int, String>): Array<T> {
            val size = decoder.size(cache) + 1
            val definitions = decoder.create(size)
            for (id in 0 until size) {
                try {
                    decoder.load(definitions, cache, id)
                } catch (e: Throwable) {
                    errors[id] = describe(e)
                }
            }
            return definitions
        }

        private fun describe(e: Throwable) = "${e.javaClass.simpleName}: ${e.message ?: "no message"}"

        /**
         * Clientscripts come from the tools CS2 codec, not from the core `ClientScriptDecoder`.
         *
         * The core decoder is written for a header this revision does not have: it skips the
         * long locals/args counts unless told it is reading 667, and it sizes the instruction
         * arrays from a fixed 12-byte back-offset instead of the switch block's own length. On
         * 727 that reads a garbage instruction count - millions of entries for a script with a
         * few hundred - so it produces both wrong counts and gigabytes of padding. The CS2 codec
         * here is verified by re-encoding every script back to identical bytes (`cs2 verify`).
         *
         * The three operand arrays run parallel to the instruction array: index n holds the
         * operand of instruction n, with the two unused stacks left at their zero value.
         */
        private fun clientScripts() {
            write("clientscript", "index ${Index.CLIENT_SCRIPTS}") { lines, report ->
                val hashes = cache.archiveNameHashes(Index.CLIENT_SCRIPTS)
                val scripts = Cs2Cache(cache)
                val last = cache.lastArchiveId(Index.CLIENT_SCRIPTS)
                var failed = 0
                for (id in 0..last) {
                    val raw = try {
                        scripts.raw(id)
                    } catch (_: Throwable) {
                        null
                    }
                    val identity = listOf<Pair<String, Any?>>(
                        "nameHash" to hashes[id]?.takeIf { it != 0 },
                        "present" to (raw != null),
                        "length" to (raw?.size ?: 0),
                        "sha1" to raw?.let { Json.sha1(it) }
                    )
                    if (raw == null) {
                        failed++
                        lines.write(id, null, identity + ("decodeError" to "no archive"))
                        continue
                    }
                    val script = try {
                        Cs2Codec.decode(raw)
                    } catch (e: Throwable) {
                        failed++
                        lines.write(id, null, identity + ("decodeError" to describe(e)))
                        continue
                    }
                    val count = script.instructions.size
                    val instructions = IntArray(count) { script.instructions[it].op.id }
                    val intOperands = IntArray(count) { script.instructions[it].intOperand }
                    val longOperands = LongArray(count) { script.instructions[it].longOperand }
                    val stringOperands = Array(count) { script.instructions[it].strOperand }
                    val switchTables = script.switchTables.map { table ->
                        table.map { case -> intArrayOf(case.key, case.offset) }
                    }
                    lines.write(
                        id, null, identity + listOf(
                            "name" to script.name,
                            "intArgumentCount" to script.intArgsCount,
                            "stringArgumentCount" to script.stringArgsCount,
                            "longArgumentCount" to script.longArgsCount,
                            "intVariableCount" to script.intLocalsCount,
                            "stringVariableCount" to script.stringLocalsCount,
                            "longVariableCount" to script.longLocalsCount,
                            "instructionCount" to count,
                            "instructions" to instructions,
                            "intOperands" to intOperands,
                            "stringOperands" to stringOperands,
                            "longOperands" to longOperands,
                            "switchTables" to switchTables
                        )
                    )
                }
                if (failed > 0) {
                    report.note = "$failed scripts had no decodable archive"
                }
            }
        }

        /**
         * Interfaces are streamed one at a time: a single interface can hold hundreds of
         * components, and holding all 1300 of them at once is the only part of this dump that
         * would need real heap.
         */
        private fun interfaces() {
            write("interface", "index ${Index.INTERFACES}") { lines, _ ->
                val decoder = InterfaceDecoder()
                val size = decoder.size(cache) + 1
                val definitions = decoder.create(size)
                for (id in 0 until size) {
                    decoder.load(definitions, cache, id)
                    val definition = definitions[id]
                    lines.write(id, definition, listOf("componentCount" to (definition.components?.size ?: 0)))
                    definitions[id] = InterfaceDefinition(id)
                }
            }
        }

        /** Font metrics carry their decoded kerning table plus the raw archive's identity. */
        private fun fontMetrics() {
            write("fontmetrics", "index ${Index.FONT_METRICS}") { lines, _ ->
                val loaded = FontDecoder().load(cache)
                for (id in loaded.indices) {
                    val data = cache.data(Index.FONT_METRICS, id, 0)
                    lines.write(id, loaded[id], rawFacts(data))
                }
            }
        }

        /**
         * Models are dumped as identity plus the header counts only. Full geometry is megabytes
         * per model and says nothing about which dev name an id carries; the vertex and face
         * counts are read straight from the footer without running a decode.
         */
        private fun models() {
            write("model", "index ${Index.MODELS}") { lines, _ ->
                for (id in archiveIds(Index.MODELS)) {
                    val data = cache.data(Index.MODELS, id, 0)
                    lines.write(id, null, rawFacts(data) + modelHeader(data))
                }
            }
        }

        /**
         * Graphics are dumped from the trailer only: frame count, canvas size, palette size and
         * each frame's placement. The pixel rasters are skipped, so nothing decompresses twice.
         */
        private fun graphics() {
            write("graphic", "index ${Index.GRAPHICS}") { lines, _ ->
                for (id in archiveIds(Index.GRAPHICS)) {
                    val data = cache.data(Index.GRAPHICS, id, 0)
                    lines.write(id, null, rawFacts(data) + graphicHeader(data))
                }
            }
        }

        /** Identity-only dump of an index whose archives have no decoder in this tree. */
        private fun rawArchives(type: String, index: Int) {
            write(type, "index $index") { lines, _ ->
                for (id in archiveIds(index)) {
                    val data = cache.data(index, id, 0)
                    lines.write(id, null, rawFacts(data) + listOf("fileCount" to cache.fileCount(index, id)))
                }
            }
        }

        /**
         * Identity plus full hex of every file in a config archive with no decoder. These
         * archives are tiny, and the bytes are what a future decoder would be written against.
         */
        private fun rawConfigArchive(type: String, archive: Int) {
            write(type, "config archive $archive") { lines, _ ->
                val last = cache.lastFileId(Index.CONFIGS, archive)
                for (id in 0..last) {
                    val data = cache.data(Index.CONFIGS, archive, id)
                    val hex = data?.takeIf { it.size <= MAX_HEX_BYTES }?.let { Json.hex(it) }
                    lines.write(id, null, rawFacts(data) + listOf("hex" to hex))
                }
            }
        }

        /** Ascending archive ids for [index], falling back to the raw archive listing. */
        private fun archiveIds(index: Int): IntArray {
            val ids = cache.archiveIds(index)
            return if (ids.isNotEmpty()) ids else cache.archives(index).sortedArray()
        }

        private fun rawFacts(data: ByteArray?): List<Pair<String, Any?>> = listOf(
            "present" to (data != null),
            "length" to (data?.size ?: 0),
            "sha1" to data?.let { Json.sha1(it) }
        )

        /**
         * Vertex and face counts from a model footer.
         *
         * Both 727 footer layouts open with the same four bytes, so the counts are readable
         * without decoding any geometry. The sizes match the tools model decoder's.
         */
        private fun modelHeader(data: ByteArray?): List<Pair<String, Any?>> {
            if (data == null || data.size < MIN_MODEL_BYTES) {
                return listOf("headerRead" to false)
            }
            val newFormat = data[data.size - 1] == (-1).toByte() && data[data.size - 2] == (-1).toByte()
            val offset = data.size - if (newFormat) NEW_FORMAT_FOOTER_BYTES else OLD_FORMAT_FOOTER_BYTES
            if (offset < 0) {
                return listOf("headerRead" to false, "newFormat" to newFormat)
            }
            val reader = BufferReader(data)
            reader.position(offset)
            val vertexCount = reader.readUnsignedShort()
            val faceCount = reader.readUnsignedShort()
            val texturedFaceCount = reader.readUnsignedByte()
            val footerFlags = reader.readUnsignedByte()
            return listOf(
                "headerRead" to true,
                "newFormat" to newFormat,
                "vertexCount" to vertexCount,
                "faceCount" to faceCount,
                "texturedFaceCount" to texturedFaceCount,
                "footerFlags" to footerFlags
            )
        }

        /**
         * Frame layout from a graphic trailer.
         *
         * The trailer is `7 + 8 * frames` bytes: the canvas size, the palette size, then four
         * shorts per frame, then the frame count itself as the final two bytes. Reading it is
         * the same walk the core graphic decoder makes
         * before it touches any pixels.
         */
        private fun graphicHeader(data: ByteArray?): List<Pair<String, Any?>> {
            if (data == null || data.size < 9) {
                return listOf("headerRead" to false)
            }
            val reader = BufferReader(data)
            reader.position(data.size - 2)
            val frames = reader.readUnsignedShort()
            val trailer = data.size - 7 - frames * 8
            if (frames <= 0 || trailer < 0) {
                return listOf("headerRead" to false, "frameCount" to frames)
            }
            reader.position(trailer)
            val width = reader.readUnsignedShort()
            val height = reader.readUnsignedShort()
            val paletteSize = reader.readUnsignedByte() + 1
            val offsetX = IntArray(frames) { reader.readUnsignedShort() }
            val offsetY = IntArray(frames) { reader.readUnsignedShort() }
            val frameWidth = IntArray(frames) { reader.readUnsignedShort() }
            val frameHeight = IntArray(frames) { reader.readUnsignedShort() }
            val layout = (0 until frames).map { frame ->
                linkedMapOf(
                    "offsetX" to offsetX[frame],
                    "offsetY" to offsetY[frame],
                    "width" to frameWidth[frame],
                    "height" to frameHeight[frame]
                )
            }
            return listOf(
                "headerRead" to true,
                "frameCount" to frames,
                "width" to width,
                "height" to height,
                "paletteSize" to paletteSize,
                "frames" to layout
            )
        }

        /** Every index that names its archives, as `{index: {archiveId: nameHash}}`. */
        private fun indexNameHashes() {
            val file = File(directory, "index-name-hashes.json")
            val builder = StringBuilder()
            builder.append('{')
            var first = true
            for (index in cache.indices().sortedArray()) {
                if (!cache.hasArchiveNames(index)) {
                    continue
                }
                val hashes = cache.archiveNameHashes(index)
                if (hashes.isEmpty()) {
                    continue
                }
                if (!first) {
                    builder.append(',')
                }
                first = false
                Json.string(builder, index.toString())
                builder.append(":{")
                var firstHash = true
                for (archive in hashes.keys.sorted()) {
                    if (!firstHash) {
                        builder.append(',')
                    }
                    firstHash = false
                    Json.string(builder, archive.toString())
                    builder.append(':').append(hashes.getValue(archive))
                }
                builder.append('}')
            }
            builder.append('}')
            file.writeText(builder.toString(), Charsets.UTF_8)
            println("index-name-hashes.json: ${file.length()} bytes")
        }

        /** Run one type's dump, timing it and recording whatever went wrong. */
        private fun write(type: String, source: String, block: (JsonLines, TypeReport) -> Unit) {
            val report = TypeReport(type, "$type.jsonl", source)
            reports.add(report)
            val start = System.currentTimeMillis()
            val lines = JsonLines(directory, report.fileName)
            try {
                block(lines, report)
            } catch (e: Throwable) {
                report.error = describe(e)
                System.err.println("Failed dumping $type: ${describe(e)}")
                e.printStackTrace()
            } finally {
                lines.close()
                report.records = lines.count
                report.bytes = lines.file.length()
                report.durationMs = System.currentTimeMillis() - start
                if (report.records == 0 && report.error == null) {
                    // Nothing under this source in this revision. An empty file would read as a
                    // dump that failed; the manifest entry says plainly that there was nothing.
                    lines.file.delete()
                    report.note = "no records: nothing under this source in the cache, file not written"
                }
            }
            println(
                "${report.fileName}: ${report.records} records, ${report.bytes} bytes, " +
                    "${report.durationMs}ms" + (report.error?.let { " ERROR $it" } ?: "")
            )
        }

        private fun manifest(durationMs: Long) {
            val builder = StringBuilder()
            builder.append("{\n")
            builder.append("  \"cache\": ")
            Json.string(builder, cache.javaClass.simpleName)
            builder.append(",\n  \"durationMs\": ").append(durationMs)
            builder.append(",\n  \"types\": {\n")
            for ((position, report) in reports.withIndex()) {
                builder.append("    ")
                Json.string(builder, report.type)
                builder.append(": {\"file\": ")
                Json.string(builder, report.fileName)
                builder.append(", \"records\": ").append(report.records)
                builder.append(", \"bytes\": ").append(report.bytes)
                builder.append(", \"durationMs\": ").append(report.durationMs)
                builder.append(", \"source\": ")
                Json.string(builder, report.source)
                builder.append(", \"error\": ")
                Json.value(builder, report.error)
                builder.append(", \"note\": ")
                Json.value(builder, report.note)
                builder.append('}')
                if (position != reports.lastIndex) {
                    builder.append(',')
                }
                builder.append('\n')
            }
            builder.append("  },\n  \"totalRecords\": ").append(reports.sumOf { it.records.toLong() })
            builder.append("\n}\n")
            val file = File(directory, "manifest.json")
            file.writeText(builder.toString(), Charsets.UTF_8)
            println("manifest.json: ${file.length()} bytes, ${reports.sumOf { it.records.toLong() }} records total")
        }
    }
}

/** Smallest archive that could hold a model header. */
private const val MIN_MODEL_BYTES = 20

/** Footer size for the `0xFFFF`-terminated model format: 21 header bytes plus the marker. */
private const val NEW_FORMAT_FOOTER_BYTES = 23

/** Footer size for the pre-`0xFFFF` model format. */
private const val OLD_FORMAT_FOOTER_BYTES = 18

/** Entry point for the `gameval` CLI mode. */
fun runGameval(cache: Cache, args: List<String>) = GamevalDump.run(cache, args)
