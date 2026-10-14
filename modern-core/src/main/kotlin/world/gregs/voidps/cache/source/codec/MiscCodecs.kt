package world.gregs.voidps.cache.source.codec

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.Definition
import world.gregs.voidps.cache.DefinitionDecoder
import world.gregs.voidps.cache.DefinitionEncoder
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.AnimationBaseDefinition
import world.gregs.voidps.cache.definition.data.BodyDefinition
import world.gregs.voidps.cache.definition.data.CutsceneDefinition
import world.gregs.voidps.cache.definition.data.DefaultsDefinition
import world.gregs.voidps.cache.definition.data.GameTipDefinition
import world.gregs.voidps.cache.definition.data.HuffmanTableDefinition
import world.gregs.voidps.cache.definition.data.QuickChatOptionDefinition
import world.gregs.voidps.cache.definition.data.QuickChatPhraseDefinition
import world.gregs.voidps.cache.definition.decoder.AnimationBaseDecoder
import world.gregs.voidps.cache.definition.decoder.BodyDecoder
import world.gregs.voidps.cache.definition.decoder.CutsceneDecoder
import world.gregs.voidps.cache.definition.decoder.DefaultsDecoder
import world.gregs.voidps.cache.definition.decoder.GameTipDecoder
import world.gregs.voidps.cache.definition.decoder.HuffmanTableDecoder
import world.gregs.voidps.cache.definition.decoder.QuickChatOptionDecoder
import world.gregs.voidps.cache.definition.decoder.QuickChatPhraseDecoder
import world.gregs.voidps.cache.definition.encoder.AnimationBaseEncoder
import world.gregs.voidps.cache.definition.encoder.BodyEncoder
import world.gregs.voidps.cache.definition.encoder.CutsceneEncoder
import world.gregs.voidps.cache.definition.encoder.DefaultsEncoder
import world.gregs.voidps.cache.definition.encoder.GameTipEncoder
import world.gregs.voidps.cache.definition.encoder.HuffmanTableEncoder
import world.gregs.voidps.cache.definition.encoder.QuickChatOptionEncoder
import world.gregs.voidps.cache.definition.encoder.QuickChatPhraseEncoder

/**
 * The eight small indices that hold a decodable type and nothing like each other.
 *
 * `DefinitionCodecs` covers the twenty-two types that are configs; these are the rest of what 727 stores
 * as records rather than as assets - animation frames and skeletons, the huffman table, the world map, both
 * quick chat indices, the client defaults, the loading screens and the cutscenes - and all of them together
 * are one entry point so that [SourceCodecs] gains one line and nothing else.
 *
 * | index | dir | codec | shape |
 * |---|---|---|---|
 * | 0 | `anims` | [AnimationFrameCodec] | `anims/<archive>.glb`, a glTF animation per frame set, three chunk group |
 * | 1 | `bases` | `json-base` | `bases/<archive>.json` |
 * | 10 | `binary` | `json-huffman` | `binary/huffman.json` |
 * | 23 | `worldmapdata` | [WorldMapCodec] | `details/`, `<area>_staticelements/`, `<area>/` |
 * | 24 | `quickchat` | `quickchat` | `quickchatoption/`, `quickchatphrase/` |
 * | 25 | `quickchat_global` | `quickchat` | the same two |
 * | 28 | `defaults` | `defaults` | `defaults/<name>.json`, one per archive |
 * | 33 | `loading_screens` | `json-gametip` | `loading_screens/<archive>.json` |
 * | 35 | `cutscenes` | `json-cutscene` | `cutscenes/<archive>.json` |
 *
 * Three shapes cover all of it. An index of one type whose archives hold a single file is a
 * [DefinitionJsonCodec] over [DefinitionLayout.Packed] with no bits, which names the file after the
 * archive and makes the definition id the archive id. An index whose archives are unrelated things is an
 * [ArchiveDirectoryCodec] with a name and a codec per archive, as index 2 has always been. Index 0 is the
 * only one that needs a codec of its own, for two reasons: a frame set is an animation, which is a thing
 * glTF says better than JSON does ([AnimationGltf]), and its group's chunk layout has to come back.
 */
object MiscCodecs {

