package org.drynor.tools.rs3port

import world.gregs.voidps.cache.definition.data.AnimationBaseDefinition
import world.gregs.voidps.cache.definition.data.AnimationDefinition
import world.gregs.voidps.cache.definition.data.AnimationFrameDefinition
import world.gregs.voidps.cache.definition.data.MaterialDefinition
import world.gregs.voidps.cache.definition.data.ModelDefinition
import world.gregs.voidps.cache.definition.data.ParticleDefinition
import world.gregs.voidps.cache.definition.data.ParticleProducerDefinition
import world.gregs.voidps.cache.definition.data.SpotAnimDefinition
import world.gregs.voidps.cache.definition.data.TextureDefinition
import world.gregs.voidps.cache.definition.decoder.AnimationFrameDecoder

class ClientDesync(message: String) : RuntimeException(message)

class ClientPacket(private val data: ByteArray, var position: Int = 0) {

    val size: Int get() = data.size

    val remaining: Int get() = data.size - position

    fun ubyte(): Int {
        need(1)
        return data[position++].toInt() and 0xff
    }

    fun byte(): Int {
        need(1)
        return data[position++].toInt()
    }

    fun ushort(): Int = (ubyte() shl 8) or ubyte()

    fun short(): Int = ushort().toShort().toInt()

    fun umedium(): Int = (ubyte() shl 16) or (ubyte() shl 8) or ubyte()

    fun int(): Int = (ubyte() shl 24) or (ubyte() shl 16) or (ubyte() shl 8) or ubyte()

    fun smart(): Int {
        need(1)
        return if ((data[position].toInt() and 0xff) < 128) ubyte() - 64 else ushort() - 49152
    }

    fun bigSmart(): Int {
        need(1)
        if (data[position] < 0) {
            return int() and 0x7fffffff
        }
        val value = ushort()
        return if (value == 32767) -1 else value
    }

    private fun need(count: Int) {
        if (position < 0 || position + count > data.size) {
            throw ClientDesync("reads past the end at byte $position of ${data.size}")
        }
    }
}

class ClientContext(
    val exists: (String, Int) -> Boolean,
    val frameExists: (Int, Int) -> Boolean,
    val baseTypes: (Int) -> IntArray?
)

object ClientGrammar {

    private const val TRIG_TABLE = 16384

    private const val EFFECTOR_ROTATION_SHIFT = 3

    private const val MODEL_FOOTER = 23

    private const val SPRITE = 39

    private const val MONOCHROME_FILL = 0

    fun effector(definition: ParticleDefinition, bytes: ByteArray): List<String> = read(bytes) { packet, out ->
        var rotation = 0
        var offsets = listOf(0, 0, 0)
        var handling = 0
        var current = false
        var vertices = false
        opcodes(packet, out) { opcode ->
            when (opcode) {
                1 -> rotation = packet.ushort()
                2 -> packet.ubyte()
                3 -> offsets = listOf(packet.int(), packet.int(), packet.int())
                4 -> {
                    packet.int()
                    out.add("opcode 4 is a bare int to the client, but the file holds a byte and an int")
                }
                6 -> handling = packet.ubyte()
                8 -> current = true
                9 -> vertices = true
                10 -> {}
                else -> return@opcodes false
            }
            true
        }
        if (rotation shl EFFECTOR_ROTATION_SHIFT >= TRIG_TABLE) {
            out.add("rotation $rotation overruns COSINE[rotation shl 3]")
        }
        expect(out, "rotation", rotation, definition.rotation)
        expect(out, "offsets", offsets, listOf(definition.offsetX, definition.offsetY, definition.offsetZ))
        expect(out, "particleHandlingType", handling, definition.particleHandlingType)
        expect(out, "currentOffset", current, definition.currentOffset)
        expect(out, "verticeCalculationType", vertices, definition.verticeCalculationType)
    }

