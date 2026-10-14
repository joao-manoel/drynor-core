package org.darkan.tools.ui

import imgui.ImGui
import imgui.flag.ImGuiChildFlags
import imgui.flag.ImGuiCol
import imgui.flag.ImGuiCond
import imgui.flag.ImGuiTreeNodeFlags
import imgui.type.ImBoolean
import imgui.type.ImInt
import imgui.type.ImString
import java.io.File
import java.lang.ProcessBuilder.Redirect
import org.darkan.cs2.Cs2HotReload
import org.darkan.cs2.Cs2SourceProject

/**
 * Drives CS2 hot reload from the editor.
 *
 * The panel is the only thing that touches the reload machinery from the render
 * thread, and it does two jobs there: it drains the watcher's event queue once
 * per frame (see [render] - this happens whether or not the window is visible,
 * because a hidden panel must not stall the pipeline), and it turns compile
 * diagnostics into somewhere the developer can actually go, by shelling out to
 * their editor with a `file:line:col` target.
 *
 * Everything else - compiling, installing, watching - happens on the watcher
 * thread inside [Cs2HotReload]; nothing here blocks on it.
 */
class Cs2ReloadPanel(
    private val reload: Cs2HotReload,
    /**
     * Script ids the open interface's hooks reference, for the "export what this
     * interface runs" button. A lambda rather than a viewer reference so this
     * panel does not depend on the viewer or on the interaction runtime; phase 2
     * supplies the real provider.
     */
    private val hookScripts: () -> List<Int> = { emptyList() },
    private val liveSource: () -> String = { "" },
) {
    var visible: Boolean = true

    private var firstFrame = true

    private val folderInput = ImString(512)
    private val scriptInput = ImInt(0)
    private val autoReload = ImBoolean(reload.autoReload)

    /** Events drained this frame, kept only for the status line. */
    private val drained = ArrayList<Cs2HotReload.Event>()

    private var status: String = "idle"
    private var lastEvent: Cs2HotReload.Event? = null

    init {
        folderInput.set(reload.project.folder.path)
    }

    /**
     * Called every frame. The drain happens before the visibility check on
     * purpose: the queue is unbounded, and a panel the developer closed should not
     * quietly turn into a memory leak or delay [Cs2HotReload.onReload] callbacks
     * the viewer depends on.
     */
    fun render(windowHeight: Int) {
        drained.clear()
        if (reload.drainInto(drained) > 0) {
            lastEvent = drained.last()
            status = drained.last().message
        }

        if (!visible) return
        if (firstFrame) {
            // Only a size hint, and only as a default: setting an explicit position
            // marks the window PosUndock in ImGui, which tears it back out of the
            // dockspace on every launch no matter what the dock builder asked for.
            // FirstUseEver lets a saved layout win, so the panel stays where it was
            // put.
            ImGui.setNextWindowSize(430f, (windowHeight * 0.55f).coerceAtLeast(340f), ImGuiCond.FirstUseEver)
            firstFrame = false
        }
        if (!ImGui.begin("CS2 Hot Reload")) {
            ImGui.end()
            return
        }

        renderSource()
        ImGui.separator()
        renderControls()
        ImGui.separator()
        renderStatus()

        if (ImGui.collapsingHeader("Errors (${reload.errors.size})", ImGuiTreeNodeFlags.DefaultOpen)) {
            renderErrors()
        }
        if (ImGui.collapsingHeader("Overrides (${reload.overridden.size})")) {
            renderOverrides()
        }
        if (ImGui.collapsingHeader("Reload log")) {
            renderLog()
        }
        if (ImGui.collapsingHeader("Interface scripts")) {
            renderHookScripts()
        }
        ImGui.end()
    }

    // ------------------------------------------------------------------- source

    private fun renderSource() {
        val live = liveSource()
        if (live.isEmpty()) {
            ImGuiText.disabled("Reading the packed cache. File > Open cache-source tree to edit without packing.")
        } else {
            ok(live)
        }
        ImGui.text("Source folder")
        ImGui.inputText("##cs2folder", folderInput)
        ImGui.sameLine()
        if (ImGui.button("Use")) {
            val target = File(folderInput.get().trim())
            status = if (reload.useFolder(target)) {
                "using ${reload.project.folder.path}"
            } else {
                reload.watcherError ?: "cannot use ${target.path}"
            }
            folderInput.set(reload.project.folder.path)
        }
        ImGui.sameLine()
        if (ImGui.button("Declarations")) {
            status = reload.project.exportDeclarations().message
        }

        if (ImGui.checkbox("Auto-reload on save", autoReload)) {
            reload.autoReload = autoReload.get()
        }
        ImGui.sameLine()
        if (reload.isWatching) {
            if (ImGui.button("Stop watching")) {
                reload.stop()
                status = "watcher stopped"
            }
        } else if (ImGui.button("Start watching")) {
            status = if (reload.start()) "watching" else reload.watcherError ?: "cannot watch"
        }

        val error = reload.watcherError
        when {
            error != null -> warn("watcher: $error")
            reload.isWatching -> ok("watching ${reload.watchedFolder?.path}")
            else -> ImGuiText.disabled("watcher stopped")
        }
    }

    // ----------------------------------------------------------------- controls

    private fun renderControls() {
        ImGui.inputInt("script id", scriptInput)
        val id = scriptInput.get()

        if (ImGui.button("Reload")) {
            status = reload.reload(id).message
        }
        ImGui.sameLine()
        if (ImGui.button("Verify")) {
            status = reload.project.verify(id).summary
        }
        ImGui.sameLine()
        if (ImGui.button("Revert")) {
            status = if (reload.revert(id)) "reverted script $id" else "script $id was not overridden"
        }
        ImGui.sameLine()
        if (ImGui.button("Open")) {
            status = open(reload.project.fileFor(id), 1, 1)
        }

        if (ImGui.button("Export + open")) {
            status = exportAndOpen(id)
        }
        ImGui.sameLine()
        if (ImGui.button("Reload all")) {
            val events = reload.reloadAll()
            status = if (events.isEmpty()) {
                "nothing is overridden yet; edit a script or use Verify to check one"
            } else {
                "reloaded ${events.size} scripts, ${events.count { !it.ok }} failed"
            }
        }
        ImGui.sameLine()
        if (ImGui.button("Revert all")) {
            status = "reverted ${reload.revertAll()} scripts"
        }

        if (reload.cacheWritable) {
            if (ImGui.button("Commit to cache")) status = reload.writeToCache(id).message
        } else {
            ImGuiText.disabled("cache is read-only; edits live for this session only")
        }
    }

    private fun renderStatus() {
        ImGuiText.wrapped(status)
        val event = lastEvent ?: return
        if (event.signatureChanged) {
            // A moved return signature invalidates every existing call site, and
            // no incremental patch can fix that; say so instead of pretending.
            warn(
                "script ${event.scriptId} changed what it returns - ${event.staleCallers.size} " +
                    "callers still discard the old number of results",
            )
            if (event.staleCallers.isNotEmpty()) {
                ImGuiText.disabled("callers: ${event.staleCallers.take(12).joinToString(", ")}")
                ImGui.sameLine()
                if (ImGui.smallButton("copy##callers")) {
                    ImGui.setClipboardText(event.staleCallers.joinToString(", "))
                }
            }
            if (ImGui.button("Re-analyse whole cache")) {
                // Seconds-long and single-threaded by design; the alternative is
                // leaving every caller's discard count wrong.
                val report = reload.reanalyse()
                status = "re-analysed ${report.analysed} scripts, ${report.problems.size} problems"
            }
        }
    }

    // ------------------------------------------------------------------- errors

    private fun renderErrors() {
        val errors = reload.errors.asReversed()
        if (errors.isEmpty()) {
            ImGuiText.disabled("No failures.")
            return
        }
        if (ImGui.smallButton("Clear log##errors")) reload.clearLog()

        if (ImGui.beginChild("Cs2ReloadErrors", 0f, 140f, ImGuiChildFlags.Borders)) {
            for ((index, event) in errors.withIndex()) {
                val diagnostic = event.diagnostic
                // Bound to a local: `Diagnostic.file` lives in the :cs2 module now, and a public
                // property from another module cannot be smart cast.
                val diagnosticFile = diagnostic?.file
                val label = if (diagnostic == null) {
                    "script ${event.scriptId}: ${event.message}"
                } else {
                    "${diagnostic.file?.name}:${diagnostic.line}:${diagnostic.column} ${diagnostic.message}"
                }
                ImGui.pushStyleColor(ImGuiCol.Text, 1f, 0.45f, 0.4f, 1f)
                ImGuiText.wrapped(label)
                ImGui.popStyleColor()
                if (diagnostic != null && diagnosticFile != null) {
                    if (ImGui.smallButton("open##err$index")) {
                        status = open(diagnosticFile, diagnostic.line, diagnostic.column)
                    }
                    ImGui.sameLine()
                    if (ImGui.smallButton("copy##err$index")) {
                        ImGui.setClipboardText(diagnostic.toString())
                    }
                    if (!diagnostic.located) {
                        ImGui.sameLine()
                        ImGuiText.disabled("(position guessed)")
                    }
                }
                ImGui.separator()
            }
        }
        ImGui.endChild()
    }

    private fun renderOverrides() {
        val ids = reload.overridden
        if (ids.isEmpty()) {
            ImGuiText.disabled("No scripts are running from source.")
            return
        }
        if (ImGui.beginChild("Cs2ReloadOverrides", 0f, 120f, ImGuiChildFlags.Borders)) {
            for (id in ids) {
                ImGui.text("script $id")
                ImGui.sameLine()
                if (ImGui.smallButton("revert##ov$id")) {
                    reload.revert(id)
                    status = "reverted script $id"
                }
                ImGui.sameLine()
                if (ImGui.smallButton("reload##ov$id")) status = reload.reload(id).message
                ImGui.sameLine()
                if (ImGui.smallButton("open##ov$id")) status = open(reload.project.fileFor(id), 1, 1)
            }
        }
        ImGui.endChild()
    }

    private fun renderLog() {
        val events = reload.log.asReversed()
        if (events.isEmpty()) {
            ImGuiText.disabled("Nothing yet.")
            return
        }
        if (ImGui.beginChild("Cs2ReloadLog", 0f, 160f, ImGuiChildFlags.Borders)) {
            for (event in events) {
                if (event.ok) {
                    ImGuiText.disabled("${event.time}  ${event.scriptId}")
                } else {
                    ImGui.pushStyleColor(ImGuiCol.Text, 1f, 0.45f, 0.4f, 1f)
                    ImGui.text("${event.time}  ${event.scriptId}")
                    ImGui.popStyleColor()
                }
                ImGui.sameLine()
                ImGuiText.wrapped(event.message)
            }
        }
        ImGui.endChild()
    }

    /**
     * Exports the scripts the open interface actually runs, so a developer can go
     * from "this button does nothing" to editing the script behind it.
     */
    private fun renderHookScripts() {
        val ids = hookScripts().distinct().sorted()
        if (ids.isEmpty()) {
            ImGuiText.disabled("No interface hooks reported.")
            return
        }
        if (ImGui.smallButton("Export all missing")) {
            val exported = ids.filterNot { reload.project.hasSource(it) }
                .map { reload.project.exportSource(it) }
            status = "exported ${exported.count { it.ok }} of ${exported.size} missing scripts"
        }
        if (ImGui.beginChild("Cs2ReloadHooks", 0f, 140f, ImGuiChildFlags.Borders)) {
            for (id in ids) {
                val present = reload.project.hasSource(id)
                if (present) ImGui.text("script $id") else ImGuiText.disabled("script $id (not exported)")
                ImGui.sameLine()
                if (ImGui.smallButton("edit##hook$id")) status = exportAndOpen(id)
                if (reload.project.hasSource(id)) {
                    ImGui.sameLine()
                    if (ImGui.smallButton("reload##hook$id")) status = reload.reload(id).message
                }
            }
        }
        ImGui.endChild()
    }

    // -------------------------------------------------------------------- editor

    private fun exportAndOpen(scriptId: Int): String {
        val result = reload.project.exportSource(scriptId)
        val file = result.file ?: return result.message
        return "${result.message}; ${open(file, 1, 1)}"
    }

    /**
     * Opens a file at a position in the developer's editor.
     *
     * `$VISUAL`/`$EDITOR` win when set, since that is where the preference lives;
     * otherwise VS Code, whose `-g file:line:col` is the form the diagnostics are
     * already printed in. A missing editor is a status line, never an exception -
     * the render thread must survive it.
     */
    private fun open(file: File, line: Int, column: Int): String {
        if (!file.isFile) return "no such file: ${file.path}"
        val editor = System.getenv("VISUAL")?.takeIf { it.isNotBlank() }
            ?: System.getenv("EDITOR")?.takeIf { it.isNotBlank() }
            ?: "code"
        val words = editor.trim().split(' ').filter { it.isNotEmpty() }
        val command = ArrayList(words)
        if (words.first().substringAfterLast('/').startsWith("code")) {
            command.add("-g")
            command.add("${file.path}:$line:$column")
        } else {
            command.add("+$line")
            command.add(file.path)
        }
        return try {
            ProcessBuilder(command)
                .redirectOutput(Redirect.DISCARD)
                .redirectError(Redirect.DISCARD)
                .start()
            "opened ${file.name}:$line:$column in ${words.first()}"
        } catch (e: Exception) {
            ImGui.setClipboardText("${file.path}:$line:$column")
            "cannot launch ${words.first()} (${e.message}); path copied to the clipboard"
        }
    }

    private fun ok(text: String) = ImGuiText.coloured(0.4f, 0.9f, 0.5f, 1f, text)

    private fun warn(text: String) = ImGuiText.coloured(1f, 0.75f, 0.3f, 1f, text)

    /** Diagnostics the viewer can surface elsewhere, newest first. */
    val errors: List<Cs2SourceProject.Diagnostic>
        get() = reload.errors.mapNotNull { it.diagnostic }.asReversed()
}
