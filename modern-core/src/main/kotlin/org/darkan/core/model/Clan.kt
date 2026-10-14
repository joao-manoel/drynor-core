package org.darkan.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

/**
 * Serializer for clan setting values which can be Int, Long, or String.
 */
object ClanSettingValueSerializer : KSerializer<Any> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ClanSettingValue") {
        element<JsonElement>("value")
    }

    override fun serialize(encoder: Encoder, value: Any) {
        val jsonEncoder = encoder as JsonEncoder
        val element = when (value) {
            is Int -> JsonPrimitive(value)
            is Long -> JsonPrimitive(value)
            is String -> JsonPrimitive(value)
            else -> throw IllegalArgumentException("Unsupported clan setting type: ${value::class}")
        }
        jsonEncoder.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): Any {
        val jsonDecoder = decoder as JsonDecoder
        val element = jsonDecoder.decodeJsonElement()
        return when {
            element is JsonPrimitive && element.isString -> element.content
            element is JsonPrimitive && element.longOrNull != null -> {
                val long = element.long
                if (long in Int.MIN_VALUE..Int.MAX_VALUE) long.toInt() else long
            }
            else -> throw IllegalArgumentException("Unsupported clan setting value: $element")
        }
    }
}

@Serializable
data class ClanMemberData(
    var rank: ClanRank = ClanRank.NONE,
    var joinDate: Long = System.currentTimeMillis(),
    var job: Int? = 0,
    var banFromKeep: Boolean? = false,
    var banFromCitadel: Boolean? = false,
    var banFromIsland: Boolean? = false,
    var firstWeek: Boolean? = false,
)

/**
 * Every persisted property is a `var`: the Mongo POJO codec constructs through the no-arg
 * constructor and then assigns via setters, so a `val` here would decode as its default and the
 * stored value would be silently discarded.
 */
@Serializable
class Clan(
    var name: String = "",
    var leaderUsername: String = "",
    var members: MutableMap<String, ClanMemberData> = mutableMapOf(),
    var bannedUsers: MutableSet<String> = mutableSetOf(),
    var settings: MutableMap<String, @Serializable(with = ClanSettingValueSerializer::class) Any> = mutableMapOf(),
    var vars: MutableMap<String, @Serializable(with = ClanSettingValueSerializer::class) Any> = mutableMapOf(),
    var ccChatRank: ClanRank = ClanRank.NONE,
    var ccKickRank: ClanRank = ClanRank.ADMIN,
    var motto: String = "",
) {
    companion object {
        const val MAX_MEMBERS = 100
        const val MIN_FILLER_SLOTS = 5
    }

    fun getVar(id: Int): Any = vars.getOrDefault(id.toString(), 0)
    fun setVar(id: Int, value: Any) { vars[id.toString()] = value }
    fun setVar(clanVar: ClanVar, value: Any) { vars[clanVar.id.toString()] = value }

    fun getSetting(setting: ClanSetting): Any = settings.getOrDefault(setting.name, setting.defaultValue)
    fun setSetting(setting: ClanSetting, value: Any) { settings[setting.name] = value }

    fun hasPermissions(username: String, minRank: ClanRank): Boolean {
        val data = members[username] ?: return false
        return data.rank.ordinal >= minRank.ordinal
    }

    fun getRank(username: String): ClanRank = members[username]?.rank ?: ClanRank.NONE

    fun getMotifTopIcon(): Int = (getSetting(ClanSetting.MOTIF_TOP_ICON) as? Number)?.toInt() ?: 0
    fun getMotifBottomIcon(): Int = (getSetting(ClanSetting.MOTIF_BOTTOM_ICON) as? Number)?.toInt() ?: 0
    fun getMotifColors(): IntArray = intArrayOf(
        (getSetting(ClanSetting.MOTIF_TOP_COLOR) as? Number)?.toInt() ?: 0,
        (getSetting(ClanSetting.MOTIF_BOTTOM_COLOR) as? Number)?.toInt() ?: 0,
        (getSetting(ClanSetting.MOTIF_PRIMARY_COLOR) as? Number)?.toInt() ?: 0,
        (getSetting(ClanSetting.MOTIF_SECONDARY_COLOR) as? Number)?.toInt() ?: 0,
    )
}
