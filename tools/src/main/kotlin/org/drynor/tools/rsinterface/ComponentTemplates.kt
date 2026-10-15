package org.drynor.tools.rsinterface

import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.definition.data.InterfaceComponentDefinition
import world.gregs.voidps.cache.definition.data.InterfaceComponentHook

/**
 * The size and position mode ids, spelled out so template code reads as intent
 * rather than as magic numbers. [InterfaceInstance] documents what each one does
 * to the box model; these are the same values.
 */
object LayoutMode {
    /** Use the base size verbatim. */
    const val SIZE_ABSOLUTE = 0

    /** Parent extent minus the base size, i.e. an inset from both edges. */
    const val SIZE_INSET = 1

    /** `base * parent / 16384`. */
    const val SIZE_PROPORTIONAL = 2

    /** Keep whatever size the component already had. */
    const val SIZE_UNCHANGED = 3

    /** Derive from the other axis through the aspect ratio. */
    const val SIZE_ASPECT = 4

    /** Absolute offset from the parent's top left. */
    const val POSITION_ABSOLUTE = 0

    /** Centred, offset by the base position. */
    const val POSITION_CENTRE = 1

    /** Absolute offset from the parent's bottom right. */
    const val POSITION_END = 2

    /** `base * parent / 16384` from the top left. */
    const val POSITION_PROPORTIONAL = 3

    /** Centred plus a proportional offset. */
    const val POSITION_PROPORTIONAL_CENTRE = 4

    /** Proportional offset from the bottom right. */
    const val POSITION_PROPORTIONAL_END = 5
}

/**
 * One component produced by a [ComponentTemplate].
 *
 * [parent] is an index *within the template's own list*, or
 * [ComponentTemplates.ATTACH] to hang off whatever the developer is inserting
 * into. [InterfaceDocument.addTemplate] resolves both into real indices.
 */
class TemplateComponent(
    val definition: InterfaceComponentDefinition,
    val parent: Int = ComponentTemplates.ATTACH
)

/**
 * A named entry in the "add component" palette.
 *
 * [create] returns fresh definitions every call - templates are prototypes, not
 * shared instances, and handing the same object to two inserts would alias two
 * components onto one definition.
 */
class ComponentTemplate(
    /** Stable id for persistence and lookup, e.g. `graphic-button`. */
    val id: String,
    val name: String,
    val description: String,
    /** Palette grouping, one of [ComponentTemplates.BASIC] / [ComponentTemplates.PRESET]. */
    val category: String,
    val create: () -> List<TemplateComponent>
)

/**
 * Starting definitions for new components.
 *
 * ### Why templates rather than the data class defaults
 *
 * `InterfaceComponentDefinition()` is not a usable starting point:
 *
 * - `id` defaults to -1, and
 *   [world.gregs.voidps.cache.definition.encoder.InterfaceEncoder] returns
 *   without writing a byte when the id is -1, so a component built with the bare
 *   constructor encodes to an empty file and disappears on the next decode.
 *   [InterfaceDocument] assigns the real id on insert, which is the only reason
 *   the templates here can leave it alone.
 * - `baseWidth` and `baseHeight` default to 0, so the component is invisible and,
 *   if it is a container, so is everything inside it.
 * - `defaultImage` and `fontId` default to -1, which draws nothing.
 * - `alpha` 0 means fully opaque and `graphicScale` 100, `lineWidth` 1 and
 *   `defaultMediaType` 1 are all sane - but only for the type that owns them, and
 *   the encoder writes a type's block whatever the other blocks contain.
 *
 * Graphic and font ids are cache specific, so [defaultGraphicId] and
 * [defaultFontId] are injectable; leave them at -1 and validation will flag the
 * new component rather than the template guessing an id that does not exist.
 */
object ComponentTemplates {

    /** [TemplateComponent.parent] value meaning "attach to the insertion parent". */
    const val ATTACH = ComponentDefinitions.ROOT

    const val BASIC = "Basic"
    const val PRESET = "Presets"

    /** Graphic id new graphic components start with; set from the cache by the editor. */
    var defaultGraphicId: Int = -1

    /** Font id new text components start with; set from the cache by the editor. */
    var defaultFontId: Int = -1

    private const val WHITE = 0xffffff
    private const val PANEL_GREY = 0x3d3529
    private const val BORDER_GREY = 0x766a55

