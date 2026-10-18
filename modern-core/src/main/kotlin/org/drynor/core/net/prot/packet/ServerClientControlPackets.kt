/**
 * Packets that drive client-side machinery: scripts, cutscenes, cursors and debug hooks.
 */
package org.drynor.core.net.prot.packet

import org.drynor.core.clientwatch.ReflectionCheck
import org.drynor.core.net.prot.ServerProt

@JvmInline value class ProcessDevConsoleCommand(val command: String) : ServerProt

data class ReflectionRequest(val id: Int, val checks: Array<ReflectionCheck>) : ServerProt {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ReflectionRequest
        if (id != other.id) return false
        if (!checks.contentEquals(other.checks)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + checks.contentHashCode()
        return result
    }
}

data class RunScript(val scriptId: Int, val params: Array<Any>) : ServerProt {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

data class SetCursor(val cursorId: Int = -1, val uptext: String? = null) : ServerProt

data class Cutscene(val cutsceneId: Int, val mapXteas: Array<IntArray>, val data: ByteArray) : ServerProt {
    init {
        require(mapXteas.all { it.size == 4 }) { "Each xtea set must be length 4" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Cutscene
        if (cutsceneId != other.cutsceneId) return false
        if (!mapXteas.contentDeepEquals(other.mapXteas)) return false
        return data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = cutsceneId
        result = 31 * result + mapXteas.contentDeepHashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}

@JvmInline value class TriggerOnDialogAbort(val dummy: Int = 0) : ServerProt

@JvmInline value class ApplyDebug(val opcode: Int) : ServerProt

data class DebugServerTriggers(val interfaceId: Int, val fromComponent: Int, val toComponent: Int, val triggers: IntArray) : ServerProt {
    init {
        require(toComponent >= fromComponent) { "toComponent must be >= fromComponent" }
        require(triggers.size == (toComponent - fromComponent)) { "triggers length must match component range" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DebugServerTriggers
        if (interfaceId != other.interfaceId) return false
        if (fromComponent != other.fromComponent) return false
        if (toComponent != other.toComponent) return false
        return triggers.contentEquals(other.triggers)
    }

    override fun hashCode(): Int {
        var result = interfaceId
        result = 31 * result + fromComponent
        result = 31 * result + toComponent
        result = 31 * result + triggers.contentHashCode()
        return result
    }
}
