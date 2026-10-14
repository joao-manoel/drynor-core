package org.darkan.core.net.prot

import io.ktor.utils.io.*
import kotlinx.io.Source
import org.darkan.core.Logger.logSevere
import org.darkan.core.net.prot.update.UpdateProtocol
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.full.primaryConstructor

class Codec {
    val serverProts = mutableMapOf<KClass<out ServerProt>, ServerProtCodec>()
    val serverProtsByOpcode = mutableMapOf<Int, ServerProtCodec>()
    val loginResponses = mutableSetOf<KClass<out ServerProt>>()
    val clientProts = mutableMapOf<KClass<out ClientProt>, ClientProtCodec>()
    val clientProtsByOpcode = mutableMapOf<Int, ClientProtCodec>()

    private val uninstantiableProts: MutableSet<KClass<*>> = ConcurrentHashMap.newKeySet()
    var updateProtocol: UpdateProtocol? = null

    class ProtCodec<P : Any>(
        val protClass: KClass<out P>,
        val opcodes: IntArray,
        val size: ProtSize,
    ) {
        val opcode: Int get() = opcodes[0]

        var encoder: (suspend P.(ByteWriteChannel) -> Unit)? = null
            internal set

        var decoder: (suspend Source.(Int) -> P)? = null
            internal set

        internal var opcodeSelector: ((P) -> Int)? = null

        fun opcodeFor(prot: P): Int = opcodeSelector?.invoke(prot) ?: opcode
    }

    class Registration<P : Any, T : P> internal constructor(
        private val codec: ProtCodec<P>,
        private val claimOpcode: (ProtCodec<P>) -> Unit = {},
    ) {
        @Suppress("UNCHECKED_CAST")
        fun encoder(opcode: ((T) -> Int)? = null, block: suspend T.(ByteWriteChannel) -> Unit): Registration<P, T> {
            if (opcode != null) codec.opcodeSelector = { opcode(it as T) }
            codec.encoder = { output -> (this as T).block(output) }
            return this
        }

        fun decoder(block: suspend Source.() -> T): Registration<P, T> {
            codec.decoder = { _ -> block() }
            claimOpcode(codec)
            return this
        }
    }

    internal inline fun <reified T : ServerProt> serverProt(
        opcode: Int,
        size: ProtSize = ProtSize.Fixed(0),
        noinline encoder: (suspend T.(ByteWriteChannel) -> Unit)? = null
    ): Registration<ServerProt, T> = registerServerProt(T::class, opcode, size, indexed = true, encoder)

    internal inline fun <reified T : ServerProt> serverProt(
        opcode: Int,
        size: Int,
        noinline encoder: (suspend T.(ByteWriteChannel) -> Unit)? = null
    ): Registration<ServerProt, T> = serverProt(opcode, ProtSize.Fixed(size), encoder)

    // Login responses reuse the handshake's response code as their opcode, so they stay out of serverProtsByOpcode.
    internal inline fun <reified T : ServerProt> loginResponse(
        opcode: Int,
        size: ProtSize,
        noinline encoder: suspend T.(ByteWriteChannel) -> Unit
    ): Registration<ServerProt, T> = registerServerProt(T::class, opcode, size, indexed = false, encoder)

    @PublishedApi
    internal fun <T : ServerProt> registerServerProt(
        protClass: KClass<T>,
        opcode: Int,
        size: ProtSize,
        indexed: Boolean,
        encoder: (suspend T.(ByteWriteChannel) -> Unit)?
    ): Registration<ServerProt, T> {
        val codec = ServerProtCodec(protClass, intArrayOf(opcode), size)
        val registration = Registration<ServerProt, T>(codec) { if (indexed) serverProtsByOpcode[opcode] = it }
        if (encoder != null) registration.encoder(block = encoder)
        serverProts[protClass] = codec
        if (indexed) serverProtsByOpcode.putIfAbsent(opcode, codec)
        else loginResponses += protClass
        return registration
    }

    internal inline fun <reified T : ClientProt> clientProt(opcodes: IntArray, size: ProtSize = ProtSize.Fixed(0), noinline decoder: (suspend Source.(Int) -> T)? = null): Registration<ClientProt, T> {
        val codec = ClientProtCodec(T::class, opcodes, size)
        codec.decoder = decoder
        opcodes.forEach { clientProtsByOpcode[it] = codec }
        clientProts[T::class] = codec
        return Registration(codec)
    }

    internal inline fun <reified T : ClientProt> clientProt(opcodes: IntArray, size: Int, noinline decoder: (suspend Source.(Int) -> T)? = null): Registration<ClientProt, T> =
        clientProt(opcodes, ProtSize.Fixed(size), decoder)