    /**
     * A bare component of [type] with defaults that at least draw something.
     *
     * The returned definition has no id; [InterfaceDocument.addComponent] packs one
     * in. Do not hand this straight to the encoder.
     */
    @Suppress("LongMethod")
    fun defaults(type: Int): InterfaceComponentDefinition {
        val definition = InterfaceComponentDefinition(type = type)
        definition.baseWidth = DEFAULT_SIZE
        definition.baseHeight = DEFAULT_SIZE
        when (type) {
            ComponentType.CONTAINER -> {
                definition.scrollWidth = 0
                definition.scrollHeight = 0
                definition.disableHover = false
            }
            ComponentType.FIGURE -> {
                definition.baseHeight = DEFAULT_SIZE / 2
                definition.colour = PANEL_GREY
                definition.filled = true
                definition.alpha = 0
            }
            ComponentType.TEXT -> {
                definition.baseHeight = TEXT_HEIGHT
                definition.fontId = defaultFontId
                definition.text = "Text"
                definition.colour = WHITE
                // 0 means "use the font's own line height".
                definition.lineHeight = 0
                definition.horizontalTextAlign = 0
                definition.verticalTextAlign = 0
                definition.shaded = false
                definition.alpha = 0
            }
            ComponentType.GRAPHIC -> {
                definition.baseWidth = GRAPHIC_SIZE
                definition.baseHeight = GRAPHIC_SIZE
                definition.defaultImage = defaultGraphicId
                definition.alpha = 0
                // colour 0 leaves the graphic untinted; rotation is the outline width
                // the client bakes around it, not an angle.
                definition.colour = 0
                definition.rotation = 0
            }
            ComponentType.MODEL -> {
                definition.defaultMediaType = MediaType.RAW_MODEL
                definition.defaultMediaId = 0
                definition.animation = -1
                // Leaving the viewport at the origin with scale 100 is what makes the
                // encoder skip the optional viewport block entirely, which is the only
                // shape of a type 6 component that round-trips byte for byte.
                definition.graphicScale = MODEL_SCALE
                definition.centreType = false
            }
            ComponentType.LINE -> {
                definition.baseHeight = 0
                definition.lineWidth = 1
                definition.colour = WHITE
                definition.lineMirrored = false
            }
        }
        return definition
    }

    /**
     * A hook to hang on `onClick` and friends.
     *
     * Arguments are ints or strings; the client swaps every value below
     * -2147483639 for event data before running the script, so
     * [EVENT_SOURCE_COMPONENT] and [EVENT_CLICKED_OPTION] are passed through as
     * plain arguments here and resolved at dispatch time.
     */
    fun hook(script: Int, vararg args: Any): InterfaceComponentHook =
        InterfaceComponentHook(script, arrayOf(*args))

    /** Placeholder the client replaces with the hash of the component clicked. */
    const val EVENT_SOURCE_COMPONENT = -2147483645

    /** Placeholder the client replaces with the option index that was clicked. */
    const val EVENT_CLICKED_OPTION = -2147483643

    /** Every template, in palette order. */
    val all: List<ComponentTemplate> = listOf(
        ComponentTemplate(
            "layer", "Layer", "Empty container. The only type that lays out children.", BASIC
        ) { single(defaults(ComponentType.CONTAINER)) },
        ComponentTemplate(
            "unclipped", "Unclipped layer",
            "Type 2. Never narrows the clip rectangle, so children can draw outside it.", BASIC
        ) { single(defaults(ComponentType.TYPE_2)) },
        ComponentTemplate(
            "rectangle", "Rectangle", "Filled or outlined block of colour.", BASIC
        ) { single(defaults(ComponentType.FIGURE)) },
        ComponentTemplate(
            "text", "Text", "Single line of text in a cache font.", BASIC
        ) { single(defaults(ComponentType.TEXT)) },
        ComponentTemplate(
            "graphic", "Graphic", "One frame of a cache graphic group.", BASIC
        ) { single(defaults(ComponentType.GRAPHIC)) },
        ComponentTemplate(
            "model", "Model", "Model viewport; media type 1 is a raw cache model.", BASIC
        ) { single(defaults(ComponentType.MODEL)) },
        ComponentTemplate(
            "line", "Line", "Straight line from the component's origin to its extent.", BASIC
        ) { single(defaults(ComponentType.LINE)) },
        ComponentTemplate(
            "scroll-layer", "Scrollable layer",
            "Container with a scroll area taller than itself, which is what makes the " +
                "client draw a scrollbar and clamp scrollY to the overflow.", PRESET
        ) { single(scrollLayer()) },
        ComponentTemplate(
            "backdrop", "Backdrop",
            "Rectangle sized by inset so it always fills its parent exactly.", PRESET
        ) { single(backdrop()) },
        ComponentTemplate(
            "label", "Centred label",
            "Text centred on both axes and stretched across its parent.", PRESET
        ) { single(label()) },
        ComponentTemplate(
            "graphic-button", "Graphic button",
            "Graphic with a right click option and a centred caption, ready for an onClick hook.",
            PRESET,
            ::graphicButton
        ),
        ComponentTemplate(
            "model-viewport", "Model viewport",
            "Layer with a model inside it, centred and clipped to the layer.", PRESET,
            ::modelViewport
        ),
        ComponentTemplate(
            "window", "Window frame",
            "Layer with a backdrop, a title bar caption and a close button; fill in the " +
                "graphic ids from the cache.", PRESET,
            ::windowFrame
        )
    )

