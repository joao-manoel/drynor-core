package world.gregs.voidps.cache.source.codec

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.Config
import world.gregs.voidps.cache.Definition
import world.gregs.voidps.cache.DefinitionDecoder
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.OpcodeEncoder
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
import world.gregs.voidps.cache.config.encoder.ClientVariableParameterEncoder
import world.gregs.voidps.cache.config.encoder.HitSplatEncoder
import world.gregs.voidps.cache.config.encoder.IdentityKitEncoder
import world.gregs.voidps.cache.config.encoder.InventoryEncoder
import world.gregs.voidps.cache.config.encoder.MapSceneEncoder
import world.gregs.voidps.cache.config.encoder.OverlayEncoder
import world.gregs.voidps.cache.config.encoder.PlayerVariableParameterEncoder
import world.gregs.voidps.cache.config.encoder.QuestEncoder
import world.gregs.voidps.cache.config.encoder.UnderlayEncoder
import world.gregs.voidps.cache.config.encoder.WorldMapInfoEncoder
import world.gregs.voidps.cache.config.data.ClientVariableParameterDefinition
import world.gregs.voidps.cache.config.data.HitSplatDefinition
import world.gregs.voidps.cache.config.data.IdentityKitDefinition
import world.gregs.voidps.cache.config.data.InventoryDefinition
import world.gregs.voidps.cache.config.data.MapSceneDefinition
import world.gregs.voidps.cache.config.data.OverlayDefinition
import world.gregs.voidps.cache.config.data.PlayerVariableParameterDefinition
import world.gregs.voidps.cache.config.data.QuestDefinition
import world.gregs.voidps.cache.config.data.UnderlayDefinition
import world.gregs.voidps.cache.config.data.WorldMapInfoDefinition
import world.gregs.voidps.cache.definition.OpcodeOrdered
import world.gregs.voidps.cache.definition.data.AnimationDefinition
import world.gregs.voidps.cache.definition.data.BASDefinition
import world.gregs.voidps.cache.definition.data.ClanVarDefinition
import world.gregs.voidps.cache.definition.data.EnumDefinition
import world.gregs.voidps.cache.definition.data.SpotAnimDefinition
import world.gregs.voidps.cache.definition.data.InterfaceComponentDefinition
import world.gregs.voidps.cache.definition.data.ItemDefinition
import world.gregs.voidps.cache.definition.data.NPCDefinition
import world.gregs.voidps.cache.definition.data.ObjectDefinition
import world.gregs.voidps.cache.definition.data.ParamDefinition
import world.gregs.voidps.cache.definition.data.StructDefinition
import world.gregs.voidps.cache.definition.data.VarBitDefinition
import world.gregs.voidps.cache.definition.decoder.AnimationDecoder
import world.gregs.voidps.cache.definition.decoder.BASDecoder
import world.gregs.voidps.cache.definition.decoder.ClanVarDecoder
import world.gregs.voidps.cache.definition.decoder.EnumDecoder
import world.gregs.voidps.cache.definition.decoder.SpotAnimDecoder
import world.gregs.voidps.cache.definition.decoder.InterfaceDecoder
import world.gregs.voidps.cache.definition.decoder.ItemDecoder
import world.gregs.voidps.cache.definition.decoder.NPCDecoder
import world.gregs.voidps.cache.definition.decoder.ObjectDecoder
import world.gregs.voidps.cache.definition.decoder.ParamDecoder
import world.gregs.voidps.cache.definition.decoder.StructDecoder
import world.gregs.voidps.cache.definition.decoder.VarBitDecoder
import world.gregs.voidps.cache.definition.encoder.AnimationEncoder
import world.gregs.voidps.cache.definition.encoder.BASEncoder
import world.gregs.voidps.cache.definition.encoder.ClanVarEncoder
import world.gregs.voidps.cache.definition.encoder.EnumEncoder
import world.gregs.voidps.cache.definition.encoder.SpotAnimEncoder
import world.gregs.voidps.cache.definition.encoder.InterfaceEncoder
import world.gregs.voidps.cache.definition.encoder.ItemEncoder
import world.gregs.voidps.cache.definition.encoder.NPCEncoder
import world.gregs.voidps.cache.definition.encoder.ObjectEncoder
import world.gregs.voidps.cache.definition.encoder.ParamEncoder
import world.gregs.voidps.cache.definition.encoder.StructEncoder
import world.gregs.voidps.cache.definition.encoder.VarBitEncoder
import world.gregs.voidps.cache.source.IndexNames

