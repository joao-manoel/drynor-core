package world.gregs.voidps.cache.definition.encoder

import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.definition.data.ModelDefinition
import world.gregs.voidps.cache.definition.decoder.ModelDecoder
import java.nio.BufferOverflowException

/**
 * Writes a model archive back, byte for byte, from what
 * [ModelDecoder][world.gregs.voidps.cache.definition.decoder.ModelDecoder] read out of it.
 *
 * A model file is not a list of opcode keyed records like every other definition in the cache: it
 * is a dozen fixed sections laid end to end, whose lengths a 21 byte footer at the *end* of the
 * file declares. So there is no opcode order to record and no shadowed payload to replay - the
 * encoder simply writes the sections in the order the client reads them and then the footer, whose
 * three length fields it fills in from what it just wrote rather than from anything stored.
 *
 * Two per element arrays are the file's own and cannot be derived from the geometry in general:
 *
 * - [ModelDefinition.vertexFlags], which says for each vertex which of x, y and z the file carries
 *   a delta for. It is "the axes whose delta is not zero" for every model in the 727 cache, and
 *   [vertexFlags] derives exactly that when the definition carries none - a file that sets a bit
 *   for a zero delta anyway keeps its own bytes and is written from those.
 * - [ModelDefinition.faceIndexTypes], which says how each face's three vertex indices follow from
 *   the face before it. [faceIndexTypes] derives it the way the packer did (prefer 2, then 3, then
 *   4, then 1) when the definition carries none.
 *
 * The geometry the decoder hands over is the renderer's, not the file's: a pre-13 model's vertices
 * and complex texture scales are upscaled by four, a face's material is the file's short minus one
 * and a face's texture pointer the file's byte minus one. All three are inverted here, which is why
 * the round trip is exact rather than merely equivalent.
 */
object ModelEncoder {

    /** [model] as the archive's bytes. */
    fun encode(model: ModelDefinition): ByteArray {
        var capacity = capacity(model)
        while (true) {
            val writer = BufferWriter(capacity)
            try {
                writer.encode(model)
                return writer.toArray()
            } catch (e: BufferOverflowException) {
                capacity *= 2
                check(capacity <= LIMIT) { "Model ${model.id} does not encode into $LIMIT bytes." }
            }
        }
    }

    /** Write [model] into [this]. */
    fun Writer.encode(model: ModelDefinition) {
        if (model.newFormat) {
            writeNewFormat(model)
        } else {
            writeOldFormat(model)
        }
    }

