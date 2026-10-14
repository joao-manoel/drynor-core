package org.darkan.tools.cache.map.environment

import org.darkan.core.EnvVars
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.secure.CRC
import world.gregs.voidps.cache.store.Container
import world.gregs.voidps.cache.store.FileStore
import world.gregs.voidps.cache.store.ReferenceTable
import java.io.File
import java.math.BigInteger
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Writes an edited region environment back into the `m{x}_{y}` archive it came from.
 *
 * This is the only code in the tools that mutates the user's cache, so every step is
 * guarded:
 *
 * 1. **Planning is separate from writing.** [plan] does all the decoding, merging and
 *    verification and produces a [EnvironmentWritePlan] describing exactly what would change.
 *    Nothing touches the filesystem until [apply] is called, which is what the CLI's dry-run
 *    mode and the editor's diff preview both use.
 * 2. **Per-region round trip before every write.** The current archive is decoded and
 *    re-encoded *unchanged* first; unless that reproduces the original bytes exactly, the
 *    plan is marked blocked and [apply] refuses. The cache-wide proof is `tools env
 *    roundtrip`, but a save also re-proves it for the one region it is about to overwrite.
 * 3. **Tile bytes are spliced, never re-encoded.** Only the effects block at the tail is
 *    regenerated; the `4 * 64 * 64` tile records in front of it are copied verbatim.
 * 4. **The original is backed up before the first write of a session**, as the raw container
 *    bytes, which is exactly what a restore needs.
 * 5. **Read-back verification.** After writing, the archive is read straight off disk again,
 *    decompressed, and compared byte for byte with what was meant to be written, and the
 *    reference table is re-read and re-decoded to check the CRC and version it now carries.
 *
 * The write itself is [Cache.write] on a cache opened writable
 * ([FileCache.load] with `writable = true`), so the container is recompressed with the
 * compression the archive already used, the reference table's CRC, version and file list are
 * brought in line, and the RSA-signed version table is rebuilt - all by the shared cache
 * library, on the same [FileStore] the server reads through.
 *
 * Saving is always explicit. Nothing here is wired to region changes, panel edits or
 * shutdown.
 */
object MapEnvironmentWriter {

    private const val BACKUP_DIR_PROPERTY = "darkan.cacheBackupDir"

    /** Archives already backed up this session, so a re-save cannot overwrite the pristine copy. */
    private val backedUp: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /**
     * Payloads written this session, keyed by archive id, and the CRC of the container they
     * went into.
     *
     * The live [Cache] memoises decompressed archives and raw sectors with no invalidation
     * hook, so after a save it keeps handing out the pre-save bytes. Planning a second save
     * off those would merge the new edits into the *old* structure and quietly undo the
     * first. The CRC is the plan's identity token: [apply] compares it against a fresh read
     * of the container on disk and refuses if anything moved underneath it.
     */
    private val written = ConcurrentHashMap<Int, ByteArray>()
    private val writtenContainerCrc = ConcurrentHashMap<Int, Int>()

    private val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /** Directory backups land in. Overridable with `-D$BACKUP_DIR_PROPERTY`. */
    fun backupDirectory(): File {
        System.getProperty(BACKUP_DIR_PROPERTY)?.let { return File(it) }
        val working = File(System.getProperty("user.dir"))
        val tools = File(working, "tools")
        return File(if (tools.isDirectory) tools else working, "cache-backups")
    }

    fun cacheDirectory(): File = File(EnvVars.cachePath)