    fun producer(definition: ParticleProducerDefinition, bytes: ByteArray, context: ClientContext): List<String> = read(bytes) { packet, out ->
        val read = HashMap<String, Any>()
        opcodes(packet, out) { opcode ->
            when (opcode) {
                1 -> read["angles"] = listOf(packet.ushort(), packet.ushort(), packet.ushort(), packet.ushort())
                2 -> packet.ubyte()
                3 -> read["speeds"] = listOf(packet.int(), packet.int())
                4 -> read["speedUpdate"] = listOf(packet.ubyte(), packet.byte())
                5 -> read["size"] = packet.ushort()
                6 -> read["colours"] = listOf(packet.int(), packet.int())
                7 -> read["lifetimes"] = listOf(packet.ushort(), packet.ushort())
                8 -> read["rates"] = listOf(packet.ushort(), packet.ushort())
                9 -> read["secondaryParticles"] = List(packet.ubyte()) { packet.ushort() }
                10 -> read["particles"] = List(packet.ubyte()) { packet.ushort() }
                12 -> read["lowestDisplayPlane"] = packet.byte()
                13 -> read["highestDisplayPlane"] = packet.byte()
                14 -> read["updatesPerCycle"] = packet.ushort()
                15 -> read["materialId"] = packet.ushort()
                16 -> read["activity"] = listOf(packet.ubyte(), packet.ushort(), packet.ushort(), packet.ubyte())
                17 -> read["nonTexturedProducerId"] = packet.ushort()
                18 -> read["faceColourRgb"] = packet.int()
                19 -> read["minimumSetting"] = packet.ubyte()
                20 -> read["colourFading"] = packet.ubyte()
                21 -> read["alphaFading"] = packet.ubyte()
                22 -> read["endSpeed"] = packet.int()
                23 -> read["speedChange"] = packet.ubyte()
                24 -> read["uniformColourVariance"] = false
                25 -> read["effectiveVertexUids"] = List(packet.ubyte()) { packet.ushort() }
                26 -> read["unusedOpcode26"] = false
                27 -> read["endSize"] = packet.ushort()
                28 -> read["sizeChange"] = packet.ubyte()
                29 -> packet.short()
                30 -> read["textured"] = true
                31 -> read["sizes"] = listOf(packet.ushort(), packet.ushort())
                32 -> read["adjustsLightIntensity"] = false
                33 -> read["killOverlapping"] = true
                34 -> read["killAboveSurface"] = false
                else -> return@opcodes false
            }
            true
        }
        val d = definition
        expect(out, "angles", read["angles"] ?: listOf(0, 0, 0, 0), listOf(d.minAngleHorizontal, d.maxAngleHorizontal, d.minAngleVertical, d.maxAngleVertical))
        expect(out, "speeds", read["speeds"] ?: listOf(0, 0), listOf(d.minSpeed, d.maxSpeed))
        expect(out, "colours", read["colours"] ?: listOf(0, 0), listOf(d.minStartColour, d.maxStartColour))
        expect(out, "lifetimes", read["lifetimes"] ?: listOf(0, 0), listOf(d.minLifetime, d.maxLifetime))
        expect(out, "rates", read["rates"] ?: listOf(0, 0), listOf(d.minParticleRate, d.maxParticleRate))
        expect(out, "lowestDisplayPlane", read["lowestDisplayPlane"] ?: -2, d.lowestDisplayPlane)
        expect(out, "highestDisplayPlane", read["highestDisplayPlane"] ?: -2, d.highestDisplayPlane)
        expect(out, "materialId", read["materialId"] ?: -1, d.materialId)
        expect(out, "faceColourRgb", read["faceColourRgb"] ?: 0, d.faceColourRgb)
        expect(out, "endSpeed", read["endSpeed"] ?: -1, d.endSpeed)
        expect(out, "sizes", read["sizes"], if (d.minSize == null && d.maxSize == null) null else listOf(d.minSize ?: 0, d.maxSize ?: 0))
        expect(out, "endSize", read["endSize"], d.endSize)
        expect(out, "textured", read["textured"] ?: false, d.textured)
        expect(out, "uniformColourVariance", read["uniformColourVariance"] ?: true, d.uniformColourVariance)
        expect(out, "unusedOpcode26", read["unusedOpcode26"] ?: true, d.unusedOpcode26)
        expect(out, "adjustsLightIntensity", read["adjustsLightIntensity"] ?: true, d.adjustsLightIntensity)
        expect(out, "killOverlapping", read["killOverlapping"] ?: false, d.killOverlapping)
        expect(out, "killAboveSurface", read["killAboveSurface"] ?: true, d.killAboveSurface)
        (read["materialId"] as Int?)?.let { if (!context.exists("material", it)) out.add("material $it does not exist") }
        for (key in listOf("particles", "secondaryParticles")) {
            @Suppress("UNCHECKED_CAST")
            for (effector in read[key] as List<Int>? ?: emptyList()) {
                if (!context.exists("effector", effector)) {
                    out.add("$key names effector $effector, which does not exist")
                }
            }
        }
        (read["nonTexturedProducerId"] as Int?)?.let { if (!context.exists("producer", it)) out.add("nonTexturedProducerId $it does not exist") }
    }