/**
 * Every definition type the source tree stores as editable JSON.
 *
 * Twenty-two types reach the bar a [DefinitionJsonCodec] needs - their encoder reproduces every one of the
 * 727 cache's files byte for byte, measured by the three parity tests `docs/cache-source.md` names - and
 * this is the one list of them. [register] hands the whole-index types to [SourceCodecs] and the index 2
 * types to [ConfigCodec]'s per-archive registry, which is what makes them the default for every user of a
 * tree; [types] is the same list for a report that wants to count files and pristine copies per type.
 *
 * Nothing here is per-tree or per-thread: a decoder and an encoder of an opcode keyed type hold no state
 * between files, so one of each is shared by every archive being unpacked or packed at once. Every decoder
 * that takes a parameter catalogue is left with its default
 * [world.gregs.voidps.cache.definition.Parameters.EMPTY], because a named parameter cannot be turned back
 * into the three byte key an encoder has to write.
 */
object DefinitionCodecs {

    /** One type, and where in the cache it lives. */
    class Type(
        /** The index the type belongs to. */
        val index: Int,
        /** The index 2 archive it is, or -1 for a type that is a whole index. */
        val archive: Int,
        /** The gameval name of the type - `obj`, `struct`, `interface`. */
        val name: String,
        /** The type's directory relative to the tree, `config_obj` or `config/struct`. */
        val directory: String,
        val codec: DefinitionJsonCodec<*>
    )

    /** Every type, indices ascending and index 2's archives with them. */
    val types: List<Type> = buildList {
        add(index(Index.OBJECTS, "loc", 8, ObjectDecoder(), ObjectEncoder()) { ObjectDefinition(it) })
        add(index(Index.ENUMS, "enum", 8, EnumDecoder(), EnumEncoder()) { EnumDefinition(it) })
        add(index(Index.NPCS, "npc", 7, NPCDecoder(), NPCEncoder()) { NPCDefinition(it) })
        add(index(Index.ITEMS, "obj", 8, ItemDecoder(), ItemEncoder()) { ItemDefinition(it) })
        add(index(Index.ANIMATIONS, "seq", 7, AnimationDecoder(), AnimationEncoder()) { AnimationDefinition(it) })
        add(index(Index.SPOTANIMS, "spotanim", 8, SpotAnimDecoder(), SpotAnimEncoder()) { SpotAnimDefinition(it) })
        add(index(Index.VAR_BIT, "varbit", 10, VarBitDecoder(), VarBitEncoder()) { VarBitDefinition(it) })
        add(interfaces())
        add(config(Config.FLOOR_UNDERLAY, "underlay", UnderlayDecoder(), UnderlayEncoder()) { UnderlayDefinition(it) })
        add(config(Config.IDENTITY_KIT, "idk", IdentityKitDecoder(), IdentityKitEncoder()) { IdentityKitDefinition(it) })
        add(config(Config.FLOOR_OVERLAY, "overlay", OverlayDecoder(), OverlayEncoder()) { OverlayDefinition(it) })
        add(config(Config.INVENTORIES, "inv", InventoryDecoder(), InventoryEncoder()) { InventoryDefinition(it) })
        add(config(Config.PARAMS, "param", ParamDecoder(), ParamEncoder()) { ParamDefinition(it) })
        add(
            config(Config.VARP, "varp", PlayerVariableParameterDecoder(), PlayerVariableParameterEncoder()) {
                PlayerVariableParameterDefinition(it)
            }
        )
        add(
            config(Config.VARC, "varc", ClientVariableParameterDecoder(), ClientVariableParameterEncoder()) {
                ClientVariableParameterDefinition(it)
            }
        )
        add(config(Config.STRUCTS, "struct", StructDecoder(), StructEncoder()) { StructDefinition(it) })
        add(config(Config.RENDER_ANIMATIONS, "bas", BASDecoder(), BASEncoder()) { BASDefinition(it) })
        add(config(Config.MAP_SCENES, "mapscene", MapSceneDecoder(), MapSceneEncoder()) { MapSceneDefinition(it) })
        add(config(Config.QUESTS, "quest", QuestDecoder(), QuestEncoder()) { QuestDefinition(it) })
        add(
            config(Config.WORLD_MAP_INFO, "mapelement", WorldMapInfoDecoder(), WorldMapInfoEncoder()) {
                WorldMapInfoDefinition(it)
            }
        )
        add(config(Config.HIT_SPLATS, "hitmark", HitSplatDecoder(), HitSplatEncoder()) { HitSplatDefinition(it) })
        add(config(Config.VAR_CLAN, "varclan", ClanVarDecoder(), ClanVarEncoder()) { ClanVarDefinition(it) })
    }

