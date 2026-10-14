package world.gregs.voidps.cache.source.codec

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.BillboardType
import world.gregs.voidps.cache.definition.data.ParticleDefinition
import world.gregs.voidps.cache.definition.data.ParticleProducerDefinition
import world.gregs.voidps.cache.definition.decoder.BillboardDecoder
import world.gregs.voidps.cache.definition.decoder.ParticleDecoder
import world.gregs.voidps.cache.definition.decoder.ParticleProducerDecoder
import world.gregs.voidps.cache.definition.encoder.BillboardEncoder
import world.gregs.voidps.cache.definition.encoder.ParticleEncoder
import world.gregs.voidps.cache.definition.encoder.ParticleProducerEncoder
import world.gregs.voidps.cache.source.ArchiveMetadata
import java.nio.file.Files
import java.nio.file.Path

/**
 * The three indices a model needs to draw itself: its own geometry, the particle configs its
 * footer emits, and the billboard configs its footer pins to a face.
 *
 * All three are editable rather than bytes, and all three are byte exact for the whole 727 cache:
 *
 * | index | dir | codec | layout |
 * |---|---|---|---|
 * | 7 | `models` | [ModelCodec] | `models/<id>.json`, one archive per model |
 * | 27 | `config_particle` | [ParticleCodec] | `config_particle/producer/<id>.json`, `.../particle/<id>.json` |
 * | 29 | `config_billboard` | [DefinitionJsonCodec] | `config_billboard/<id>.json`, one archive |
 *
 * [register] is the one line [SourceCodecs.reset] calls, so nothing here has to be repeated there.
 */
object ModelCodecs {

    /** Index 29 has one archive whose files are the billboard ids; nine bits covers all 360. */
    private const val BILLBOARD_BITS = 9

    /** `config_particle/producer`, the client's `ParticleProducerType` (archive 0). */
    const val PRODUCER_DIRECTORY = "producer"

    /** `config_particle/particle`, the client's `ParticleType` (archive 1). */
    const val PARTICLE_DIRECTORY = "particle"

    /** Index 29 as one JSON file per billboard, named by its id. */
    val billboards: DefinitionJsonCodec<BillboardType> = run {
        val decoder = BillboardDecoder()
        val encoder = BillboardEncoder()
        DefinitionJsonCodec(
            id = "json-billboard",
            layout = DefinitionLayout.Packed(BILLBOARD_BITS),
            create = { BillboardType(id = it) },
            decode = { definition, bytes -> decoder.readLoop(definition, BufferReader(bytes)) },
            encode = { definition, writer -> with(encoder) { writer.encode(definition) } }
        )
    }

    /** Index 27 archive 0, inside its own directory. */
    val producers: DefinitionJsonCodec<ParticleProducerDefinition> = run {
        val decoder = ParticleProducerDecoder()
        val encoder = ParticleProducerEncoder()
        DefinitionJsonCodec(
            id = "json-particle-producer",
            layout = DefinitionLayout.Flat,
            create = { ParticleProducerDefinition(id = it) },
            decode = { definition, bytes -> decoder.readLoop(definition, BufferReader(bytes)) },
            encode = { definition, writer -> with(encoder) { writer.encode(definition) } }
        )
    }

    /** Index 27 archive 1, inside its own directory. */
    val particles: DefinitionJsonCodec<ParticleDefinition> = run {
        val decoder = ParticleDecoder()
        val encoder = ParticleEncoder()
        DefinitionJsonCodec(
            id = "json-particle",
            layout = DefinitionLayout.Flat,
            create = { ParticleDefinition(id = it) },
            decode = { definition, bytes -> decoder.readLoop(definition, BufferReader(bytes)) },
            encode = { definition, writer -> with(encoder) { writer.encode(definition) } }
        )
    }

    /** Claim indices 7, 27 and 29. Called by [SourceCodecs.reset]. */
    fun register() {
        SourceCodecs.builtin(Index.MODELS, ModelCodec)
        SourceCodecs.builtin(Index.PARTICLES, ParticleCodec)
        SourceCodecs.builtin(Index.BILLBOARDS, billboards)
    }
}

/**
 * Index 27, whose two archives are two unrelated types: producers in archive 0 and the particles
 * they spawn in archive 1.
 *
 * The same shape as [ConfigCodec], for the same reason and in miniature: each archive gets a
 * directory of its own and a codec of its own, and the sub-codec lays its files out inside that
 * directory without repeating the archive ([DefinitionLayout.Flat]). An archive nothing has a
 * codec for would be raw bytes, but 727 has only these two.
 */
object ParticleCodec : SourceCodec {

    override val id: String
        get() = "particle"

    /** [archive]'s directory name under `config_particle/`. */
    fun name(archive: Int): String = when (archive) {
        ParticleProducerDecoder.ARCHIVE -> ModelCodecs.PRODUCER_DIRECTORY
        ParticleDecoder.ARCHIVE -> ModelCodecs.PARTICLE_DIRECTORY
        else -> "${ConfigCodec.UNNAMED_PREFIX}$archive"
    }

    /** The archive [name] is the directory of, or -1. */
    fun archive(name: String): Int = when (name) {
        ModelCodecs.PRODUCER_DIRECTORY -> ParticleProducerDecoder.ARCHIVE
        ModelCodecs.PARTICLE_DIRECTORY -> ParticleDecoder.ARCHIVE
        else -> if (!name.startsWith(ConfigCodec.UNNAMED_PREFIX)) {
            -1
        } else {
            name.substring(ConfigCodec.UNNAMED_PREFIX.length).toIntOrNull() ?: -1
        }
    }

    /** [archive]'s codec, raw files by default. */
    fun codec(archive: Int): SourceCodec = when (archive) {
        ParticleProducerDecoder.ARCHIVE -> ModelCodecs.producers
        ParticleDecoder.ARCHIVE -> ModelCodecs.particles
        else -> RawFilesCodec.FLAT
    }

    override fun exact(archive: Int): Boolean = codec(archive).exact(archive)

    override fun fileIdsFromMetadata(archive: Int): Boolean = codec(archive).fileIdsFromMetadata(archive)

    override fun unpack(directory: Path, archive: SourceArchive): UnpackedArchive {
        val name = name(archive.archive)
        val unpacked = codec(archive.archive).unpack(directory.resolve(name), archive)
        return UnpackedArchive(unpacked.files.map { SourceFile("$name/${it.path}", it.bytes) }, unpacked.pristine)
    }

    override fun pack(directory: Path, archive: Int, metadata: ArchiveMetadata): PackedArchive =
        codec(archive).pack(directory.resolve(name(archive)), archive, metadata)

    override fun archiveOf(path: String): Int? {
        val separator = path.indexOf('/')
        if (separator <= 0) {
            return null
        }
        val archive = archive(path.substring(0, separator))
        return if (archive < 0) null else archive
    }

    override fun files(directory: Path, archive: Int): List<Path> {
        val folder = directory.resolve(name(archive))
        if (!Files.isDirectory(folder)) {
            return emptyList()
        }
        return codec(archive).files(folder, archive)
    }
}
