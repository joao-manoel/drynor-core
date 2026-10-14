package org.darkan.cs2

import java.io.File
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchService
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.ReadOnlyCache

/**
 * Live-reloads clientscripts from source while the editor is running.
 *
 * The chain that makes this work, and the reason each link matters:
 *
 *  - [Cs2HookDispatcher][org.darkan.cs2.vm.Cs2HookDispatcher] builds its
 *    virtual machine once but resolves every hook and every `GOSUB` through
 *    `Cs2Context.script(id)` at dispatch time. Nothing downstream caches the
 *    resolved script - the machine's own state is per-`execute` frames plus the
 *    five global int arrays, neither of which is derived from a script - so
 *    changing what the context returns is genuinely enough to swap bytecode
 *    mid-session. No invalidation call is needed for the VM.
 *  - The replacement is installed into [Cs2Analyzer] rather than kept in this
 *    overlay, because the code generator and the structurer resolve calls through
 *    a context too, and any of them holding the bare analyzer would silently keep
 *    the stale version. This class then implements [Cs2Context] by delegation so
 *    it can be handed to the dispatcher directly.
 *  - What *is* stale after a reload is host-side state a previous run created:
 *    hooks a script bound with `IF_SETON*` stay bound to whatever they were bound
 *    to. Those are script *ids*, resolved per dispatch, so they still point at the
 *    new bytecode; but if the edit changed which hooks get bound, the old
 *    bindings survive until the interface is reset. [onReload] exists for the
 *    viewer to do that.
 *
 * Threading: the watcher thread reads files, compiles and installs; the render
 * thread drains [poll]/[drainInto] once per frame. The state crossing that
 * boundary is the event queue (concurrent), the log (guarded by a monitor held
 * only for the append and the snapshot, never across a compile), the analyzer's
 * override map (concurrent), and [autoReload] (volatile).
 */
