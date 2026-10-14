package org.darkan.cs2

/**
 * The values the client substitutes into a hook's arguments when it fires it.
 *
 * A hook is bound long before it runs, so the values that only exist at the
 * moment of the event - where the mouse was, which component was clicked - are
 * written into the bound arguments as magic numbers and replaced on dispatch.
 * They are read straight from the client's own substitution table
 * (`CS2Executor.executeHook`), and are far enough outside any id range to be
 * recognised on sight.
 */
object Cs2Events {

    /** The value each event name stands for. */
    val VALUES: Map<String, Int> = linkedMapOf(
        "event_mousex" to -2147483647,
        "event_mousey" to -2147483646,
        "event_com" to -2147483645,
        "event_opindex" to -2147483644,
        "event_comsubid" to -2147483643,
        "event_com2" to -2147483642,
        "event_comsubid2" to -2147483641,
        "event_keycode" to -2147483640,
        "event_keychar" to -2147483639,
    )

    /** What each one is, for the declaration's doc comment. */
    val MEANINGS: Map<String, String> = linkedMapOf(
        "event_mousex" to "mouse x when the event fired",
        "event_mousey" to "mouse y when the event fired",
        "event_com" to "the component the event came from",
        "event_opindex" to "which of the component's options was clicked",
        "event_comsubid" to "the slot within the source component",
        "event_com2" to "the component the event was aimed at",
        "event_comsubid2" to "the slot within that component",
        "event_keycode" to "the key that was pressed",
        "event_keychar" to "the character it typed",
    )

    /** The two that carry a component hash; the rest are plain numbers. */
    val COMPONENTS: Set<String> = setOf("event_com", "event_com2")

    val NAMES: Map<Int, String> = VALUES.entries.associate { (name, value) -> value to name }

    /** The name for a value the client substitutes, or null when it is a number. */
    fun nameOf(value: Int): String? = NAMES[value]

    /** The value an event name stands for, or null when the name is not one. */
    fun valueOf(name: String): Int? = VALUES[name]
}
