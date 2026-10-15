package org.drynor.cs2.vm

import org.drynor.cs2.Cs2Context
import world.gregs.voidps.cache.definition.data.InterfaceComponentHook

/**
 * Event data a hook can ask for.
 *
 * Bound hook arguments are not all literal: the compiler stores sentinel values
 * where the mouse position, the source component or the typed key should go, and
 * the client substitutes the live values when the event fires. Anything left at
 * its default here substitutes as -1, which is what the client does when the
 * event carries no such value.
 */
data class Cs2Event(
    val mouseX: Int = -1,
    val mouseY: Int = -1,
    /** Packed id of the component the event happened on. */
    val source: Int = -1,
    val sourceSlot: Int = -1,
    /** Packed id of the component being dropped onto, for drag and use events. */
    val target: Int = -1,
    val targetSlot: Int = -1,
    val clickedOption: Int = -1,
    val keyCode: Int = -1,
    val keyChar: Int = -1,
    /** Replaces the `event_opbase` string argument. */
    val optionName: String = "",
)

/**
 * Runs the callbacks bound to component events.
 *
 * The sentinel values below are the client's; see `CS2Executor.executeHookInternal`.
 * They are ordinary ints in the bytecode, which is why they have to be replaced
 * on the way in rather than resolved by the virtual machine.
 */
class Cs2HookDispatcher(
    private val context: Cs2Context,
    private val host: Cs2Host,
) {
    /**
     * One machine per level of nesting.
     *
     * A hook can run while another is already running - a script writes a var, the
     * host turns that into a transmit dispatch, and that script writes another -
     * and [Cs2Vm.execute] clears its stacks and frames on entry, so sharing one
     * machine would wipe the outer script's state mid-instruction. The client has
     * exactly this arrangement: `getNextScriptExecutor` hands out `CS2Executor`s
     * from a pool indexed by depth and grows it on demand, which also means each
     * nesting level gets its own operand stacks *and* its own global arrays.
     */
    private val machines = ArrayList<Cs2Vm>(4)
    private var depth = 0

    private fun <T> withMachine(block: (Cs2Vm) -> T): T {
        if (machines.size <= depth) {
            machines.add(Cs2Vm(context, host))
        }
        val machine = machines[depth++]
        try {
            return block(machine)
        } finally {
            depth--
        }
    }

    /** Runs [hook], substituting event data for the sentinel arguments. */
    fun dispatch(hook: InterfaceComponentHook, event: Cs2Event): Cs2Values? {
        val script = context.script(hook.script) ?: run {
            host.onError("Hook targets missing script ${hook.script}")
            return null
        }

        val ints = ArrayList<Int>()
        val strings = ArrayList<String>()
        val longs = ArrayList<Long>()
        for (argument in hook.args) {
            when (argument) {
                is Int -> ints.add(substitute(argument, event))
                is String -> strings.add(if (argument == OPTION_NAME) event.optionName else argument)
                is Long -> longs.add(argument)
            }
        }
        return withMachine { it.execute(script, ints, strings, longs, hook.script) }
    }

    /** Runs the same [hook] shape held directly by the virtual machine. */
    fun dispatch(hook: Cs2Hook, event: Cs2Event): Cs2Values? {
        val script = context.script(hook.scriptId) ?: run {
            host.onError("Hook targets missing script ${hook.scriptId}")
            return null
        }
        return withMachine {
            it.execute(
                script,
                hook.args.ints.map { arg -> substitute(arg, event) },
                hook.args.strings.map { arg -> if (arg == OPTION_NAME) event.optionName else arg },
                hook.args.longs,
                hook.scriptId,
            )
        }
    }

    private fun substitute(value: Int, event: Cs2Event) = when (value) {
        MOUSE_X -> event.mouseX
        MOUSE_Y -> event.mouseY
        SOURCE_COMPONENT -> event.source
        CLICKED_OPTION -> event.clickedOption
        SOURCE_SLOT -> event.sourceSlot
        TARGET_COMPONENT -> event.target
        TARGET_SLOT -> event.targetSlot
        KEY_CODE -> event.keyCode
        KEY_CHAR -> event.keyChar
        else -> value
    }

    companion object {
        const val MOUSE_X = -2147483647
        const val MOUSE_Y = -2147483646
        const val SOURCE_COMPONENT = -2147483645
        const val CLICKED_OPTION = -2147483644
        const val SOURCE_SLOT = -2147483643
        const val TARGET_COMPONENT = -2147483642
        const val TARGET_SLOT = -2147483641
        const val KEY_CODE = -2147483640
        const val KEY_CHAR = -2147483639

        const val OPTION_NAME = "event_opbase"

        /** True when an argument is a placeholder the event fills in. */
        fun isSentinel(value: Int) = value in MOUSE_X..KEY_CHAR
    }
}
