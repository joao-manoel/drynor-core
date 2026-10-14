package org.darkan.cs2.vm

import org.darkan.cs2.Cs2Op

/** Values handed to or returned from an opcode, one list per operand stack. */
data class Cs2Values(
    val ints: List<Int> = emptyList(),
    val strings: List<String> = emptyList(),
    val longs: List<Long> = emptyList(),
) {
    companion object {
        val EMPTY = Cs2Values()
    }
}

/**
 * A callback bound to a component event: the script to run and the arguments
 * captured when the hook was set.
 */
data class Cs2Hook(
    val scriptId: Int,
    val args: Cs2Values,
    /** Varps, stats or keys that gate the transmit-style hooks. */
    val triggers: List<Int> = emptyList(),
)

/**
 * Everything the virtual machine cannot do on its own.
 *
 * The VM implements the core instruction set - the stacks, locals, branches,
 * switches, calls and global arrays - and hands every other opcode here. That
 * keeps the machine complete by construction: a host that only cares about
 * interfaces can implement the component opcodes and let [invoke] return
 * defaults for the rest, without the VM ever losing track of the stacks.
 */
interface Cs2Host {

    /**
     * Runs one non-core opcode. [args] arrives in source order, and the result
     * must supply exactly as many values as the opcode's signature pushes;
     * [Cs2Host.defaults] builds a correctly-shaped empty result.
     */
    fun invoke(op: Cs2Op, operand: Int, args: Cs2Values): Cs2Values

    /** Binds a callback to a component event. */
    fun setHook(op: Cs2Op, component: Int, hook: Cs2Hook?)

    fun varp(id: Int): Int

    fun setVarp(id: Int, value: Int)

    fun varbit(id: Int): Int

    fun setVarbit(id: Int, value: Int)

    fun varc(id: Int): Int

    fun setVarc(id: Int, value: Int)

    fun varcString(id: Int): String

    fun setVarcString(id: Int, value: String)

    /** Reported when a script fails; the editor surfaces these to the user. */
    fun onError(message: String)

    companion object {
        /** A zero/empty result shaped to whatever the opcode pushes. */
        fun defaults(op: Cs2Op) = Cs2Values(
            ints = List(op.pushInt) { 0 },
            strings = List(op.pushStr) { "" },
            longs = List(op.pushLong) { 0L },
        )
    }
}

/** A host that does nothing, for running scripts with no interface attached. */
open class NoOpCs2Host : Cs2Host {

    private val varps = HashMap<Int, Int>()
    private val varbits = HashMap<Int, Int>()
    private val varcs = HashMap<Int, Int>()
    private val varcStrings = HashMap<Int, String>()

    override fun invoke(op: Cs2Op, operand: Int, args: Cs2Values) = Cs2Host.defaults(op)

    override fun setHook(op: Cs2Op, component: Int, hook: Cs2Hook?) = Unit

    override fun varp(id: Int) = varps[id] ?: 0

    override fun setVarp(id: Int, value: Int) {
        varps[id] = value
    }

    override fun varbit(id: Int) = varbits[id] ?: 0

    override fun setVarbit(id: Int, value: Int) {
        varbits[id] = value
    }

    override fun varc(id: Int) = varcs[id] ?: 0

    override fun setVarc(id: Int, value: Int) {
        varcs[id] = value
    }

    override fun varcString(id: Int) = varcStrings[id] ?: ""

    override fun setVarcString(id: Int, value: String) {
        varcStrings[id] = value
    }

    override fun onError(message: String) = Unit
}
