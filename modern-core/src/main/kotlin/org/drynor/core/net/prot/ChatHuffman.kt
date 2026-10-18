package org.drynor.core.net.prot

import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.secure.Huffman
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

class ChatHuffman(val huffman: Huffman) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ChatHuffman>
}

internal suspend fun chatHuffman(): Huffman = coroutineContext[ChatHuffman]?.huffman ?: Cache.huffman