    /**
     * Work out what saving [edit] into `m{regionX}_{regionY}` would do, without writing.
     *
     * Returns a plan whose [EnvironmentWritePlan.blocked] is non-null when the write must not
     * proceed - a missing archive, a round-trip failure, or a merge that does not survive a
     * decode/encode cycle of its own.
     */
    fun plan(cache: Cache, regionX: Int, regionY: Int, edit: EnvironmentEdit): EnvironmentWritePlan {
        val name = "m${regionX}_$regionY"
        val archiveId = cache.archiveId(Index.MAPS, name)
        if (archiveId == -1) {
            return blocked(regionX, regionY, -1, name, "region ($regionX, $regionY) has no map archive")
        }
        // Every `m` archive in this cache holds exactly one file, and the write replaces that
        // file by id. More than one would mean the effects block is not the whole group and the
        // splice below is addressing the wrong bytes, so refuse rather than guess.
        val files = cache.files(Index.MAPS, archiveId)
        if (files.size != 1) {
            return blocked(
                regionX, regionY, archiveId, name,
                "$name has ${files.size} files; the writer only handles a single-file map archive",
            )
        }
        val fileId = files[0]
        val original = written[archiveId]
            ?: cache.data(Index.MAPS, archiveId, fileId)
            ?: return blocked(regionX, regionY, archiveId, name, "map archive $archiveId has no data")
        val containerCrc = writtenContainerCrc[archiveId]
            ?: cache.sector(Index.MAPS, archiveId)?.let { token(it) }

        val layout = try {
            MapEnvironmentDecoder.readLayout(original, regionX, regionY)
        } catch (exception: Exception) {
            return blocked(regionX, regionY, archiveId, name, "effects block does not decode: ${exception.message}")
        }

        // Guard 2: prove the encoder is byte-exact for this archive before trusting it with it.
        val reEncoded = MapEnvironmentEncoder.encodeArchive(original, layout)
        if (!reEncoded.contentEquals(original)) {
            return blocked(
                regionX, regionY, archiveId, name,
                "re-encoding $name unchanged does not reproduce the original bytes " +
                    "(${original.size} -> ${reEncoded.size}); refusing to write",
            )
        }

        // The merge only ever touches the first opcode-0 / opcode-128 block, because a later
        // one would overwrite it at decode time and the edit would silently do nothing. No
        // archive in this cache has more than one of either, so refuse rather than guess.
        val environmentBlocks = layout.blocks.count { it is EnvironmentRecord }
        val skyboxBlocks = layout.blocks.count { it is SkyboxRecord }
        if (environmentBlocks > 1 || skyboxBlocks > 1) {
            return blocked(
                regionX, regionY, archiveId, name,
                "$name has $environmentBlocks environment and $skyboxBlocks skybox blocks; " +
                    "merging into a repeated block is not supported",
            )
        }

        val merged = MapEnvironmentEncoder.merge(layout, edit)
        val updated = MapEnvironmentEncoder.encodeArchive(original, merged)

        // Guard: whatever we are about to write must itself decode and round-trip.
        val verify = try {
            MapEnvironmentDecoder.readLayout(updated, regionX, regionY)
        } catch (exception: Exception) {
            return blocked(
                regionX, regionY, archiveId, name,
                "the edited effects block does not decode back: ${exception.message}",
            )
        }
        if (!MapEnvironmentEncoder.encodeArchive(updated, verify).contentEquals(updated)) {
            return blocked(
                regionX, regionY, archiveId, name,
                "the edited effects block does not round-trip; refusing to write",
            )
        }

        return EnvironmentWritePlan(
            regionX = regionX,
            regionY = regionY,
            archiveId = archiveId,
            fileId = fileId,
            archiveName = name,
            original = original,
            updated = updated,
            originalLayout = layout,
            updatedLayout = merged,
            changes = describeChanges(layout, merged),
            blocked = null,
            containerCrc = containerCrc,
        )
    }