    internal inline fun <reified T : ClientProt> clientProt(opcode: Int, size: ProtSize = ProtSize.Fixed(0), noinline decoder: (suspend Source.() -> T)? = null): Registration<ClientProt, T> =
        clientProt(intArrayOf(opcode), size, decoder?.let { { _ -> this.it() } })

    internal inline fun <reified T : ClientProt> clientProt(opcode: Int, size: Int, noinline decoder: (suspend Source.() -> T)? = null): Registration<ClientProt, T> =
        clientProt(intArrayOf(opcode), ProtSize.Fixed(size), decoder?.let { { _ -> this.it() } })

    suspend inline fun <reified T : ClientProt> decodeClientProt(opcode: Int, channel: Source): T? {
        val codec = clientProtsByOpcode[opcode] ?: return null
        return decode(codec, opcode, channel) as T?
    }

    suspend fun decodeServerProt(opcode: Int, channel: Source): ServerProt? {
        val codec = serverProtsByOpcode[opcode] ?: return null
        return decode(codec, opcode, channel)
    }

    suspend inline fun <reified T : ServerProt> decodeServerProt(channel: Source): T {
        val codec = serverProts[T::class] ?: error("No ServerProt codec for ${T::class}")
        return decode(codec, codec.opcode, channel) as T? ?: error("Cannot decode ${T::class}")
    }

    suspend fun <P : Any> decode(codec: ProtCodec<P>, opcode: Int, channel: Source): P? {
        codec.decoder?.let { return it.invoke(channel, opcode) }
        check(codec.size is ProtSize.Fixed && codec.size.length == 0) { "No decoder for non-empty packet ${codec.protClass.simpleName}" }
        return createInstance(codec.protClass, opcode)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : ClientProt> createInstanceForOpcode(opcode: Int): T? {
        val protClass = clientProtsByOpcode[opcode]?.protClass ?: return null
        return createInstance(protClass, opcode) as T?
    }

    // A constructor whose parameters all have defaults is called with callBy so Kotlin fills them in.
    private fun <T : Any> createInstance(protClass: KClass<T>, opcode: Int): T? {
        return try {
            if (protClass.isValue) {
                val constructor = protClass.primaryConstructor
                    ?: return null.also { reportUninstantiable(protClass, opcode, "value class has no primary constructor") }
                createValueClassInstance(constructor)
            } else {
                val constructor = protClass.constructors.firstOrNull { it.parameters.isEmpty() }
                    ?: protClass.constructors.firstOrNull { candidate -> candidate.parameters.all { it.isOptional } }
                    ?: return null.also {
                        reportUninstantiable(protClass, opcode, "no constructor with an empty or fully-defaulted parameter list")
                    }
                constructor.callBy(emptyMap())
            }
        } catch (e: Exception) {
            reportUninstantiable(protClass, opcode, "${e::class.simpleName}: ${e.message}")
            null
        }
    }

    private fun reportUninstantiable(protClass: KClass<*>, opcode: Int, reason: String) {
        if (!uninstantiableProts.add(protClass)) return
        logSevere(
            "Cannot instantiate payload-less packet ${protClass.qualifiedName ?: protClass} " +
                "(opcode $opcode): $reason. Give it a decoder or a no-argument constructor."
        )
    }

    private fun <T : Any> createValueClassInstance(constructor: KFunction<T>): T {
        val args = mutableMapOf<KParameter, Any?>()
        for (param in constructor.parameters) {
            when (param.type.classifier) {
                Int::class -> args[param] = 0
                String::class -> args[param] = ""
                Boolean::class -> args[param] = false
                else -> args[param] = null
            }
        }
        return constructor.callBy(args)
    }

    suspend fun encodeServerProt(prot: ServerProt, output: ByteWriteChannel) {
        val codec = serverProts[prot::class] ?: error("Unknown packet type: ${prot::class}")
        codec.encoder?.invoke(prot, output)
    }

    suspend fun encodeClientProt(prot: ClientProt, output: ByteWriteChannel) {
        val codec = clientProts[prot::class] ?: error("Unknown packet type: ${prot::class}")
        codec.encoder?.invoke(prot, output)
    }

    companion object {
        private val codecs = mutableMapOf<Int, Codec>()

        fun register(revision: Int, init: Codec.() -> Unit): Codec {
            val codec = Codec().apply(init)
            return codecs.getOrPut(revision) { codec }
        }

        fun get(revision: Int) = codecs[revision]
    }
}

typealias ServerProtCodec = Codec.ProtCodec<ServerProt>
typealias ClientProtCodec = Codec.ProtCodec<ClientProt>