    /** Claim all eight indices. Called by [SourceCodecs.reset]. */
    fun register() {
        // Index 0 is a glTF animation per archive, and its three chunk groups are the one layout no
        // shared codec reproduces.
        SourceCodecs.builtin(Index.ANIMATION_FRAMES, AnimationFrameCodec)
        SourceCodecs.builtin(Index.ANIMATION_SKELETONS, bases)
        SourceCodecs.builtin(Index.HUFFMAN, huffman)
        SourceCodecs.builtin(Index.WORLD_MAP, WorldMapCodec)
        // The two quick chat indices are the same two archives; index 25's ids carry 0x8000 at runtime.
        SourceCodecs.builtin(Index.QUICK_CHAT_MESSAGES, quickChat)
        SourceCodecs.builtin(Index.QUICK_CHAT_MENUS, quickChat)
        SourceCodecs.builtin(Index.DEFAULTS, defaults)
        SourceCodecs.builtin(Index.GAME_TIPS, gameTips)
        SourceCodecs.builtin(Index.CUTSCENES, cutscenes)
    }

    /** Index 1: one skeleton per archive, `bases/<archive>.json`. */
    private val bases = single("json-base", AnimationBaseDecoder(), AnimationBaseEncoder()) {
        AnimationBaseDefinition(it)
    }

    /** Index 33: one loading screen per archive, and the stage table in archive 0. */
    private val gameTips = single("json-gametip", GameTipDecoder(), GameTipEncoder()) { GameTipDefinition(it) }

    /** Index 35: one cutscene per archive. */
    private val cutscenes = single("json-cutscene", CutsceneDecoder(), CutsceneEncoder()) { CutsceneDefinition(it) }

    /**
     * Index 10: the one archive the client asks for by name, so the file is called what it is.
     *
     * `huffman` is a certain name - `docs/cache-archive-names.md`, "Index 10" - and the reference table's
     * hash is kept in `index.json` like every other index's, so the name is a display concern here too.
     */
    private val huffman = ArchiveDirectoryCodec(
        id = "json-huffman",
        name = { HUFFMAN_NAME },
        archive = { name -> if (name == HUFFMAN_NAME) HUFFMAN_ARCHIVE else -1 },
        codec = { huffmanTable },
        nested = false
    )

    private val huffmanTable =
        named(HUFFMAN_NAME, "json-huffman", HuffmanTableDecoder(), HuffmanTableEncoder()) { HuffmanTableDefinition(it) }

    /**
     * Indices 24 and 25: archive 0 is the menus and archive 1 the phrases, in both.
     *
     * The directories are the gameval type names, so a file's path and its catalog entry line up:
     * `quickchat/quickchatoption/5.json` is `quickchatoption.json`'s entry 5.
     */
    private val quickChat = ArchiveDirectoryCodec(
        id = "quickchat",
        name = { archive -> QUICK_CHAT_NAMES[archive] ?: archive.toString() },
        archive = { name -> QUICK_CHAT_ARCHIVES[name] ?: name.toIntOrNull() ?: -1 },
        codec = { archive ->
            when (archive) {
                QUICK_CHAT_OPTIONS -> quickChatOptions
                QUICK_CHAT_PHRASES -> quickChatPhrases
                else -> RawFilesCodec.FLAT
            }
        }
    )

    private val quickChatOptions = flat("json-quickchatoption", QuickChatOptionDecoder(), QuickChatOptionEncoder()) {
        QuickChatOptionDefinition(it)
    }

    private val quickChatPhrases = flat("json-quickchatphrase", QuickChatPhraseDecoder(), QuickChatPhraseEncoder()) {
        QuickChatPhraseDefinition(it)
    }

    /**
     * Index 28: six archives of six unrelated settings, named by the client's own `DefaultsFile`.
     *
     * Each holds one file, so the archive's name is the file's name and there is no directory in between.
     * Equipment is [BodyDefinition], which the server already had; the other five share
     * [DefaultsDefinition], whose fields are nullable so an archive's file carries only its own.
     */
    private val defaults = ArchiveDirectoryCodec(
        id = "defaults",
        name = { archive -> DEFAULTS_NAMES[archive] ?: archive.toString() },
        archive = { name -> DEFAULTS_ARCHIVES[name] ?: name.toIntOrNull() ?: -1 },
        codec = { archive -> defaultsCodecs[archive] ?: RawArchiveCodec },
        nested = false
    )