    /** Perform the write described by [plan]. Throws rather than half-writing. */
    fun apply(plan: EnvironmentWritePlan): EnvironmentWriteResult {
        check(plan.blocked == null) { "Refusing to apply a blocked plan: ${plan.blocked}" }
        check(plan.hasChanges) { "Nothing to write - the edit matches what is already in the cache" }

        val cacheDir = cacheDirectory()
        val backupDir = backupDirectory()
        val log = ArrayList<String>()

        // Writable so Cache.write works; the RSA key pair so the version table it rebuilds is
        // signed the way the JS5 server will serve it.
        val cache = FileCache.load(
            cacheDir.path,
            BigInteger(EnvVars.js5RsaExponent),
            BigInteger(EnvVars.js5RsaModulus),
            writable = true,
        )
        try {
            val container = cache.container(Index.MAPS, plan.archiveId)
                ?: throw IllegalStateException("Could not read the current container for ${plan.archiveName}")

            // Guard: the plan was computed from a specific set of bytes. If the archive on
            // disk is no longer those bytes, the merge behind this plan is meaningless.
            val onDisk = token(container)
            check(plan.containerCrc == null || plan.containerCrc == onDisk) {
                "${plan.archiveName} changed on disk since the preview was taken " +
                    "(container crc ${plan.containerCrc} vs $onDisk); take a fresh preview"
            }

            backup(backupDir, "${plan.archiveName}.idx${Index.MAPS}.a${plan.archiveId}", container)?.let {
                log.add("backed up ${container.size} container bytes to ${it.absolutePath}")
            }
            val tableBefore = cache.container(FileStore.REFERENCE_INDEX, Index.MAPS)
            if (tableBefore != null) {
                backup(backupDir, "referencetable.idx${FileStore.REFERENCE_INDEX}.a${Index.MAPS}", tableBefore)?.let {
                    log.add("backed up the index ${Index.MAPS} reference table to ${it.absolutePath}")
                }
            }

            val compression = Container.decode(container).compression
            val version = cache.archiveVersion(Index.MAPS, plan.archiveId)
            val crc = cache.archiveCrc(Index.MAPS, plan.archiveId)

            cache.write(Index.MAPS, plan.archiveId, plan.fileId, plan.updated)

            // Guard 5: read the archive straight back off the store, decompress it, and compare.
            // The container bytes are core's to build now, so the proof is that what comes back
            // out of the sectors decompresses to exactly what was meant to go in.
            val readBack = cache.container(Index.MAPS, plan.archiveId)
                ?: throw IllegalStateException("Wrote ${plan.archiveName} but could not read it back")
            val decoded = Container.decode(readBack)
            check(decoded.compression == compression) {
                "Read-back of ${plan.archiveName} is ${decoded.compression}, not the $compression it was stored as"
            }
            check(decoded.data().contentEquals(plan.updated)) {
                "Read-back payload of ${plan.archiveName} differs (${decoded.decompressedSize} vs ${plan.updated.size} bytes)"
            }
            val newVersion = cache.archiveVersion(Index.MAPS, plan.archiveId)
            check(newVersion == version + 1) {
                "${plan.archiveName} went from version $version to $newVersion; a write bumps it by one"
            }
            check(decoded.version == (newVersion and 0xffff)) {
                "${plan.archiveName}'s container trailer says ${decoded.version}, not ${newVersion and 0xffff}"
            }
            val newCrc = CRC.calculate(readBack, 0, readBack.size - if (decoded.version == null) 0 else TRAILER)
            log.add(
                "wrote ${readBack.size} container bytes (${plan.updated.size} payload, " +
                    "recompressed as ${compression.name.lowercase()}) for ${plan.archiveName}"
            )
            log.add("read-back verified")
            log.add("archive version $version -> $newVersion")

            // Guard 5, second half: the table a client validates against is the one on disk, so
            // re-read and re-decode it rather than trusting the model core just updated.
            log.addAll(verifyTable(cache, plan, newCrc, newVersion, crc))

            // The next plan for this archive has to start from these bytes, not the stale
            // ones the live Cache is still memoising.
            writtenContainerCrc[plan.archiveId] = token(readBack)
        } finally {
            cache.close()
        }

        // The live Cache still holds the pre-save bytes in its own caches, so seed the
        // environment loader with what was written rather than letting a later read of the
        // stale archive undo the edit in the viewer.
        written[plan.archiveId] = plan.updated
        MapEnvironmentLoader.store(
            plan.regionX,
            plan.regionY,
            MapEnvironmentDecoder.buildRegion(plan.updatedLayout, plan.regionX, plan.regionY),
        )

        return EnvironmentWriteResult(plan, log)
    }

