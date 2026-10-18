package org.drynor.core.cache

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.QuickChatPhraseDefinition
import world.gregs.voidps.cache.definition.decoder.QuickChatPhraseDecoder
import java.util.concurrent.ConcurrentHashMap

/** Decodes only defined phrases through the current cache backend. */
object QuickChatDefinitions {
    private val definitions = ConcurrentHashMap<Int, QuickChatPhraseDefinition>()
    @JvmStatic fun getDefs(id: Int): QuickChatPhraseDefinition? {
        if (id !in 0..65535) return null
        return definitions[id] ?: run {
            val index = if (id < 32768) Index.QUICK_CHAT_MESSAGES else Index.QUICK_CHAT_MENUS
            val bytes = Cache.get().data(index, 1, id and 32767) ?: return null
            val value = QuickChatPhraseDefinition(id)
            QuickChatPhraseDecoder().readLoop(value, BufferReader(bytes))
            if (id >= 32768) value.responses?.let { responses ->
                for (i in responses.indices) responses[i] = responses[i] or 32768
            }
            definitions.putIfAbsent(id, value) ?: value
        }
    }
}