    fun seq(definition: AnimationDefinition, bytes: ByteArray, context: ClientContext): List<String> = read(bytes) { packet, out ->
        var durations: List<Int>? = null
        var frames: List<Int>? = null
        var loop = -1
        var sounds: List<List<Int>?>? = null
        opcodes(packet, out) { opcode ->
            when (opcode) {
                1 -> {
                    val count = packet.ushort()
                    durations = List(count) { packet.ushort() }
                    val low = List(count) { packet.ushort() }
                    frames = List(count) { low[it] + (packet.ushort() shl AnimationFrameDecoder.BITS) }
                }
                2 -> loop = packet.ushort()
                3 -> repeat(packet.ubyte()) { packet.ubyte() }
                5, 8, 9, 10, 11 -> packet.ubyte()
                6, 7 -> packet.ushort()
                12 -> {
                    val count = packet.ubyte()
                    repeat(count * 2) { packet.ushort() }
                }
                13 -> sounds = List(packet.ushort()) {
                    val count = packet.ubyte()
                    if (count == 0) null else List(count) { index -> if (index == 0) packet.umedium() else packet.ushort() }
                }
                14, 15, 18 -> {}
                19 -> {
                    packet.ubyte()
                    packet.ubyte()
                }
                20 -> {
                    packet.ubyte()
                    packet.ushort()
                    packet.ushort()
                }
                else -> return@opcodes false
            }
            true
        }
        expect(out, "durations", durations, definition.durations?.toList())
        expect(out, "frames", frames, definition.frames?.toList())
        expect(out, "loopOffset", if (loop == 65535) -1 else loop, definition.loopOffset)
        expect(out, "sounds", sounds, definition.sounds?.map { it?.toList() })
        val count = frames?.size ?: 0
        if (loop != -1 && loop != 65535 && loop >= count) {
            out.add("loop offset $loop is past the last of $count frames")
        }
        for (frame in frames.orEmpty()) {
            val archive = frame ushr AnimationFrameDecoder.BITS
            val file = frame and ((1 shl AnimationFrameDecoder.BITS) - 1)
            if (!context.frameExists(archive, file)) {
                out.add("frame $archive/$file does not exist")
            }
        }
        for (sound in sounds.orEmpty().filterNotNull()) {
            val id = sound[0] ushr 8
            if (!context.exists("sound", id)) {
                out.add("sound $id does not exist")
            }
        }
    }

    fun spotanim(definition: SpotAnimDefinition, bytes: ByteArray, context: ClientContext): List<String> = read(bytes) { packet, out ->
        var model = 0
        var seq = -1
        var sizeXY = 128
        var sizeZ = 128
        var replay = false
        opcodes(packet, out) { opcode ->
            when (opcode) {
                1 -> model = packet.bigSmart()
                2 -> seq = packet.bigSmart()
                4 -> sizeXY = packet.ushort()
                5 -> sizeZ = packet.ushort()
                6, 15 -> packet.ushort()
                7, 8, 14 -> packet.ubyte()
                9, 11, 12, 13 -> {}
                10 -> replay = true
                16 -> packet.int()
                40, 41 -> repeat(packet.ubyte() * 2) { packet.ushort() }
                else -> return@opcodes false
            }
            true
        }
        expect(out, "modelId", model, definition.modelId)
        expect(out, "animationId", seq, definition.animationId)
        expect(out, "sizeXY", sizeXY, definition.sizeXY)
        expect(out, "sizeZ", sizeZ, definition.sizeZ)
        expect(out, "aBoolean2402", replay, definition.aBoolean2402)
        if (!context.exists("model", model)) {
            out.add("model $model does not exist")
        }
        if (seq != -1 && !context.exists("seq", seq)) {
            out.add("seq $seq does not exist")
        }
    }

