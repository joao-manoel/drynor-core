package world.gregs.voidps.cache.definition

import org.drynor.core.model.Vars
import world.gregs.voidps.buffer.read.Reader

interface Transforms {
    var varbit: Int
    var varp: Int
    var transforms: IntArray?

    fun readTransforms(buffer: Reader, isLast: Boolean) {
        varbit = buffer.readUnsignedShort()
        if (varbit == 65535) {
            varbit = -1
        }
        varp = buffer.readUnsignedShort()
        if (varp == 65535) {
            varp = -1
        }
        var last = -1
        if (isLast) {
            // Legacy uses readBigSmart() for the "last" object ID
            last = buffer.readBigSmart()
        }
        // Legacy: newProtocol (>= 910) uses readSmart(), old uses readUnsignedByte()
        // Revision 727 < 910, so use readUnsignedByte()
        val length = buffer.readUnsignedByte()
        transforms = IntArray(length + 2)
        for (count in 0..length) {
            // Legacy uses readBigSmart() for transform IDs
            transforms!![count] = buffer.readBigSmart()
        }
        transforms!![length + 1] = last
    }

    /**
     * Resolves this definition's varbit/varp transform against [vars], returning the id a definition
     * whose own id is [defaultId] presents as.
     *
     * Legacy `getIdForPlayer(VarManager)`, identical on both `ObjectDefinitions` and `NPCDefinitions`.
     * [transforms] holds one target id per var value, plus - as its last element - the id to fall back
     * to when the var selects nothing, which is also what a null [vars] gets. A target of -1 means the
     * definition is not shown at all for that var value, so it falls back too.
     */
    fun getIdForVars(defaultId: Int, vars: Vars?): Int {
        val transforms = transforms
        if (transforms == null || transforms.isEmpty()) {
            return defaultId
        }
        val fallback = transforms[transforms.size - 1]
        if (vars == null) {
            return fallback
        }
        var index = -1
        if (varbit != -1) {
            index = vars.getVarBit(varbit)
        } else if (varp != -1) {
            index = vars.getVar(varp)
        }
        if (index >= 0 && index < transforms.size - 1 && transforms[index] != -1) {
            return transforms[index]
        }
        return fallback
    }
}
