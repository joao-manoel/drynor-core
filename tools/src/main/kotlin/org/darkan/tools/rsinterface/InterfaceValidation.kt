package org.darkan.tools.rsinterface

import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.InterfaceComponentDefinition

/** How badly a [InterfaceIssue] is likely to hurt. */
enum class IssueSeverity {
    /** Worth knowing, harmless. */
    INFO,

    /** The interface will decode and draw, but not the way the author expects. */
    WARNING,

    /** Saving or loading this will break: broken indices, or a corrupt encode. */
    ERROR
}

/**
 * One developer facing finding from [InterfaceDocument.validate].
 *
 * Structured rather than pre-formatted so the UI can group by severity, filter by
 * [code] and jump the selection to [componentIndex].
 */
data class InterfaceIssue(
    val severity: IssueSeverity,
    /** Component the issue is about, or -1 when it concerns the whole interface. */
    val componentIndex: Int,
    /** Stable machine readable id, e.g. `PARENT_NOT_CONTAINER`. */
    val code: String,
    val message: String
) {
    override fun toString(): String {
        val where = if (componentIndex >= 0) "[$componentIndex] " else ""
        return "$severity $where$code: $message"
    }
}

/**
 * The cache-side existence questions validation needs to answer.
 *
 * Kept as lambdas so validation stays headless: the viewer's [GraphicCache] and
 * [FontCache] upload GL textures on lookup, which is exactly what a background
 * validation pass must not do. [of] answers the same questions straight from the
 * index tables, which is a map lookup per call.
 */
class InterfaceAssets(
    val graphicExists: (Int) -> Boolean,
    val fontExists: (Int) -> Boolean,
    val scriptExists: (Int) -> Boolean,
    val interfaceExists: (Int) -> Boolean
) {
    companion object {

        /** Answers everything with "it exists", for validating without a cache. */
        val NONE = InterfaceAssets({ true }, { true }, { true }, { true })

        /**
         * Existence probes backed by the index tables read at cache load:
         * graphic groups are archives of index 8, font metrics archives of index 13,
         * clientscripts archives of index 12 and interfaces archives of index 3.
         * Nothing is decompressed or decoded.
         */
        fun of(cache: Cache) = InterfaceAssets(
            graphicExists = { id -> id >= 0 && cache.files(Index.GRAPHICS, id).isNotEmpty() },
            fontExists = { id -> id >= 0 && cache.files(Index.FONT_METRICS, id).isNotEmpty() },
            scriptExists = { id -> id >= 0 && cache.files(Index.CLIENT_SCRIPTS, id).isNotEmpty() },
            interfaceExists = { id -> id >= 0 && cache.files(Index.INTERFACES, id).isNotEmpty() }
        )
    }
}

/**
 * Structural and format checks over a whole component array.
 *
 * The checks fall into three groups:
 *
 * 1. **Index integrity** - out of range parents, cycles, too many components.
 *    These break the decoder or the layout outright.
 * 2. **Silent no-ops** - the client's layout only descends into components of
 *    type 0, so anything parented to another type is never positioned and never
 *    drawn, and a container with no size clips all of its children away. These
 *    are the two mistakes that make a new interface render as nothing at all.
 * 3. **Save fidelity** - fields the if3 encoder cannot represent for the
 *    component's type, and the three constructs where
 *    [world.gregs.voidps.cache.definition.encoder.InterfaceEncoder] and
 *    [world.gregs.voidps.cache.definition.decoder.InterfaceDecoder] disagree
 *    badly enough to corrupt the file. See [InterfaceStore.verifyRoundTrip] for
 *    the byte level version of this group.
 */