    private fun Writer.writeNewFormat(model: ModelDefinition) {
        val textures = model.textureTriangleCount
        val faces = model.faceCount
        val vertices = model.vertexCount
        val flags = vertexFlags(model)
        val indexTypes = faceIndexTypes(model)
        val hasFaceTypes = (model.footerFlags and ModelDecoder.FLAG_FACE_TYPES) != 0
        val hasParticles = (model.footerFlags and ModelDecoder.FLAG_PARTICLES) != 0
        val hasBillboards = (model.footerFlags and ModelDecoder.FLAG_BILLBOARDS) != 0
        val hasVersion = (model.footerFlags and ModelDecoder.FLAG_VERSION) != 0

        // 1. The texture mapping type of each texture triangle, which is what tells the two
        //    texture triangle sections and the scale block their sizes.
        for (index in 0 until textures) {
            writeByte(model.texturesMapping?.get(index)?.toInt() ?: 0)
        }
        // 2. Which of x, y and z each vertex carries a delta for.
        writeBytes(flags)
        // 3. Face render types.
        if (hasFaceTypes) {
            writeSection(model.faceRenderTypes, faces)
        }
        // 4. How each face's vertex indices follow from the one before it.
        writeBytes(indexTypes)
        // 5. Face priorities, when the model has one per face rather than one for all of them.
        if (model.modelPriority == ModelDecoder.PER_FACE_PRIORITY) {
            writeSection(model.facePriorities, faces)
        }
        // 6. and 7. The skin (bone group) of each face and of each vertex.
        if (model.faceSkinFlag == 1) {
            writeUnsigned(model.faceSkins, faces)
        }
        if (model.vertexSkinFlag == 1) {
            writeUnsigned(model.vertexSkins, vertices)
        }
        // 8. Face alphas.
        if (model.alphaFlag == 1) {
            writeSection(model.faceAlphas, faces)
        }
        // 9. The face indices themselves, as smart deltas against the running index.
        val faceIndicesStart = position()
        writeFaceIndices(model, indexTypes)
        val faceIndicesLength = position() - faceIndicesStart
        // 10. The material of each face, the file's value being one more than the decoded one.
        if (model.textureFlag == 1) {
            for (index in 0 until faces) {
                writeShort(((model.faceTextures?.get(index)?.toInt() ?: -1) + 1) and 0xffff)
            }
        }
        // 11. One byte per *textured* face, pointing at the texture triangle that projects it.
        val textureIndicesStart = position()
        val pointers = model.faceTexturePointers
        if (pointers != null) {
            for (index in 0 until faces) {
                if ((model.faceTextures?.get(index)?.toInt() ?: -1) != -1) {
                    writeByte((pointers[index].toInt() + 1) and 0xff)
                }
            }
        } else {
            // A file that declared the section without a texture triangle to point at; nothing
            // read these, so they go back exactly as they came.
            model.unusedTexturePointers?.let { writeBytes(it) }
        }
        val textureIndicesLength = position() - textureIndicesStart
        // 12. Face colours.
        for (index in 0 until faces) {
            writeShort(model.faceColors[index].toInt() and 0xffff)
        }
        // 13, 14, 15. The vertices, one axis per section, as smart deltas.
        val scale = if (model.version < ModelDecoder.UPSCALE_BELOW_VERSION) 2 else 0
        val vertexXLength = writeAxis(model.vertexX, flags, 0x1, scale)
        val vertexYLength = writeAxis(model.vertexY, flags, 0x2, scale)
        val vertexZLength = writeAxis(model.vertexZ, flags, 0x4, scale)
        // 16 to 21. The texture triangles: the planar ones, then the projected ones, then the
        // projection's scale, rotation, direction and speed, each in a section of its own.
        writeTextureTriangles(model)
        // 22. The particle attachments and the billboards.
        if (hasParticles) {
            writeByte(model.particleEmitters?.size ?: 0)
            for (emitter in model.particleEmitters ?: emptyArray()) {
                writeShort(emitter.particleId)
                writeShort(emitter.appliesOnFace)
            }
            writeByte(model.particleEffectors?.size ?: 0)
            for (effector in model.particleEffectors ?: emptyArray()) {
                writeShort(effector.effectorId)
                writeShort(effector.appliesOnVertex)
            }
        }
        if (hasBillboards) {
            writeByte(model.billboards?.size ?: 0)
            for (billboard in model.billboards ?: emptyArray()) {
                writeShort(billboard.billboardId)
                writeShort(billboard.appliesOnFace)
                writeByte(billboard.depth)
                writeByte(billboard.distance)
            }
        }
        model.trailing?.let { writeBytes(it) }
        // The version byte sits between the sections and the footer.
        if (hasVersion) {
            writeByte(model.version)
        }
        writeShort(vertices)
        writeShort(faces)
        writeByte(textures)
        writeByte(model.footerFlags)
        writeByte(model.modelPriority)
        writeByte(model.alphaFlag)
        writeByte(model.faceSkinFlag)
        writeByte(model.textureFlag)
        writeByte(model.vertexSkinFlag)
        writeShort(vertexXLength)
        writeShort(vertexYLength)
        writeShort(vertexZLength)
        writeShort(faceIndicesLength)
        writeShort(textureIndicesLength)
        // The marker that tells this layout from the older one.
        writeShort(0xffff)
    }

    /**
     * The pre-`0xFFFF` layout: no texture mapping block at the front, the texture triangles
     * between the face colours and the vertices, and a shorter footer with no texture index length
     * and no version byte.
     */
    private fun Writer.writeOldFormat(model: ModelDefinition) {
        val faces = model.faceCount
        val vertices = model.vertexCount
        val flags = vertexFlags(model)
        val indexTypes = faceIndexTypes(model)

        writeBytes(flags)
        writeBytes(indexTypes)
        if (model.modelPriority == ModelDecoder.PER_FACE_PRIORITY) {
            writeSection(model.facePriorities, faces)
        }
        if (model.faceSkinFlag == 1) {
            writeUnsigned(model.faceSkins, faces)
        }
        // This layout's "has face render types" byte is a value, not a bit field.
        if (model.footerFlags == 1) {
            writeSection(model.faceRenderTypes, faces)
        }
        if (model.vertexSkinFlag == 1) {
            writeUnsigned(model.vertexSkins, vertices)
        }
        if (model.alphaFlag == 1) {
            writeSection(model.faceAlphas, faces)
        }
        val faceIndicesStart = position()
        writeFaceIndices(model, indexTypes)
        val faceIndicesLength = position() - faceIndicesStart
        for (index in 0 until faces) {
            writeShort(model.faceColors[index].toInt() and 0xffff)
        }
        for (index in 0 until model.textureTriangleCount) {
            writeShort(model.textureFaceX?.get(index)?.toInt()?.and(0xffff) ?: 0)
            writeShort(model.textureFaceY?.get(index)?.toInt()?.and(0xffff) ?: 0)
            writeShort(model.textureFaceZ?.get(index)?.toInt()?.and(0xffff) ?: 0)
        }
        // Always version 12, so always at quarter scale.
        val vertexXLength = writeAxis(model.vertexX, flags, 0x1, 2)
        val vertexYLength = writeAxis(model.vertexY, flags, 0x2, 2)
        val vertexZLength = writeAxis(model.vertexZ, flags, 0x4, 2)
        model.trailing?.let { writeBytes(it) }
        writeShort(vertices)
        writeShort(faces)
        writeByte(model.textureTriangleCount)
        writeByte(model.footerFlags)
        writeByte(model.modelPriority)
        writeByte(model.alphaFlag)
        writeByte(model.faceSkinFlag)
        writeByte(model.vertexSkinFlag)
        writeShort(vertexXLength)
        writeShort(vertexYLength)
        writeShort(vertexZLength)
        writeShort(faceIndicesLength)
    }