    /**
     * Re-read index 255's archive for index 5 off the store, decode it, and check the entry it
     * now holds for [plan]'s archive describes the container that was just written.
     *
     * [Cache.write] rewrote the table (and the signed version table derived from it); this only
     * ever reads, so a disagreement means the write did not land the way core reported it.
     */
    private fun verifyTable(
        cache: Cache,
        plan: EnvironmentWritePlan,
        crc: Int,
        version: Int,
        previousCrc: Int,
    ): List<String> {
        val log = ArrayList<String>()
        val container = cache.container(FileStore.REFERENCE_INDEX, Index.MAPS)
            ?: throw IllegalStateException("Wrote ${plan.archiveName} but index ${Index.MAPS} has no reference table")
        val decoded = Container.decode(container)
        val table = ReferenceTable.decode(decoded.data())
        val entry = table.archive(plan.archiveId)
            ?: throw IllegalStateException("The reference table read back has no entry for archive ${plan.archiveId}")
        check(entry.crc == crc) {
            "Reference table read-back has crc ${entry.crc}, expected $crc"
        }
        check(entry.version == version) {
            "Reference table read-back has version ${entry.version}, expected $version"
        }
        check(entry.fileIds.contentEquals(intArrayOf(plan.fileId))) {
            "Reference table read-back lists files ${entry.fileIds.toList()} for archive ${plan.archiveId}, " +
                "expected only ${plan.fileId}"
        }
        log.add(
            "reference table: archive ${plan.archiveId} crc " +
                "0x${previousCrc.toUInt().toString(16)} -> 0x${crc.toUInt().toString(16)}, version -> $version"
        )
        log.add(
            "reference table rewritten as ${decoded.compression.name.lowercase()} (${container.size} bytes), " +
                "version table re-signed"
        )
        return log
    }

    private fun backup(directory: File, name: String, container: ByteArray): File? {
        if (!backedUp.add(name)) return null
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Could not create the backup directory ${directory.absolutePath}")
        }
        // Never overwrite an existing backup: two saves inside the same second would
        // otherwise replace the pristine copy with an already-modified one.
        val stamp = LocalDateTime.now().format(timestamp)
        var file = File(directory, "$name.$stamp.container")
        var attempt = 1
        while (file.exists()) {
            file = File(directory, "$name.$stamp-$attempt.container")
            attempt++
        }
        file.writeBytes(container)
        return file
    }

    private fun blocked(
        regionX: Int,
        regionY: Int,
        archiveId: Int,
        name: String,
        reason: String,
    ) = EnvironmentWritePlan(
        regionX = regionX,
        regionY = regionY,
        archiveId = archiveId,
        fileId = 0,
        archiveName = name,
        original = ByteArray(0),
        updated = ByteArray(0),
        originalLayout = EnvironmentLayout.EMPTY,
        updatedLayout = EnvironmentLayout.EMPTY,
        changes = emptyList(),
        blocked = reason,
        containerCrc = null,
    )

    /** Human-readable field-level diff of the effects block. */
    private fun describeChanges(before: EnvironmentLayout, after: EnvironmentLayout): List<String> {
        val changes = ArrayList<String>()
        val old = before.environmentIndex().let { if (it >= 0) before.blocks[it] as EnvironmentRecord else null }
        val new = after.environmentIndex().let { if (it >= 0) after.blocks[it] as EnvironmentRecord else null }

        if (old == null && new != null) changes.add("opcode 0 environment block added")
        if (new != null) {
            // An absent block diffs against the all-flags-clear shape, so the fields that
            // caused it to be added still show up individually.
            val old = old ?: EnvironmentRecord.EMPTY
            colourChange(changes, "sun colour", old.sunColour, new.sunColour, MapEnvironment.DEFAULT_SUN_COLOUR)
            scaledChange(changes, "sun ambient", old.sunAmbient, new.sunAmbient, MapEnvironment.DEFAULT_SUN_AMBIENT)
            scaledChange(changes, "key light", old.sunLight, new.sunLight, MapEnvironment.DEFAULT_SUN_LIGHT)
            scaledChange(changes, "backlight", old.sunBacklight, new.sunBacklight, MapEnvironment.DEFAULT_SUN_BACKLIGHT)
            if (!positionEquals(old.sunPosition, new.sunPosition)) {
                changes.add("sun position: ${position(old.sunPosition)} -> ${position(new.sunPosition)}")
            }
            colourChange(changes, "fog colour", old.fogColour, new.fogColour, MapEnvironment.DEFAULT_FOG_COLOUR)
            if (old.fogDepth != new.fogDepth) {
                changes.add("fog depth: ${old.fogDepth ?: "absent (0)"} -> ${new.fogDepth ?: "absent (0)"}")
            }
            if (old.flags() != new.flags()) {
                changes.add(
                    "flag byte: 0x${old.flags().toString(16).padStart(2, '0')} -> " +
                        "0x${new.flags().toString(16).padStart(2, '0')}"
                )
            }
        }

        val oldSky = before.skyboxIndex().let { if (it >= 0) before.blocks[it] as SkyboxRecord else null }
        val newSky = after.skyboxIndex().let { if (it >= 0) after.blocks[it] as SkyboxRecord else null }
        when {
            oldSky == null && newSky != null ->
                changes.add("skybox: none -> id ${newSky.id} rotation ${newSky.rotation}")
            oldSky != null && newSky == null ->
                changes.add("skybox: id ${oldSky.id} -> none (opcode 128 block removed)")
            oldSky != null && newSky != null && (oldSky.id != newSky.id || oldSky.rotation != newSky.rotation) ->
                changes.add(
                    "skybox: id ${oldSky.id} rotation ${oldSky.rotation} -> " +
                        "id ${newSky.id} rotation ${newSky.rotation}"
                )
        }
        return changes
    }

    private fun colourChange(into: MutableList<String>, label: String, old: Int?, new: Int?, default: Int) {
        if (old == new) return
        into.add("$label: ${colour(old, default)} -> ${colour(new, default)}")
    }

    private fun scaledChange(into: MutableList<String>, label: String, old: Int?, new: Int?, default: Float) {
        if (old == new) return
        into.add("$label: ${scaled(old, default)} -> ${scaled(new, default)}")
    }

    private fun colour(value: Int?, default: Int): String = if (value == null) {
        "absent (0x${(default and 0xffffff).toString(16).padStart(6, '0')})"
    } else {
        "0x${(value and 0xffffff).toString(16).padStart(6, '0')}"
    }

    private fun scaled(value: Int?, default: Float): String = if (value == null) {
        "absent (%.4f)".format(default)
    } else {
        "%.4f (raw $value)".format(value / 256.0f)
    }

    private fun position(value: IntArray?): String =
        if (value == null) "absent" else "(${value[0]}, ${value[1]}, ${value[2]})"

    private fun positionEquals(a: IntArray?, b: IntArray?): Boolean = when {
        a == null && b == null -> true
        a == null || b == null -> false
        else -> a.contentEquals(b)
    }

    /**
     * The identity of a set of container bytes, used only to notice that an archive moved
     * underneath a plan. It covers the version trailer as well, which the JS5 CRC does not:
     * a bumped version with an unchanged payload still invalidates a plan.
     */
    private fun token(container: ByteArray): Int = CRC.calculate(container, 0, container.size)

    /** Every archive container in indices 0-35 ends in a two byte version trailer. */
    private const val TRAILER = 2
}

