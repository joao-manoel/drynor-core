package org.darkan.core.net.prot

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

class ProtDecodeContext(val localPlayerIndex: Int) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ProtDecodeContext> {
        suspend fun localPlayerIndex(): Int? = coroutineContext[Key]?.localPlayerIndex?.takeIf { it > 0 }
    }
}