    /** Claim every type's index, and every index 2 type's archive. Called by [SourceCodecs.reset]. */
    fun register() {
        for (type in types) {
            if (type.archive < 0) {
                SourceCodecs.builtin(type.index, type.codec)
            } else {
                ConfigCodec.register(type.archive, type.codec)
            }
        }
    }

    /** A whole index of one opcode keyed type, one flat file per definition id. */
    private fun <T> index(
        index: Int,
        name: String,
        bits: Int,
        decoder: DefinitionDecoder<T>,
        encoder: OpcodeEncoder<T>,
        create: (Int) -> T
    ): Type where T : Definition, T : OpcodeOrdered = Type(
        index = index,
        archive = -1,
        name = name,
        directory = IndexNames.name(index),
        codec = ordered("json-$name", DefinitionLayout.Packed(bits), decoder, encoder, create)
    )

    /** One index 2 archive, a file per file inside the archive's own directory. */
    private fun <T> config(
        archive: Int,
        name: String,
        decoder: DefinitionDecoder<T>,
        encoder: OpcodeEncoder<T>,
        create: (Int) -> T
    ): Type where T : Definition, T : OpcodeOrdered = Type(
        index = Index.CONFIGS,
        archive = archive,
        name = name,
        directory = "${IndexNames.name(Index.CONFIGS)}/${ConfigCodec.name(archive)}",
        codec = ordered("json-$name", DefinitionLayout.Flat, decoder, encoder, create)
    )

    /**
     * Index 3, whose components are a fixed field layout rather than an opcode list, so it has neither
     * [OpcodeOrdered] nor an [OpcodeEncoder] and its decode is the component's own `read`.
     */
    private fun interfaces(): Type {
        val decoder = InterfaceDecoder()
        val encoder = InterfaceEncoder()
        return Type(
            index = Index.INTERFACES,
            archive = -1,
            name = "interface",
            directory = IndexNames.name(Index.INTERFACES),
            codec = DefinitionJsonCodec(
                id = "json-interface",
                layout = DefinitionLayout.Nested(COMPONENT_BITS),
                create = { InterfaceComponentDefinition(id = it) },
                decode = { definition, bytes -> with(decoder) { definition.read(BufferReader(bytes)) } },
                encode = { definition, writer -> with(encoder) { writer.encode(definition) } }
            )
        )
    }

    /**
     * An opcode keyed type: decoded with `readLoop`, which is the only read path that records the file's
     * opcode order and its overwritten payloads, and encoded by replaying them.
     */
    private fun <T> ordered(
        id: String,
        layout: DefinitionLayout,
        decoder: DefinitionDecoder<T>,
        encoder: OpcodeEncoder<T>,
        create: (Int) -> T
    ): DefinitionJsonCodec<T> where T : Definition, T : OpcodeOrdered = DefinitionJsonCodec(
        id = id,
        layout = layout,
        create = create,
        decode = { definition, bytes -> decoder.readLoop(definition, BufferReader(bytes)) },
        encode = { definition, writer -> with(encoder) { writer.encode(definition) } }
    )

    /** An interface component id is the low 16 bits of its definition id. */
    private const val COMPONENT_BITS = 16
}
