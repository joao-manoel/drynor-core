package world.gregs.voidps.cache.source.codec

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import world.gregs.voidps.cache.MapSquare
import world.gregs.voidps.cache.definition.data.MapDefinition
import world.gregs.voidps.cache.definition.data.MapEffect
import world.gregs.voidps.cache.definition.data.MapEnvironmentEffect
import world.gregs.voidps.cache.definition.data.MapHdrEffect
import world.gregs.voidps.cache.definition.data.MapLight
import world.gregs.voidps.cache.definition.data.MapLightGridEffect
import world.gregs.voidps.cache.definition.data.MapLightGridLevel
import world.gregs.voidps.cache.definition.data.MapLightsEffect
import world.gregs.voidps.cache.definition.data.MapNpcSpawn
import world.gregs.voidps.cache.definition.data.MapObject
import world.gregs.voidps.cache.definition.data.MapSkyboxEffect
import world.gregs.voidps.cache.definition.data.MapTile
import world.gregs.voidps.cache.source.SourceFiles
import world.gregs.voidps.cache.source.SourceJson

/**
 * One map square archive as an editable JSON file, and back.
 *
 * The three shapes a map square is stored in - terrain, locations, npc spawns - each get their own
 * file per region ([MapCodec]); this is the spelling of all three. Writing is deterministic to the
 * byte so re-unpacking an unchanged cache leaves the tree alone; reading is tolerant of key order,
 * whitespace and unknown keys, and a missing key reads as its default.
 *
 * ## Terrain
 *
 * A 64 x 64 x 4 grid of tiles cannot be an object per tile - that is 16,384 objects per region and
 * a gigabyte of tree - so a tile is one **token group** and a row of 64 of them is one line:
 *
 * ```json
 * {
 *   "region": "50_50",
 *   "levels": 4,
 *   "tiles": [
 *     [
 *       "o12/0/1,s2,u36,h29 u36,h29 - u36 ...",
 *       ...
 *     ]
 *   ],
 *   "effects": []
 * }
 * ```
 *
 * `tiles` has one array per level, each with 64 rows of one localX, and each row has 64 tiles of
 * ascending localY separated by a space - the order the file itself stores them in, so reading down
 * a file is reading the bytes in order. One tile's records are separated by `,`:
 *
 * | token | record | opcode |
 * |---|---|---|
 * | `o<id>/<shape>/<rotation>` | overlay | `2 + shape * 4 + rotation`, then the id byte |
 * | `s<settings>` | tile settings | `49 + settings` |
 * | `u<underlay>` | underlay | `81 + underlay` |
 * | `h<height>` | explicit height | `1`, then the height byte |
 * | `-` | a tile with no records at all | just the `0` terminator |
 *
 * **The token order is the record order**, which is what makes the file byte exact: the tokens are
 * a transcription of the tile's records and not a set of fields somebody has to re-derive an order
 * for. Every tile in the 727 cache lists its records in the order above, so that is also the order
 * an editor should write; `h` is last because the height record is what ends the tile.
 *
 * A height of `1` is how the cache spells "deliberately flat" - the client folds an explicit `1` to
 * `0` - and no `h` token at all means "let the client derive the height".
 *
 * ## Locations and npc spawns
 *
 * One object per line, in file order:
 *
 * ```json
 * {
 *   "region": "50_50",
 *   "locs": [
 *     {"id": 1276, "x": 12, "y": 40, "level": 0, "shape": 10, "rotation": 2}
 *   ]
 * }
 * ```
 *
 * Order matters here too: consecutive objects sharing an id are one group in the file, group ids
 * ascend and positions within a group never go backwards. `MapObjectEncoder` writes a list that
 * satisfies that verbatim and regroups one that does not, so an editor can append and still get a
 * valid file - it just will not be the same bytes any more.
 *
 * A `"padding"` count above `locs` is the zero bytes 48 archives end with past their last record
 * (`"trailing"` with the hex when they are not zeroes, which nothing in 727 is) - see
 * [MapDefinition.trailing]. Npc spawns are the same file with `npcs` and no shape or rotation.
 */
internal object MapJson {