    /** One codec per archive, built once: index 28 has six and they never change. */
    private val defaultsCodecs: Map<Int, SourceCodec> by lazy {
        val decoder = DefaultsDecoder()
        val encoder = DefaultsEncoder()
        DEFAULTS_NAMES.entries.associate { (archive, name) ->
            archive to if (archive == DefaultsDefinition.EQUIPMENT) {
                named(name, "json-equipment", BodyDecoder(), BodyEncoder()) { BodyDefinition(it) }
            } else {
                named(name, "json-defaults", decoder, encoder) { DefaultsDefinition(it) }
            }
        }
    }

    /** One JSON file per archive, named by the archive id: `bases/2405.json`. */
    private fun <T : Definition> single(
        id: String,
        decoder: DefinitionDecoder<T>,
        encoder: DefinitionEncoder<T>,
        create: (Int) -> T
    ): DefinitionJsonCodec<T> = codec(id, DefinitionLayout.Packed(0), decoder, encoder, create)

    /** One JSON file per file, in a directory that is the archive: `quickchat/quickchatphrase/5.json`. */
    private fun <T : Definition> flat(
        id: String,
        decoder: DefinitionDecoder<T>,
        encoder: DefinitionEncoder<T>,
        create: (Int) -> T
    ): DefinitionJsonCodec<T> = codec(id, DefinitionLayout.Flat, decoder, encoder, create)

    /** The archive's one file, named after the archive: `defaults/equipment.json`. */
    private fun <T : Definition> named(
        name: String,
        id: String,
        decoder: DefinitionDecoder<T>,
        encoder: DefinitionEncoder<T>,
        create: (Int) -> T
    ): DefinitionJsonCodec<T> = codec(id, NamedLayout(name), decoder, encoder, create)

    /**
     * A definition codec over any layout, decoding through the decoder's read path alone.
     *
     * `readLoop` and not `load`, for the reason `DefinitionJsonCodec` gives: `changeValues` derives values
     * the file does not hold - a world map area's bounds, a quick chat id's 0x8000 - and a definition
     * re-encoded after it no longer matches its file.
     */
    private fun <T : Definition> codec(
        id: String,
        layout: DefinitionLayout,
        decoder: DefinitionDecoder<T>,
        encoder: DefinitionEncoder<T>,
        create: (Int) -> T
    ): DefinitionJsonCodec<T> = DefinitionJsonCodec(
        id = id,
        layout = layout,
        create = create,
        decode = { definition, bytes -> decoder.readLoop(definition, BufferReader(bytes)) },
        encode = { definition, writer -> with(encoder) { writer.encode(definition) } }
    )

    /** The archive of index 10 the client asks for, and what it calls it. */
    private const val HUFFMAN_ARCHIVE = 1
    private const val HUFFMAN_NAME = "huffman"

    private const val QUICK_CHAT_OPTIONS = 0
    private const val QUICK_CHAT_PHRASES = 1

    private val QUICK_CHAT_NAMES = mapOf(
        QUICK_CHAT_OPTIONS to "quickchatoption",
        QUICK_CHAT_PHRASES to "quickchatphrase"
    )

    private val QUICK_CHAT_ARCHIVES = QUICK_CHAT_NAMES.entries.associate { (archive, name) -> name to archive }

    /** Index 28's archives, named as the client's `DefaultsFile` names them. */
    private val DEFAULTS_NAMES = mapOf(
        DefaultsDefinition.SCENE_CUBE_TEXTURES to "scene_cube_textures",
        DefaultsDefinition.ENTITY to "entity",
        DefaultsDefinition.TITLE_SCREEN_MUSIC to "titlescreen_music_info",
        DefaultsDefinition.EQUIPMENT to "equipment",
        DefaultsDefinition.INTERACTIONS to "interactions",
        DefaultsDefinition.CUTSCENE to "cutscene"
    )

    private val DEFAULTS_ARCHIVES = DEFAULTS_NAMES.entries.associate { (archive, name) -> name to archive }
}
