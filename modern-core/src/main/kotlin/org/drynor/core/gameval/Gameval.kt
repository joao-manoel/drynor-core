package org.drynor.core.gameval

import org.drynor.core.EnvVars
import java.io.File

/**
 * Runtime id <-> Jagex dev-name lookup over the revision-727 `gamevals/<type>.json` catalogs, so
 * code can say `Gameval.requireId(Gameval.INV, "worn")` and logs/UIs can print names instead of
 * magic numbers. See `docs/gameval.md` for the catalogs, the placeholder rule and `GAMEVAL_PATH`.
 *
 * The catalogs live in the cache source tree (`<tree>/gamevals`, see `docs/cache-source.md`);
 * [EnvVars.gamevalPath] names that folder and there is no other copy.
 */
object Gameval : GamevalCatalog(File(EnvVars.gamevalPath)) {
    const val OBJ = "obj"
    const val NPC = "npc"
    const val LOC = "loc"
    const val SEQ = "seq"
    const val SPOTANIM = "spotanim"
    const val ENUM = "enum"
    const val STRUCT = "struct"
    const val PARAM = "param"
    const val INV = "inv"
    const val INTERFACE = "interface"
    const val COMPONENT = GamevalCatalog.COMPONENT
    const val CLIENTSCRIPT = GamevalCatalog.CLIENTSCRIPT
    const val VARP = "varp"
    const val VARBIT = "varbit"
    const val VARC = "varc"
    const val VARCSTR = "varcstr"
    const val VARCLAN = "varclan"
    const val VARBITCLAN = "varbitclan"
    const val VARCLANSETTING = "varclansetting"
    const val VARBITCLANSETTING = "varbitclansetting"
    const val BAS = "bas"
    const val HITMARK = "hitmark"
    const val HEADBAR = "headbar"
    const val CURSOR = "cursor"
    const val MAPELEMENT = "mapelement"
    const val QUEST = "quest"
    const val MODEL = "model"

    /** Jagex `type graphic` = 2D images; not spotanim. */
    const val GRAPHIC = "graphic"
    const val SOUND = "sound"
    const val MIDI = "midi"
    const val MATERIAL = "material"
    const val FONTMETRICS = "fontmetrics"
    const val IDK = "idk"
    const val OVERLAY = "overlay"
    const val UNDERLAY = "underlay"
    const val MAPSCENE = "mapscene"
    const val SKYBOX = "skybox"
    const val QUICKCHATOPTION = "quickchatoption"
    const val QUICKCHATPHRASE = "quickchatphrase"

    /** World map areas: the `details` archive file ids the `WORLDMAP_*` clientscript opcodes take. */
    const val WORLDMAP = "worldmap"

    /** Every catalog type name, in the order the contract lists them. */
    val TYPES: List<String> = listOf(
        OBJ, NPC, LOC, SEQ, SPOTANIM, ENUM, STRUCT, PARAM, INV, INTERFACE, COMPONENT, CLIENTSCRIPT,
        VARP, VARBIT, VARC, VARCSTR, VARCLAN, VARBITCLAN, VARCLANSETTING, VARBITCLANSETTING,
        BAS, HITMARK, HEADBAR, CURSOR, MAPELEMENT, QUEST, MODEL, GRAPHIC, SOUND, MIDI, MATERIAL, FONTMETRICS,
        IDK, OVERLAY, UNDERLAY, MAPSCENE, SKYBOX, QUICKCHATOPTION, QUICKCHATPHRASE, WORLDMAP,
    )

    /** The folder catalogs are read from before falling back to the bundled copy. */
    val folder: File = File(EnvVars.gamevalPath)
}
