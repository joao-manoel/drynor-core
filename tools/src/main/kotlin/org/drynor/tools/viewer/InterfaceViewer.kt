package org.drynor.tools.viewer

import imgui.ImDrawList
import imgui.ImGui
import imgui.flag.ImGuiCol
import imgui.flag.ImGuiDir
import imgui.flag.ImGuiDockNodeFlags
import imgui.flag.ImGuiMouseButton
import imgui.internal.ImGui as ImGuiInternal
import imgui.internal.flag.ImGuiDockNodeFlags as InternalDockNodeFlags
import imgui.type.ImInt
import imgui.type.ImString
import org.drynor.cs2.Cs2Analyzer
import org.drynor.cs2.Cs2HotReload
import org.drynor.cs2.OpKind
import org.drynor.cs2.vm.Cs2Event
import org.drynor.cs2.vm.Cs2Hook
import org.drynor.cs2.vm.Cs2Values
import org.drynor.cs2.vm.Cs2HookDispatcher
import org.drynor.tools.engine.Engine
import org.drynor.tools.engine.render.Canvas2D
import org.drynor.tools.engine.render.ObjIcons
import org.drynor.tools.engine.render.RenderTarget
import org.drynor.tools.engine.window.WindowConfig
import org.drynor.tools.rsinterface.ComponentState
import org.drynor.tools.rsinterface.ComponentTemplates
import org.drynor.tools.rsinterface.ComponentType
import org.drynor.tools.rsinterface.FontCache
import org.drynor.tools.rsinterface.GraphicCache
import org.drynor.tools.rsinterface.InterfaceAssets
import org.drynor.tools.rsinterface.InterfaceCs2Host
import org.drynor.tools.rsinterface.InterfaceDocument
import org.drynor.tools.rsinterface.InterfaceEvent
import org.drynor.tools.rsinterface.InterfaceInstance
import org.drynor.tools.rsinterface.InterfaceInteraction
import org.drynor.tools.rsinterface.InterfaceModelRenderer
import org.drynor.tools.rsinterface.InterfacePointer
import org.drynor.tools.rsinterface.InterfaceRenderer
import org.drynor.tools.rsinterface.InterfaceSourceTree
import org.drynor.tools.rsinterface.InterfaceState
import org.drynor.tools.rsinterface.InterfaceStore
import org.drynor.tools.rsinterface.InterfaceViewport
import org.drynor.tools.rsinterface.RsObjIconFont
import org.drynor.tools.rsinterface.SourceWatch
import org.drynor.tools.ui.AssetPicker
import org.drynor.tools.ui.CommandPalette
import org.drynor.tools.ui.Cs2HookPanel
import org.drynor.tools.ui.Cs2ReloadPanel
import org.drynor.tools.ui.GizmoBox
import org.drynor.tools.ui.GizmoHandle
import org.drynor.tools.ui.ImGuiLayer
import org.drynor.tools.ui.InterfaceEditContext
import org.drynor.tools.ui.InterfaceEditorPanel
import org.drynor.tools.ui.InterfaceEventPanel
import org.drynor.tools.ui.InterfaceInspectorPanel
import org.drynor.tools.ui.InterfaceLibraryPanel
import org.drynor.tools.ui.InterfaceMenuOverlay
import org.drynor.tools.ui.InterfaceStatePanel
import org.drynor.tools.ui.InterfaceValidationPanel
import org.drynor.tools.ui.SelectionGizmo
import org.drynor.tools.ui.StagePanel
import org.drynor.tools.ui.ImGuiText
import org.lwjgl.glfw.GLFW.GLFW_ARROW_CURSOR
import org.lwjgl.glfw.GLFW.GLFW_KEY_A
import org.lwjgl.glfw.GLFW.GLFW_KEY_C
import org.lwjgl.glfw.GLFW.GLFW_KEY_D
import org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE
import org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE
import org.lwjgl.glfw.GLFW.GLFW_KEY_F1
import org.lwjgl.glfw.GLFW.GLFW_KEY_F5
import org.lwjgl.glfw.GLFW.GLFW_KEY_F6
import org.lwjgl.glfw.GLFW.GLFW_KEY_F7
import org.lwjgl.glfw.GLFW.GLFW_KEY_L
import org.lwjgl.glfw.GLFW.GLFW_KEY_N
import org.lwjgl.glfw.GLFW.GLFW_KEY_P
import org.lwjgl.glfw.GLFW.GLFW_KEY_S
import org.lwjgl.glfw.GLFW.GLFW_KEY_V
import org.lwjgl.glfw.GLFW.GLFW_KEY_Y
import org.lwjgl.glfw.GLFW.GLFW_KEY_Z
import org.lwjgl.glfw.GLFW.GLFW_MOD_ALT
import org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL
import org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT
import org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT
import org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_MIDDLE
import org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT
import org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT
import org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT
import org.lwjgl.opengl.GL11.glClear
import org.lwjgl.opengl.GL11.glClearColor
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.definition.data.InterfaceDefinition
import world.gregs.voidps.cache.definition.decoder.InterfaceDecoder
import world.gregs.voidps.cache.definition.decoder.ItemDecoder
import java.io.File

/** What the pointer and the keyboard mean while they are over the stage. */
enum class EditorMode {
    /** Clicking selects, the gizmo moves and resizes, no hook is dispatched. */
    DESIGN,

    /** The interface behaves like the client: hooks fire from real input. */
    PLAY,
}

/**
 * The interface editor.
 *
 * This class is the shell: it owns the window, the GL objects, the CS2 runtime
 * and the panels, and it decides the order things happen in each frame. All the
 * actual work lives elsewhere - the panels draw, [InterfaceDocument] edits,
 * [InterfaceInteraction] runs the interface. What is genuinely hard here, and
 * what the rest of this comment is about, is the three orderings that have to be
 * right.
 *
 * ### The frame order
 *
 * The interface is drawn into an off-screen [RenderTarget] that [StagePanel]
 * shows as a texture. ImGui only samples that texture when its draw data is
 * submitted, so the target can be - and must be - filled *after* every panel is
 * built and *before* `imGui.endFrame()`. That buys a stage with zero frames of
 * lag whose contents already reflect this frame's input, and it is why
 * [renderStage] sits between [ImGuiLayer.endDockspace] and
 * [ImGuiLayer.endFrame] rather than anywhere more obvious.
 *
 * The stage panel is also submitted *first* among the panels, because it is what
 * computes the stage-space pointer, and the interaction runtime has to be fed
 * before the panels that display its state - otherwise the hover chain and the
 * event trace are always one frame stale.
 *
 * ### Who gets the pointer
 *
 * Three things want it and they overlap:
 *
 * - **ImGui** claims the mouse whenever it is over any window, and the stage
 *   *is* an ImGui window, so `wantCaptureMouse` is useless here - it is true
 *   exactly when the interface should be getting the pointer.
 *   [StagePanel.hovered] is the right question: it is the stage's own invisible
 *   button being hovered, which is false when a popup, the option menu or
 *   another window is on top.
 * - **The gizmo** takes priority inside the selection's handles in Design mode,
 *   which is why the pick and the gizmo cannot both act on one press.
 * - **The interaction runtime** gets everything else, but only in Play mode, and
 *   it keeps getting it while a press that started on the stage is held, so a
 *   drag that leaves the panel still completes.
 *
 * The keyboard is simpler but has the same trap: `wantCaptureKeyboard` goes true
 * as soon as ImGui's keyboard navigation activates, which would kill every
 * shortcut. `wantTextInput` is the question actually being asked - "is a text
 * field swallowing this" - so that is what gates both the shortcuts and the key
 * hooks.
 *
 * ### Why the load scripts keep re-running
 *
 * [InterfaceDocument] re-seeds every [ComponentState] from its definition after
 * any edit, which throws away everything the CS2 load scripts did - the graphics
 * they set, the text they wrote. Re-running them is the only way to put it back,
 * so any edit sets [scriptsDirty] and the next frame re-runs them before
 * anything is drawn. The cost is one dispatch per component that has a load
 * script and it is reported in the stage's status line, because a surprise in
 * there is the first place a slow frame would show up.
 */
