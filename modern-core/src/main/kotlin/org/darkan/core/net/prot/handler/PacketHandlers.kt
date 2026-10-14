package org.darkan.core.net.prot.handler

import kotlinx.coroutines.runBlocking
import org.darkan.core.Logger.logError
import org.darkan.core.Logger.logInfo
import org.darkan.core.Logger.logWarn
import org.darkan.core.getClasses
import java.lang.reflect.ParameterizedType

interface PacketHandler<T, K : Any> {
    suspend fun handle(player: T, packet: K)
}

object PacketHandlers {
    private val PACKET_HANDLERS = mutableMapOf<Class<*>, PacketHandler<*, *>>()

    fun loadHandlersFromPackage(pack: String) {
        logInfo("Initializing packet handlers ($pack)...")
        val classes = try {
            getClasses(pack)
        } catch (e: Exception) {
            logError("Failed to scan package $pack for packet handlers", e)
            return
        }

        val candidates = classes.filter { PacketHandler::class.java.isAssignableFrom(it) }
        var succeeded = 0
        var failed = 0
        candidates.forEach { clazz ->
            try {
                @Suppress("UNCHECKED_CAST")
                val instance = clazz.getConstructor().newInstance() as PacketHandler<*, *>
                mapHandler(instance)
                succeeded++
            } catch (e: Throwable) {
                failed++
                logError("Failed to register packet handler ${clazz.name}: ${e::class.simpleName}: ${e.message}", e)
            }
        }
        logInfo("Packet handlers loaded: $succeeded succeeded, $failed failed, ${PACKET_HANDLERS.size} packet->handler mappings from ${candidates.size} candidate classes.")
    }

    fun mapHandler(handler: PacketHandler<*, *>) {
        val parameterized = handler.javaClass.genericInterfaces.firstOrNull { it is ParameterizedType } as? ParameterizedType
            ?: error("Handler ${handler.javaClass.name} does not directly implement PacketHandler<T, K> with parameterized types (genericInterfaces=${handler.javaClass.genericInterfaces.joinToString()})")
        @Suppress("UNCHECKED_CAST")
        val clazz = parameterized.actualTypeArguments.getOrNull(1) as? Class<*>
            ?: error("Handler ${handler.javaClass.name} has no packet type argument in ${parameterized.typeName}")
        val existing = PACKET_HANDLERS[clazz]
        if (existing != null && existing.javaClass != handler.javaClass) {
            logWarn("  Overwriting ${existing.javaClass.simpleName} with ${handler.javaClass.simpleName} for ${clazz.simpleName}")
        }
        logInfo("  Mapped handler: ${handler.javaClass.simpleName} -> ${clazz.simpleName} (${clazz.name})")
        PACKET_HANDLERS[clazz] = handler
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> getHandler(packet: Class<*>) = PACKET_HANDLERS[packet] as? PacketHandler<T, Any>

    /**
     * Blocking dispatch for Java callers (e.g. Player.processPackets on the world thread).
     * Handlers that call only non-suspend legacy Java code complete synchronously.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> handleBlocking(player: T, packet: Any) {
        val handler = PACKET_HANDLERS[packet::class.java] as? PacketHandler<T, Any>
        if (handler == null) {
            logWarn("No handler found for packet: ${packet::class.java.simpleName} (${packet::class.java.name}), registered keys: ${PACKET_HANDLERS.keys.map { it.simpleName }}")
            return
        }
        try {
            runBlocking { handler.handle(player, packet) }
        } catch (e: Throwable) {
            logError("Handler ${handler.javaClass.simpleName} threw for ${packet::class.simpleName}", e)
        }
    }
}