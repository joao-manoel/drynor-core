package rs.drynor.core.persistence

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.darkan.core.model.Clan
import org.darkan.core.model.ClanSetting
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.definition.data.ClanVarDefinition

/** Converts the old numeric/packed storage and HTTP contract at the boundary only. */
class ClanJson(
    private val definition: (Int) -> ClanVarDefinition? = { Cache.clanVarSettings.getOrNull(it) },
    private val gson: Gson = Gson(),
) {
    // The old world exposed some permission ids that the modern client no longer transmits.
    private val oldIds = ClanJson::class.java.getResourceAsStream("/drynor/migrations/legacy-clan-setting-ids.json").use { stream -> requireNotNull(stream); JsonParser.parseReader(stream.reader()).asJsonObject.entrySet().associate { it.key to it.value.asInt } }
    private fun oldId(setting: ClanSetting) = if (setting.id >= 0) setting.id else oldIds[setting.name] ?: -1
    fun native(json: String): Clan {
        val objectValue = JsonParser.parseString(json).asJsonObject
        val clan = gson.fromJson(objectValue, Clan::class.java)
        clan.settings = values(objectValue.getAsJsonObject("settings"), true)
        clan.vars = values(objectValue.getAsJsonObject("vars"), false)
        (clan.settings[ClanSetting.MOTTO.name] as? String)?.let { clan.motto = it }
        return clan
    }

    fun legacy(json: String): Clan {
        val source = JsonParser.parseString(json).asJsonObject
        val native = source.deepCopy().apply {
            add("leaderUsername", remove("clanLeaderUsername"))
            remove("settings"); remove("vars"); remove("updateBlock")
        }
        val clan = gson.fromJson(native, Clan::class.java)
        val raw = values(source.getAsJsonObject("settings"), false)
        clan.settings = raw.mapKeys { "raw:${it.key}" }.toMutableMap()
        if (raw.isNotEmpty()) {
            for (setting in ClanSetting.entries.filter { oldId(it) >= 0 }) {
                val id = oldId(setting)
                val layout = definition(id) ?: continue
                val value = if (layout.baseVar >= 0) {
                    val bits = (raw[layout.baseVar.toString()] as? Number)?.toInt() ?: 0
                    (bits ushr layout.startBit) and mask(layout.endBit - layout.startBit + 1)
                } else raw[id.toString()] ?: zero(setting)
                clan.settings[setting.name] = normalize(setting, value)
            }
        }
        clan.vars = values(source.getAsJsonObject("vars"), false)
        (clan.settings[ClanSetting.MOTTO.name] as? String)?.let { clan.motto = it }
        return clan
    }

    fun legacy(clan: Clan): JsonObject {
        val result = gson.toJsonTree(clan).asJsonObject
        result.add("clanLeaderUsername", result.remove("leaderUsername"))
        val raw = clan.settings.filterKeys { it.startsWith("raw:") }.mapKeys { it.key.removePrefix("raw:") }.toMutableMap()
        for ((key, value) in clan.settings) {
            val setting = ClanSetting.forName(key) ?: continue
            val id = oldId(setting)
            if (id < 0) continue
            val layout = requireNotNull(definition(id)) { "Missing clan setting definition $id" }
            if (layout.baseVar >= 0) {
                val width = layout.endBit - layout.startBit + 1
                val shifted = mask(width) shl layout.startBit
                val old = (raw[layout.baseVar.toString()] as? Number)?.toInt() ?: 0
                raw[layout.baseVar.toString()] = (old and shifted.inv()) or (((value as Number).toInt() shl layout.startBit) and shifted)
            } else raw[id.toString()] = normalize(setting, value)
        }
        result.add("settings", gson.toJsonTree(raw))
        return result
    }

    /** Typed numbers for the old packet encoder; never round-trip 64-bit values through Double. */
    fun legacySettings(clan: Clan): Map<Int, Any> = values(legacy(clan).getAsJsonObject("settings"), false).map { (key, value) ->
        val id = key.toInt()
        val layout = definition(id)
        val typed = if (value is Number && (layout?.type?.longStackSize ?: 0) > 0) value.toLong() else value
        id to typed
    }.toMap()

    /** Only cache-defined ids can be sent to the client; unknown persisted fields stay in storage. */
    fun wireSettings(clan: Clan): Map<Int, Any> = legacySettings(clan).filterKeys { definition(it) != null }

    private fun values(objectValue: JsonObject?, settings: Boolean): MutableMap<String, Any> =
        objectValue?.entrySet()?.associate { (key, json) ->
            require(json.isJsonPrimitive) { "Clan values must be scalar" }
            val primitive = json.asJsonPrimitive
            val value: Any = if (primitive.isString) primitive.asString else {
                require(primitive.isNumber && primitive.asBigDecimal.stripTrailingZeros().scale() <= 0) { "Clan numbers must be integral" }
                val number = primitive.asBigDecimal.longValueExact()
                if (number in Int.MIN_VALUE..Int.MAX_VALUE) number.toInt() else number
            }
            key to if (settings) ClanSetting.forName(key)?.let { normalize(it, value) } ?: value else value
        }?.toMutableMap() ?: mutableMapOf()

    private fun normalize(setting: ClanSetting, value: Any): Any = when (setting.defaultValue) {
        is Long -> (value as Number).toLong()
        is Int -> {
            val number = (value as Number).toLong()
            require(number in Int.MIN_VALUE..Int.MAX_VALUE)
            number.toInt()
        }
        else -> value.toString()
    }

    private fun zero(setting: ClanSetting): Any = when (setting.defaultValue) { is Long -> 0L; is Int -> 0; else -> "" }
    private fun mask(width: Int): Int { require(width in 1..32); return if (width == 32) -1 else (1 shl width) - 1 }
}