class InterfaceViewer(
    private val cache: Cache,
    startInterfaceId: Int = 0,
    private val startup: InterfaceStartup = InterfaceStartup(),
) : Engine(WindowConfig(title = "Drynor Interface Editor", width = 1600, height = 900)) {

    // --- Rendering ---------------------------------------------------------

    private lateinit var imGui: ImGuiLayer
    private lateinit var canvas: Canvas2D
    private lateinit var graphics: GraphicCache
    private lateinit var fonts: FontCache
    private lateinit var modelRenderer: InterfaceModelRenderer
    private lateinit var icons: ObjIcons
    private lateinit var renderer: InterfaceRenderer
    private val stageTarget = RenderTarget()
    private lateinit var stagePanel: StagePanel

    // --- Model -------------------------------------------------------------

    private lateinit var store: InterfaceStore
    private lateinit var assets: InterfaceAssets
    private val context = InterfaceEditContext()
    private var frameInstance: InterfaceInstance? = null
    private var currentId = startInterfaceId.coerceAtLeast(0)

    /** Set by any edit; cleared once the load scripts have been re-run. */
    private var scriptsDirty = false
    private var loadScriptMillis = 0L

    // --- Live source -------------------------------------------------------

    // Components and clientscripts only. Graphics, fonts and models always come from the packed
    // cache, which is the form a pack turns the tree's copies back into.
    private var source: InterfaceSourceTree? = null
    private val sourceWatch = SourceWatch()

    // A save arrives as a burst of events around a half-written file, so the re-read waits for
    // the writes to stop rather than parsing the first one. 0 is nothing pending.
    private var sourceChangedAt = 0L
    private var sourceReloads = 0

    private val compiledScripts = HashSet<Int>()
    private var scriptReloads = 0
    private var lastLiveReload = 0L

    // --- CS2 ---------------------------------------------------------------

    private lateinit var scripts: Cs2Analyzer
    private lateinit var reload: Cs2HotReload
    private lateinit var state: InterfaceState
    private lateinit var cs2Host: InterfaceCs2Host
    private lateinit var dispatcher: Cs2HookDispatcher
    private lateinit var interaction: InterfaceInteraction

    // --- Panels ------------------------------------------------------------

    private lateinit var library: InterfaceLibraryPanel
    private lateinit var tree: InterfaceEditorPanel
    private lateinit var inspector: InterfaceInspectorPanel
    private lateinit var eventPanel: InterfaceEventPanel
    private lateinit var hookPanel: Cs2HookPanel
    private lateinit var reloadPanel: Cs2ReloadPanel
    private lateinit var statePanel: InterfaceStatePanel
    private lateinit var validation: InterfaceValidationPanel
    private lateinit var picker: AssetPicker
    private lateinit var menuOverlay: InterfaceMenuOverlay
    private val palette = CommandPalette()
    private val gizmo = SelectionGizmo()

    // --- Editor state ------------------------------------------------------

    private var mode = EditorMode.DESIGN
    private var status = ""
    private var stageHeld = false
    private var gizmoPreview: GizmoBox? = null
    private val heldKeys = LinkedHashSet<Int>()

    private var layoutBuilt = false
    private var layoutExists = false

    /**
     * Frames left to force every panel into its node.
     *
     * The dock builder writes the layout into ImGui's settings, but a panel that
     * calls `setNextWindowPos` on its own first frame - [Cs2ReloadPanel] does -
     * lands as a floating window before those settings are ever applied. Pinning
     * the dock id explicitly for the first few frames after a build is what makes
     * the default layout hold for every panel rather than most of them.
     */
    private var dockFrames = 0
    private val dockNodes = HashMap<String, Int>()
    private var showDialog = false
    private var showSourceDialog = false
    private val sourcePath = ImString(320)
    private val dialogId = ImInt(0)
    private val dialogPath = ImString(320)
    private var dialogSource = 0

    // --- Interface index for the library ------------------------------------

    private lateinit var decoder: InterfaceDecoder
    private lateinit var definitions: Array<InterfaceDefinition>
    private lateinit var decodedIds: BooleanArray

    // ------------------------------------------------------------------- init

    override fun onInit() {
        // Checked before ImGui reads it, so a first run can lay the panels out.
        layoutExists = File(LAYOUT_FILE).isFile

        imGui = ImGuiLayer(window)
        imGui.init()
        imGui.applyEditorTheme()

        canvas = Canvas2D(loadShaderSource("/shaders/ui.vert"), loadShaderSource("/shaders/ui.frag"))
        graphics = GraphicCache(cache)
        fonts = FontCache(cache, graphics)
        modelRenderer = InterfaceModelRenderer(cache, window, renderSystem, colorShader, texturedShader)
        icons = ObjIcons(cache, RsObjIconFont(fonts))
        renderer = InterfaceRenderer(canvas, graphics, fonts, modelRenderer, icons)
        stagePanel = StagePanel(stageTarget)

        decoder = InterfaceDecoder()
        definitions = decoder.create(decoder.size(cache) + 1)
        decodedIds = BooleanArray(definitions.size)

        store = InterfaceStore(cache)
        assets = InterfaceAssets.of(cache)
        seedTemplateDefaults()

        // The analyzer resolves every script's return signature and parameter
        // types once, which is what lets the virtual machine keep its stacks
        // straight. The hot reload wraps it and is what the dispatcher talks to,
        // so a recompiled script is live without anything being invalidated.
        scripts = Cs2Analyzer(cache)
        scripts.analyse()
        reload = Cs2HotReload(cache, scripts)
        cs2Host = InterfaceCs2Host(
            interfaces = { id -> if (id == currentId) frameInstance else null },
            enums = { id -> Cache.enums.getOrNull(id) },
            structs = { id -> Cache.structs.getOrNull(id) },
            params = { id -> Cache.params.getOrNull(id) },
        )
        cs2Host.paragraphLines = { text, width, fontId -> fonts.font(fontId)?.lineCount(text, width) ?: 1 }
        dispatcher = Cs2HookDispatcher(reload, cs2Host)
        interaction = InterfaceInteraction({ frameInstance }, dispatcher, cs2Host)
        interaction.liveMode = false
        state = InterfaceState(cs2Host)
        reload.onReload = { event ->
            // Hooks a previous run bound with IF_SETON* survive a reload, so the
            // interface has to be rebuilt for the edit to be visible at all.
            if (event.ok) {
                scriptsDirty = true
                if (event.scriptId >= 0) {
                    scriptReloads++
                    lastLiveReload = System.currentTimeMillis()
                }
            }
            status = event.message
            if (event.staleCallers.isNotEmpty()) {
                status = "${event.message} - ${event.staleCallers.size} callers need a re-analyse"
            }
        }

        context.onEdit = {
            scriptsDirty = true
        }

        // Start watching straight away when the source folder is there. Hot reload
        // is only "edit in the editor and see it" if nothing has to be armed first;
        // having to press Start every launch is the whole friction the feature
        // exists to remove. A missing folder just leaves the watcher stopped, which
        // the panel reports.
        useSource(startupTree(), announce = false)
        if (reload.project.folder.isDirectory && !reload.start()) {
            status = reload.watcherError ?: "could not watch ${reload.project.folder.path}"
        }

        buildPanels()
        registerCommands()
        applyStartup()
        openInterface(currentId)
    }

    // ----------------------------------------------------------- live source

    private fun startupTree(): File? = when {
        startup.packedOnly -> null
        startup.source != null -> startup.source
        else -> InterfaceSourceTree.discover()
    }

    private fun useSource(root: File?, announce: Boolean = true) {
        if (root == null) {
            source = null
            sourceWatch.stop()
            if (announce) {
                status = "live source off; reading the packed cache"
                openInterface(currentId)
            }
            return
        }
        val tree = try {
            InterfaceSourceTree.open(root)
        } catch (e: Exception) {
            status = "not a cache source tree: ${root.path} (${e.message})"
            return
        }
        source = tree
        if (!reload.useFolder(tree.clientscripts)) {
            status = reload.watcherError ?: "cannot compile from ${tree.clientscripts.path}"
        }
        sourcePath.set(tree.root.path)
        if (announce) {
            status = "live source: ${tree.root.path}"
            openInterface(currentId)
        }
    }

    private fun watchSource() {
        val tree = source ?: return
        val directory = tree.directory(currentId)
        if (sourceWatch.directories != listOf(directory)) {
            sourceWatch.watch(listOf(directory))
        }
    }

    private fun reloadFromSource(): String {
        val tree = source ?: return "not in live source mode"
        if (!tree.has(currentId)) return "${tree.name} has no interface $currentId"
        val selection = context.primary
        return try {
            tree.invalidate()
            attach(InterfaceDocument(currentId, tree.components(currentId)))
            if (selection in 0 until context.componentCount) {
                context.setSelection(listOf(selection))
            }
            sourceReloads++
            lastLiveReload = System.currentTimeMillis()
            "interface $currentId re-read from ${tree.name}"
        } catch (e: Exception) {
            "interface $currentId: ${e.message ?: e::class.simpleName}"
        }
    }

    private fun pollSource() {
        val tree = source ?: return
        val directory = tree.directory(currentId)
        if (sourceWatch.poll().any { it.parentFile == directory }) {
            sourceChangedAt = System.currentTimeMillis()
        }
        if (sourceChangedAt != 0L && System.currentTimeMillis() - sourceChangedAt >= SOURCE_DEBOUNCE) {
            sourceChangedAt = 0L
            status = reloadFromSource()
        }
    }

    /**
     * Without this a fresh session would run the *cache's* bytecode for every script nobody has
     * saved yet, so an edit made before launch would be invisible until it was touched again. The
     * closure is re-read after each install, because compiling a script can add a call its cached
     * bytecode never had.
     */
    private fun compileInterfaceScripts(): Int {
        source ?: return 0
        val pending = ArrayDeque<Int>()
        val seen = HashSet<Int>()
        hookPanel.scripts().forEach { if (seen.add(it)) pending.add(it) }
        var compiled = 0
        while (pending.isNotEmpty() && seen.size <= SCRIPT_CLOSURE_LIMIT) {
            val id = pending.removeFirst()
            if (reload.project.hasSource(id) && compiledScripts.add(id) && reload.reload(id).ok) {
                compiled++
            }
            val script = scripts.script(id) ?: continue
            for (instruction in script.instructions) {
                if (instruction.op.kind == OpKind.GOSUB && seen.add(instruction.intOperand)) {
                    pending.add(instruction.intOperand)
                }
            }
        }
        return compiled
    }

    private fun applyStartup() {
        val reports = ArrayList<String>()
        startup.vars.forEach { reports.add(state.set(it)) }
        startup.stats.forEach { reports.add(state.setStat(it)) }
        startup.runs.forEach { reports.add(state.addRun(it)) }
        if (reports.isNotEmpty()) {
            reports.forEach { println("interface state: $it") }
            status = reports.last()
        }
    }

    private fun runStateScript(script: InterfaceState.ScriptRun): String = try {
        dispatcher.dispatch(Cs2Hook(script.scriptId, Cs2Values(script.ints, script.strings)), Cs2Event())
        "ran ${script.label}"
    } catch (e: Exception) {
        "${script.label} failed: ${e.message ?: e::class.simpleName}"
    }

    private fun liveStatus(): String {
        val tree = source ?: return ""
        val reloads = sourceReloads + scriptReloads
        if (reloads == 0 || lastLiveReload == 0L) {
            return "live: ${tree.name}"
        }
        val seconds = (System.currentTimeMillis() - lastLiveReload) / 1000.0
        return "live: ${tree.name}, $reloads reloads, last ${"%.1f".format(seconds)} s ago"
    }

    /**
     * Points the "add component" templates at ids that exist in this cache.
     * Guessing one that does not makes every new graphic or text component draw
     * nothing, which reads as the editor being broken rather than as a default.
     */
    private fun seedTemplateDefaults() {
        for (id in 0 until minOf(graphics.count, TEMPLATE_SCAN)) {
            if (graphics.definition(id) != null) {
                ComponentTemplates.defaultGraphicId = id
                break
            }
        }
        for (id in 0 until minOf(fonts.count, TEMPLATE_SCAN)) {
            if (fonts.font(id) != null) {
                ComponentTemplates.defaultFontId = id
                renderer.fallbackFontId = id
                break
            }
        }
    }

    private fun buildPanels() {
        val itemCount = ItemDecoder().size(cache) + 1
        picker = AssetPicker(graphics, fonts, { id -> icons.definition(id) }, itemCount, icons)

        library = InterfaceLibraryPanel(definitions.size) { id -> definitionOf(id) }
        library.onOpen = { id -> openInterface(id) }

        tree = InterfaceEditorPanel(context)
        inspector = InterfaceInspectorPanel(context, picker, ::runHook, ::openScript)
        eventPanel = InterfaceEventPanel(context, interaction, cs2Host)
        hookPanel = Cs2HookPanel(context, cs2Host, ::runHook, ::openScript) { scriptsDirty = true }
        reloadPanel = Cs2ReloadPanel(reload, { hookPanel.scripts() }, ::liveStatus)
        // The panel no longer forces its own position, so it docks like any other
        // and belongs in the default layout: editing a script in VS Code and seeing
        // the interface pick it up is a headline feature, not something to go
        // looking for in a menu.
        reloadPanel.visible = true
        statePanel = InterfaceStatePanel(state, { scriptsDirty = true }, ::runStateScript)
        validation = InterfaceValidationPanel(context, store, assets)
        menuOverlay = InterfaceMenuOverlay(interaction)

        stagePanel.overlay = { drawStageOverlay() }
        stagePanel.toolbar = { drawStageToolbar() }
    }

    private fun definitionOf(id: Int): InterfaceDefinition? {
        if (id < 0 || id >= definitions.size) {
            return null
        }
        if (!decodedIds[id]) {
            decodedIds[id] = true
            try {
                decoder.load(definitions, cache, id)
            } catch (e: Exception) {
                return null
            }
        }
        return if (definitions[id].components == null) null else definitions[id]
    }

    // ----------------------------------------------------------- open / save

    private fun openInterface(id: Int) {
        val tree = source
        val document = if (tree != null && tree.has(id)) {
            try {
                InterfaceDocument(id, tree.components(id))
            } catch (e: Exception) {
                status = "${tree.name} interface $id: ${e.message}; falling back to the packed cache"
                fromCache(id)
            }
        } else {
            fromCache(id)
        }
        attach(document)
        watchSource()
    }

    private fun fromCache(id: Int): InterfaceDocument = try {
        store.open(id)
    } catch (e: Exception) {
        status = "could not decode interface $id: ${e.message}"
        InterfaceDocument.empty(id)
    }

    private fun attach(document: InterfaceDocument) {
        currentId = document.id
        context.attach(document)
        document.onRebuild = { instance ->
            // Fired from inside instance(); point the frame at the new one before
            // anything - a load script asking the host for this interface has to
            // reach the instance that is about to be drawn.
            frameInstance = instance
            scriptsDirty = true
        }
        frameInstance = document.instance()
        interaction.reset()
        cs2Host.clearHooks()
        cs2Host.log.clear()
        val compiled = compileInterfaceScripts()
        frameInstance?.let { runLoadScripts(it) }
        scriptsDirty = false
        library.currentId = document.id
        library.noteOpened(document.id)
        gizmo.cancel()
        updateTitle()
        status = "interface ${document.id}: ${document.componentCount} components, last id ${store.lastInterfaceId()}" +
            if (compiled > 0) "; $compiled scripts compiled from source" else ""
    }

    /**
     * Runs every component's load script, which is how an interface builds
     * itself - the cache holds the skeleton and the scripts fill it in. Dispatch
     * goes through the interaction runtime so the run shows up in the trace, with
     * live mode forced on because this is an explicit request.
     */
    private fun runLoadScripts(instance: InterfaceInstance) {
        val started = System.nanoTime()
        cs2Host.log.clear()
        // Resolve the box model first: the client only runs a load hook on an
        // interface it has already drawn, and a script that asks how tall a
        // component is has to get the answer the client would have given it.
        if (stageTarget.width > 0 && stageTarget.height > 0) {
            instance.layout(stageTarget.width, stageTarget.height)
        } else {
            instance.layoutDeclared()
        }
        val previous = interaction.liveMode
        interaction.liveMode = true
        try {
            for (component in instance.components) {
                if (component.definition.onLoadScript == null) {
                    continue
                }
                interaction.dispatch(component, InterfaceEvent.LOAD, Cs2Event(source = component.hash))
            }
        } finally {
            interaction.liveMode = previous
        }
        state.runs.forEach { runStateScript(it) }
        loadScriptMillis = (System.nanoTime() - started) / 1_000_000
    }

    private fun runHook(index: Int, event: InterfaceEvent) {
        val state = frameInstance?.state(index) ?: return
        val previous = interaction.liveMode
        interaction.liveMode = true
        try {
            val ran = interaction.dispatch(
                state,
                event,
                Cs2Event(source = state.hash, clickedOption = 1, optionName = state.opBase),
            )
            status = if (ran) "$event ran on component $index" else "$event has no hook on component $index"
        } finally {
            interaction.liveMode = previous
        }
    }

    private fun openScript(scriptId: Int) {
        val result = reload.project.exportSource(scriptId)
        status = result.message
        reloadPanel.visible = true
    }

    private fun saveSidecar() {
        val document = context.document ?: return
        status = try {
            val file = store.saveSidecar(document)
            document.markSaved()
            updateTitle()
            "saved ${file.path}"
        } catch (e: Exception) {
            "save failed: ${e.message}"
        }
    }

    private fun saveToCache() {
        val document = context.document ?: return
        status = try {
            val result = store.save(document)
            updateTitle()
            "wrote ${result.files} files to the cache" +
                if (result.warnings.isEmpty()) "" else "; ${result.warnings.joinToString("; ")}"
        } catch (e: Exception) {
            "cache save failed: ${e.message}"
        }
    }

    private fun updateTitle() {
        val document = context.document
        val dirty = if (document?.dirty == true) " *" else ""
        window.setTitle("Drynor Interface Editor - ${document?.id ?: "-"}$dirty")
    }

    // ------------------------------------------------------------------ frame

    override fun onInput() {
        // The base engine grabs the cursor on right click for its fly cameras; a
        // 2D editor always wants a visible pointer.
        if (input.mouseCaptured) {
            input.releaseMouse()
        }
    }

    override fun onRender() {
        glClearColor(BACKGROUND, BACKGROUND, BACKGROUND, 1f)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)

        pollSource()
        frameInstance = context.document?.instance()
        if (scriptsDirty) {
            frameInstance?.let { runLoadScripts(it) }
            scriptsDirty = false
        }
        context.frame(frameInstance)
        interaction.liveMode = mode == EditorMode.PLAY

        imGui.beginFrame()
        imGui.beginDockspace()
        if (!layoutBuilt) {
            layoutBuilt = true
            if (!layoutExists) {
                buildDefaultLayout()
            }
        }
        drawMenuBar()

        // The stage first: it computes the pointer everything else reads.
        dock("Stage")
        val drawStage = stagePanel.render(fps)
        routePointer(drawStage)
        routeKeyboard()

        dock("Interfaces")
        library.render()
        tree.stageWidth = stageTarget.width
        tree.stageHeight = stageTarget.height
        dock("Tree")
        tree.render()
        dock("Inspector")
        inspector.render()
        eventPanel.playMode = mode == EditorMode.PLAY
        dock("Interaction")
        eventPanel.render()
        dock("CS2 Hooks")
        hookPanel.render()
        dock("Validation")
        validation.render()
        dock("CS2 Hot Reload")
        reloadPanel.render(window.height)
        dock("State")
        statePanel.render(window.height)
        menuOverlay.render(stagePanel::screenX, stagePanel::screenY)
        drawDialog()
        palette.render()

        imGui.endDockspace()
        if (dockFrames > 0) {
            dockFrames--
        }
        if (drawStage) {
            renderStage()
        }
        imGui.endFrame()
    }

    /**
     * Fills the render target. Called after every panel is built and before the
     * ImGui draw data is submitted, so what lands here is what the stage shows
     * this frame.
     */
    private fun renderStage() {
        val width = stageTarget.width
        val height = stageTarget.height
        // Model components switch the viewport themselves and flip their scissor
        // box against the surface height; without this they aim at the window and
        // every model viewport lands off screen.
        modelRenderer.targetWidth = width
        modelRenderer.targetHeight = height

        stageTarget.begin()
        renderSystem.enableDepthWrite()
        // A transparent clear is what lets the panel's checkerboard show through
        // the parts of the stage the interface does not cover.
        glClearColor(0f, 0f, 0f, 0f)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)

        canvas.begin(width, height)
        frameInstance?.let { renderer.render(it, 0, 0, width, height) }
        canvas.end()

        stageTarget.end(window.width, window.height)
        modelRenderer.targetWidth = 0
        modelRenderer.targetHeight = 0
    }

    // ---------------------------------------------------------------- routing

    private fun routePointer(drawStage: Boolean) {
        val io = ImGui.getIO()
        // Not wantCaptureMouse: the stage is an ImGui window, so that flag is true
        // exactly when the interface should be receiving the pointer.
        val overStage = drawStage && stagePanel.hovered && !menuOverlay.hovered
        if (overStage && input.mouse.isButtonPressed(GLFW_MOUSE_BUTTON_LEFT)) {
            stageHeld = true
        }
        if (!input.mouse.isButtonDown(GLFW_MOUSE_BUTTON_LEFT)) {
            stageHeld = false
        }
        // A press that began on the stage keeps feeding it, so a drag that leaves
        // the panel still completes rather than sticking down forever.
        val active = overStage || stageHeld
        val live = mode == EditorMode.PLAY

        val wheel = if (overStage && !io.keyCtrl) input.mouse.scrollY.toInt() else 0
        val pointer = InterfacePointer(
            x = if (active) stagePanel.pointerX else -1,
            y = if (active) stagePanel.pointerY else -1,
            left = live && active && input.mouse.isButtonDown(GLFW_MOUSE_BUTTON_LEFT),
            right = live && active && input.mouse.isButtonDown(GLFW_MOUSE_BUTTON_RIGHT),
            middle = live && active && input.mouse.isButtonDown(GLFW_MOUSE_BUTTON_MIDDLE),
            wheel = if (live) wheel else 0,
        )
        interaction.update(
            InterfaceViewport(0, 0, stageTarget.width, stageTarget.height),
            pointer,
            (deltaTime * 1000f).toLong(),
        )

        if (!live && overStage && !gizmo.dragging &&
            input.mouse.isButtonPressed(GLFW_MOUSE_BUTTON_LEFT)
        ) {
            pickComponent(io.keyCtrl)
        }
    }

    /**
     * Design-mode picking. Hits arrive back to front, so the last is the topmost;
     * a bare layer is only picked when nothing it contains was hit, which is what
     * makes clicking a button select the button rather than the panel behind it.
     *
     * Runtime components are skipped, and that is the whole reason this is not a
     * one-liner. A `CC_CREATE` child exists only in the live [InterfaceInstance]:
     * it has no entry in the document, so [InterfaceEditContext.definition] answers
     * null for it, the inspector has nothing to show, the tree has no row to
     * highlight and saving would not write it out. Interfaces whose load scripts
     * build anything - a dropdown, a scrollbar, the five shadow copies of a label -
     * put such a child on top of every component worth editing, so picking the
     * topmost hit outright selects an index the rest of the editor cannot resolve
     * and the click silently does nothing at all.
     *
     * What the developer means by clicking a runtime child is the authored component
     * that owns it, so the search drops to the topmost *editable* hit and, failing
     * that, walks up from the runtime hit to its nearest authored ancestor.
     */
    private fun pickComponent(additive: Boolean) {
        val instance = frameInstance ?: return
        val hits = instance.hitTest(
            0, 0, stageTarget.width, stageTarget.height,
            stagePanel.pointerX, stagePanel.pointerY,
        )
        val editable = hits.filter { !it.runtime }
        val picked = editable.lastOrNull { it.type != ComponentType.CONTAINER }
            ?: editable.lastOrNull()
            ?: hits.lastOrNull()?.let { hit -> instance.ancestors(hit).firstOrNull { !it.runtime } }
        if (picked == null) {
            if (!additive) {
                context.clearSelection()
            }
            return
        }
        context.select(picked.index, additive)
        context.revealed = picked.index
    }

    private fun routeKeyboard() {
        // wantTextInput, not wantCaptureKeyboard: the latter also goes true as
        // soon as ImGui's keyboard navigation activates, which would silently
        // disable every shortcut in the editor.
        val typing = ImGui.getIO().wantTextInput
        val presses = input.keyboard.presses
        val shortcuts = presses.map { it.key to it.mods }
        palette.handleShortcuts(shortcuts, typing)
        tree.keys = if (typing) emptyList() else shortcuts

        if (mode != EditorMode.PLAY || typing) {
            releaseKeys(all = true)
            return
        }
        // The whole frame's input, repeats included: a held key drives a scrollbar
        // arrow through its auto-repeats, not through the initial press.
        val chars = ArrayDeque(input.keyboard.chars)
        for (event in presses) {
            if (isShortcut(event.key, event.mods)) {
                continue
            }
            val typed = if (chars.isEmpty()) NO_CHAR else chars.removeFirst().toChar()
            heldKeys.add(event.key)
            interaction.key(event.key, typed, event.mods, event.repeat)
        }
        releaseKeys(all = false)
    }

    private fun releaseKeys(all: Boolean) {
        if (heldKeys.isEmpty()) {
            return
        }
        val iterator = heldKeys.iterator()
        while (iterator.hasNext()) {
            val key = iterator.next()
            if (all || input.keyboard.isKeyReleased(key)) {
                interaction.keyReleased(key)
                iterator.remove()
            }
        }
    }

    /** True when a key press is already spoken for by an editor shortcut. */
    private fun isShortcut(key: Int, mods: Int): Boolean {
        val relevant = mods and (GLFW_MOD_CONTROL or GLFW_MOD_SHIFT or GLFW_MOD_ALT)
        return palette.all.any { it.key == key && it.mods == relevant }
    }

    // ---------------------------------------------------------- stage overlay

    private fun drawStageToolbar() {
        if (mode == EditorMode.PLAY) {
            ImGui.pushStyleColor(ImGuiCol.Text, 0.4f, 0.9f, 0.55f, 1f)
            ImGui.text("PLAY")
        } else {
            ImGui.pushStyleColor(ImGuiCol.Text, 1f, 0.78f, 0.35f, 1f)
            ImGui.text("DESIGN")
        }
        ImGui.popStyleColor()
        ImGui.sameLine()
        if (ImGui.smallButton("F5")) {
            toggleMode()
        }
    }

    /**
     * Selection chrome, drawn over the stage image in screen space so it stays a
     * crisp one pixel however far the stage is zoomed.
     */
    private fun drawStageOverlay() {
        val instance = frameInstance ?: return
        val drawList = ImGui.getWindowDrawList()
        if (mode == EditorMode.PLAY) {
            // Selection gets out of the way: a faint outline only, no handles, so
            // the interface looks like the client rather than like an editor.
            context.primaryState()?.let { outline(drawList, it, PLAY_OUTLINE) }
            return
        }

        if (stagePanel.hovered && !gizmo.dragging) {
            val hits = instance.hitTest(
                0, 0, stageTarget.width, stageTarget.height,
                stagePanel.pointerX, stagePanel.pointerY,
            )
            val hover = hits.lastOrNull { it.type != ComponentType.CONTAINER } ?: hits.lastOrNull()
            if (hover != null && hover.index != context.primary) {
                outline(drawList, hover, HOVER_OUTLINE)
            }
        }
        for (index in context.selection) {
            if (index != context.primary) {
                instance.state(index)?.let { outline(drawList, it, SECONDARY_OUTLINE) }
            }
        }
        context.primaryState()?.let { handleGizmo(drawList, it) }
    }

    private fun outline(drawList: ImDrawList, state: ComponentState, colour: Int) {
        drawList.addRect(
            stagePanel.screenX(state.screenX),
            stagePanel.screenY(state.screenY),
            stagePanel.screenX(state.screenX + maxOf(state.width, 1)),
            stagePanel.screenY(state.screenY + maxOf(state.height, 1)),
            colour,
        )
    }

    private fun handleGizmo(drawList: ImDrawList, state: ComponentState) {
        val box = GizmoBox(state.screenX, state.screenY, state.width, state.height)
        val pointerX = stagePanel.pointerX
        val pointerY = stagePanel.pointerY

        if (stagePanel.hovered || gizmo.dragging) {
            val handle = gizmo.hitTest(box, pointerX, pointerY, stagePanel.imageScale)
            if (!gizmo.dragging && handle != GizmoHandle.NONE &&
                input.mouse.isButtonPressed(GLFW_MOUSE_BUTTON_LEFT)
            ) {
                gizmo.begin(handle, box, pointerX, pointerY)
            }
            window.setCursorShape(if (gizmo.dragging) gizmo.active.cursor else handle.cursor)
        }

        var drawn = box
        if (gizmo.dragging) {
            if (!input.mouse.isButtonDown(GLFW_MOUSE_BUTTON_LEFT)) {
                gizmo.end()
                gizmoPreview = null
                context.endEdit()
                window.setCursorShape(GLFW_ARROW_CURSOR)
            } else {
                val updated = gizmo.update(pointerX, pointerY, ImGui.getIO().keyShift)
                if (updated != null) {
                    gizmoPreview = updated
                    applyGizmo(state, updated)
                    drawn = updated
                }
            }
        }
        gizmo.draw(drawList, drawn, stagePanel::screenX, stagePanel::screenY, true)
    }

    /**
     * Turns a dragged box back into authored values.
     *
     * The gizmo works in resolved stage pixels; the definition holds a base value
     * plus a mode that says how the layout pass got from one to the other. Each
     * mode is inverted exactly rather than the position simply being nudged by
     * the drag delta, because for the far-edge and proportional modes a nudge is
     * the wrong sign or the wrong scale, and the component would run away from
     * the pointer.
     */
    private fun applyGizmo(state: ComponentState, box: GizmoBox) {
        val instance = frameInstance ?: return
        val parent = instance.state(state.parent)
        val parentWidth = when {
            parent == null -> stageTarget.width
            parent.scrollWidth != 0 -> parent.scrollWidth
            else -> parent.width
        }
        val parentHeight = when {
            parent == null -> stageTarget.height
            parent.scrollHeight != 0 -> parent.scrollHeight
            else -> parent.height
        }
        // The parent's content origin, recovered from the two coordinates the
        // layout already produced for this component.
        val localX = box.x - (state.screenX - state.x)
        val localY = box.y - (state.screenY - state.y)

        context.editPrimary("Move or resize", "gizmo@${state.index}") { definition ->
            definition.baseWidth =
                inverseSize(definition.horizontalSizeMode.toInt(), box.width, parentWidth, definition.baseWidth)
            definition.baseHeight =
                inverseSize(definition.verticalSizeMode.toInt(), box.height, parentHeight, definition.baseHeight)
            definition.basePositionX = inversePosition(
                definition.horizontalPositionMode.toInt(), localX, box.width, parentWidth, definition.basePositionX
            )
            definition.basePositionY = inversePosition(
                definition.verticalPositionMode.toInt(), localY, box.height, parentHeight, definition.basePositionY
            )
        }
    }

    private fun inverseSize(mode: Int, resolved: Int, parent: Int, current: Int): Int = when (mode) {
        0 -> resolved
        1 -> parent - resolved
        2 -> if (parent > 0) (resolved shl 14) / parent else current
        // Unchanged and aspect are not driven by the base value at all.
        else -> current
    }

    @Suppress("MagicNumber")
    private fun inversePosition(mode: Int, resolved: Int, size: Int, parent: Int, current: Int): Int = when (mode) {
        0 -> resolved
        1 -> resolved - ((parent - size) / 2)
        2 -> parent - size - resolved
        3 -> if (parent > 0) (resolved shl 14) / parent else current
        4 -> if (parent > 0) ((resolved - ((parent - size) / 2)) shl 14) / parent else current
        else -> if (parent > 0) ((parent - size - resolved) shl 14) / parent else current
    }

    // ------------------------------------------------------------------- menu

    private fun drawMenuBar() {
        if (!ImGui.beginMainMenuBar()) {
            return
        }
        val document = context.document
        if (ImGui.beginMenu("File")) {
            menuCommand("file.new")
            menuCommand("file.save")
            menuCommand("file.import")
            ImGui.separator()
            if (store.writable) {
                menuCommand("file.cache")
            } else {
                ImGui.beginDisabled()
                ImGui.menuItem("Save to cache")
                ImGui.endDisabled()
                if (ImGui.isItemHovered()) {
                    ImGuiText.tooltip((store.readOnlyReason ?: ""))
                }
            }
            ImGui.separator()
            if (ImGui.menuItem("Open cache-source tree...")) {
                sourcePath.set(source?.root?.path ?: InterfaceSourceTree.discover()?.path ?: "cache-source")
                showSourceDialog = true
            }
            if (ImGui.menuItem("Use the packed cache", "", false, source != null)) {
                useSource(null)
            }
            ImGui.separator()
            if (ImGui.menuItem("Reveal sidecar path") && document != null) {
                ImGui.setClipboardText(store.jsonFile(document.id).absolutePath)
                status = "copied ${store.jsonFile(document.id).absolutePath}"
            }
            ImGui.endMenu()
        }
        if (ImGui.beginMenu("Edit")) {
            menuCommand("edit.undo")
            menuCommand("edit.redo")
            ImGui.separator()
            menuCommand("edit.duplicate")
            menuCommand("edit.delete")
            menuCommand("edit.copy")
            menuCommand("edit.paste")
            ImGui.separator()
            menuCommand("edit.selectAll")
            menuCommand("edit.deselect")
            ImGui.endMenu()
        }
        if (ImGui.beginMenu("View")) {
            panelToggle("Stage") { stagePanel.visible = !stagePanel.visible }
            panelToggle("Interfaces") { library.visible = !library.visible }
            panelToggle("Tree") { tree.visible = !tree.visible }
            panelToggle("Inspector") { inspector.visible = !inspector.visible }
            panelToggle("Interaction") { eventPanel.visible = !eventPanel.visible }
            panelToggle("CS2 Hooks") { hookPanel.visible = !hookPanel.visible }
            panelToggle("CS2 Hot Reload") { reloadPanel.visible = !reloadPanel.visible }
            panelToggle("State") { statePanel.visible = !statePanel.visible }
            panelToggle("Validation") { validation.visible = !validation.visible }
            ImGui.separator()
            menuCommand("view.resetLayout")
            menuCommand("view.resetView")
            ImGui.endMenu()
        }
        if (ImGui.beginMenu("Run")) {
            menuCommand("run.mode")
            menuCommand("run.load")
            menuCommand("run.source")
            menuCommand("run.reset")
            menuCommand("run.clearTrace")
            ImGui.endMenu()
        }
        if (ImGui.beginMenu("Help")) {
            menuCommand("help.palette")
            if (ImGui.beginMenu("Shortcuts")) {
                palette.renderShortcutList()
                ImGui.endMenu()
            }
            ImGui.endMenu()
        }

        // The right hand side of the bar is a status line: the mode, the dirty
        // marker and whatever the last action reported.
        ImGui.sameLine(ImGui.getWindowSizeX() - STATUS_WIDTH)
        if (mode == EditorMode.PLAY) {
            ImGuiText.coloured(0.4f, 0.9f, 0.55f, "PLAY")
        } else {
            ImGuiText.coloured(1f, 0.78f, 0.35f, "DESIGN")
        }
        ImGui.sameLine()
        ImGuiText.disabled("|")
        ImGui.sameLine()
        if (document?.dirty == true) {
            ImGuiText.coloured(1f, 0.78f, 0.35f, "unsaved")
        } else {
            ImGuiText.disabled("saved")
        }
        ImGui.sameLine()
        ImGuiText.disabled("|")
        ImGui.sameLine()
        ImGuiText.disabled((status.take(STATUS_CHARS)))
        ImGui.endMainMenuBar()

        stagePanel.status = buildString {
            append("interface $currentId")
            val live = liveStatus()
            if (live.isNotEmpty()) {
                append("   ").append(live)
            }
            if (loadScriptMillis > 0) {
                append("   load scripts ${loadScriptMillis}ms")
            }
            if (validation.errorCount > 0) {
                append("   ${validation.errorCount} errors")
            }
        }
    }

    private fun menuCommand(id: String) {
        val command = palette.all.firstOrNull { it.id == id } ?: return
        val usable = command.enabled()
        if (ImGui.menuItem(command.label, command.shortcut, false, usable)) {
            palette.run(id)
        }
    }

    private fun panelToggle(label: String, toggle: () -> Unit) {
        if (ImGui.menuItem(label)) {
            toggle()
        }
    }

    // ----------------------------------------------------------------- dialog

    /**
     * New / duplicate / import, in one modal. All three end in a document being
     * attached, and the only thing that differs is where the components come
     * from, so splitting them into three dialogs would be three copies of the
     * same id field.
     */
    private fun drawDialog() {
        drawSourceDialog()
        if (showDialog) {
            ImGui.openPopup(DIALOG)
            showDialog = false
        }
        if (!ImGui.beginPopupModal(DIALOG)) {
            return
        }
        ImGui.setNextItemWidth(120f)
        ImGui.inputInt("Interface id", dialogId)
        val id = dialogId.get()
        if (id < 0) {
            dialogId.set(0)
        }
        if (store.exists(id)) {
            ImGuiText.coloured(1f, 0.78f, 0.35f, "Interface $id already exists in the cache.")
        } else {
            ImGuiText.disabled("Free. The highest in the cache is ${store.lastInterfaceId()}.")
        }
        ImGui.separator()

        if (ImGui.radioButton("Empty", dialogSource == SOURCE_EMPTY)) {
            dialogSource = SOURCE_EMPTY
        }
        ImGui.sameLine()
        if (ImGui.radioButton("Copy the open interface", dialogSource == SOURCE_COPY)) {
            dialogSource = SOURCE_COPY
        }
        ImGui.sameLine()
        if (ImGui.radioButton("Import JSON", dialogSource == SOURCE_IMPORT)) {
            dialogSource = SOURCE_IMPORT
        }
        if (dialogSource == SOURCE_IMPORT) {
            ImGui.setNextItemWidth(-1f)
            ImGui.inputTextWithHint("##path", "path to a sidecar", dialogPath)
        }
        if (dialogSource == SOURCE_EMPTY) {
            ImGuiText.disabled("Add components from the tree's right click menu, or the Add menu.")
        }

        ImGui.separator()
        if (ImGui.button("Create", DIALOG_BUTTON, 0f)) {
            createFromDialog(id)
            ImGui.closeCurrentPopup()
        }
        ImGui.sameLine()
        if (ImGui.button("Cancel", DIALOG_BUTTON, 0f)) {
            ImGui.closeCurrentPopup()
        }
        ImGui.endPopup()
    }

    private fun drawSourceDialog() {
        if (showSourceDialog) {
            ImGui.openPopup(SOURCE_DIALOG)
            showSourceDialog = false
        }
        if (!ImGui.beginPopupModal(SOURCE_DIALOG)) {
            return
        }
        ImGuiText.wrapped(
            "Components come from <tree>/interfaces/<id>/*.json and clientscripts from " +
                "<tree>/clientscripts; graphics, fonts and models stay with the packed cache. " +
                "Nothing is written to the tree.",
        )
        ImGui.setNextItemWidth(-1f)
        ImGui.inputTextWithHint("##sourcepath", "path to a cache source tree", sourcePath)
        val candidate = File(sourcePath.get().trim())
        if (InterfaceSourceTree.rootOf(candidate) == null) {
            ImGuiText.coloured(1f, 0.78f, 0.35f, "No cache.json there; that is not a cache source tree.")
        } else {
            ImGuiText.disabled("Ready.")
        }
        ImGui.separator()
        if (ImGui.button("Use", DIALOG_BUTTON, 0f)) {
            useSource(candidate)
            ImGui.closeCurrentPopup()
        }
        ImGui.sameLine()
        if (ImGui.button("Cancel", DIALOG_BUTTON, 0f)) {
            ImGui.closeCurrentPopup()
        }
        ImGui.endPopup()
    }

    private fun createFromDialog(id: Int) {
        val document = when (dialogSource) {
            SOURCE_COPY -> context.document?.copyAs(id) ?: InterfaceDocument.empty(id)
            SOURCE_IMPORT -> try {
                store.importJson(File(dialogPath.get().trim()))
            } catch (e: Exception) {
                status = "import failed: ${e.message}"
                return
            }
            else -> InterfaceDocument.empty(id)
        }
        attach(document)
    }

    // --------------------------------------------------------------- commands

    @Suppress("LongMethod")
    private fun registerCommands() {
        palette.register(
            "file.new", "New interface", "File", GLFW_KEY_N, GLFW_MOD_CONTROL,
            "Empty, a copy of the open one, or an imported sidecar.",
        ) {
            dialogId.set(store.lastInterfaceId() + 1)
            showDialog = true
        }
        palette.register(
            "file.save", "Save JSON sidecar", "File", GLFW_KEY_S, GLFW_MOD_CONTROL,
            "The working save. Writes ${InterfaceStore.DEFAULT_DIRECTORY}/interface-<id>.json and " +
                "backs up whatever the cache currently holds.",
            enabled = { context.open },
        ) { saveSidecar() }
        palette.register(
            "file.import", "Import JSON...", "File", 0, 0,
            "Load a sidecar written by an earlier save.",
        ) {
            dialogSource = SOURCE_IMPORT
            dialogId.set(currentId)
            dialogPath.set(store.jsonFile(currentId).path)
            showDialog = true
        }
        palette.register(
            "file.cache", "Save to cache", "File", 0, 0,
            "Writes index 3 directly. Only available with a writable cache.",
            enabled = { store.writable && context.open },
        ) { saveToCache() }

        palette.register(
            "edit.undo", "Undo", "Edit", GLFW_KEY_Z, GLFW_MOD_CONTROL, "",
            enabled = { context.document?.canUndo == true },
        ) {
            context.undo()
            status = "undo"
        }
        palette.register(
            "edit.redo", "Redo", "Edit", GLFW_KEY_Y, GLFW_MOD_CONTROL, "",
            enabled = { context.document?.canRedo == true },
        ) {
            context.redo()
            status = "redo"
        }
        palette.register(
            "edit.duplicate", "Duplicate", "Edit", GLFW_KEY_D, GLFW_MOD_CONTROL,
            "Deep copies the selection, repointing hook arguments at the copies.",
            enabled = { context.primary >= 0 },
        ) {
            val targets = context.targets()
            context.structural { it.duplicateComponents(targets) }
        }
        palette.register(
            "edit.delete", "Delete", "Edit", GLFW_KEY_DELETE, 0,
            "Deletes the selection and everything under it.",
            enabled = { context.primary >= 0 },
        ) {
            val targets = context.targets()
            context.structural { it.deleteComponents(targets) }
        }
        palette.register(
            "edit.copy", "Copy subtree", "Edit", GLFW_KEY_C, GLFW_MOD_CONTROL, "",
            enabled = { context.primary >= 0 },
        ) {
            val document = context.document ?: return@register
            context.clipboard =
                ComponentTemplates.extract(document, context.primary, "clipboard", "Pasted subtree")
        }
        palette.register(
            "edit.paste", "Paste into selection", "Edit", GLFW_KEY_V, GLFW_MOD_CONTROL, "",
            enabled = { context.clipboard != null },
        ) {
            val template = context.clipboard ?: return@register
            val parent = context.primary
            context.structural { it.addTemplate(template, parent) }
        }
        palette.register(
            "edit.selectAll", "Select all", "Edit", GLFW_KEY_A, GLFW_MOD_CONTROL, "",
            enabled = { context.open },
        ) {
            context.setSelection((0 until context.componentCount).toList())
        }
        palette.register("edit.deselect", "Deselect", "Edit", GLFW_KEY_ESCAPE, 0, "") {
            if (interaction.menuOpen) {
                interaction.closeMenu()
            } else {
                context.clearSelection()
            }
        }

        palette.register(
            "view.resetLayout", "Reset panel layout", "View", 0, 0,
            "Puts every panel back where it started.",
        ) { buildDefaultLayout() }
        palette.register("view.resetView", "Reset stage zoom", "View", 0, 0, "") { stagePanel.resetView() }
        palette.register("view.library", "Focus the interface list", "View", GLFW_KEY_L, GLFW_MOD_CONTROL, "") {
            library.visible = true
            ImGui.setWindowFocus("Interfaces")
        }

        palette.register(
            "run.mode", "Toggle Design / Play", "Run", GLFW_KEY_F5, 0,
            "Design selects and moves; Play runs the interface the way the client would.",
        ) { toggleMode() }
        palette.register(
            "run.load", "Run load scripts", "Run", GLFW_KEY_F6, 0,
            "Re-runs every component's onLoad, which is how an interface builds itself.",
            enabled = { context.open },
        ) { scriptsDirty = true }
        palette.register(
            "run.reset", "Reset the interface", "Run", 0, 0,
            "Drops every runtime binding and re-seeds from the cache.",
            enabled = { context.open },
        ) {
            cs2Host.clearHooks()
            cs2Host.log.clear()
            interaction.reset()
            context.document?.rebuild()
            scriptsDirty = true
            status = "interface reset"
        }
        palette.register(
            "run.source", "Reload from cache-source", "Run", GLFW_KEY_F7, 0,
            "Re-reads the interface's component JSON and re-compiles every edited script, without packing.",
            enabled = { source != null },
        ) {
            val components = reloadFromSource()
            val events = reload.reloadAll()
            status = if (events.isEmpty()) {
                components
            } else {
                "$components; ${events.size} scripts recompiled, ${events.count { !it.ok }} failed"
            }
        }
        palette.register("run.clearTrace", "Clear the event trace", "Run", 0, 0, "") {
            interaction.clearTrace()
        }

        palette.register(
            "help.palette", "Command palette", "Help", GLFW_KEY_P, GLFW_MOD_CONTROL, "",
        ) { palette.show() }
        palette.register("help.shortcuts", "Shortcut list", "Help", GLFW_KEY_F1, 0, "") {
            palette.show()
        }
    }

    private fun toggleMode() {
        mode = if (mode == EditorMode.PLAY) EditorMode.DESIGN else EditorMode.PLAY
        interaction.liveMode = mode == EditorMode.PLAY
        interaction.reset()
        gizmo.cancel()
        window.setCursorShape(GLFW_ARROW_CURSOR)
        status = if (mode == EditorMode.PLAY) "play mode: hooks fire" else "design mode: click to select"
    }

    // ----------------------------------------------------------------- layout

    /**
     * The starting arrangement, built with the dock builder rather than shipped
     * as an ini file so it survives a panel being renamed. Only run when there is
     * no saved layout, or on request.
     */
    private fun buildDefaultLayout() {
        val root = imGui.dockspace()
        if (root == 0) {
            return
        }
        val viewport = ImGui.getMainViewport()
        ImGuiInternal.dockBuilderRemoveNode(root)
        ImGuiInternal.dockBuilderAddNode(
            root,
            InternalDockNodeFlags.DockSpace or ImGuiDockNodeFlags.PassthruCentralNode,
        )
        ImGuiInternal.dockBuilderSetNodeSize(root, viewport.workSizeX, viewport.workSizeY)

        val left = ImInt()
        val body = ImInt()
        ImGuiInternal.dockBuilderSplitNode(root, ImGuiDir.Left, 0.19f, left, body)
        val leftLower = ImInt()
        val leftUpper = ImInt()
        ImGuiInternal.dockBuilderSplitNode(left.get(), ImGuiDir.Down, 0.58f, leftLower, leftUpper)
        val right = ImInt()
        val centre = ImInt()
        ImGuiInternal.dockBuilderSplitNode(body.get(), ImGuiDir.Right, 0.27f, right, centre)
        val rightLower = ImInt()
        val rightUpper = ImInt()
        ImGuiInternal.dockBuilderSplitNode(right.get(), ImGuiDir.Down, 0.42f, rightLower, rightUpper)
        val bottom = ImInt()
        val stage = ImInt()
        ImGuiInternal.dockBuilderSplitNode(centre.get(), ImGuiDir.Down, 0.30f, bottom, stage)

        dockNodes.clear()
        dockNodes["Interfaces"] = leftUpper.get()
        dockNodes["Tree"] = leftLower.get()
        dockNodes["Stage"] = stage.get()
        dockNodes["Interaction"] = bottom.get()
        dockNodes["CS2 Hot Reload"] = bottom.get()
        dockNodes["State"] = bottom.get()
        dockNodes["Inspector"] = rightUpper.get()
        dockNodes["CS2 Hooks"] = rightLower.get()
        dockNodes["Validation"] = rightLower.get()
        for ((title, node) in dockNodes) {
            ImGuiInternal.dockBuilderDockWindow(title, node)
        }
        ImGuiInternal.dockBuilderFinish(root)
        dockFrames = DOCK_FRAMES
    }

    /** Pins the next window into its default node while [dockFrames] is counting down. */
    private fun dock(title: String) {
        if (dockFrames <= 0) {
            return
        }
        dockNodes[title]?.let { ImGui.setNextWindowDockID(it) }
    }

    // ---------------------------------------------------------------- cleanup

    override fun onCleanup() {
        sourceWatch.close()
        reload.close()
        modelRenderer.cleanup()
        icons.cleanup()
        fonts.cleanup()
        graphics.cleanup()
        canvas.cleanup()
        stageTarget.cleanup()
        imGui.cleanup()
    }

    companion object {
        private const val LAYOUT_FILE = "drynor-tools-layout.ini"
        private const val BACKGROUND = 0.09f
        private const val TEMPLATE_SCAN = 4096
        /**
         * Handed to a key hook when the press produced no typed character.
         * Zero rather than a space on purpose: a component's typed-character
         * bindings are only matched when the bound code is above zero, so this
         * can never collide with one, whereas a space could.
         */
        private const val NO_CHAR = '\u0000'

        private const val STATUS_WIDTH = 560f
        private const val STATUS_CHARS = 72
        private const val DIALOG = "New interface"
        private const val SOURCE_DIALOG = "Live source"

        /** How long a component file has to stay unwritten before it is re-read. */
        private const val SOURCE_DEBOUNCE = 150L

        /** Ceiling on the call closure compiled when an interface opens in live source mode. */
        private const val SCRIPT_CLOSURE_LIMIT = 512
        private const val DIALOG_BUTTON = 110f
        private const val DOCK_FRAMES = 4

        private const val SOURCE_EMPTY = 0
        private const val SOURCE_COPY = 1
        private const val SOURCE_IMPORT = 2

        /** ImGui packs colours as ABGR. */
        private const val HOVER_OUTLINE = 0x9028c8ffL.toInt()
        private const val SECONDARY_OUTLINE = 0xa0ffb040L.toInt()
        private const val PLAY_OUTLINE = 0x4028c8ffL.toInt()
    }
}