    fun base(definition: AnimationBaseDefinition, bytes: ByteArray): List<String> = read(bytes) { packet, out ->
        val count = packet.ubyte()
        val types = List(count) { packet.ubyte().let { type -> if (type == 6) 2 else type } }
        val shadowed = List(count) { packet.ubyte() }
        val submeshes = List(count) { packet.ushort() }
        val lengths = List(count) { packet.ubyte() }
        val labels = List(count) { transform -> List(lengths[transform]) { packet.ubyte() } }
        trailing(packet, out)
        expect(out, "types", types, definition.types.map { if (it == 6) 2 else it })
        expect(out, "shadowed", shadowed, definition.shadowed.toList())
        expect(out, "submeshes", submeshes, definition.submeshes.toList())
        expect(out, "labels", labels, definition.labels.map { it?.toList() ?: emptyList() })
    }

    fun frame(definition: AnimationFrameDefinition, bytes: ByteArray, context: ClientContext): List<String> = read(bytes) { packet, out ->
        packet.ubyte()
        val base = packet.ushort()
        val count = packet.ubyte()
        expect(out, "base", base, definition.baseId)
        val types = context.baseTypes(base)
        if (types == null) {
            out.add("base $base does not exist")
            return@read
        }
        if (count > types.size) {
            out.add("$count transforms on a base of ${types.size}; base.transformationType[i] overruns")
            return@read
        }
        val values = ClientPacket(bytes, 4 + count)
        val flags = List(count) { packet.ubyte() }
        val read = ArrayList<Int>()
        for (flag in flags) {
            for (bit in Rs3Anims.AXES) {
                if (flag and bit != 0) {
                    read.add(values.smart())
                }
            }
        }
        if (values.remaining != 0) {
            out.add("${values.remaining} bytes after the transforms; the client throws when the frame does not end on its last value")
        }
        expect(out, "flags", flags, definition.flags.toList())
        expect(out, "values", read, definition.values.toList())
    }

    fun texture(definition: TextureDefinition, bytes: ByteArray, context: ClientContext): List<String> = read(bytes) { packet, out ->
        val count = packet.ubyte()
        for (index in 0 until count) {
            val expected = definition.operations[index]
            packet.ubyte()
            val type = packet.ubyte()
            packet.ubyte()
            val opcodes = packet.ubyte()
            val children = when (type) {
                SPRITE -> {
                    repeat(opcodes) {
                        if (packet.ubyte() == 0) {
                            val sprite = packet.ushort()
                            expect(out, "operation $index sprite", sprite, expected.opcodes.firstOrNull()?.value)
                            if (!context.exists("graphic", sprite)) {
                                out.add("operation $index draws graphic $sprite, which does not exist")
                            }
                        }
                    }
                    0
                }
                MONOCHROME_FILL -> {
                    repeat(opcodes) {
                        if (packet.ubyte() == 0) {
                            packet.ubyte()
                        }
                    }
                    0
                }
                else -> {
                    out.add("operation $index is type $type, which this check has no client grammar for")
                    return@read
                }
            }
            expect(out, "operation $index type", type, expected.type)
            repeat(children) {
                val child = packet.ubyte()
                if (child >= count) {
                    out.add("operation $index names child $child of $count")
                }
            }
        }
        for (output in listOf("opaque", "opacity", "hdr")) {
            val operation = packet.ubyte()
            if (operation >= count) {
                out.add("$output output is operation $operation of $count")
            }
        }
        val trailer = (definition.trailer?.length ?: 0) / 2
        if (packet.remaining != trailer) {
            out.add("${packet.remaining} bytes after the outputs where the definition's trailer is $trailer")
        }
    }

    fun materials(definitions: List<MaterialDefinition>, bytes: ByteArray): List<String> = read(bytes) { packet, out ->
        val count = packet.ushort()
        val defined = List(count) { packet.ubyte() == 1 }
        val present = defined.indices.filter { defined[it] }
        val columns = HashMap<Int, MutableList<Any>>()
        fun column(read: () -> Any) {
            for (id in present) {
                columns.getOrPut(id) { ArrayList() }.add(read())
            }
        }
        column { packet.ubyte() == 0 }
        column { packet.ubyte() == 1 }
        column { packet.ubyte() == 1 }
        column { packet.byte() }
        column { packet.byte() }
        column { packet.byte() }
        column { packet.byte() }
        column { packet.ushort() }
        column { packet.byte() }
        column { packet.byte() }
        column { packet.ubyte() == 1 }
        column { packet.ubyte() == 1 }
        column { packet.byte() }
        column { packet.ubyte() == 1 }
        column { packet.ubyte() == 1 }
        column { packet.ubyte() == 1 }
        column { packet.ubyte() }
        column { packet.int() }
        column { packet.ubyte() }
        trailing(packet, out)
        for (material in definitions) {
            val d = material
            val expected = listOf<Any>(
                d.detailsOnly, d.isSmall, d.skipTriangles, d.brightness.toInt(), d.alpha.toInt(), d.effectId.toInt(),
                d.effectParam1.toInt(), d.colourHsl, d.textureSpeedU.toInt(), d.textureSpeedV.toInt(), d.unusedBoolean,
                d.isBrickTile, d.mipmapping.toInt(), d.repeatS, d.repeatT, d.hdr, d.combineMode, d.effectParam2, d.effectCombiner
            )
            expect(out, "material ${d.id}", columns[d.id], expected)
        }
    }