    /** [definition]'s terrain archive as the exact text of its file, trailing newline included. */
    fun writeTerrain(definition: MapDefinition, square: MapSquare.Square): ByteArray {
        val out = StringBuilder(1 shl 16)
        out.append("{\n")
        out.append("  \"region\": ").append(SourceJson.quote(square.directory)).append(",\n")
        out.append("  \"levels\": ").append(definition.levels).append(",\n")
        out.append("  \"tiles\": [")
        for (level in 0 until definition.levels) {
            out.append(if (level == 0) "\n" else ",\n")
            out.append("    [\n")
            for (localX in 0 until MapDefinition.SIZE) {
                out.append("      \"")
                for (localY in 0 until MapDefinition.SIZE) {
                    if (localY != 0) {
                        out.append(' ')
                    }
                    tile(out, definition.getTile(localX, localY, level))
                }
                out.append('"')
                out.append(if (localX == MapDefinition.SIZE - 1) "\n" else ",\n")
            }
            out.append("    ]")
        }
        if (definition.levels > 0) {
            out.append('\n').append("  ")
        }
        out.append("],\n")
        effects(out, definition.effects)
        out.append("}\n")
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    /** [bytes] read back into the definition of [square]. */
    fun readTerrain(bytes: ByteArray, square: MapSquare.Square): MapDefinition {
        val root = json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
        val definition = MapDefinition(
            id = square.region,
            levels = root["levels"]?.jsonPrimitive?.int ?: square.shape.levels
        )
        val levels = root["tiles"]?.jsonArray ?: JsonArray(emptyList())
        require(levels.size == definition.levels) {
            "${square.name}: \"levels\" says ${definition.levels} but \"tiles\" holds ${levels.size}."
        }
        for ((level, rows) in levels.withIndex()) {
            val lines = rows.jsonArray
            require(lines.size == MapDefinition.SIZE) {
                "${square.name} level $level has ${lines.size} rows, not ${MapDefinition.SIZE}."
            }
            for ((localX, row) in lines.withIndex()) {
                readRow(definition, square, level, localX, row.jsonPrimitive.content)
            }
        }
        root["effects"]?.let { definition.effects.addAll(readEffects(it.jsonArray, square)) }
        return definition
    }

    /** [definition]'s locations archive as the exact text of its file. */
    fun writeLocs(definition: MapDefinition, square: MapSquare.Square): ByteArray {
        val objects = definition.objects
        val out = StringBuilder(objects.size * 72 + 64)
        out.append("{\n")
        out.append("  \"region\": ").append(SourceJson.quote(square.directory)).append(",\n")
        trailing(out, definition.trailing)
        out.append("  \"locs\": [")
        for ((position, loc) in objects.withIndex()) {
            out.append(if (position == 0) "\n" else ",\n")
            out.append("    {\"id\": ").append(loc.id)
            out.append(", \"x\": ").append(loc.x)
            out.append(", \"y\": ").append(loc.y)
            out.append(", \"level\": ").append(loc.level)
            out.append(", \"shape\": ").append(loc.shape)
            out.append(", \"rotation\": ").append(loc.rotation)
            out.append('}')
        }
        if (objects.isNotEmpty()) {
            out.append('\n').append("  ")
        }
        out.append("]\n}\n")
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    /** [bytes] read back into the definition of [square]. */
    fun readLocs(bytes: ByteArray, square: MapSquare.Square): MapDefinition {
        val root = json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
        val definition = MapDefinition(square.region, levels = 0, trailing = readTrailing(root, square))
        val locs = root["locs"]?.jsonArray ?: return definition
        for (element in locs) {
            val loc = element.jsonObject
            definition.objects.add(
                MapObject(
                    id = int(loc, "id", square),
                    x = int(loc, "x", square),
                    y = int(loc, "y", square),
                    level = loc["level"]?.jsonPrimitive?.int ?: 0,
                    shape = loc["shape"]?.jsonPrimitive?.int ?: 0,
                    rotation = loc["rotation"]?.jsonPrimitive?.int ?: 0
                )
            )
        }
        return definition
    }

    /**
     * The bytes a file ends with that no record accounts for, as a count when they are zeroes and
     * as hex when they are not - see [MapDefinition.trailing]. All 48 archives in the 727 cache
     * that have any are zero padded, so `trailing` never appears in the shipped tree.
     */
    private fun trailing(out: StringBuilder, trailing: ByteArray?) {
        if (trailing == null || trailing.isEmpty()) {
            return
        }
        if (trailing.all { it.toInt() == 0 }) {
            out.append("  \"padding\": ").append(trailing.size).append(",\n")
        } else {
            out.append("  \"trailing\": ").append(SourceJson.quote(SourceFiles.hex(trailing))).append(",\n")
        }
    }

    private fun readTrailing(root: JsonObject, square: MapSquare.Square): ByteArray? {
        root["padding"]?.let { return ByteArray(it.jsonPrimitive.int) }
        val hex = root["trailing"]?.jsonPrimitive?.content ?: return null
        require(hex.length % 2 == 0) { "${square.name}: \"trailing\" is not whole bytes of hex." }
        return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /** [spawns]'s npc archive as the exact text of its file. */
    fun writeNpcs(spawns: List<MapNpcSpawn>, square: MapSquare.Square): ByteArray {
        val out = StringBuilder(spawns.size * 56 + 64)
        out.append("{\n")
        out.append("  \"region\": ").append(SourceJson.quote(square.directory)).append(",\n")
        out.append("  \"npcs\": [")
        for ((position, spawn) in spawns.withIndex()) {
            out.append(if (position == 0) "\n" else ",\n")
            out.append("    {\"npc\": ").append(spawn.npc)
            out.append(", \"x\": ").append(spawn.x)
            out.append(", \"y\": ").append(spawn.y)
            out.append(", \"level\": ").append(spawn.level)
            out.append('}')
        }
        if (spawns.isNotEmpty()) {
            out.append('\n').append("  ")
        }
        out.append("]\n}\n")
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    /** [bytes] read back into the npc spawns of [square]. */
    fun readNpcs(bytes: ByteArray, square: MapSquare.Square): MutableList<MapNpcSpawn> {
        val root = json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
        val spawns = root["npcs"]?.jsonArray ?: return ArrayList(0)
        val out = ArrayList<MapNpcSpawn>(spawns.size)
        for (element in spawns) {
            val spawn = element.jsonObject
            out.add(
                MapNpcSpawn(
                    npc = int(spawn, "npc", square),
                    x = int(spawn, "x", square),
                    y = int(spawn, "y", square),
                    level = spawn["level"]?.jsonPrimitive?.int ?: 0
                )
            )
        }
        return out
    }

    /** One tile's records as tokens, or `-` when it has none. */
    private fun tile(out: StringBuilder, tile: MapTile) {
        val start = out.length
        if (tile.attrOpcode != 0 || tile.overlayId != 0 || tile.overlayPath != 0 || tile.overlayRotation != 0) {
            out.append(OVERLAY).append(tile.overlayId)
                .append('/').append(tile.overlayPath)
                .append('/').append(tile.overlayRotation)
        }
        if (tile.settings != 0) {
            separate(out, start)
            out.append(SETTINGS).append(tile.settings)
        }
        if (tile.underlayId != 0) {
            separate(out, start)
            out.append(UNDERLAY).append(tile.underlayId)
        }
        if (tile.height != 0) {
            separate(out, start)
            out.append(HEIGHT).append(tile.height)
        }
        if (out.length == start) {
            out.append(EMPTY)
        }
    }

    private fun separate(out: StringBuilder, start: Int) {
        if (out.length != start) {
            out.append(',')
        }
    }

    /** One row of 64 tiles, `localY` ascending. */
    private fun readRow(
        definition: MapDefinition,
        square: MapSquare.Square,
        level: Int,
        localX: Int,
        row: String
    ) {
        var localY = 0
        var start = 0
        while (start <= row.length) {
            var end = row.indexOf(' ', start)
            if (end < 0) {
                end = row.length
            }
            require(localY < MapDefinition.SIZE) {
                "${square.name} level $level row $localX has more than ${MapDefinition.SIZE} tiles."
            }
            definition.tiles[MapDefinition.index(localX, localY, level)] =
                readTile(row, start, end, square, level, localX, localY)
            localY++
            start = end + 1
        }
        require(localY == MapDefinition.SIZE) {
            "${square.name} level $level row $localX has $localY tiles, not ${MapDefinition.SIZE}."
        }
    }

    /** One tile's tokens, `[start, end)` of [row]. */
    private fun readTile(
        row: String,
        start: Int,
        end: Int,
        square: MapSquare.Square,
        level: Int,
        localX: Int,
        localY: Int
    ): Long {
        var height = 0
        var opcode = 0
        var overlay = 0
        var path = 0
        var rotation = 0
        var settings = 0
        var underlay = 0
        var position = start
        while (position < end) {
            var next = row.indexOf(',', position)
            if (next < 0 || next > end) {
                next = end
            }
            val token = row.substring(position, next)
            position = next + 1
            if (token == EMPTY) {
                continue
            }
            require(token.isNotEmpty()) { where(square, level, localX, localY, "has an empty token") }
            val value = token.substring(1)
            when (token[0]) {
                OVERLAY -> {
                    val parts = value.split('/')
                    require(parts.size == 3) {
                        where(square, level, localX, localY, "overlay token '$token' is not id/shape/rotation")
                    }
                    overlay = number(parts[0], token, square, level, localX, localY)
                    path = number(parts[1], token, square, level, localX, localY)
                    rotation = number(parts[2], token, square, level, localX, localY)
                    opcode = 2 + (path * 4) + rotation
                }
                SETTINGS -> settings = number(value, token, square, level, localX, localY)
                UNDERLAY -> underlay = number(value, token, square, level, localX, localY)
                HEIGHT -> height = number(value, token, square, level, localX, localY)
                else -> error(where(square, level, localX, localY, "has an unknown token '$token'"))
            }
        }
        return MapTile.pack(height, opcode, overlay, path, rotation, settings, underlay)
    }

    private fun number(
        value: String,
        token: String,
        square: MapSquare.Square,
        level: Int,
        localX: Int,
        localY: Int
    ): Int = value.toIntOrNull()
        ?: error(where(square, level, localX, localY, "token '$token' is not a number"))

    private fun where(square: MapSquare.Square, level: Int, localX: Int, localY: Int, message: String) =
        "${square.name} tile ($localX, $localY, $level) $message."

    private fun int(element: JsonObject, key: String, square: MapSquare.Square): Int =
        element[key]?.jsonPrimitive?.int ?: error("${square.name}: a record has no \"$key\".")

    /** The environment records, one per line where they fit and expanded where they do not. */
    private fun effects(out: StringBuilder, effects: List<MapEffect>) {
        out.append("  \"effects\": [")
        for ((position, effect) in effects.withIndex()) {
            out.append(if (position == 0) "\n" else ",\n")
            when (effect) {
                is MapEnvironmentEffect -> environment(out, effect)
                is MapLightsEffect -> lights(out, effect)
                is MapHdrEffect -> {
                    out.append("    {\"type\": \"hdr\", \"bloom\": ").append(effect.bloom)
                    out.append(", \"brightpass\": ").append(effect.brightpass)
                    out.append(", \"whitePoint\": ").append(effect.whitePoint).append('}')
                }
                is MapSkyboxEffect -> {
                    out.append("    {\"type\": \"skybox\", \"id\": ").append(effect.id)
                    out.append(", \"x\": ").append(effect.x)
                    out.append(", \"y\": ").append(effect.y)
                    out.append(", \"z\": ").append(effect.z)
                    out.append(", \"rotation\": ").append(effect.rotation).append('}')
                }
                is MapLightGridEffect -> lightGrid(out, effect)
            }
        }
        if (effects.isNotEmpty()) {
            out.append('\n').append("  ")
        }
        out.append("]\n")
    }

    private fun environment(out: StringBuilder, effect: MapEnvironmentEffect) {
        out.append("    {\n      \"type\": \"environment\"")
        effect.sunColour?.let { out.append(",\n      \"sunColour\": ").append(it) }
        effect.sunAmbient?.let { out.append(",\n      \"sunAmbient\": ").append(it) }
        effect.sunLight?.let { out.append(",\n      \"sunLight\": ").append(it) }
        effect.sunBacklight?.let { out.append(",\n      \"sunBacklight\": ").append(it) }
        effect.sunPosition?.let { out.append(",\n      \"sunPosition\": ").append(SourceJson.array(it)) }
        effect.fogColour?.let { out.append(",\n      \"fogColour\": ").append(it) }
        effect.fogDepth?.let { out.append(",\n      \"fogDepth\": ").append(it) }
        effect.cubeTexture?.let { out.append(",\n      \"cubeTexture\": ").append(SourceJson.array(it)) }
        out.append("\n    }")
    }

    private fun lights(out: StringBuilder, effect: MapLightsEffect) {
        out.append("    {\n      \"type\": \"lights\",\n      \"lights\": [")
        for ((position, light) in effect.lights.withIndex()) {
            out.append(if (position == 0) "\n" else ",\n")
            out.append("        {\"packedLevel\": ").append(light.packedLevel)
            out.append(", \"x\": ").append(light.x)
            out.append(", \"z\": ").append(light.z)
            out.append(", \"heightOffset\": ").append(light.heightOffset)
            out.append(", \"packedRadius\": ").append(light.packedRadius)
            out.append(", \"ranges\": ").append(SourceJson.array(light.ranges))
            out.append(", \"colour\": ").append(light.colour)
            out.append(", \"packedType\": ").append(light.packedType)
            if (light.lightType >= 0) {
                out.append(", \"lightType\": ").append(light.lightType)
            }
            out.append('}')
        }
        if (effect.lights.isNotEmpty()) {
            out.append('\n').append("      ")
        }
        out.append("]\n    }")
    }

    private fun lightGrid(out: StringBuilder, effect: MapLightGridEffect) {
        out.append("    {\n      \"type\": \"lightGrid\",\n      \"levels\": [")
        for ((position, level) in effect.levels.withIndex()) {
            out.append(if (position == 0) "\n" else ",\n")
            val samples = level.samples
            if (samples == null) {
                out.append("        {\"mode\": ").append(level.mode).append('}')
                continue
            }
            out.append("        {\n          \"mode\": ").append(level.mode)
            out.append(",\n          \"samples\": [")
            val perRow = MapDefinition.SIZE / MapLightGridLevel.BLOCK
            for (row in 0 until samples.size / perRow) {
                out.append(if (row == 0) "\n" else ",\n")
                out.append("            [")
                for (column in 0 until perRow) {
                    if (column != 0) {
                        out.append(", ")
                    }
                    out.append(samples[row * perRow + column].toInt())
                }
                out.append(']')
            }
            out.append("\n          ]\n        }")
        }
        out.append("\n      ]\n    }")
    }

    private fun readEffects(effects: JsonArray, square: MapSquare.Square): List<MapEffect> {
        val out = ArrayList<MapEffect>(effects.size)
        for (element in effects) {
            val effect = element.jsonObject
            val type = effect["type"]?.jsonPrimitive?.content
                ?: error("${square.name}: an effect record has no \"type\".")
            out.add(
                when (type) {
                    "environment" -> MapEnvironmentEffect(
                        sunColour = effect["sunColour"]?.jsonPrimitive?.int,
                        sunAmbient = effect["sunAmbient"]?.jsonPrimitive?.int,
                        sunLight = effect["sunLight"]?.jsonPrimitive?.int,
                        sunBacklight = effect["sunBacklight"]?.jsonPrimitive?.int,
                        sunPosition = effect["sunPosition"]?.let { numbers(it.jsonArray) },
                        fogColour = effect["fogColour"]?.jsonPrimitive?.int,
                        fogDepth = effect["fogDepth"]?.jsonPrimitive?.int,
                        cubeTexture = effect["cubeTexture"]?.let { numbers(it.jsonArray) }
                    )
                    "lights" -> MapLightsEffect(
                        (effect["lights"]?.jsonArray ?: JsonArray(emptyList())).map { light ->
                            readLight(light.jsonObject, square)
                        }
                    )
                    "hdr" -> MapHdrEffect(
                        bloom = int(effect, "bloom", square),
                        brightpass = int(effect, "brightpass", square),
                        whitePoint = int(effect, "whitePoint", square)
                    )
                    "skybox" -> MapSkyboxEffect(
                        id = int(effect, "id", square),
                        x = int(effect, "x", square),
                        y = int(effect, "y", square),
                        z = int(effect, "z", square),
                        rotation = int(effect, "rotation", square)
                    )
                    "lightGrid" -> MapLightGridEffect(
                        (effect["levels"]?.jsonArray ?: JsonArray(emptyList())).map { level ->
                            readLightGridLevel(level.jsonObject, square)
                        }
                    )
                    else -> error("${square.name}: unknown effect type '$type'.")
                }
            )
        }
        return out
    }

    private fun readLight(light: JsonObject, square: MapSquare.Square): MapLight = MapLight(
        packedLevel = int(light, "packedLevel", square),
        x = int(light, "x", square),
        z = int(light, "z", square),
        heightOffset = int(light, "heightOffset", square),
        packedRadius = int(light, "packedRadius", square),
        ranges = numbers(light["ranges"]?.jsonArray ?: JsonArray(emptyList())),
        colour = int(light, "colour", square),
        packedType = int(light, "packedType", square),
        lightType = light["lightType"]?.jsonPrimitive?.int ?: -1
    )

    private fun readLightGridLevel(level: JsonObject, square: MapSquare.Square): MapLightGridLevel {
        val mode = int(level, "mode", square)
        val rows = level["samples"]?.jsonArray ?: return MapLightGridLevel(mode, null)
        val samples = ByteArray(MapLightGridLevel.SAMPLE_COUNT)
        var position = 0
        for (row in rows) {
            for (sample in row.jsonArray) {
                require(position < samples.size) {
                    "${square.name}: a light grid level holds more than ${samples.size} samples."
                }
                samples[position++] = sample.jsonPrimitive.int.toByte()
            }
        }
        require(position == samples.size) {
            "${square.name}: a light grid level holds $position samples, not ${samples.size}."
        }
        return MapLightGridLevel(mode, samples)
    }

    private fun numbers(array: JsonArray): IntArray = IntArray(array.size) { array[it].jsonPrimitive.int }

    private val json = Json { ignoreUnknownKeys = true }

    /** A tile with no records at all. */
    private const val EMPTY = "-"

    private const val OVERLAY = 'o'
    private const val SETTINGS = 's'
    private const val UNDERLAY = 'u'
    private const val HEIGHT = 'h'
}