    /**
     * The texture triangle sections, in the order the client's six buffers read them: the planar
     * triangles, the projected triangles, and then the projection's scales, rotations, directions
     * and speeds - with a cube mapping's two translation bytes riding along behind its speed byte,
     * which is where the client reads them from.
     */
    private fun Writer.writeTextureTriangles(model: ModelDefinition) {
        val textures = model.textureTriangleCount
        if (textures == 0) {
            return
        }
        for (index in 0 until textures) {
            if (mapping(model, index) == 0) {
                writeTriangle(model, index)
            }
        }
        for (index in 0 until textures) {
            if (mapping(model, index) in 1..3) {
                writeTriangle(model, index)
            }
        }
        val bytes = ModelDecoder.scaleBytes(model.version)
        // A pre-13 model's scales were upscaled with its vertices, except a cylinder's Z.
        val scaled = model.version < ModelDecoder.UPSCALE_BELOW_VERSION
        var complex = 0
        for (index in 0 until textures) {
            val mapping = mapping(model, index)
            if (mapping !in 1..3) {
                continue
            }
            var x = model.texturesScaleX?.get(complex) ?: 0
            var y = model.texturesScaleY?.get(complex) ?: 0
            var z = model.texturesScaleZ?.get(complex) ?: 0
            if (scaled) {
                x = x shr 2
                y = y shr 2
                if (mapping != 1) {
                    z = z shr 2
                }
            }
            when (bytes) {
                6 -> {
                    writeShort(x and 0xffff)
                    writeShort(y and 0xffff)
                    writeShort(z and 0xffff)
                }
                7 -> {
                    writeShort(x and 0xffff)
                    writeMedium(y and 0xffffff)
                    writeShort(z and 0xffff)
                }
                else -> {
                    writeMedium(x and 0xffffff)
                    writeMedium(y and 0xffffff)
                    writeMedium(z and 0xffffff)
                }
            }
            complex++
        }
        complex = 0
        for (index in 0 until textures) {
            if (mapping(model, index) in 1..3) {
                writeByte(model.texturesRotation?.get(complex)?.toInt() ?: 0)
                complex++
            }
        }
        complex = 0
        for (index in 0 until textures) {
            if (mapping(model, index) in 1..3) {
                writeByte(model.texturesDirection?.get(complex)?.toInt() ?: 0)
                complex++
            }
        }
        complex = 0
        var cube = 0
        for (index in 0 until textures) {
            val mapping = mapping(model, index)
            if (mapping !in 1..3) {
                continue
            }
            writeByte(model.texturesSpeed?.get(complex) ?: 0)
            if (mapping == 2) {
                writeByte(model.texturesUtrans?.get(cube) ?: 0)
                writeByte(model.texturesVtrans?.get(cube) ?: 0)
                cube++
            }
            complex++
        }
    }

    private fun Writer.writeTriangle(model: ModelDefinition, index: Int) {
        writeShort(model.textureFaceX?.get(index)?.toInt()?.and(0xffff) ?: 0)
        writeShort(model.textureFaceY?.get(index)?.toInt()?.and(0xffff) ?: 0)
        writeShort(model.textureFaceZ?.get(index)?.toInt()?.and(0xffff) ?: 0)
    }

    private fun mapping(model: ModelDefinition, index: Int): Int =
        model.texturesMapping?.get(index)?.toInt()?.and(0xff) ?: 0

    /** One axis of the vertices as smart deltas, and how many bytes that took. */
    private fun Writer.writeAxis(values: IntArray, flags: ByteArray, bit: Int, shift: Int): Int {
        val start = position()
        var previous = 0
        for (index in values.indices) {
            val value = values[index] shr shift
            if ((flags[index].toInt() and bit) != 0) {
                writeModelSmart(value - previous)
            }
            previous = value
        }
        return position() - start
    }

