package org.drynor.tools.osrs

import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.secure.CRC
import world.gregs.voidps.cache.store.ArchiveGroup
import world.gregs.voidps.cache.store.Compression
import world.gregs.voidps.cache.store.Container
import world.gregs.voidps.cache.store.FileEntry
import world.gregs.voidps.cache.store.FileStore
import world.gregs.voidps.cache.store.ReferenceTable
import java.nio.file.Path

/** Offline preview copy only. Rewrite each group and reference table once, not once per frame. */
internal object InfernoAssetWriter {
    data class Asset(val index: Int, val archive: Int, val file: Int, val bytes: ByteArray, val replaceExisting: Boolean = false)
    fun write(path: Path, assets: List<Asset>) {
        FileStore.open(path.toFile(), writable = true).use { store ->
            for ((index, indexed) in assets.groupBy { it.index }) {
                val table = ReferenceTable.decode(Container.decode(requireNotNull(store.read(255, index))).data())
                for ((archive, additions) in indexed.groupBy { it.archive }) {
                    require(additions.map { it.file }.distinct().size == additions.size)
                    val entry = table.archive(archive)
                    val contents = sortedMapOf<Int, ByteArray>()
                    if (entry != null) {
                        val group = ArchiveGroup.split(Container.decode(requireNotNull(store.read(index, archive))).data(), entry.fileIds)
                        for ((position, id) in entry.fileIds.withIndex()) contents[id] = group.files[position]
                    }
                    for (asset in additions) {
                        require((asset.file in contents) == asset.replaceExisting) { "Unexpected overwrite/presence: $index/$archive/${asset.file}" }
                        contents[asset.file] = asset.bytes
                    }
                    val files = contents.values.toList()
                    val chunks = if (index == Index.ANIMATION_FRAMES && files.size > 1) listOf(
                        IntArray(files.size) { 4 }, IntArray(files.size) { files[it][3].toInt() and 255 },
                        IntArray(files.size) { files[it].size - 4 - (files[it][3].toInt() and 255) },
                    ) else null
                    val version = (entry?.version ?: 0) + 1
                    val container = Container.compress(ArchiveGroup.join(files, chunks), Compression.GZIP, version and 0xffff)
                    val bytes = container.encode()
                    store.write(index, archive, bytes)
                    val target = table.archiveOrCreate(archive)
                    target.crc = CRC.calculate(bytes, 0, bytes.size - 2)
                    target.version = version
                    if (table.whirlpool) target.whirlpool = container.whirlpool()
                    val names = target.files.associate { it.id to it.nameHash }
                    target.files.clear()
                    for (id in contents.keys) target.files.add(FileEntry(id, names[id] ?: 0))
                }
                store.write(255, index, Container.compress(table.encode(), Compression.GZIP, null).encode())
            }
        }
    }
}