class Cs2HotReload(
    private val cache: Cache,
    private val analyzer: Cs2Analyzer,
    val project: Cs2SourceProject = Cs2SourceProject(cache, analyzer),
) : Cs2Context, AutoCloseable {

    /** Whether file changes reload on their own, or wait for an explicit call. */
    @Volatile
    var autoReload: Boolean = true

    /** Why the watcher is not running, when it is not. */
    @Volatile
    var watcherError: String? = null
        private set

    /**
     * Invoked on the render thread from [drainInto] for each event, after the
     * bytecode is already live. Meant for re-running an interface's load scripts
     * so the change shows up without a manual click.
     */
    @Volatile
    var onReload: ((Event) -> Unit)? = null

    private val queue = ConcurrentLinkedQueue<Event>()
    private val entries = ArrayDeque<Event>()
    private val logLock = Any()

    @Volatile
    private var watcher: WatchService? = null

    @Volatile
    private var thread: Thread? = null

    @Volatile
    private var watching: Path? = null

    // ------------------------------------------------------------------- events

    /** One reload attempt, successful or not. */
    class Event(
        val timestamp: Long,
        val scriptId: Int,
        val file: File?,
        val ok: Boolean,
        val message: String,
        val diagnostic: Cs2SourceProject.Diagnostic? = null,
        /** True when the edit moved what the script leaves on the stacks. */
        val signatureChanged: Boolean = false,
        /**
         * Scripts whose already-compiled call sites now discard the wrong number
         * of results. Only populated when [signatureChanged].
         */
        val staleCallers: List<Int> = emptyList(),
    ) {
        val time: String
            get() = CLOCK.format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))

        override fun toString(): String = "$time ${if (ok) "ok" else "FAILED"} $scriptId: $message"

        private companion object {
            val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        }
    }

    /** Newest last, capped at [LOG_LIMIT]. */
    val log: List<Event> get() = synchronized(logLock) { entries.toList() }

    val errors: List<Event> get() = synchronized(logLock) { entries.filter { !it.ok } }

    val overridden: List<Int> get() = analyzer.overriddenIds

    fun clearLog() = synchronized(logLock) { entries.clear() }

    /** Takes one pending event, or null. Safe to call from the render thread. */
    fun poll(): Event? = queue.poll()

    /**
     * Moves every pending event into [sink] and runs [onReload] for each.
     * Returns how many were drained. Call once per frame.
     */
    fun drainInto(sink: MutableList<Event>): Int {
        var count = 0
        while (true) {
            val event = queue.poll() ?: break
            sink.add(event)
            onReload?.invoke(event)
            count++
        }
        return count
    }

    fun drain(): List<Event> {
        val events = ArrayList<Event>()
        drainInto(events)
        return events
    }

    // ------------------------------------------------------------------ reloads

    /** Compiles [scriptId] from source and installs it. */
    fun reload(scriptId: Int): Event = install(scriptId, project.fileFor(scriptId), project.compile(scriptId))

    /**
     * Compiles one file and installs it under the id in its header, which need not
     * match the file name.
     */
    fun reloadFile(file: File): Event =
        install(project.scriptIdOf(file) ?: -1, file, project.compileFile(file))

    /**
     * Re-compiles every script that currently has an override.
     *
     * This is the set of edits actually in play, so it is what "reload all" means
     * in a session; sweeping all 6500-odd source files is [sweepFolder].
     */
    fun reloadAll(): List<Event> = overridden.map { reload(it) }

    /** Drops the override for [scriptId], going back to the cached bytecode. */
    fun revert(scriptId: Int): Boolean {
        val dropped = analyzer.uninstall(scriptId)
        if (dropped) record(Event(now(), scriptId, project.fileFor(scriptId), true, "reverted to the cached bytecode"))
        return dropped
    }

    fun revertAll(): Int {
        val count = analyzer.uninstallAll()
        if (count > 0) record(Event(now(), -1, null, true, "reverted $count scripts to the cached bytecode"))
        return count
    }

    /** Counts from compiling every source file in the folder. */
    class Sweep(
        val compiled: Int,
        val failed: Int,
        val identical: Int,
        val differing: Int,
        val installed: Int,
        val failures: List<Cs2SourceProject.Diagnostic>,
    ) {
        /** Share of compiled files whose bytes matched the cache exactly. */
        val exactRate: Double get() = if (compiled == 0) 0.0 else identical.toDouble() / compiled
    }

    /**
     * Compiles every source file in the folder, reporting how many still match the
     * cache byte for byte.
     *
     * Slow - it is the whole corpus - so it belongs on a worker thread or a CLI
     * run, not in a frame. It defaults to verifying rather than installing because
     * "differs from the cache" is not the same as "edited": around one file in a
     * hundred re-compiles to equivalent but differently laid out bytes, and
     * installing those wholesale would put a hundred scripts nobody touched on the
     * hot path. [installEdits] opts into treating every difference as an edit.
     */
    fun sweepFolder(
        limit: Int? = null,
        installEdits: Boolean = false,
        progress: (Int, Int) -> Unit = { _, _ -> },
    ): Sweep {
        val ids = project.sourceIds().let { if (limit == null) it else it.take(limit) }
        var compiled = 0
        var identical = 0
        var differing = 0
        var installedCount = 0
        val failures = ArrayList<Cs2SourceProject.Diagnostic>()

        ids.forEachIndexed { index, id ->
            when (val result = project.compile(id)) {
                is Cs2SourceProject.CompileResult.Failed -> failures.add(result.error)
                is Cs2SourceProject.CompileResult.Ok -> {
                    compiled++
                    val original = project.cachedBytes(result.result.scriptId)
                    if (original != null && result.result.bytes.contentEquals(original)) {
                        identical++
                    } else {
                        differing++
                        if (installEdits) {
                            analyzer.install(result.result.scriptId, result.result.script)
                            installedCount++
                        }
                    }
                }
            }
            progress(index + 1, ids.size)
        }
        return Sweep(compiled, failures.size, identical, differing, installedCount, failures)
    }

    /**
     * Installs a compile result and works out what it invalidated.
     *
     * The interprocedural half of the analysis is re-derived by
     * [Cs2Analyzer.install]; the part that cannot be is flagged here. A script
     * whose return signature moved leaves every existing call site wrong, because
     * those sites were compiled to discard the old number of results, and the only
     * real fixes are recompiling those callers from source or reverting.
     */
    private fun install(fallbackId: Int, file: File?, result: Cs2SourceProject.CompileResult): Event {
        val compiled = result.compiled
        if (compiled == null) {
            val diagnostic = result.diagnostic
            val event = Event(
                timestamp = now(),
                scriptId = diagnostic?.scriptId?.takeIf { it >= 0 } ?: fallbackId,
                file = file,
                ok = false,
                message = diagnostic?.message ?: "compile failed",
                diagnostic = diagnostic,
            )
            record(event)
            return event
        }

        val installed = analyzer.install(compiled.scriptId, compiled.script)
        val original = project.cachedBytes(compiled.scriptId)
        val matchesCache = original != null && compiled.bytes.contentEquals(original)
        val callers = if (installed.signatureChanged) analyzer.callersOf(compiled.scriptId) else emptyList()

        val notes = ArrayList<String>()
        notes.add("${compiled.script.size} instructions, ${compiled.bytes.size} bytes")
        notes.add(if (matchesCache) "unchanged from the cache" else "differs from the cache")
        if (installed.headerChanged) notes.add("argument counts changed")
        if (installed.signatureChanged) {
            notes.add(
                "returns ${installed.previousReturnSignature} -> ${installed.returnSignature}; " +
                    "${callers.size} callers need recompiling",
            )
        }
        installed.failure?.let { notes.add("does not balance: $it") }

        val event = Event(
            timestamp = now(),
            scriptId = compiled.scriptId,
            file = file,
            ok = installed.failure == null,
            message = notes.joinToString("; "),
            signatureChanged = installed.signatureChanged,
            staleCallers = callers,
        )
        record(event)
        return event
    }

    /**
     * Re-runs whole-cache analysis with the current overrides in place.
     *
     * The honest answer to a changed return signature: the fixpoint that resolved
     * every script's signature in the first place is the only thing that can
     * resolve them again consistently. It takes seconds and mutates analysis state
     * the render thread reads, so it is a deliberate action rather than something
     * a file save triggers, and it must be called with nothing else running.
     */
    fun reanalyse(log: (String) -> Unit = {}): Cs2Analyzer.Report {
        val report = analyzer.analyse(log)
        record(Event(now(), -1, null, report.ok, "re-analysed ${report.analysed} scripts, ${report.problems.size} problems"))
        return report
    }

    /** Scripts whose call sites are stale because [scriptId]'s signature moved. */
    fun callersOf(scriptId: Int): List<Int> = analyzer.callersOf(scriptId)

    // -------------------------------------------------------------- cache write

    /**
     * True when the tool's cache can be written at all.
     *
     * Both [world.gregs.voidps.cache.FileCache] and
     * [world.gregs.voidps.cache.MemoryCache] extend [ReadOnlyCache], whose `write`
     * throws, so this is normally false and committing is not offered.
     */
    val cacheWritable: Boolean get() = cache !is ReadOnlyCache

    /**
     * Commits an edited script into cache index 12, making the change outlive the
     * session. Fails loudly rather than pretending, because a silently dropped
     * write is indistinguishable from a working one until the next launch.
     */
    fun writeToCache(scriptId: Int): Event {
        if (!cacheWritable) {
            val event = Event(
                now(), scriptId, project.fileFor(scriptId), false,
                "cache is read-only (${cache::class.simpleName}); cannot commit script $scriptId",
            )
            record(event)
            return event
        }
        val result = project.compile(scriptId)
        val compiled = result.compiled ?: return install(scriptId, project.fileFor(scriptId), result)
        return try {
            cache.write(Index.CLIENT_SCRIPTS, compiled.scriptId, 0, compiled.bytes)
            val updated = cache.update()
            val event = Event(
                now(), compiled.scriptId, compiled.file, updated,
                if (updated) "committed ${compiled.bytes.size} bytes to cache index 12"
                else "cache.update() refused the write",
            )
            record(event)
            event
        } catch (e: Exception) {
            val event = Event(
                now(), compiled.scriptId, compiled.file, false,
                "cache write failed: ${e.message ?: e::class.simpleName}",
            )
            record(event)
            event
        }
    }

    // ------------------------------------------------------------------ watcher

    val isWatching: Boolean get() = thread?.isAlive == true

    val watchedFolder: File? get() = watching?.toFile()

    /**
     * Starts watching the project folder.
     *
     * A directory watch is the only option: watching individual files misses the
     * write-temp-then-rename dance editors do, which shows up as a create of a new
     * name in the directory rather than a modification of the old one.
     */
    fun start(): Boolean {
        stop()
        val directory = project.folder
        if (!directory.isDirectory) {
            watcherError = "no such folder: ${directory.path}"
            return false
        }
        return try {
            val service = FileSystems.getDefault().newWatchService()
            val path = directory.toPath()
            // Scripts live one folder deep, under a folder per gameval category, and
            // a WatchService only reports the directories it was handed - so the
            // root and every category are registered, and a category created later
            // is picked up when its own creation event arrives.
            register(service, path)
            for (child in directory.listFiles().orEmpty()) {
                if (child.isDirectory) register(service, child.toPath())
            }
            watcher = service
            watching = path
            watcherError = null
            val worker = Thread({ watch(service, path) }, "cs2-hot-reload")
            worker.isDaemon = true
            thread = worker
            worker.start()
            record(Event(now(), -1, directory, true, "watching ${directory.path}"))
            true
        } catch (e: Exception) {
            watcherError = "cannot watch ${directory.path}: ${e.message}"
            false
        }
    }

    fun stop() {
        val service = watcher
        watcher = null
        watching = null
        val worker = thread
        thread = null
        try {
            service?.close()
        } catch (e: Exception) {
            // Closing is what wakes the thread up; a failure here only means it
            // was already gone.
        }
        worker?.join(500)
    }

    /** Moves the watch to a different folder, restarting it when it was running. */
    fun useFolder(directory: File): Boolean {
        val wasWatching = isWatching
        stop()
        val resolved = project.useFolder(directory)
        if (resolved != directory) {
            watcherError = "no such folder: ${directory.path}"
            return false
        }
        return if (wasWatching) start() else true
    }

    private fun register(service: WatchService, path: Path) {
        path.register(
            service,
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_MODIFY,
            StandardWatchEventKinds.ENTRY_DELETE,
        )
    }

    /**
     * Watch loop with a quiet-period debounce.
     *
     * Saves arrive in bursts - a create for the temp file, a rename, then a
     * modify - and compiling on the first of them reads a half-written file. Each
     * touched path is therefore held until [DEBOUNCE_MS] passes with nothing
     * further arriving for it.
     */
    private fun watch(service: WatchService, root: Path) {
        val pending = HashMap<File, Long>()
        while (thread === Thread.currentThread()) {
            try {
                val key = service.poll(POLL_MS, TimeUnit.MILLISECONDS)
                if (key != null) {
                    val watched = key.watchable() as? Path ?: root
                    for (event in key.pollEvents()) {
                        val relative = event.context() as? Path ?: continue
                        val file = watched.resolve(relative).toFile()
                        // A category folder that did not exist when the watch
                        // started still has to be watched, or its first save is the
                        // only event ever seen from it.
                        if (file.isDirectory) {
                            try {
                                register(service, file.toPath())
                            } catch (e: Exception) {
                                // Already watched, or gone again; neither is fatal.
                            }
                            continue
                        }
                        if (!interesting(file)) continue
                        pending[file] = System.currentTimeMillis()
                    }
                    if (!key.reset()) return
                }
            } catch (e: ClosedWatchServiceException) {
                return
            } catch (e: InterruptedException) {
                return
            }

            if (pending.isEmpty()) continue
            val now = System.currentTimeMillis()
            val due = pending.filterValues { now - it >= DEBOUNCE_MS }.keys.toList()
            due.forEach { pending.remove(it) }
            for (file in due) {
                try {
                    handle(file)
                } catch (e: Exception) {
                    // The watcher outliving one bad file matters more than the
                    // file; a crash here would silently end hot reload.
                    record(
                        Event(now(), -1, file, false, "reload crashed: ${e.message ?: e::class.simpleName}"),
                    )
                }
            }
        }
    }

    /** Editor scratch files (`.ts~`, `.#foo`, `foo.ts.tmp`) are not sources. */
    private fun interesting(file: File): Boolean {
        if (file.name.startsWith(".")) return false
        return project.isScriptFile(file) || project.isDeclarationFile(file)
    }

    private fun handle(file: File) {
        if (project.isDeclarationFile(file)) {
            project.reloadSymbols()
            record(Event(now(), -1, file, true, "declarations changed; symbol table re-read"))
            if (autoReload) reloadAll()
            return
        }
        if (!file.isFile) {
            // Deleted: the cached bytecode is the only version left.
            val id = Cs2Names.scriptIdOfPath(
                Cs2Layout.relativePath(project.folder, file) ?: return,
            ) ?: return
            if (analyzer.uninstall(id)) {
                record(Event(now(), id, file, true, "source deleted; reverted to the cached bytecode"))
            }
            return
        }
        if (!autoReload) {
            record(Event(now(), project.scriptIdOf(file) ?: -1, file, true, "changed (auto-reload off)"))
            return
        }
        reloadFile(file)
    }

    // --------------------------------------------------------------- bookkeeping

    private fun now() = System.currentTimeMillis()

    private fun record(event: Event) {
        synchronized(logLock) {
            entries.addLast(event)
            while (entries.size > LOG_LIMIT) entries.removeFirst()
        }
        queue.add(event)
    }

    override fun script(id: Int): Cs2Script? = analyzer.script(id)

    override fun paramIsString(paramId: Int): Boolean = analyzer.paramIsString(paramId)

    override fun close() = stop()

    companion object {
        /** Quiet period a file has to sit through before it is compiled. */
        const val DEBOUNCE_MS = 150L
        private const val POLL_MS = 100L
        private const val LOG_LIMIT = 200

    }
}