/** What a save would do, produced without touching the filesystem. */
class EnvironmentWritePlan(
    val regionX: Int,
    val regionY: Int,
    val archiveId: Int,
    /** The archive's single file, which is the one the write replaces. */
    val fileId: Int,
    val archiveName: String,
    val original: ByteArray,
    val updated: ByteArray,
    val originalLayout: EnvironmentLayout,
    val updatedLayout: EnvironmentLayout,
    /** Field-level description of the difference, one line per changed field. */
    val changes: List<String>,
    /** Non-null when the write must not proceed; [MapEnvironmentWriter.apply] refuses. */
    val blocked: String?,
    /**
     * CRC of the container these bytes came out of, or null when it could not be read.
     * [MapEnvironmentWriter.apply] re-reads the container and refuses if it has moved on.
     */
    val containerCrc: Int?,
) {
    val hasChanges: Boolean get() = !updated.contentEquals(original)

    val effectsSizeBefore: Int get() = original.size - originalLayout.tileSectionLength
    val effectsSizeAfter: Int get() = updated.size - updatedLayout.tileSectionLength

    fun summary(): String = when {
        blocked != null -> "BLOCKED: $blocked"
        !hasChanges -> "no change - $archiveName already matches the editor"
        else -> "$archiveName (archive $archiveId): effects $effectsSizeBefore -> $effectsSizeAfter bytes, " +
            "archive ${original.size} -> ${updated.size} bytes"
    }
}

/** Result of a completed write, with the audit log of every step taken. */
class EnvironmentWriteResult(val plan: EnvironmentWritePlan, val log: List<String>)
