package org.darkan.tools.rsinterface

import java.io.File
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchService
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/**
 * Watches a handful of directories and hands what changed to the render thread.
 *
 * Directories rather than files, because an editor saving a file writes a temporary one and
 * renames it: the old name is never modified, so a per-file watch sees nothing. The queue is the
 * only state crossing the thread boundary.
 */
class SourceWatch : AutoCloseable {

    private val queue = ConcurrentLinkedQueue<File>()

    @Volatile
    private var service: WatchService? = null

    @Volatile
    private var thread: Thread? = null

    @Volatile
    var error: String? = null
        private set

    @Volatile
    var directories: List<File> = emptyList()
        private set

    val watching: Boolean get() = thread?.isAlive == true

    fun watch(targets: List<File>): Boolean {
        stop()
        val present = targets.filter { it.isDirectory }
        if (present.isEmpty()) {
            error = if (targets.isEmpty()) null else "no such folder: ${targets.first().path}"
            return false
        }
        return try {
            val watcher = FileSystems.getDefault().newWatchService()
            for (directory in present) {
                register(watcher, directory.toPath())
            }
            service = watcher
            directories = present
            error = null
            val worker = Thread({ run(watcher) }, "interface-source-watch")
            worker.isDaemon = true
            thread = worker
            worker.start()
            true
        } catch (e: Exception) {
            error = "cannot watch ${present.first().path}: ${e.message}"
            false
        }
    }

    fun poll(): List<File> {
        if (queue.isEmpty()) {
            return emptyList()
        }
        val changed = LinkedHashSet<File>()
        while (true) {
            changed.add(queue.poll() ?: break)
        }
        return changed.toList()
    }

    fun stop() {
        val watcher = service
        service = null
        thread = null
        directories = emptyList()
        try {
            watcher?.close()
        } catch (e: Exception) {
            error = "cannot stop the watcher: ${e.message}"
        }
    }

    override fun close() = stop()

    private fun register(watcher: WatchService, path: Path) {
        path.register(
            watcher,
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_MODIFY,
            StandardWatchEventKinds.ENTRY_DELETE,
        )
    }

    private fun run(watcher: WatchService) {
        while (service === watcher) {
            val key = try {
                watcher.poll(POLL_MILLIS, TimeUnit.MILLISECONDS) ?: continue
            } catch (e: ClosedWatchServiceException) {
                return
            } catch (e: InterruptedException) {
                return
            }
            val directory = key.watchable() as? Path
            for (event in key.pollEvents()) {
                val name = event.context() as? Path ?: continue
                queue.add(directory?.resolve(name)?.toFile() ?: name.toFile())
            }
            if (!key.reset()) {
                return
            }
        }
    }

    private companion object {
        const val POLL_MILLIS = 250L
    }
}