    /** The face index section: what each face's [faceIndexTypes] entry says it reads. */
    private fun Writer.writeFaceIndices(model: ModelDefinition, types: ByteArray) {
        var last = 0
        for (index in types.indices) {
            val faceA = model.faceA[index]
            val faceB = model.faceB[index]
            val faceC = model.faceC[index]
            when (types[index].toInt() and 0xff) {
                1 -> {
                    writeModelSmart(faceA - last)
                    writeModelSmart(faceB - faceA)
                    writeModelSmart(faceC - faceB)
                }
                // 2, 3 and 4 all take their a and b from the face before them and read only c;
                // a type the client does not know reads nothing, exactly as its decode does.
                2, 3, 4 -> writeModelSmart(faceC - last)
                else -> {}
            }
            last = faceC
        }
    }

    /**
     * A signed smart: one byte for -64 to 63, two for anything up to -16384 to 16383.
     *
     * Model specific rather than a [Writer] member: the cache's other smart
     * ([Writer.writeSmart]) is unsigned and offset by 32768, and nothing outside a model file uses
     * this one.
     */
    private fun Writer.writeModelSmart(value: Int) {
        if (value in -64..63) {
            writeByte(value + 64)
        } else {
            writeShort(value + 49152)
        }
    }

    private fun Writer.writeSection(values: ByteArray?, count: Int) {
        for (index in 0 until count) {
            writeByte(values?.get(index)?.toInt() ?: 0)
        }
    }

    private fun Writer.writeUnsigned(values: IntArray?, count: Int) {
        for (index in 0 until count) {
            writeByte(values?.get(index) ?: 0)
        }
    }

    /** [ModelDefinition.vertexFlags] when the file carried them, [deriveVertexFlags] otherwise. */
    fun vertexFlags(model: ModelDefinition): ByteArray = model.vertexFlags ?: deriveVertexFlags(model)

    /**
     * The vertex flags the packer would have written: a bit per axis whose delta is not zero.
     *
     * Every model in the 727 cache carries exactly these, which is why no model file in the tree
     * has to spell them out.
     */
    fun deriveVertexFlags(model: ModelDefinition): ByteArray {
        val shift = if (model.version < ModelDecoder.UPSCALE_BELOW_VERSION) 2 else 0
        val flags = ByteArray(model.vertexCount)
        var x = 0
        var y = 0
        var z = 0
        for (index in 0 until model.vertexCount) {
            val vertexX = model.vertexX[index] shr shift
            val vertexY = model.vertexY[index] shr shift
            val vertexZ = model.vertexZ[index] shr shift
            var flag = 0
            if (vertexX != x) {
                flag = flag or 0x1
            }
            if (vertexY != y) {
                flag = flag or 0x2
            }
            if (vertexZ != z) {
                flag = flag or 0x4
            }
            flags[index] = flag.toByte()
            x = vertexX
            y = vertexY
            z = vertexZ
        }
        return flags
    }

    /** [ModelDefinition.faceIndexTypes] when the file carried them, [deriveFaceIndexTypes] otherwise. */
    fun faceIndexTypes(model: ModelDefinition): ByteArray = model.faceIndexTypes ?: deriveFaceIndexTypes(model)

    /**
     * The face index types the packer would have written.
     *
     * Type 2 keeps a and takes the previous c as b, type 3 keeps b and takes the previous c as a,
     * type 4 swaps a and b, and type 1 spells all three out. Preferring them in that order is what
     * reproduces every model in the 727 cache; a file that chose differently keeps its own bytes.
     */
    fun deriveFaceIndexTypes(model: ModelDefinition): ByteArray {
        val types = ByteArray(model.faceCount)
        var a = 0
        var b = 0
        var c = 0
        for (index in 0 until model.faceCount) {
            val faceA = model.faceA[index]
            val faceB = model.faceB[index]
            val faceC = model.faceC[index]
            types[index] = when {
                index > 0 && faceA == a && faceB == c -> 2
                index > 0 && faceA == c && faceB == b -> 3
                index > 0 && faceA == b && faceB == a -> 4
                else -> 1
            }
            a = faceA
            b = faceB
            c = faceC
        }
        return types
    }

    /** Room for the worst case of every section, so nothing has to grow in the common case. */
    private fun capacity(model: ModelDefinition): Int =
        HEADROOM + model.vertexCount * 9 + model.faceCount * 16 + model.textureTriangleCount * 22 +
            (model.particleEmitters?.size ?: 0) * 4 + (model.particleEffectors?.size ?: 0) * 4 +
            (model.billboards?.size ?: 0) * 6 + (model.trailing?.size ?: 0)

    private const val HEADROOM = 64

    private const val LIMIT = 8 * 1024 * 1024
}
