package org.darkan.tools.rsinterface

import java.io.File
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.InterfaceComponentDefinition
import world.gregs.voidps.cache.definition.decoder.InterfaceDecoder
import world.gregs.voidps.cache.source.ArchiveMetadata
import world.gregs.voidps.cache.source.IndexMetadata
import world.gregs.voidps.cache.source.SourceTree
import world.gregs.voidps.cache.source.codec.SourceCodecs
import world.gregs.voidps.cache.store.ArchiveGroup

/**
 * A cache source tree the editor reads live, without packing it first.
 *
 * Index 3 goes through the tree's own codec rather than a second reader of the JSON: [components]
 * packs the interface's directory exactly as a build would and decodes the bytes that come out, so
 * what the stage shows is what the next `cache build` will put in the cache - pristine sidecars,
 * encoder quirks and all. Interfaces the tree does not hold fall back to the packed cache the
 * viewer opened, which is also where every graphic, font and model still comes from.
 */
class InterfaceSourceTree private constructor(val root: File, private val tree: SourceTree) {

    val clientscripts: File = File(root, CLIENTSCRIPTS)

    val interfaces: File = tree.indexDirectory(Index.INTERFACES).toFile()

    private val codec = SourceCodecs.codec(Index.INTERFACES)
    private val decoder = InterfaceDecoder()

    @Volatile
    private var metadata: IndexMetadata? = null

    val name: String get() = root.name

    fun directory(interfaceId: Int): File = File(interfaces, interfaceId.toString())

    fun has(interfaceId: Int): Boolean = directory(interfaceId).isDirectory

    fun ids(): List<Int> = interfaces.listFiles().orEmpty()
        .mapNotNull { if (it.isDirectory) it.name.toIntOrNull() else null }
        .sorted()

    // A gap in the file ids yields a default component, as the cache decoder does; the index is the position.
    fun components(interfaceId: Int): List<InterfaceComponentDefinition> {
        val packed = codec.pack(interfaces.toPath(), interfaceId, archive(interfaceId))
        val files = ArchiveGroup.split(packed.group, packed.fileIds).files
        val count = (packed.fileIds.maxOrNull() ?: -1) + 1
        val components = Array(count) { InterfaceComponentDefinition(id = (interfaceId shl 16) or it) }
        for ((position, file) in packed.fileIds.withIndex()) {
            with(decoder) { components[file].read(BufferReader(files[position])) }
        }
        return components.toList()
    }

    fun invalidate() {
        metadata = null
    }

    private fun archive(interfaceId: Int): ArchiveMetadata {
        var current = metadata
        if (current == null) {
            current = tree.metadata(Index.INTERFACES)
            metadata = current
        }
        return current.archive(interfaceId) ?: ArchiveMetadata()
    }

    override fun toString(): String = root.path

    companion object {
        const val CLIENTSCRIPTS = "clientscripts"

        private val DEFAULTS = listOf("cache-source", "../cache-source")

        fun isTree(directory: File): Boolean = File(directory, SourceTree.CACHE_FILE).isFile

        fun rootOf(path: File): File? {
            var current: File? = path.absoluteFile
            var depth = 0
            while (current != null && depth < MAX_DEPTH) {
                if (isTree(current)) return current
                current = current.parentFile
                depth++
            }
            return null
        }

        fun open(path: File): InterfaceSourceTree {
            val root = rootOf(path) ?: throw IllegalArgumentException("${path.path} is not inside a cache source tree")
            return InterfaceSourceTree(root, SourceTree.open(root))
        }

        fun discover(): File? = DEFAULTS.map(::File).firstOrNull { isTree(it) }

        private const val MAX_DEPTH = 6
    }
}