    fun sprite(id: Int, bytes: ByteArray): List<String> = read(bytes) { packet, out ->
        packet.position = bytes.size - 2
        val count = packet.ushort()
        if (count != 1) {
            out.add("$count frames; a texture sprite is read with listSingle, frame 0 only")
        }
        packet.position = bytes.size - 7 - count * 8
        val width = packet.ushort()
        val height = packet.ushort()
        val palette = packet.ubyte() + 1
        val minX = List(count) { packet.ushort() }
        val minY = List(count) { packet.ushort() }
        val widths = List(count) { packet.ushort() }
        val heights = List(count) { packet.ushort() }
        val pixelsEnd = bytes.size - 7 - count * 8 - (palette - 1) * 3
        packet.position = 0
        for (frame in 0 until count) {
            if (minX[frame] + widths[frame] > width || minY[frame] + heights[frame] > height) {
                out.add("frame $frame box leaves the ${width}x$height canvas")
            }
            val pixels = widths[frame] * heights[frame]
            val flags = packet.ubyte()
            repeat(pixels) {
                val index = packet.ubyte()
                if (index >= palette) {
                    out.add("palette index $index of $palette")
                    return@read
                }
            }
            if (flags and 0x2 != 0) {
                repeat(pixels) { packet.ubyte() }
            }
        }
        if (packet.position != pixelsEnd) {
            out.add("pixels end at byte ${packet.position}, the palette starts at $pixelsEnd")
        }
        if (id < 0) {
            out.add("graphic id $id")
        }
    }