object InterfaceValidator {

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun validate(
        interfaceId: Int,
        components: List<InterfaceComponentDefinition>,
        assets: InterfaceAssets = InterfaceAssets.NONE
    ): List<InterfaceIssue> {
        val issues = ArrayList<InterfaceIssue>()
        if (components.size > ComponentDefinitions.MAX_COMPONENTS) {
            issues.add(
                InterfaceIssue(
                    IssueSeverity.ERROR, -1, "TOO_MANY_COMPONENTS",
                    "${components.size} components; a component index must fit in 16 bits " +
                        "and 65535 is the decoder's no-parent marker, so the limit is " +
                        "${ComponentDefinitions.MAX_COMPONENTS}"
                )
            )
        }
        val childCounts = IntArray(components.size)
        components.forEachIndexed { index, component ->
            val expectedId = ComponentDefinitions.pack(interfaceId, index)
            if (component.id != expectedId) {
                issues.add(
                    InterfaceIssue(
                        IssueSeverity.ERROR, index, "ID_MISMATCH",
                        "id is ${component.id}, expected $expectedId " +
                            "(componentIndex or (interfaceId shl 16))"
                    )
                )
            }
            val parent = component.parent
            if (parent != ComponentDefinitions.ROOT) {
                if (parent < 0 || parent >= components.size) {
                    issues.add(
                        InterfaceIssue(
                            IssueSeverity.ERROR, index, "PARENT_OUT_OF_RANGE",
                            "parent $parent is outside 0..${components.lastIndex}"
                        )
                    )
                } else {
                    childCounts[parent]++
                    val parentType = components[parent].type
                    if (parentType != ComponentType.CONTAINER) {
                        issues.add(
                            InterfaceIssue(
                                IssueSeverity.WARNING, index, "PARENT_NOT_CONTAINER",
                                "parent $parent is ${ComponentType.name(parentType)}; only type 0 " +
                                    "propagates a coordinate space, so this component is never laid " +
                                    "out and never drawn"
                            )
                        )
                    }
                }
            }
            validateComponent(interfaceId, components, index, component, assets, issues)
        }
        detectCycles(components, issues)
        components.forEachIndexed { index, component ->
            if (childCounts[index] == 0) {
                return@forEachIndexed
            }
            val zeroWidth = component.baseWidth == 0 && component.horizontalSizeMode.toInt() == 0
            val zeroHeight = component.baseHeight == 0 && component.verticalSizeMode.toInt() == 0
            if (zeroWidth || zeroHeight) {
                issues.add(
                    InterfaceIssue(
                        IssueSeverity.WARNING, index, "ZERO_SIZE_PARENT",
                        "absolute size ${component.baseWidth}x${component.baseHeight} with " +
                            "${childCounts[index]} children; the clip rectangle collapses and every " +
                            "child is invisible"
                    )
                )
            }
        }
        return issues
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod", "LongParameterList")
    private fun validateComponent(
        interfaceId: Int,
        components: List<InterfaceComponentDefinition>,
        index: Int,
        component: InterfaceComponentDefinition,
        assets: InterfaceAssets,
        issues: MutableList<InterfaceIssue>
    ) {
        when (component.type) {
            ComponentType.GRAPHIC -> {
                if (component.defaultImage < 0) {
                    issues.add(
                        InterfaceIssue(
                            IssueSeverity.INFO, index, "GRAPHIC_UNSET",
                            "graphic component with no image. Common and usually deliberate - most " +
                                "cache interfaces leave the graphic at -1 and let a load script set " +
                                "it - but a hand authored component that stays -1 draws nothing"
                        )
                    )
                } else if (!assets.graphicExists(component.defaultImage)) {
                    issues.add(
                        InterfaceIssue(
                            IssueSeverity.WARNING, index, "GRAPHIC_MISSING",
                            "graphic ${component.defaultImage} is not in cache index 8"
                        )
                    )
                }
            }
            ComponentType.TEXT -> {
                if (component.fontId < 0) {
                    issues.add(
                        InterfaceIssue(
                            IssueSeverity.WARNING, index, "FONT_UNSET",
                            "text component with fontId -1 draws nothing"
                        )
                    )
                } else if (!assets.fontExists(component.fontId)) {
                    issues.add(
                        InterfaceIssue(
                            IssueSeverity.WARNING, index, "FONT_MISSING",
                            "font ${component.fontId} is not in cache index 13"
                        )
                    )
                }
                if (component.lineCount != 0) {
                    issues.add(
                        InterfaceIssue(
                            IssueSeverity.INFO, index, "LINE_COUNT_NOT_ENCODED",
                            "lineCount is neither read nor written by the if3 codec; it will be 0 " +
                                "after a save"
                        )
                    )
                }
            }
            ComponentType.MODEL -> {
                if (component.defaultMediaType != MODEL_MEDIA_TYPE) {
                    issues.add(
                        InterfaceIssue(
                            IssueSeverity.WARNING, index, "MEDIA_TYPE_NOT_ENCODED",
                            "defaultMediaType ${component.defaultMediaType} cannot be saved; the if3 " +
                                "format has no media type field and the decoder forces 1 (raw model) " +
                                "for every type 6 component"
                        )
                    )
                }
                if (component.viewportZ != 0 && !component.centreType) {
                    issues.add(
                        InterfaceIssue(
                            IssueSeverity.ERROR, index, "VIEWPORT_Z_DROPPED",
                            "viewportZ is only written inside the centreType block; with centreType " +
                                "false the whole viewport block is skipped and origin, rotation and " +
                                "scale are all lost on save"
                        )
                    )
                }
            }
        }
        if (component.type != ComponentType.CONTAINER &&
            (component.scrollWidth != 0 || component.scrollHeight != 0)
        ) {
            issues.add(
                InterfaceIssue(
                    IssueSeverity.INFO, index, "SCROLL_ON_NON_CONTAINER",
                    "scroll size is only encoded for type 0 components"
                )
            )
        }
        if (component.type !in COLOUR_TYPES && component.colour != 0) {
            issues.add(
                InterfaceIssue(
                    IssueSeverity.INFO, index, "COLOUR_NOT_ENCODED",
                    "colour is only encoded for types 3, 4, 5 and 9"
                )
            )
        }
        if (component.type !in ALPHA_TYPES && component.alpha != 0) {
            issues.add(
                InterfaceIssue(
                    IssueSeverity.INFO, index, "ALPHA_NOT_ENCODED",
                    "alpha is only encoded for types 3, 4 and 5"
                )
            )
        }
        if (component.type != ComponentType.TEXT && component.text.isNotEmpty()) {
            issues.add(
                InterfaceIssue(
                    IssueSeverity.INFO, index, "TEXT_NOT_ENCODED",
                    "text is only encoded for type 4 components"
                )
            )
        }
        val icons = component.mouseIcon
        if (icons != null && icons.count { it != -1 } > MAX_ENCODED_MOUSE_ICONS) {
            // The format carries at most two option cursors: the decoder reads an
            // index byte and a short, then optionally one more pair. Anything past
            // the second set entry has nowhere to go and is dropped on save.
            issues.add(
                InterfaceIssue(
                    IssueSeverity.WARNING, index, "MOUSE_ICON_DROPPED",
                    "${icons.count { it != -1 }} option cursors are set; the format carries at " +
                        "most $MAX_ENCODED_MOUSE_ICONS, so the rest are dropped on save"
                )
            )
        }
        if (component.onTargetLeave != null) {
            issues.add(
                InterfaceIssue(
                    IssueSeverity.WARNING, index, "ON_TARGET_LEAVE_DROPPED",
                    "the encoder always writes the original if3 format marker, which has no " +
                        "onTargetLeave slot; the hook is dropped on save"
                )
            )
        }
        if (!component.unknown.isNullOrEmpty()) {
            issues.add(
                InterfaceIssue(
                    IssueSeverity.INFO, index, "TYPE_FLAG_STRING",
                    "component carries the type-flag string \"${component.unknown}\"; it is written " +
                        "back as type or 0x80 plus the string"
                )
            )
        }
        for ((name, hook) in component.hooks()) {
            if (!assets.scriptExists(hook.script)) {
                issues.add(
                    InterfaceIssue(
                        IssueSeverity.WARNING, index, "SCRIPT_MISSING",
                        "$name points at clientscript ${hook.script}, which is not in cache index 12"
                    )
                )
            }
            for (arg in hook.args) {
                if (arg !is Int || arg < 0) {
                    continue
                }
                val target = ComponentDefinitions.interfaceOf(arg)
                if (target == 0) {
                    continue
                }
                if (target == interfaceId) {
                    if (ComponentDefinitions.indexOf(arg) >= components.size) {
                        issues.add(
                            InterfaceIssue(
                                IssueSeverity.WARNING, index, "HOOK_ARG_DANGLING",
                                "$name argument $arg addresses component " +
                                    "${ComponentDefinitions.indexOf(arg)} of this interface, which " +
                                    "does not exist"
                            )
                        )
                    }
                } else if (!assets.interfaceExists(target)) {
                    issues.add(
                        InterfaceIssue(
                            IssueSeverity.INFO, index, "HOOK_ARG_FOREIGN",
                            "$name argument $arg looks like a component hash for interface $target, " +
                                "which is not in the cache; index shifts here are never remapped"
                        )
                    )
                }
            }
        }
    }

    /**
     * Flags every component that cannot reach the root by walking `parent`.
     * Walks each chain once with a colour marker, so the whole array costs O(n).
     */
    private fun detectCycles(
        components: List<InterfaceComponentDefinition>,
        issues: MutableList<InterfaceIssue>
    ) {
        val state = IntArray(components.size) // 0 unvisited, 1 on the current path, 2 settled
        for (start in components.indices) {
            if (state[start] != 0) {
                continue
            }
            val path = ArrayList<Int>()
            var current = start
            while (true) {
                if (current < 0 || current >= components.size) {
                    break
                }
                if (state[current] == 1) {
                    issues.add(
                        InterfaceIssue(
                            IssueSeverity.ERROR, current, "PARENT_CYCLE",
                            "parent chain loops: ${path.dropWhile { it != current }.joinToString(" -> ")}" +
                                " -> $current"
                        )
                    )
                    break
                }
                if (state[current] == 2) {
                    break
                }
                state[current] = 1
                path.add(current)
                current = components[current].parent
            }
            for (visited in path) {
                state[visited] = 2
            }
        }
    }

    /** The only media type the if3 format can express for a type 6 component. */
    private const val MODEL_MEDIA_TYPE = 1

    /** Option cursors the if3 format has room for; the rest are dropped on save. */
    private const val MAX_ENCODED_MOUSE_ICONS = 2

    private val COLOUR_TYPES = intArrayOf(
        ComponentType.FIGURE, ComponentType.TEXT, ComponentType.GRAPHIC, ComponentType.LINE
    )
    private val ALPHA_TYPES = intArrayOf(
        ComponentType.FIGURE, ComponentType.TEXT, ComponentType.GRAPHIC
    )
}