    private val index: Map<String, ComponentTemplate> = all.associateBy { it.id }

    fun byId(id: String): ComponentTemplate? = index[id]

    val byCategory: Map<String, List<ComponentTemplate>> = all.groupBy { it.category }

    /** The basic template for a component type, or null for the layout-only types. */
    fun forType(type: Int): ComponentTemplate? = when (type) {
        ComponentType.CONTAINER -> byId("layer")
        ComponentType.TYPE_2 -> byId("unclipped")
        ComponentType.FIGURE -> byId("rectangle")
        ComponentType.TEXT -> byId("text")
        ComponentType.GRAPHIC -> byId("graphic")
        ComponentType.MODEL -> byId("model")
        ComponentType.LINE -> byId("line")
        else -> null
    }

    /**
     * Turns an existing subtree into a reusable template, which is how a real RS
     * window frame is obtained: point this at a cache interface that already has
     * one rather than hard coding graphic ids that differ between caches.
     *
     * The extracted components are deep copies, so the template is independent of
     * the document it came from. The subtree root is re-parented to [ATTACH].
     */
    fun extract(
        document: InterfaceDocument,
        rootIndex: Int,
        id: String,
        name: String,
        description: String = "Extracted from interface ${document.id} component $rootIndex",
        category: String = PRESET
    ): ComponentTemplate? {
        val subtree = document.subtree(rootIndex)
        if (subtree.isEmpty()) {
            return null
        }
        // Positions within the template list, so the copies point at each other.
        val positions = HashMap<Int, Int>(subtree.size)
        subtree.forEachIndexed { offset, node -> positions[node] = offset }
        val sources = subtree.map { document.components[it] }
        val parents = subtree.map { node ->
            if (node == rootIndex) ATTACH else positions[document.components[node].parent] ?: ATTACH
        }
        return ComponentTemplate(id, name, description, category) {
            sources.mapIndexed { offset, source ->
                TemplateComponent(ComponentDefinitions.deepCopy(source), parents[offset])
            }
        }
    }

    /** [extract] straight from the cache, without opening a document first. */
    fun extract(
        cache: Cache,
        interfaceId: Int,
        rootIndex: Int,
        id: String,
        name: String,
        description: String = "Extracted from interface $interfaceId component $rootIndex",
        category: String = PRESET
    ): ComponentTemplate? =
        extract(InterfaceDocument.decode(cache, interfaceId), rootIndex, id, name, description, category)

    // --- Preset bodies -----------------------------------------------------

    private fun single(definition: InterfaceComponentDefinition) = listOf(TemplateComponent(definition))

    private fun scrollLayer(): InterfaceComponentDefinition {
        val layer = defaults(ComponentType.CONTAINER)
        layer.baseWidth = SCROLL_WIDTH
        layer.baseHeight = SCROLL_HEIGHT
        layer.scrollWidth = SCROLL_WIDTH
        layer.scrollHeight = SCROLL_HEIGHT * 2
        return layer
    }

    private fun backdrop(): InterfaceComponentDefinition {
        val rectangle = defaults(ComponentType.FIGURE)
        // Inset mode with a base size of 0 resolves to the parent's full extent.
        rectangle.baseWidth = 0
        rectangle.baseHeight = 0
        rectangle.horizontalSizeMode = LayoutMode.SIZE_INSET.toByte()
        rectangle.verticalSizeMode = LayoutMode.SIZE_INSET.toByte()
        rectangle.colour = PANEL_GREY
        rectangle.filled = true
        return rectangle
    }

