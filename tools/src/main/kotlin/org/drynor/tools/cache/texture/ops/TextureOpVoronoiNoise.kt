package org.drynor.tools.cache.texture.ops

import org.drynor.tools.cache.texture.TextureOperation
import world.gregs.voidps.buffer.read.BufferReader
import java.util.Random
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Type 15: Procedural Voronoi noise generator.
 * Creates cellular/Worley noise patterns.
 */
class TextureOpVoronoiNoise : TextureOperation(0, true) {
    private var randomBytes: ByteArray = ByteArray(512)
    private var pointPositions: ShortArray = ShortArray(512)
    private var randomSeed: Int = 0
    private var jitter: Int = 2048
    private var cellCountX: Int = 5
    private var cellCountY: Int = 5
    private var outputType: Int = 2
    private var distanceType: Int = 1

    override fun getMonochromeOutput(y: Int): IntArray {
        val output = monochromeImageCache.getPalette(y)
        if (monochromeImageCache.dirty) {
            val verticalPos = verticalTable[y] * cellCountY + 2048
            val verticalCell = verticalPos shr 12
            val verticalCellNext = verticalCell + 1

            for (x in 0 until currentWidth) {
                var dist1 = Int.MAX_VALUE
                var dist2 = Int.MAX_VALUE
                var dist3 = Int.MAX_VALUE
                var dist4 = Int.MAX_VALUE

                val horizontalPos = horizontalTable[x] * cellCountX + 2048
                val horizontalCell = horizontalPos shr 12
                val horizontalCellNext = horizontalCell + 1

                for (cellY in (verticalCell - 1)..verticalCellNext) {
                    val yIndex = randomBytes[(if (cellY >= cellCountY) cellY - cellCountY else cellY) and 0xff].toInt() and 0xff

                    for (cellX in (horizontalCell - 1)..horizontalCellNext) {
                        val pointIndex = 2 * (randomBytes[yIndex + (if (cellX >= cellCountX) cellX - cellCountX else cellX) and 0xff].toInt() and 0xff)
                        var deltaX = horizontalPos - (pointPositions[pointIndex].toInt() + (cellX shl 12))
                        var deltaY = verticalPos - (pointPositions[pointIndex + 1].toInt() + (cellY shl 12))

                        val distance = when (distanceType) {
                            1 -> (deltaY * deltaY + deltaX * deltaX) shr 12
                            2 -> (if (deltaX < 0) -deltaX else deltaX) + (if (deltaY < 0) -deltaY else deltaY)
                            3 -> {
                                deltaX = if (deltaX < 0) -deltaX else deltaX
                                deltaY = if (deltaY < 0) -deltaY else deltaY
                                max(deltaX, deltaY)
                            }
                            4 -> {
                                deltaX = (sqrt((if (deltaX < 0) -deltaX else deltaX) / 4096.0) * 4096.0).toInt()
                                deltaY = (sqrt((if (deltaY < 0) -deltaY else deltaY) / 4096.0) * 4096.0).toInt()
                                val sum = deltaX + deltaY
                                (sum * sum) shr 12
                            }
                            5 -> {
                                deltaX *= deltaX
                                deltaY *= deltaY
                                (sqrt(sqrt((deltaY + deltaX) / 1.6777216E7)) * 4096.0).toInt()
                            }
                            else -> (sqrt((deltaX * deltaX + deltaY * deltaY) / 1.6777216E7) * 4096.0).toInt()
                        }

                        if (distance < dist4) {
                            dist1 = dist2
                            dist2 = dist3
                            dist3 = dist4
                            dist4 = distance
                        } else if (distance < dist3) {
                            dist1 = dist2
                            dist2 = dist3
                            dist3 = distance
                        } else if (distance < dist2) {
                            dist1 = dist2
                            dist2 = distance
                        } else if (distance < dist1) {
                            dist1 = distance
                        }
                    }
                }

                output[x] = when (outputType) {
                    0 -> dist4
                    1 -> dist3
                    2 -> dist3 - dist4
                    3 -> dist2
                    4 -> dist1
                    else -> 0
                }
            }
        }
        return output
    }

    override fun postDecode() {
        randomBytes = generateRandomBytes(randomSeed)
        initializePointPositions()
    }

    private fun generateRandomBytes(seed: Int): ByteArray {
        val result = ByteArray(512)
        for (i in 0 until 256) {
            result[i] = i.toByte()
        }
        val random = Random(seed.toLong())
        for (i in 0 until 256) {
            val j = nextUnbiasedRandom(random, 256 - i) + i
            val temp = result[i]
            result[i] = result[j]
            result[j] = temp
        }
        for (i in 256 until 512) {
            result[i] = result[i - 256]
        }
        return result
    }

    private fun initializePointPositions() {
        val random = Random(randomSeed.toLong())
        pointPositions = ShortArray(512)
        if (jitter > 0) {
            for (i in 0 until 512) {
                pointPositions[i] = nextUnbiasedRandom(random, jitter).toShort()
            }
        }
    }

    private fun nextUnbiasedRandom(random: Random, range: Int): Int {
        if (range <= 0) return 0
        var value: Int
        var limit: Int
        do {
            value = random.nextInt() and 0x7fffffff
            limit = value % range
        } while (value - limit + (range - 1) < 0)
        return limit
    }

    override fun decodeOpcode(opcode: Int, buffer: BufferReader) {
        when (opcode) {
            0 -> {
                cellCountX = buffer.readUnsignedByte()
                cellCountY = cellCountX
            }
            1 -> randomSeed = buffer.readUnsignedByte()
            2 -> jitter = buffer.readShort() and 0xFFFF
            3 -> outputType = buffer.readUnsignedByte()
            4 -> distanceType = buffer.readUnsignedByte()
            5 -> cellCountX = buffer.readUnsignedByte()
            6 -> cellCountY = buffer.readUnsignedByte()
        }
    }
}
