package org.drynor.tools.rsinterface

/**
 * Interface component type ids, matching the client's `ComponentType` enum.
 *
 * Only [CONTAINER], [FIGURE], [TEXT], [GRAPHIC], [MODEL] and [LINE] carry draw
 * behaviour; the remaining ids are layout-only. [TYPE_2] is special-cased by the
 * layout: it never narrows the clip rectangle for its own children.
 */
object ComponentType {
    const val CONTAINER = 0
    const val TYPE_1 = 1
    const val TYPE_2 = 2
    const val FIGURE = 3
    const val TEXT = 4
    const val GRAPHIC = 5
    const val MODEL = 6
    const val TYPE_7 = 7
    const val TYPE_8 = 8
    const val LINE = 9

    fun name(type: Int): String = when (type) {
        CONTAINER -> "Layer"
        TYPE_1 -> "Type 1"
        TYPE_2 -> "Unclipped"
        FIGURE -> "Rectangle"
        TEXT -> "Text"
        GRAPHIC -> "Graphic"
        MODEL -> "Model"
        TYPE_7 -> "Type 7"
        TYPE_8 -> "Type 8"
        LINE -> "Line"
        else -> "Type $type"
    }
}

/**
 * Model source kinds, matching the client's `MediaType` enum. The component's
 * media type selects which id in [ComponentState.mediaId] means what.
 *
 * Only [RAW_MODEL] and [ITEM] can be resolved from the cache alone; the entity
 * kinds need live player/NPC state and are rendered as an empty viewport.
 */
object MediaType {
    const val UNDEFINED = 0
    const val RAW_MODEL = 1
    const val NPC_HEAD = 2
    const val PLAYER_HEAD = 3
    const val ITEM = 4
    const val PLAYER_MODEL = 5
    const val NPC_MODEL = 6
    const val PLAYER_HEAD_IGNORE_WORN = 7
    const val ITEM_CONTAINER_MALE = 8
    const val ITEM_CONTAINER_FEMALE = 9
}