    fun model(definition: ModelDefinition, bytes: ByteArray, context: ClientContext): List<String> = read(bytes) { packet, out ->
        if (bytes.size < MODEL_FOOTER || bytes[bytes.size - 1] != (-1).toByte() || bytes[bytes.size - 2] != (-1).toByte()) {
            out.add("no new format marker; the client would read it as an old format model")
            return@read
        }
        val footer = ClientPacket(bytes, bytes.size - MODEL_FOOTER)
        val vertices = footer.ushort()
        val faces = footer.ushort()
        val textures = footer.ubyte()
        val flags = footer.ubyte()
        val hasFaceTypes = flags and 0x1 != 0
        val hasParticles = flags and 0x2 != 0
        val hasBillboards = flags and 0x4 != 0
        var version = 12
        if (flags and 0x8 != 0) {
            footer.position -= 7
            version = footer.ubyte()
            footer.position += 6
        }
        val priority = footer.ubyte()
        val hasAlpha = footer.ubyte() == 1
        val hasFaceSkins = footer.ubyte() == 1
        val hasMaterials = footer.ubyte() == 1
        val hasVertexSkins = footer.ubyte() == 1
        val lengthX = footer.ushort()
        val lengthY = footer.ushort()
        val lengthZ = footer.ushort()
        val lengthFaces = footer.ushort()
        val lengthTextureIndices = footer.ushort()
        expect(out, "vertices", vertices, definition.vertexCount)
        expect(out, "faces", faces, definition.faceCount)
        expect(out, "texture triangles", textures, definition.textureTriangleCount)
        val mappings = List(textures) { packet.byte() }
        val simple = mappings.count { it == 0 }
        val complex = mappings.count { it in 1..3 }
        val cube = mappings.count { it == 2 }
        var index = textures
        val vertexFlags = index
        index += vertices
        val faceTypes = index
        if (hasFaceTypes) index += faces
        val faceIndexTypes = index
        index += faces
        val priorities = index
        if (priority == 255) index += faces
        val faceSkins = index
        if (hasFaceSkins) index += faces
        val vertexSkins = index
        if (hasVertexSkins) index += vertices
        val alphas = index
        if (hasAlpha) index += faces
        val faceIndices = index
        index += lengthFaces
        val materials = index
        if (hasMaterials) index += faces * 2
        val textureIndices = index
        index += lengthTextureIndices
        val colours = index
        index += faces * 2
        val positionsX = index
        index += lengthX
        val positionsY = index
        index += lengthY
        val positionsZ = index
        index += lengthZ
        val simpleTriangles = index
        index += simple * 6
        val complexTriangles = index
        index += complex * 6
        val scaleBytes = if (version == 14) 7 else if (version >= 15) 9 else 6
        val complexScales = index
        index += complex * scaleBytes
        val complexRotations = index
        index += complex
        val complexDirections = index
        index += complex
        val complexSpeeds = index
        index = cube * 2 + index + complex
        val flagsPacket = ClientPacket(bytes, vertexFlags)
        val x = ClientPacket(bytes, positionsX)
        val y = ClientPacket(bytes, positionsY)
        val z = ClientPacket(bytes, positionsZ)
        val readX = IntArray(vertices)
        val readY = IntArray(vertices)
        val readZ = IntArray(vertices)
        var lastX = 0
        var lastY = 0
        var lastZ = 0
        for (vertex in 0 until vertices) {
            val flag = flagsPacket.ubyte()
            lastX += if (flag and 0x1 != 0) x.smart() else 0
            lastY += if (flag and 0x2 != 0) y.smart() else 0
            lastZ += if (flag and 0x4 != 0) z.smart() else 0
            readX[vertex] = lastX
            readY[vertex] = lastY
            readZ[vertex] = lastZ
        }
        if (x.position != positionsY || y.position != positionsZ || z.position != simpleTriangles) {
            out.add("vertex streams end at ${x.position}/${y.position}/${z.position}, the footer's lengths say $positionsY/$positionsZ/$simpleTriangles")
        }
        expect(out, "positions", Triple(readX.toList(), readY.toList(), readZ.toList()), Triple(definition.vertexX.toList(), definition.vertexY.toList(), definition.vertexZ.toList()))
        val colour = ClientPacket(bytes, colours)
        val material = ClientPacket(bytes, materials)
        val texture = ClientPacket(bytes, textureIndices)
        val layers = ClientPacket(bytes, priorities)
        val faceLayers = IntArray(faces)
        for (face in 0 until faces) {
            colour.ushort()
            faceLayers[face] = if (priority == 255) layers.byte() else priority
            if (hasMaterials) {
                val id = material.ushort() - 1
                if (id >= 0 && !context.exists("material", id)) {
                    out.add("face $face draws material $id, which does not exist")
                }
                if (id >= 0 && textures > 0) {
                    val pointer = texture.ubyte() - 1
                    if (pointer >= textures) {
                        out.add("face $face names texture triangle $pointer of $textures")
                    }
                }
            }
        }
        if (hasMaterials && textures > 0 && texture.position != colours) {
            out.add("texture indices end at ${texture.position}, the colours start at $colours")
        }
        val types = ClientPacket(bytes, faceIndexTypes)
        val indices = ClientPacket(bytes, faceIndices)
        var a = 0
        var b = 0
        var c = 0
        var last = 0
        val faceA = IntArray(faces)
        val faceB = IntArray(faces)
        val faceC = IntArray(faces)
        for (face in 0 until faces) {
            when (val type = types.ubyte()) {
                1 -> {
                    a = indices.smart() + last
                    b = indices.smart() + a
                    c = indices.smart() + b
                    last = c
                }
                2 -> {
                    b = c
                    c = indices.smart() + last
                    last = c
                }
                3 -> {
                    a = c
                    c = indices.smart() + last
                    last = c
                }
                4 -> {
                    val previous = a
                    a = b
                    b = previous
                    c = indices.smart() + last
                    last = c
                }
                else -> out.add("face $face has index type $type, which the client skips")
            }
            faceA[face] = a
            faceB[face] = b
            faceC[face] = c
            if (a !in 0 until vertices || b !in 0 until vertices || c !in 0 until vertices) {
                out.add("face $face ($a, $b, $c) leaves the $vertices vertices")
            }
        }
        if (indices.position != materials) {
            out.add("face indices end at ${indices.position}, the footer says $materials")
        }
        expect(out, "faces", Triple(faceA.toList(), faceB.toList(), faceC.toList()), Triple(definition.faceA.toList(), definition.faceB.toList(), definition.faceC.toList()))
        val simplePacket = ClientPacket(bytes, simpleTriangles)
        val complexPacket = ClientPacket(bytes, complexTriangles)
        for (pointer in 0 until textures) {
            val corners = if (mappings[pointer] == 0) simplePacket else complexPacket
            repeat(3) {
                val vertex = corners.ushort()
                if (vertex >= vertices) {
                    out.add("texture triangle $pointer names vertex $vertex of $vertices")
                }
            }
        }
        if (simplePacket.position != complexTriangles || complexPacket.position != complexScales) {
            out.add("texture triangles end at ${simplePacket.position}/${complexPacket.position}, expected $complexTriangles/$complexScales")
        }
        if (complexRotations > complexDirections || complexSpeeds > index) {
            out.add("complex texture sections overlap")
        }
        val attachments = ClientPacket(bytes, index)
        val emitters = ArrayList<List<Int>>()
        val effectors = ArrayList<List<Int>>()
        val billboards = ArrayList<List<Int>>()
        if (hasParticles) {
            repeat(attachments.ubyte()) {
                val producer = attachments.ushort()
                val face = attachments.ushort()
                if (face >= faces) {
                    out.add("emitter on face $face of $faces; facesLayer[face] overruns")
                } else if (!context.exists("producer", producer)) {
                    out.add("emitter producer $producer does not exist")
                }
                emitters.add(listOf(producer, face))
            }
            repeat(attachments.ubyte()) {
                val effector = attachments.ushort()
                val vertex = attachments.ushort()
                if (vertex >= vertices) {
                    out.add("effector on vertex $vertex of $vertices")
                }
                if (!context.exists("effector", effector)) {
                    out.add("effector $effector does not exist")
                }
                effectors.add(listOf(effector, vertex))
            }
        }
        if (hasBillboards) {
            repeat(attachments.ubyte()) {
                val billboard = attachments.ushort()
                val face = attachments.ushort()
                val depth = attachments.ubyte()
                val distance = attachments.byte()
                if (face >= faces) {
                    out.add("billboard on face $face of $faces")
                }
                if (!context.exists("billboard", billboard)) {
                    out.add("billboard $billboard does not exist")
                }
                billboards.add(listOf(billboard, face, depth, distance))
            }
        }
        val end = bytes.size - MODEL_FOOTER - if (flags and 0x8 != 0) 1 else 0
        if (attachments.position != end) {
            out.add("attachments end at ${attachments.position}, the footer (and version byte) start at $end")
        }
        expect(out, "emitters", emitters, definition.particleEmitters.orEmpty().map { listOf(it.particleId, it.appliesOnFace) })
        expect(out, "effectors", effectors, definition.particleEffectors.orEmpty().map { listOf(it.effectorId, it.appliesOnVertex) })
        expect(out, "billboards", billboards, definition.billboards.orEmpty().map { listOf(it.billboardId, it.appliesOnFace, it.depth, it.distance) })
    }

    private fun read(bytes: ByteArray, parse: (ClientPacket, MutableList<String>) -> Unit): List<String> {
        val out = ArrayList<String>()
        try {
            parse(ClientPacket(bytes), out)
        } catch (e: ClientDesync) {
            out.add("desync: ${e.message}")
        } catch (e: IndexOutOfBoundsException) {
            out.add("desync: ${e.message}")
        }
        return out
    }

    private fun opcodes(packet: ClientPacket, out: MutableList<String>, decode: (Int) -> Boolean) {
        while (true) {
            if (packet.remaining == 0) {
                out.add("no terminating opcode 0")
                return
            }
            val opcode = packet.ubyte()
            if (opcode == 0) {
                trailing(packet, out)
                return
            }
            if (!decode(opcode)) {
                out.add("opcode $opcode is unknown to the client, which reads no payload for it")
            }
        }
    }

    private fun trailing(packet: ClientPacket, out: MutableList<String>) {
        if (packet.remaining != 0) {
            out.add("${packet.remaining} bytes left over")
        }
    }

    private fun expect(out: MutableList<String>, field: String, read: Any?, expected: Any?) {
        if (read != expected) {
            out.add("client reads $field as $read, the definition holds $expected")
        }
    }
}
