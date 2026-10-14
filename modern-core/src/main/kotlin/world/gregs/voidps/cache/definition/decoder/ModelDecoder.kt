package world.gregs.voidps.cache.definition.decoder

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.BillboardData
import world.gregs.voidps.cache.definition.data.ModelDefinition
import world.gregs.voidps.cache.definition.data.ParticleEffectorAttachment
import world.gregs.voidps.cache.definition.data.ParticleEmitterAttachment

/**
 * Why a model id produced no [ModelDefinition].
 *
 * [ModelDecoder.decode] collapses every one of these to `null`, which reads like a decoder
 * bug wherever it is reported. [ModelDecoder.describeUnavailable] separates a genuine gap in
 * the cache data from a failure that is ours to fix.
 */
enum class ModelUnavailableReason {
    /** No archive under this id in [Index.MODELS] — the id is not in the cache at all. */
    MISSING_ARCHIVE,

    /**
     * The archive is present and decompresses, but declares zero vertices or zero faces.
     *
     * Rev727 ships exactly eight of these — 1077, 2862, 4420, 6932, 15553, 15554, 15555 and
     * 15556 — and all eight are byte-identical 24-byte placeholders: version 15, every footer
     * flag clear except `hasVersion`, and the `0xFFFF` new-format marker. There is no geometry
     * to recover. The reference client builds an equally empty `Mesh` from the same bytes
     * (`Mesh.decodeNewFormat` allocates `numVertices == 0` arrays and returns), so object
     * instances pointing at these ids draw nothing in the real game either.
     */
    EMPTY_PLACEHOLDER,

    /** Fewer bytes than a header needs, or declared sections running past the end of the data. */
    TRUNCATED,

    /** The archive threw while decoding. Unlike the others, this one is a bug on our side. */
    DECODE_ERROR
}

/** A [ModelUnavailableReason] together with a message naming the evidence behind it. */
data class ModelUnavailable(val reason: ModelUnavailableReason, val message: String)

/**
 * Decoder for the RS model binary format (revision 727), both of its layouts.
 *
 * The decode is lossless: every byte of an archive reaches a field of the [ModelDefinition] it
 * produces, so [world.gregs.voidps.cache.definition.encoder.ModelEncoder] can write the archive
 * back exactly. That is more than the renderer needs - it has no use for the skin sections, the
 * particle attachments or the footer's own bytes - and everything it has no use for is kept
 * below the "layout fidelity" line of [ModelDefinition] rather than mixed in with the geometry.
 *
 * The geometry itself keeps the semantics the viewer has always had: a pre-13 model's vertices
 * and complex texture scales arrive already upscaled by four (the client's `Mesh.upscale`), a
 * face's material is the file's short minus one so that -1 means none, and a face's texture
 * pointer is the file's byte minus one. All three are exactly invertible, which is why the
 * encoder can still reproduce the file.
 *
 * Based on the reference client's `Mesh.decodeNewFormat` and `Mesh.decodeOldFormat`.
 */
object ModelDecoder {

    /** Smallest archive that could hold a model header; see [decode]. */
    private const val MIN_MODEL_BYTES = 20

    /** Footer size for the `0xFFFF`-terminated format: 21 header bytes plus the 2 marker bytes. */
    const val NEW_FORMAT_FOOTER_BYTES = 23

    /** Footer size for the pre-`0xFFFF` format. */
    const val OLD_FORMAT_FOOTER_BYTES = 18

    /** Footer flag: the file carries a face render type per face. */
    const val FLAG_FACE_TYPES = 0x1

    /** Footer flag: the file carries particle emitters and effectors. */
    const val FLAG_PARTICLES = 0x2

    /** Footer flag: the file carries billboard attachments. */
    const val FLAG_BILLBOARDS = 0x4

    /** Footer flag: a version byte sits immediately before the footer. */
    const val FLAG_VERSION = 0x8

    /** The version a file with no version byte is read as, and below which vertices are upscaled. */
    const val DEFAULT_VERSION = 12

    fun decode(cache: Cache, modelId: Int): ModelDefinition? {
        val data = cache.data(Index.MODELS, modelId) ?: return null
        return try {
            decode(modelId, data)
        } catch (e: Exception) {
            System.err.println("Failed to decode model $modelId: ${e.message}")
            null
        }
    }