    private fun label(): InterfaceComponentDefinition {
        val text = defaults(ComponentType.TEXT)
        text.baseWidth = 0
        text.horizontalSizeMode = LayoutMode.SIZE_INSET.toByte()
        text.horizontalTextAlign = 1
        text.verticalTextAlign = 1
        return text
    }

    private fun graphicButton(): List<TemplateComponent> {
        val graphic = defaults(ComponentType.GRAPHIC)
        graphic.baseWidth = BUTTON_WIDTH
        graphic.baseHeight = BUTTON_HEIGHT
        graphic.name = "Button"
        graphic.options = arrayOf("Ok")
        // The client only delivers clicks for the setting bits an interface author
        // sets; leave them at 0 here rather than guessing, and let the developer
        // author them alongside the hook.
        graphic.setting.setting = 0
        val caption = defaults(ComponentType.TEXT)
        caption.baseWidth = 0
        caption.baseHeight = 0
        caption.horizontalSizeMode = LayoutMode.SIZE_INSET.toByte()
        caption.verticalSizeMode = LayoutMode.SIZE_INSET.toByte()
        caption.horizontalTextAlign = 1
        caption.verticalTextAlign = 1
        caption.text = "Ok"
        // A graphic is not a container, so a caption parented to it would never be
        // laid out. The pair goes side by side under the insertion parent instead.
        return listOf(TemplateComponent(graphic), TemplateComponent(caption))
    }

    private fun modelViewport(): List<TemplateComponent> {
        val layer = defaults(ComponentType.CONTAINER)
        layer.baseWidth = VIEWPORT_SIZE
        layer.baseHeight = VIEWPORT_SIZE
        val model = defaults(ComponentType.MODEL)
        model.baseWidth = 0
        model.baseHeight = 0
        model.horizontalSizeMode = LayoutMode.SIZE_INSET.toByte()
        model.verticalSizeMode = LayoutMode.SIZE_INSET.toByte()
        return listOf(TemplateComponent(layer), TemplateComponent(model, parent = 0))
    }

    private fun windowFrame(): List<TemplateComponent> {
        val root = defaults(ComponentType.CONTAINER)
        root.baseWidth = WINDOW_WIDTH
        root.baseHeight = WINDOW_HEIGHT
        root.name = "Window"
        val background = backdrop()
        val border = defaults(ComponentType.FIGURE)
        border.baseWidth = 0
        border.baseHeight = 0
        border.horizontalSizeMode = LayoutMode.SIZE_INSET.toByte()
        border.verticalSizeMode = LayoutMode.SIZE_INSET.toByte()
        border.colour = BORDER_GREY
        border.filled = false
        val title = label()
        title.baseHeight = TITLE_HEIGHT
        title.verticalSizeMode = LayoutMode.SIZE_ABSOLUTE.toByte()
        title.text = "Window"
        val close = defaults(ComponentType.GRAPHIC)
        close.baseWidth = CLOSE_SIZE
        close.baseHeight = CLOSE_SIZE
        close.basePositionX = CLOSE_INSET
        close.basePositionY = CLOSE_INSET
        close.horizontalPositionMode = LayoutMode.POSITION_END.toByte()
        close.name = "Close"
        close.options = arrayOf("Close")
        return listOf(
            TemplateComponent(root),
            TemplateComponent(background, parent = 0),
            TemplateComponent(border, parent = 0),
            TemplateComponent(title, parent = 0),
            TemplateComponent(close, parent = 0)
        )
    }

    private const val DEFAULT_SIZE = 100
    private const val TEXT_HEIGHT = 16
    private const val GRAPHIC_SIZE = 32
    private const val MODEL_SCALE = 100
    private const val SCROLL_WIDTH = 160
    private const val SCROLL_HEIGHT = 200
    private const val BUTTON_WIDTH = 72
    private const val BUTTON_HEIGHT = 24
    private const val VIEWPORT_SIZE = 128
    private const val WINDOW_WIDTH = 256
    private const val WINDOW_HEIGHT = 192
    private const val TITLE_HEIGHT = 20
    private const val CLOSE_SIZE = 16
    private const val CLOSE_INSET = 4
}