    /**
     * Explain why [modelId] yields no definition, or `null` when it decodes successfully.
     *
     * Both footer layouts put the vertex and face counts in their first four bytes, so the
     * counts can be read without running a full decode. That short parse is repeated here
     * rather than shared with [decodeNewFormat]/[decodeOldFormat] so that this diagnostic
     * stays off the decode hot path and cannot change it; the two footer sizes it depends on
     * are the same named constants the decoders use.
     */
    fun describeUnavailable(cache: Cache, modelId: Int): ModelUnavailable? {
        val data = cache.data(Index.MODELS, modelId)
            ?: return ModelUnavailable(
                ModelUnavailableReason.MISSING_ARCHIVE,
                "no archive $modelId in index ${Index.MODELS}: this id is not in the cache"
            )

        if (data.size < MIN_MODEL_BYTES) {
            return ModelUnavailable(
                ModelUnavailableReason.TRUNCATED,
                "archive is only ${data.size} bytes, too short to hold a model header"
            )
        }

        val isNewFormat = newFormat(data)
        val footerOffset = data.size - if (isNewFormat) NEW_FORMAT_FOOTER_BYTES else OLD_FORMAT_FOOTER_BYTES
        if (footerOffset < 0) {
            return ModelUnavailable(
                ModelUnavailableReason.TRUNCATED,
                "archive is ${data.size} bytes, shorter than its own footer"
            )
        }

        val reader = BufferReader(data)
        reader.position(footerOffset)
        val vertexCount = reader.readUnsignedShort()
        val faceCount = reader.readUnsignedShort()
        if (vertexCount == 0 || faceCount == 0) {
            return ModelUnavailable(
                ModelUnavailableReason.EMPTY_PLACEHOLDER,
                "archive is present (${data.size} bytes) and decompresses cleanly, but declares " +
                        "$vertexCount vertices and $faceCount faces: an empty placeholder with no " +
                        "geometry. Nothing can draw it, and the reference client draws nothing either"
            )
        }

        return try {
            if (decode(modelId, data) == null) {
                ModelUnavailable(
                    ModelUnavailableReason.TRUNCATED,
                    "header declares $vertexCount vertices and $faceCount faces, but the sections " +
                            "they describe run past the end of the ${data.size}-byte archive"
                )
            } else {
                null
            }
        } catch (e: Exception) {
            ModelUnavailable(
                ModelUnavailableReason.DECODE_ERROR,
                "decoding threw ${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    /** Whether [data] ends in the `0xFFFF` marker, which is what tells the two layouts apart. */
    fun newFormat(data: ByteArray): Boolean =
        data.size >= 2 && data[data.size - 1] == (-1).toByte() && data[data.size - 2] == (-1).toByte()

    /**
     * [data] as a definition that reproduces it, whatever it holds.
     *
     * The lossless entry point, and the one the source tree's codec uses. Unlike [decode] it never
     * answers null: an archive that declares no geometry - the eight 24-byte placeholders rev727
     * ships - is a model with no vertices and no faces, which is exactly what the client builds
     * from the same bytes, and is a thing that has to round trip like any other.
     *
     * @throws IllegalStateException when the sections the footer declares do not fit in [data]
     */
    fun read(modelId: Int, data: ByteArray): ModelDefinition {
        check(data.size >= MIN_MODEL_BYTES) { "Model $modelId is ${data.size} bytes, too short for a footer." }
        val model = if (newFormat(data)) decodeNewFormat(modelId, data) else decodeOldFormat(modelId, data)
        return checkNotNull(model) { "Model $modelId declares sections that run past its ${data.size} bytes." }
    }

    /**
     * [data] as a definition the renderer can draw, or null when there is nothing to draw.
     *
     * What every caller that means to *use* a model wants: an archive with no vertices or no faces
     * is null here rather than an empty mesh, and so is one whose sections do not fit.
     * [describeUnavailable] says which of those it was. [read] is the total function.
     */
    fun decode(modelId: Int, data: ByteArray): ModelDefinition? {
        if (data.size < MIN_MODEL_BYTES) {
            return null
        }
        val model = if (newFormat(data)) {
            decodeNewFormat(modelId, data)
        } else {
            decodeOldFormat(modelId, data)
        }
        if (model == null || model.vertexCount == 0 || model.faceCount == 0) {
            return null
        }
        return model
    }

    private fun decodeNewFormat(modelId: Int, data: ByteArray): ModelDefinition? {
        val buffer = BufferReader(data)
        val buffer2 = BufferReader(data)

        // Read header from end of file (23 bytes before end)
        buffer.position(data.size - NEW_FORMAT_FOOTER_BYTES)
        val vertexCount = buffer.readUnsignedShort()
        val faceCount = buffer.readUnsignedShort()
        val texturedFaceCount = buffer.readUnsignedByte()
        val footerFlags = buffer.readUnsignedByte()

        val hasFaceTypes = (footerFlags and FLAG_FACE_TYPES) != 0
        val hasParticleEffects = (footerFlags and FLAG_PARTICLES) != 0
        val hasBillboards = (footerFlags and FLAG_BILLBOARDS) != 0
        val hasVersion = (footerFlags and FLAG_VERSION) != 0

        // The version byte sits immediately before the 23 byte footer, so it is read out of order
        // and the cursor put back where the flags left it.
        var version = DEFAULT_VERSION
        if (hasVersion) {
            buffer.position(data.size - NEW_FORMAT_FOOTER_BYTES - 1)
            version = buffer.readUnsignedByte()
            buffer.position(data.size - NEW_FORMAT_FOOTER_BYTES + 6)
        }

        val modelPriority = buffer.readUnsignedByte()
        val hasFaceAlpha = buffer.readUnsignedByte()
        val hasFaceSkins = buffer.readUnsignedByte()
        val hasFaceTextures = buffer.readUnsignedByte()
        val hasVertexSkins = buffer.readUnsignedByte()

        val vertexXLength = buffer.readUnsignedShort()
        val vertexYLength = buffer.readUnsignedShort()
        val vertexZLength = buffer.readUnsignedShort()
        val faceIndicesLength = buffer.readUnsignedShort()
        val textureIndicesLength = buffer.readUnsignedShort()

        // Decode texture mapping types first (they're at the start of the file)
        var simpleTextureFaceCount = 0
        var complexTextureFaceCount = 0
        var cubeTextureFaceCount = 0
        var texturesMapping: ByteArray? = null
        if (texturedFaceCount > 0) {
            texturesMapping = ByteArray(texturedFaceCount)
            buffer.position(0)
            for (i in 0 until texturedFaceCount) {
                val mapping = buffer.readByte().toByte()
                texturesMapping[i] = mapping
                if (mapping.toInt() == 0) {
                    simpleTextureFaceCount++
                }
                if (mapping.toInt() in 1..3) {
                    complexTextureFaceCount++
                }
                if (mapping.toInt() == 2) {
                    cubeTextureFaceCount++
                }
            }
        }

        // Calculate offsets (order matters!)
        // Data starts after textured face count (reference uses texturedFaceCount directly as starting index)
        var offset = texturedFaceCount
        val vertexFlagsOffset = offset
        offset += vertexCount

        val faceTypeOffset = offset
        if (hasFaceTypes) {
            offset += faceCount
        }

        val faceIndexTypesOffset = offset
        offset += faceCount

        val facePrioritiesOffset = offset
        if (modelPriority == PER_FACE_PRIORITY) {
            offset += faceCount
        }

        val faceSkinOffset = offset
        if (hasFaceSkins == 1) {
            offset += faceCount
        }

        val vertexSkinOffset = offset
        if (hasVertexSkins == 1) {
            offset += vertexCount
        }

        val faceAlphasOffset = offset
        if (hasFaceAlpha == 1) {
            offset += faceCount
        }

        val faceIndicesOffset = offset
        offset += faceIndicesLength

        val faceTexturesOffset = offset
        if (hasFaceTextures == 1) {
            offset += faceCount * 2
        }

        // TEXTURE INDICES (faceTexturePointers) - size is textureIndicesLength from footer
        val textureIndicesOffset = offset
        offset += textureIndicesLength

        val faceColorsOffset = offset
        offset += faceCount * 2

        val vertexXOffset = offset
        offset += vertexXLength

        val vertexYOffset = offset
        offset += vertexYLength

        val vertexZOffset = offset
        offset += vertexZLength

        // Texture triangle data offsets
        val simpleTexTriOffset = offset
        offset += simpleTextureFaceCount * 6

        val complexTexTriOffset = offset
        offset += complexTextureFaceCount * 6

        // Complex texture transformation data
        // Calculate bytes per complex texture based on version:
        // - Version < 14: 6 bytes (UShort + UShort + UShort for scales)
        // - Version 14: 7 bytes (UShort + UMedium + UShort for scales)
        // - Version >= 15: 9 bytes (UMedium + UMedium + UMedium for scales)
        val complexTexScaleOffset = offset
        offset += complexTextureFaceCount * scaleBytes(version)

        val complexTexRotationOffset = offset
        offset += complexTextureFaceCount

        val complexTexDirectionOffset = offset
        offset += complexTextureFaceCount

        // Speed offset - 1 byte per complex texture, plus 2 bytes for each cube texture (U/V trans)
        val complexTexSpeedOffset = offset
        offset += complexTextureFaceCount + cubeTextureFaceCount * 2

        // Footer section (particles + billboards) starts here
        val footerOffset = offset

        // Validate data fits
        val minRequired = vertexZOffset + vertexZLength
        if (minRequired > data.size) {
            return null
        }

        // Decode vertex flags
        buffer.position(vertexFlagsOffset)
        val vertexFlags = ByteArray(vertexCount)
        for (i in 0 until vertexCount) {
            vertexFlags[i] = buffer.readByte().toByte()
        }

        // Decode vertices (delta encoded)
        val vertexX = IntArray(vertexCount)
        val vertexY = IntArray(vertexCount)
        val vertexZ = IntArray(vertexCount)

        buffer.position(vertexXOffset)
        var vX = 0
        for (i in 0 until vertexCount) {
            val flag = vertexFlags[i].toInt() and 0xff
            if ((flag and 0x1) != 0) {
                vX += readSmart(buffer)
            }
            vertexX[i] = vX
        }

        buffer.position(vertexYOffset)
        var vY = 0
        for (i in 0 until vertexCount) {
            val flag = vertexFlags[i].toInt() and 0xff
            if ((flag and 0x2) != 0) {
                vY += readSmart(buffer)
            }
            vertexY[i] = vY
        }

        buffer.position(vertexZOffset)
        var vZ = 0
        for (i in 0 until vertexCount) {
            val flag = vertexFlags[i].toInt() and 0xff
            if ((flag and 0x4) != 0) {
                vZ += readSmart(buffer)
            }
            vertexZ[i] = vZ
        }

        // Upscale vertices if version < 13 (required to match scene coordinates)
        // RS models with older format have vertices at 1/4 scale
        val shouldUpscale = version < UPSCALE_BELOW_VERSION
        if (shouldUpscale) {
            for (i in 0 until vertexCount) {
                vertexX[i] = vertexX[i] shl 2
                vertexY[i] = vertexY[i] shl 2
                vertexZ[i] = vertexZ[i] shl 2
            }
        }

        // Vertex skins - one byte per vertex, in a section of their own
        val vertexSkins: IntArray? = if (hasVertexSkins == 1) {
            buffer.position(vertexSkinOffset)
            IntArray(vertexCount) { buffer.readUnsignedByte() }
        } else {
            null
        }

        // Decode face index types
        buffer.position(faceIndexTypesOffset)
        val faceIndexTypes = ByteArray(faceCount)
        for (i in 0 until faceCount) {
            faceIndexTypes[i] = buffer.readByte().toByte()
        }

        // Decode face indices
        val faceA = IntArray(faceCount)
        val faceB = IntArray(faceCount)
        val faceC = IntArray(faceCount)
        buffer.position(faceIndicesOffset)
        readFaces(buffer, faceIndexTypes, faceA, faceB, faceC)

        // Decode colors
        val faceColors = ShortArray(faceCount)
        if (faceColorsOffset + faceCount * 2 <= data.size) {
            buffer.position(faceColorsOffset)
            for (i in 0 until faceCount) {
                faceColors[i] = buffer.readUnsignedShort().toShort()
            }
        }

        // Decode priorities
        val facePriorities: ByteArray? = if (modelPriority == PER_FACE_PRIORITY && facePrioritiesOffset + faceCount <= data.size) {
            buffer.position(facePrioritiesOffset)
            ByteArray(faceCount) { buffer.readByte().toByte() }
        } else {
            null
        }

        // Decode alphas
        val faceAlphas: ByteArray? = if (hasFaceAlpha == 1 && faceAlphasOffset + faceCount <= data.size) {
            buffer.position(faceAlphasOffset)
            ByteArray(faceCount) { buffer.readByte().toByte() }
        } else {
            null
        }

        // Face skins - one byte per face, as the vertex skins are per vertex
        val faceSkins: IntArray? = if (hasFaceSkins == 1 && faceSkinOffset + faceCount <= data.size) {
            buffer.position(faceSkinOffset)
            IntArray(faceCount) { buffer.readUnsignedByte() }
        } else {
            null
        }

        // Decode face types (render types)
        val faceRenderTypes: ByteArray? = if (hasFaceTypes && faceTypeOffset + faceCount <= data.size) {
            buffer.position(faceTypeOffset)
            ByteArray(faceCount) { buffer.readByte().toByte() }
        } else {
            null
        }

        // Decode face textures (material IDs)
        val faceTextures: ShortArray? = if (hasFaceTextures == 1 && faceTexturesOffset + faceCount * 2 <= data.size) {
            buffer.position(faceTexturesOffset)
            ShortArray(faceCount) { (buffer.readUnsignedShort() - 1).toShort() }
        } else {
            null
        }

        // Decode face texture pointers (index into texture triangles for UV projection)
        val faceTexturePointers: ByteArray? = if (hasFaceTextures == 1 && texturedFaceCount > 0 && textureIndicesOffset + textureIndicesLength <= data.size) {
            buffer.position(textureIndicesOffset)
            ByteArray(faceCount) { faceIdx ->
                // Only decode texture pointer if face has a material
                if (faceTextures != null && faceTextures[faceIdx].toInt() != -1) {
                    (buffer.readUnsignedByte() - 1).toByte()
                } else {
                    -1
                }
            }
        } else {
            null
        }

        // The same section when there is no texture triangle for it to point at, which the client
        // allocates no array for and never reads; see ModelDefinition.unusedTexturePointers.
        val unusedTexturePointers: ByteArray? = if (faceTexturePointers == null && hasFaceTextures == 1 &&
            textureIndicesLength > 0 && textureIndicesOffset + textureIndicesLength <= data.size
        ) {
            buffer.position(textureIndicesOffset)
            ByteArray(textureIndicesLength) { buffer.readByte().toByte() }
        } else {
            null
        }

        // Decode texture triangles (for UV projection)
        var textureFaceX: ShortArray? = null
        var textureFaceY: ShortArray? = null
        var textureFaceZ: ShortArray? = null
        // Complex texture transformation arrays (indexed by complex texture index)
        var texturesScaleX: IntArray? = null
        var texturesScaleY: IntArray? = null
        var texturesScaleZ: IntArray? = null
        var texturesRotation: ByteArray? = null
        var texturesDirection: ByteArray? = null
        var texturesSpeed: IntArray? = null
        // Cube texture arrays (indexed by cube texture index)
        var texturesUtrans: IntArray? = null
        var texturesVtrans: IntArray? = null

        if (texturedFaceCount > 0 && simpleTexTriOffset + simpleTextureFaceCount * 6 <= data.size) {
            textureFaceX = ShortArray(texturedFaceCount)
            textureFaceY = ShortArray(texturedFaceCount)
            textureFaceZ = ShortArray(texturedFaceCount)

            // Allocate complex texture arrays if we have any complex mappings
            if (complexTextureFaceCount > 0) {
                texturesScaleX = IntArray(complexTextureFaceCount)
                texturesScaleY = IntArray(complexTextureFaceCount)
                texturesScaleZ = IntArray(complexTextureFaceCount)
                texturesRotation = ByteArray(complexTextureFaceCount)
                texturesDirection = ByteArray(complexTextureFaceCount)
                texturesSpeed = IntArray(complexTextureFaceCount)
            }
            if (cubeTextureFaceCount > 0) {
                texturesUtrans = IntArray(cubeTextureFaceCount)
                texturesVtrans = IntArray(cubeTextureFaceCount)
            }

            // Create additional buffers for reading complex texture data at different offsets
            val scaleBuffer = BufferReader(data)
            val rotationBuffer = BufferReader(data)
            val directionBuffer = BufferReader(data)
            val speedBuffer = BufferReader(data)

            buffer.position(simpleTexTriOffset)
            buffer2.position(complexTexTriOffset)
            scaleBuffer.position(complexTexScaleOffset)
            rotationBuffer.position(complexTexRotationOffset)
            directionBuffer.position(complexTexDirectionOffset)
            speedBuffer.position(complexTexSpeedOffset)

            var complexIdx = 0
            var cubeIdx = 0

            for (i in 0 until texturedFaceCount) {
                val mappingType = texturesMapping?.get(i)?.toInt()?.and(0xff) ?: 0
                if (mappingType == 0) {
                    // Simple/planar mapping - read from simpleTexTriOffset
                    textureFaceX[i] = buffer.readUnsignedShort().toShort()
                    textureFaceY[i] = buffer.readUnsignedShort().toShort()
                    textureFaceZ[i] = buffer.readUnsignedShort().toShort()
                } else if (mappingType in 1..3) {
                    // Complex mapping - read triangle vertices from complexTexTriOffset
                    textureFaceX[i] = buffer2.readUnsignedShort().toShort()
                    textureFaceY[i] = buffer2.readUnsignedShort().toShort()
                    textureFaceZ[i] = buffer2.readUnsignedShort().toShort()

                    // Read scale values based on version
                    if (version < 15) {
                        texturesScaleX!![complexIdx] = scaleBuffer.readUnsignedShort()
                        if (version < 14) {
                            texturesScaleY!![complexIdx] = scaleBuffer.readUnsignedShort()
                        } else {
                            texturesScaleY!![complexIdx] = scaleBuffer.readUnsignedMedium()
                        }
                        texturesScaleZ!![complexIdx] = scaleBuffer.readUnsignedShort()
                    } else {
                        texturesScaleX!![complexIdx] = scaleBuffer.readUnsignedMedium()
                        texturesScaleY!![complexIdx] = scaleBuffer.readUnsignedMedium()
                        texturesScaleZ!![complexIdx] = scaleBuffer.readUnsignedMedium()
                    }

                    texturesRotation!![complexIdx] = rotationBuffer.readByte().toByte()
                    texturesDirection!![complexIdx] = directionBuffer.readByte().toByte()
                    texturesSpeed!![complexIdx] = speedBuffer.readByte()

                    // Type 2 (cube mapping) has additional U/V translation
                    if (mappingType == 2) {
                        texturesUtrans!![cubeIdx] = speedBuffer.readByte()
                        texturesVtrans!![cubeIdx] = speedBuffer.readByte()
                        cubeIdx++
                    }

                    complexIdx++
                }
            }

            // Upscale texture scales if vertices were upscaled (version < 13)
            // This matches the RS client's upscale() function which scales both vertices AND texture scales
            // Important: For cylindrical mapping (type 1), texturesScaleZ is NOT upscaled
            if (shouldUpscale && complexTextureFaceCount > 0 && texturesScaleX != null) {
                val scaleX = texturesScaleX
                val scaleY = texturesScaleY!!
                val scaleZ = texturesScaleZ!!
                var scaleIdx = 0
                for (i in 0 until texturedFaceCount) {
                    val mappingType = texturesMapping?.get(i)?.toInt()?.and(0xff) ?: 0
                    if (mappingType in 1..3) {
                        scaleX[scaleIdx] = scaleX[scaleIdx] shl 2
                        scaleY[scaleIdx] = scaleY[scaleIdx] shl 2
                        // For cylindrical mapping (type 1), don't upscale Z
                        if (mappingType != 1) {
                            scaleZ[scaleIdx] = scaleZ[scaleIdx] shl 2
                        }
                        scaleIdx++
                    }
                }
            }
        }

        // Decode footer section: particle attachments and billboards
        var particleEmitters: Array<ParticleEmitterAttachment>? = null
        var particleEffectors: Array<ParticleEffectorAttachment>? = null
        var billboards: Array<BillboardData>? = null

        buffer.position(footerOffset)
        if (hasParticleEffects) {
            val emitterCount = buffer.readUnsignedByte()
            if (emitterCount > 0) {
                particleEmitters = Array(emitterCount) {
                    ParticleEmitterAttachment(buffer.readUnsignedShort(), buffer.readUnsignedShort())
                }
            }
            val effectorCount = buffer.readUnsignedByte()
            if (effectorCount > 0) {
                particleEffectors = Array(effectorCount) {
                    ParticleEffectorAttachment(buffer.readUnsignedShort(), buffer.readUnsignedShort())
                }
            }
        }

        if (hasBillboards) {
            val billboardCount = buffer.readUnsignedByte()
            if (billboardCount > 0) {
                billboards = Array(billboardCount) {
                    val id = buffer.readUnsignedShort()
                    val face = buffer.readUnsignedShort()
                    val depth = buffer.readUnsignedByte()
                    val distance = buffer.readByte()  // signed byte
                    BillboardData(id, face, depth, distance)
                }
            }
        }

        // Whatever is left between the last section read and the version byte belongs to the file
        // and to nothing here; keeping it is what makes the round trip exact for a model nobody
        // has seen the shape of.
        val end = data.size - NEW_FORMAT_FOOTER_BYTES - if (hasVersion) 1 else 0
        val trailing = trailing(data, buffer.position(), end)

        return ModelDefinition(
            id = modelId,
            version = version,
            vertexCount = vertexCount,
            faceCount = faceCount,
            vertexX = vertexX,
            vertexY = vertexY,
            vertexZ = vertexZ,
            faceA = faceA,
            faceB = faceB,
            faceC = faceC,
            faceColors = faceColors,
            faceAlphas = faceAlphas,
            faceRenderTypes = faceRenderTypes,
            facePriorities = facePriorities,
            faceTextures = faceTextures,
            faceTexturePointers = faceTexturePointers,
            textureTriangleCount = texturedFaceCount,
            texturesMapping = texturesMapping,
            textureFaceX = textureFaceX,
            textureFaceY = textureFaceY,
            textureFaceZ = textureFaceZ,
            textureCoordU = null,
            textureCoordV = null,
            vertexNormalsX = null,
            vertexNormalsY = null,
            vertexNormalsZ = null,
            unusedTexturePointers = unusedTexturePointers,
            texturesScaleX = texturesScaleX,
            texturesScaleY = texturesScaleY,
            texturesScaleZ = texturesScaleZ,
            texturesRotation = texturesRotation,
            texturesDirection = texturesDirection,
            texturesSpeed = texturesSpeed,
            texturesUtrans = texturesUtrans,
            texturesVtrans = texturesVtrans,
            billboards = billboards,
            newFormat = true,
            footerFlags = footerFlags,
            modelPriority = modelPriority,
            alphaFlag = hasFaceAlpha,
            faceSkinFlag = hasFaceSkins,
            textureFlag = hasFaceTextures,
            vertexSkinFlag = hasVertexSkins,
            vertexFlags = vertexFlags,
            faceIndexTypes = faceIndexTypes,
            faceSkins = faceSkins,
            vertexSkins = vertexSkins,
            particleEmitters = particleEmitters,
            particleEffectors = particleEffectors,
            trailing = trailing
        )
    }

    /**
     * The pre-`0xFFFF` layout.
     *
     * Three things differ from [decodeNewFormat] besides the shorter footer: the sections start at
     * offset 0 rather than after a block of texture mapping bytes, the texture triangles sit
     * between the face colours and the vertices instead of after them, and every texture triangle
     * is planar - there are no mapping types, no scales and no cube translations. Its face render
     * type byte is also a bit field the client unpacks into a render type, a texture pointer and a
     * material; it is kept whole, which is both what the viewer has always read and what the
     * encoder needs.
     */
    private fun decodeOldFormat(modelId: Int, data: ByteArray): ModelDefinition? {
        val buffer = BufferReader(data)

        // Read header from end of file (18 bytes before end)
        buffer.position(data.size - OLD_FORMAT_FOOTER_BYTES)
        val vertexCount = buffer.readUnsignedShort()
        val faceCount = buffer.readUnsignedShort()
        val texturedFaceCount = buffer.readUnsignedByte()

        val hasRenderTypes = buffer.readUnsignedByte()
        val modelPriority = buffer.readUnsignedByte()
        val hasAlphas = buffer.readUnsignedByte()
        val hasFaceSkins = buffer.readUnsignedByte()
        val hasVertexSkins = buffer.readUnsignedByte()

        val vertexXLength = buffer.readUnsignedShort()
        val vertexYLength = buffer.readUnsignedShort()
        val vertexZLength = buffer.readUnsignedShort()
        val faceIndicesLength = buffer.readUnsignedShort()

        // Calculate offsets - this layout starts at 0, there is no texture mapping block
        var offset = 0
        val vertexFlagsOffset = offset
        offset += vertexCount

        val faceIndexTypesOffset = offset
        offset += faceCount

        val facePrioritiesOffset = offset
        if (modelPriority == PER_FACE_PRIORITY) {
            offset += faceCount
        }

        val faceSkinOffset = offset
        if (hasFaceSkins == 1) {
            offset += faceCount
        }

        val faceRenderTypesOffset = offset
        if (hasRenderTypes == 1) {
            offset += faceCount
        }

        val vertexSkinOffset = offset
        if (hasVertexSkins == 1) {
            offset += vertexCount
        }

        val faceAlphasOffset = offset
        if (hasAlphas == 1) {
            offset += faceCount
        }

        val faceIndicesOffset = offset
        offset += faceIndicesLength

        val faceColorsOffset = offset
        offset += faceCount * 2

        val textureTrianglesOffset = offset
        offset += texturedFaceCount * 6

        val vertexXOffset = offset
        offset += vertexXLength

        val vertexYOffset = offset
        offset += vertexYLength

        val vertexZOffset = offset
        offset += vertexZLength

        // Validate
        if (vertexZOffset + vertexZLength > data.size) {
            return null
        }

        // Decode vertex flags
        buffer.position(vertexFlagsOffset)
        val vertexFlags = ByteArray(vertexCount)
        for (i in 0 until vertexCount) {
            vertexFlags[i] = buffer.readByte().toByte()
        }

        // Decode vertices (delta encoded)
        val vertexX = IntArray(vertexCount)
        val vertexY = IntArray(vertexCount)
        val vertexZ = IntArray(vertexCount)

        buffer.position(vertexXOffset)
        var vX = 0
        for (i in 0 until vertexCount) {
            val flag = vertexFlags[i].toInt() and 0xff
            if ((flag and 0x1) != 0) {
                vX += readSmart(buffer)
            }
            vertexX[i] = vX
        }

        buffer.position(vertexYOffset)
        var vY = 0
        for (i in 0 until vertexCount) {
            val flag = vertexFlags[i].toInt() and 0xff
            if ((flag and 0x2) != 0) {
                vY += readSmart(buffer)
            }
            vertexY[i] = vY
        }

        buffer.position(vertexZOffset)
        var vZ = 0
        for (i in 0 until vertexCount) {
            val flag = vertexFlags[i].toInt() and 0xff
            if ((flag and 0x4) != 0) {
                vZ += readSmart(buffer)
            }
            vertexZ[i] = vZ
        }

        // This layout is always version 12, so its vertices are always at quarter scale
        for (i in 0 until vertexCount) {
            vertexX[i] = vertexX[i] shl 2
            vertexY[i] = vertexY[i] shl 2
            vertexZ[i] = vertexZ[i] shl 2
        }

        val vertexSkins: IntArray? = if (hasVertexSkins == 1) {
            buffer.position(vertexSkinOffset)
            IntArray(vertexCount) { buffer.readUnsignedByte() }
        } else {
            null
        }

        // Decode face index types
        buffer.position(faceIndexTypesOffset)
        val faceIndexTypes = ByteArray(faceCount)
        for (i in 0 until faceCount) {
            faceIndexTypes[i] = buffer.readByte().toByte()
        }

        // Decode face indices
        val faceA = IntArray(faceCount)
        val faceB = IntArray(faceCount)
        val faceC = IntArray(faceCount)
        buffer.position(faceIndicesOffset)
        readFaces(buffer, faceIndexTypes, faceA, faceB, faceC)

        // Decode colors
        val faceColors = ShortArray(faceCount)
        if (faceColorsOffset + faceCount * 2 <= data.size) {
            buffer.position(faceColorsOffset)
            for (i in 0 until faceCount) {
                faceColors[i] = buffer.readUnsignedShort().toShort()
            }
        }

        // Decode priorities
        val facePriorities: ByteArray? = if (modelPriority == PER_FACE_PRIORITY && facePrioritiesOffset + faceCount <= data.size) {
            buffer.position(facePrioritiesOffset)
            ByteArray(faceCount) { buffer.readByte().toByte() }
        } else {
            null
        }

        // Decode alphas
        val faceAlphas: ByteArray? = if (hasAlphas == 1 && faceAlphasOffset + faceCount <= data.size) {
            buffer.position(faceAlphasOffset)
            ByteArray(faceCount) { buffer.readByte().toByte() }
        } else {
            null
        }

        val faceSkins: IntArray? = if (hasFaceSkins == 1 && faceSkinOffset + faceCount <= data.size) {
            buffer.position(faceSkinOffset)
            IntArray(faceCount) { buffer.readUnsignedByte() }
        } else {
            null
        }

        // Decode render types
        val faceRenderTypes: ByteArray? = if (hasRenderTypes == 1 && faceRenderTypesOffset + faceCount <= data.size) {
            buffer.position(faceRenderTypesOffset)
            ByteArray(faceCount) { buffer.readByte().toByte() }
        } else {
            null
        }

        // Texture triangles: all planar, and the mapping block the newer layout has does not exist
        var texturesMapping: ByteArray? = null
        var textureFaceX: ShortArray? = null
        var textureFaceY: ShortArray? = null
        var textureFaceZ: ShortArray? = null
        if (texturedFaceCount > 0 && textureTrianglesOffset + texturedFaceCount * 6 <= data.size) {
            texturesMapping = ByteArray(texturedFaceCount)
            textureFaceX = ShortArray(texturedFaceCount)
            textureFaceY = ShortArray(texturedFaceCount)
            textureFaceZ = ShortArray(texturedFaceCount)
            buffer.position(textureTrianglesOffset)
            for (i in 0 until texturedFaceCount) {
                textureFaceX[i] = buffer.readUnsignedShort().toShort()
                textureFaceY[i] = buffer.readUnsignedShort().toShort()
                textureFaceZ[i] = buffer.readUnsignedShort().toShort()
            }
        }

        val trailing = trailing(data, vertexZOffset + vertexZLength, data.size - OLD_FORMAT_FOOTER_BYTES)

        return ModelDefinition(
            id = modelId,
            version = DEFAULT_VERSION,
            vertexCount = vertexCount,
            faceCount = faceCount,
            vertexX = vertexX,
            vertexY = vertexY,
            vertexZ = vertexZ,
            faceA = faceA,
            faceB = faceB,
            faceC = faceC,
            faceColors = faceColors,
            faceAlphas = faceAlphas,
            faceRenderTypes = faceRenderTypes,
            facePriorities = facePriorities,
            faceTextures = null,
            faceTexturePointers = null,
            textureTriangleCount = texturedFaceCount,
            texturesMapping = texturesMapping,
            textureFaceX = textureFaceX,
            textureFaceY = textureFaceY,
            textureFaceZ = textureFaceZ,
            textureCoordU = null,
            textureCoordV = null,
            vertexNormalsX = null,
            vertexNormalsY = null,
            vertexNormalsZ = null,
            texturesScaleX = null,
            texturesScaleY = null,
            texturesScaleZ = null,
            texturesRotation = null,
            texturesDirection = null,
            texturesSpeed = null,
            texturesUtrans = null,
            texturesVtrans = null,
            billboards = null,
            newFormat = false,
            footerFlags = hasRenderTypes,
            modelPriority = modelPriority,
            alphaFlag = hasAlphas,
            faceSkinFlag = hasFaceSkins,
            textureFlag = 0,
            vertexSkinFlag = hasVertexSkins,
            vertexFlags = vertexFlags,
            faceIndexTypes = faceIndexTypes,
            faceSkins = faceSkins,
            vertexSkins = vertexSkins,
            trailing = trailing
        )
    }

    /**
     * The face index section, which is the same in both layouts: each face either rewrites all
     * three of its vertex indices (type 1) or reuses two of the previous face's and reads one.
     */
    private fun readFaces(buffer: BufferReader, faceIndexTypes: ByteArray, faceA: IntArray, faceB: IntArray, faceC: IntArray) {
        var a = 0
        var b = 0
        var c = 0
        var lastIndex = 0
        for (i in faceIndexTypes.indices) {
            when (faceIndexTypes[i].toInt() and 0xff) {
                1 -> {
                    a = readSmart(buffer) + lastIndex
                    lastIndex = a
                    b = readSmart(buffer) + lastIndex
                    lastIndex = b
                    c = readSmart(buffer) + lastIndex
                    lastIndex = c
                }
                2 -> {
                    b = c
                    c = readSmart(buffer) + lastIndex
                    lastIndex = c
                }
                3 -> {
                    a = c
                    c = readSmart(buffer) + lastIndex
                    lastIndex = c
                }
                4 -> {
                    val temp = a
                    a = b
                    b = temp
                    c = readSmart(buffer) + lastIndex
                    lastIndex = c
                }
            }
            faceA[i] = a
            faceB[i] = b
            faceC[i] = c
        }
    }

    /** `data[from until to]`, or null when there is nothing there. */
    private fun trailing(data: ByteArray, from: Int, to: Int): ByteArray? =
        if (to > from && to <= data.size && from >= 0) data.copyOfRange(from, to) else null

    /**
     * How many bytes one complex texture's three scales take, which the format changed twice:
     * six for a pre-14 model, seven for a 14 (the Y scale grew to a medium) and nine for a 15 or
     * later (all three did).
     */
    fun scaleBytes(version: Int): Int = when {
        version < 14 -> 6
        version == 14 -> 7
        else -> 9
    }

    /**
     * Read a smart-encoded value.
     * If first byte < 128: single byte, value range -64 to +63
     * If first byte >= 128: two bytes, value range -16384 to +16383
     */
    private fun readSmart(buffer: BufferReader): Int {
        val peek = buffer.readUnsignedByte()
        return if (peek < 128) {
            peek - 64
        } else {
            ((peek shl 8) or buffer.readUnsignedByte()) - 49152
        }
    }

    fun getModelCount(cache: Cache): Int {
        return cache.lastArchiveId(Index.MODELS) + 1
    }

    /** The priority byte that means "the file carries a priority per face" rather than one for all. */
    const val PER_FACE_PRIORITY = 255

    /** Vertices and complex texture scales of a model older than this are stored at quarter scale. */
    const val UPSCALE_BELOW_VERSION = 13
}
