package world.gregs.voidps.cache.definition.data

/**
 * Billboard attachment data decoded from model binary footer.
 *
 * Each billboard defines a 2D textured quad attached to a face in the model.
 * The BillboardType config (loaded from cache index 29) determines whether the
 * anchor face is hidden (hasUid=true) and what material to render in its place.
 *
 * @param billboardId BillboardType config ID (index 29, archive 0)
 * @param appliesOnFace Face index this billboard is attached to
 * @param depth Depth byte (used for animation grouping)
 * @param distance Distance offset from the anchor face centroid (signed)
 */
data class BillboardData(
    val billboardId: Int,
    val appliesOnFace: Int,
    val depth: Int,
    val distance: Int
)

/**
 * A particle emitter the model's footer attaches to one of its faces.
 *
 * The face is where the particles come out of; the id names a
 * [ParticleProducerDefinition][world.gregs.voidps.cache.definition.data.ParticleProducerDefinition]
 * in index 27 archive 0, which is everything about how they behave. The client resolves the face
 * into its three vertices and its priority the moment it decodes the model and keeps neither the
 * face index nor the id, so this is the pair as the file actually holds it.
 *
 * @param particleId the producer's id in index 27 archive 0
 * @param appliesOnFace the face the emitter sits on
 */
data class ParticleEmitterAttachment(
    val particleId: Int,
    val appliesOnFace: Int
)

/**
 * A particle effector the model's footer attaches to one of its vertices.
 *
 * The id names a [ParticleDefinition][world.gregs.voidps.cache.definition.data.ParticleDefinition]
 * in index 27 archive 1 - a force field that pushes particles about - and the vertex is where it
 * acts from.
 *
 * @param effectorId the particle's id in index 27 archive 1
 * @param appliesOnVertex the vertex the effector sits on
 */
data class ParticleEffectorAttachment(
    val effectorId: Int,
    val appliesOnVertex: Int
)

/**
 * Decoded model data from the RS cache.
 *
 * Note: RS models with version < 13 need their vertices upscaled by 4 to match
 * scene coordinates. The decoder handles this automatically.
 */
data class ModelDefinition(
    val id: Int,
    val version: Int,  // Model format version. Version < 13 vertices are pre-upscaled by decoder.
    val vertexCount: Int,
    val faceCount: Int,
    val vertexX: IntArray,
    val vertexY: IntArray,
    val vertexZ: IntArray,
    val faceA: IntArray,
    val faceB: IntArray,
    val faceC: IntArray,
    val faceColors: ShortArray,
    val faceAlphas: ByteArray?,
    val faceRenderTypes: ByteArray?,
    val facePriorities: ByteArray?,
    /** Material/texture ID for each face (0 = no texture, >0 = material ID + 1) */
    val faceTextures: ShortArray?,
    /** Index into texture triangles for UV projection (-1 = use default UVs) */
    val faceTexturePointers: ByteArray?,
    /** Number of texture triangles defined in this model */
    val textureTriangleCount: Int,
    /** Mapping type for each texture triangle (0=planar, 1-3=cylindrical) */
    val texturesMapping: ByteArray?,
    /** Vertex A index for each texture triangle */
    val textureFaceX: ShortArray?,
    /** Vertex B index for each texture triangle */
    val textureFaceY: ShortArray?,
    /** Vertex C index for each texture triangle */
    val textureFaceZ: ShortArray?,
    val textureCoordU: FloatArray?,
    val textureCoordV: FloatArray?,
    val vertexNormalsX: IntArray?,
    val vertexNormalsY: IntArray?,
    val vertexNormalsZ: IntArray?,
    // Complex texture mapping fields (for types 1-3: cylindrical, cube, spherical)
    /** Scale X for complex texture mappings (types 1-3) - indexed by complex texture index */
    val texturesScaleX: IntArray?,
    /** Scale Y for complex texture mappings (types 1-3) - indexed by complex texture index */
    val texturesScaleY: IntArray?,
    /** Scale Z for complex texture mappings (types 1-3) - indexed by complex texture index */
    val texturesScaleZ: IntArray?,
    /** Rotation (0-255 maps to 0-2pi) for complex mappings - indexed by complex texture index */
    val texturesRotation: ByteArray?,
    /** Direction/orientation (0-3) for complex mappings - indexed by complex texture index */
    val texturesDirection: ByteArray?,
    /** Animation speed offset for complex mappings - indexed by complex texture index */
    val texturesSpeed: IntArray?,
    /** Cube mapping U translation - indexed by cube texture index (type 2 only) */
    val texturesUtrans: IntArray?,
    /** Cube mapping V translation - indexed by cube texture index (type 2 only) */
    val texturesVtrans: IntArray?,
    /** Billboard attachments decoded from model footer (null if no billboards) */
    val billboards: Array<BillboardData>? = null,

    // --- Layout fidelity: everything the renderer never looks at but the file still holds ------
    //
    // Below this line nothing is used to draw anything. It is what [ModelEncoder] needs to write
    // the archive back byte for byte: the footer's own bytes, the two per-element layout arrays,
    // the skin sections neither the viewer nor the client's scene graph reads here, and the
    // particle attachments. See `docs/cache-source.md`, "models".

    /**
     * Whether the archive ended in the `0xFFFF` marker, which is the newer of the two 727 layouts.
     *
     * The old layout has no version byte, no particles and no billboards, packs its texture
     * triangles between the face colours and the vertices, and starts its vertex flags at offset 0
     * rather than after the texture mapping bytes.
     */
    val newFormat: Boolean = true,
    /**
     * The footer's flag byte: `0x1` face render types, `0x2` particles, `0x4` billboards, `0x8` a
     * version byte. Kept whole because the four bits above them are the file's to carry even
     * though nothing reads them.
     *
     * For the old layout there is no such byte and this holds that layout's single "has face
     * render types" byte instead, which is a value rather than a bit field.
     */
    val footerFlags: Int = 0,
    /** The footer's priority byte. 255 means the file carries a [facePriorities] section. */
    val modelPriority: Int = 0,
    /** The footer's "has face alphas" byte, kept raw: only the value 1 turns the section on. */
    val alphaFlag: Int = 0,
    /** The footer's "has face skins" byte, raw, as [alphaFlag]. */
    val faceSkinFlag: Int = 0,
    /** The footer's "has face materials" byte, raw, as [alphaFlag]. */
    val textureFlag: Int = 0,
    /** The footer's "has vertex skins" byte, raw, as [alphaFlag]. */
    val vertexSkinFlag: Int = 0,
    /**
     * Per vertex, which of x, y and z the file carries a delta for: `0x1`, `0x2`, `0x4`.
     *
     * Almost always exactly "the axes whose delta is not zero", which is what
     * [ModelEncoder][world.gregs.voidps.cache.definition.encoder.ModelEncoder] derives when this is
     * null; a file that sets a bit for a zero delta anyway keeps its own bytes here.
     */
    val vertexFlags: ByteArray? = null,
    /**
     * Per face, how its three vertex indices follow from the face before it: 1 rewrites all three,
     * 2, 3 and 4 keep two of them and read one.
     *
     * Derived from the faces when null, exactly as [vertexFlags] is.
     */
    val faceIndexTypes: ByteArray? = null,
    /** The skin (bone group) each face belongs to, when [faceSkinFlag] is 1. */
    val faceSkins: IntArray? = null,
    /** The skin (bone group) each vertex belongs to, when [vertexSkinFlag] is 1. */
    val vertexSkins: IntArray? = null,
    /**
     * The texture index section of a file that declares no texture triangles at all.
     *
     * A model may set the footer's "has face materials" byte and declare a texture index length
     * without carrying a single texture triangle for those indices to point at - model 73412 of
     * rev727 is the one that does, with 47 such bytes. The client allocates no `facesTexture`
     * array in that case and never reads them, so they reach no [faceTexturePointers]; they are
     * still the file's, so they are kept here. Null whenever [faceTexturePointers] is not, which
     * is every other model in the cache.
     */
    val unusedTexturePointers: ByteArray? = null,
    /** Particle emitters the footer attaches to faces, when [footerFlags] has `0x2`. */
    val particleEmitters: Array<ParticleEmitterAttachment>? = null,
    /** Particle effectors the footer attaches to vertices, when [footerFlags] has `0x2`. */
    val particleEffectors: Array<ParticleEffectorAttachment>? = null,
    /**
     * Bytes between the end of the last section and the footer that nothing here accounts for.
     *
     * No archive in the 727 cache has any; the field exists so that one that did would round trip
     * rather than lose them silently.
     */
    val trailing: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ModelDefinition
        return id == other.id
    }

    override fun hashCode(): Int = id

    companion object {
        val EMPTY = ModelDefinition(
            id = -1,
            version = 12,
            vertexCount = 0,
            faceCount = 0,
            vertexX = intArrayOf(),
            vertexY = intArrayOf(),
            vertexZ = intArrayOf(),
            faceA = intArrayOf(),
            faceB = intArrayOf(),
            faceC = intArrayOf(),
            faceColors = shortArrayOf(),
            faceAlphas = null,
            faceRenderTypes = null,
            facePriorities = null,
            faceTextures = null,
            faceTexturePointers = null,
            textureTriangleCount = 0,
            texturesMapping = null,
            textureFaceX = null,
            textureFaceY = null,
            textureFaceZ = null,
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
            billboards = null
        )

        /**
         * Combine multiple model definitions into a single definition.
         * This matches how the RS client combines multiple models for multi-model objects
         * (e.g., fountains with water + stone base).
         *
         * The combination:
         * - Welds vertices at identical positions (like RS Mesh constructor)
         * - Adjusts face indices to reference welded vertices
         * - Combines face data (colors, alphas, textures, etc.)
         * - Adjusts texture triangle indices
         *
         * @param models List of models to combine
         * @param combinedId ID for the combined model (typically first model's ID or a composite)
         * @return Combined model definition, or null if models list is empty
         */
        fun combine(models: List<ModelDefinition>, combinedId: Int): ModelDefinition? {
            if (models.isEmpty()) return null
            if (models.size == 1) return models[0]

            // First pass: count totals and check for complex texture data
            var totalVertices = 0
            var totalFaces = 0
            var totalTextures = 0
            var totalComplexTextures = 0  // Types 1-3
            var totalCubeTextures = 0     // Type 2 only
            var hasAlpha = false
            var hasRenderTypes = false
            var hasPriorities = false
            var hasFaceTextures = false
            var hasFaceTexturePointers = false
            var hasTexturesMapping = false
            var hasComplexTextureData = false
            var hasCubeTextureData = false
            var totalBillboards = 0
            var maxVersion = 0

            for (model in models) {
                totalVertices += model.vertexCount
                totalFaces += model.faceCount
                totalTextures += model.textureTriangleCount
                if (model.billboards != null) totalBillboards += model.billboards.size
                hasAlpha = hasAlpha || model.faceAlphas != null
                hasRenderTypes = hasRenderTypes || model.faceRenderTypes != null
                hasPriorities = hasPriorities || model.facePriorities != null
                hasFaceTextures = hasFaceTextures || model.faceTextures != null
                hasFaceTexturePointers = hasFaceTexturePointers || model.faceTexturePointers != null
                hasTexturesMapping = hasTexturesMapping || model.texturesMapping != null
                hasComplexTextureData = hasComplexTextureData || model.texturesScaleX != null
                hasCubeTextureData = hasCubeTextureData || model.texturesUtrans != null
                if (model.version > maxVersion) maxVersion = model.version

                // Count complex and cube texture triangles
                if (model.texturesMapping != null) {
                    for (t in 0 until model.textureTriangleCount) {
                        val mapping = model.texturesMapping[t].toInt() and 0xff
                        if (mapping in 1..3) totalComplexTextures++
                        if (mapping == 2) totalCubeTextures++
                    }
                }
            }

            // Allocate combined arrays
            val combinedVertexX = IntArray(totalVertices)
            val combinedVertexY = IntArray(totalVertices)
            val combinedVertexZ = IntArray(totalVertices)
            val combinedFaceA = IntArray(totalFaces)
            val combinedFaceB = IntArray(totalFaces)
            val combinedFaceC = IntArray(totalFaces)
            val combinedFaceColors = ShortArray(totalFaces)
            val combinedFaceAlphas = if (hasAlpha) ByteArray(totalFaces) else null
            val combinedFaceRenderTypes = if (hasRenderTypes) ByteArray(totalFaces) else null
            val combinedFacePriorities = if (hasPriorities) ByteArray(totalFaces) else null
            val combinedFaceTextures = if (hasFaceTextures) ShortArray(totalFaces) else null
            val combinedFaceTexturePointers = if (hasFaceTexturePointers) ByteArray(totalFaces) else null
            val combinedTexturesMapping = if (hasTexturesMapping && totalTextures > 0) ByteArray(totalTextures) else null
            val combinedTextureFaceX = if (totalTextures > 0) ShortArray(totalTextures) else null
            val combinedTextureFaceY = if (totalTextures > 0) ShortArray(totalTextures) else null
            val combinedTextureFaceZ = if (totalTextures > 0) ShortArray(totalTextures) else null
            // Complex texture data arrays (indexed by complex texture index)
            val combinedTexturesScaleX = if (hasComplexTextureData && totalComplexTextures > 0) IntArray(totalComplexTextures) else null
            val combinedTexturesScaleY = if (hasComplexTextureData && totalComplexTextures > 0) IntArray(totalComplexTextures) else null
            val combinedTexturesScaleZ = if (hasComplexTextureData && totalComplexTextures > 0) IntArray(totalComplexTextures) else null
            val combinedTexturesRotation = if (hasComplexTextureData && totalComplexTextures > 0) ByteArray(totalComplexTextures) else null
            val combinedTexturesDirection = if (hasComplexTextureData && totalComplexTextures > 0) ByteArray(totalComplexTextures) else null
            val combinedTexturesSpeed = if (hasComplexTextureData && totalComplexTextures > 0) IntArray(totalComplexTextures) else null
            // Cube texture data arrays (indexed by cube texture index, type 2 only)
            val combinedTexturesUtrans = if (hasCubeTextureData && totalCubeTextures > 0) IntArray(totalCubeTextures) else null
            val combinedTexturesVtrans = if (hasCubeTextureData && totalCubeTextures > 0) IntArray(totalCubeTextures) else null

            // Vertex welding map: (x, y, z) -> welded vertex index
            // This matches the RS client's Mesh.connect() method which welds vertices at identical positions
            val weldedVertices = mutableMapOf<Triple<Int, Int, Int>, Int>()
            val weldedVertexX = mutableListOf<Int>()
            val weldedVertexY = mutableListOf<Int>()
            val weldedVertexZ = mutableListOf<Int>()

            fun weldVertex(x: Int, y: Int, z: Int): Int {
                val key = Triple(x, y, z)
                return weldedVertices.getOrPut(key) {
                    val idx = weldedVertexX.size
                    weldedVertexX.add(x)
                    weldedVertexY.add(y)
                    weldedVertexZ.add(z)
                    idx
                }
            }

            // Allocate combined billboard array
            val combinedBillboards = if (totalBillboards > 0) {
                Array(totalBillboards) { BillboardData(0, 0, 0, 0) }
            } else null

            // Second pass: combine data
            var faceOffset = 0
            var textureOffset = 0
            var complexTextureOffset = 0  // Offset into combined complex texture arrays
            var cubeTextureOffset = 0     // Offset into combined cube texture arrays
            var billboardOffset = 0

            for (model in models) {
                // Build vertex index mapping for this model (old index -> welded index)
                val vertexMap = IntArray(model.vertexCount)
                for (v in 0 until model.vertexCount) {
                    vertexMap[v] = weldVertex(model.vertexX[v], model.vertexY[v], model.vertexZ[v])
                }

                // Copy billboards with adjusted face indices (must be done before faces
                // are counted, matching the RS client's Mesh combine constructor)
                if (model.billboards != null) {
                    for (b in model.billboards.indices) {
                        val bb = model.billboards[b]
                        combinedBillboards!![billboardOffset + b] = BillboardData(
                            billboardId = bb.billboardId,
                            appliesOnFace = bb.appliesOnFace + faceOffset,
                            depth = bb.depth,
                            distance = bb.distance
                        )
                    }
                    billboardOffset += model.billboards.size
                }

                // Copy faces with remapped vertex indices
                for (f in 0 until model.faceCount) {
                    val destIdx = faceOffset + f
                    combinedFaceA[destIdx] = vertexMap[model.faceA[f]]
                    combinedFaceB[destIdx] = vertexMap[model.faceB[f]]
                    combinedFaceC[destIdx] = vertexMap[model.faceC[f]]
                    combinedFaceColors[destIdx] = model.faceColors[f]

                    combinedFaceAlphas?.let { arr ->
                        // Default to 0 (opaque) when a model has no faceAlphas array.
                        // RS uses inverted alpha: 0 = opaque, 255 = fully transparent.
                        // Using -1 (0xFF = 255) would incorrectly mark faces as invisible.
                        arr[destIdx] = model.faceAlphas?.get(f) ?: 0
                    }
                    combinedFaceRenderTypes?.let { arr ->
                        arr[destIdx] = model.faceRenderTypes?.get(f) ?: 0
                    }
                    combinedFacePriorities?.let { arr ->
                        arr[destIdx] = model.facePriorities?.get(f) ?: 0
                    }
                    combinedFaceTextures?.let { arr ->
                        arr[destIdx] = model.faceTextures?.get(f) ?: -1
                    }
                    combinedFaceTexturePointers?.let { arr ->
                        val pointer = model.faceTexturePointers?.get(f)?.toInt() ?: -1
                        // Adjust texture pointer to account for combined texture triangle offset
                        arr[destIdx] = if (pointer >= 0) (pointer + textureOffset).toByte() else -1
                    }
                }

                // Copy texture triangles with remapped vertex indices
                // Note: Negative indices (like -32767) are sentinel values meaning "no texture vertex"
                // and should be preserved as-is rather than remapped
                // Track local complex/cube texture indices for this model
                var modelComplexIdx = 0
                var modelCubeIdx = 0
                for (t in 0 until model.textureTriangleCount) {
                    val destIdx = textureOffset + t
                    val mapping = model.texturesMapping?.get(t)?.toInt()?.and(0xff) ?: 0
                    combinedTexturesMapping?.let { arr ->
                        arr[destIdx] = mapping.toByte()
                    }
                    combinedTextureFaceX?.let { arr ->
                        val oldIdx = model.textureFaceX?.get(t)?.toInt() ?: 0
                        arr[destIdx] = if (oldIdx >= 0 && oldIdx < model.vertexCount) {
                            vertexMap[oldIdx].toShort()
                        } else {
                            model.textureFaceX?.get(t) ?: 0
                        }
                    }
                    combinedTextureFaceY?.let { arr ->
                        val oldIdx = model.textureFaceY?.get(t)?.toInt() ?: 0
                        arr[destIdx] = if (oldIdx >= 0 && oldIdx < model.vertexCount) {
                            vertexMap[oldIdx].toShort()
                        } else {
                            model.textureFaceY?.get(t) ?: 0
                        }
                    }
                    combinedTextureFaceZ?.let { arr ->
                        val oldIdx = model.textureFaceZ?.get(t)?.toInt() ?: 0
                        arr[destIdx] = if (oldIdx >= 0 && oldIdx < model.vertexCount) {
                            vertexMap[oldIdx].toShort()
                        } else {
                            model.textureFaceZ?.get(t) ?: 0
                        }
                    }

                    // Copy complex texture data (types 1-3)
                    if (mapping in 1..3) {
                        val srcComplexIdx = modelComplexIdx
                        val dstComplexIdx = complexTextureOffset + modelComplexIdx
                        combinedTexturesScaleX?.let { arr ->
                            arr[dstComplexIdx] = model.texturesScaleX?.get(srcComplexIdx) ?: 0
                        }
                        combinedTexturesScaleY?.let { arr ->
                            arr[dstComplexIdx] = model.texturesScaleY?.get(srcComplexIdx) ?: 0
                        }
                        combinedTexturesScaleZ?.let { arr ->
                            arr[dstComplexIdx] = model.texturesScaleZ?.get(srcComplexIdx) ?: 0
                        }
                        combinedTexturesRotation?.let { arr ->
                            arr[dstComplexIdx] = model.texturesRotation?.get(srcComplexIdx) ?: 0
                        }
                        combinedTexturesDirection?.let { arr ->
                            arr[dstComplexIdx] = model.texturesDirection?.get(srcComplexIdx) ?: 0
                        }
                        combinedTexturesSpeed?.let { arr ->
                            arr[dstComplexIdx] = model.texturesSpeed?.get(srcComplexIdx) ?: 0
                        }
                        modelComplexIdx++

                        // Copy cube texture data (type 2 only)
                        if (mapping == 2) {
                            val srcCubeIdx = modelCubeIdx
                            val dstCubeIdx = cubeTextureOffset + modelCubeIdx
                            combinedTexturesUtrans?.let { arr ->
                                arr[dstCubeIdx] = model.texturesUtrans?.get(srcCubeIdx) ?: 0
                            }
                            combinedTexturesVtrans?.let { arr ->
                                arr[dstCubeIdx] = model.texturesVtrans?.get(srcCubeIdx) ?: 0
                            }
                            modelCubeIdx++
                        }
                    }
                }

                faceOffset += model.faceCount
                textureOffset += model.textureTriangleCount
                complexTextureOffset += modelComplexIdx
                cubeTextureOffset += modelCubeIdx
            }

            // Create final welded vertex arrays
            val finalVertexX = weldedVertexX.toIntArray()
            val finalVertexY = weldedVertexY.toIntArray()
            val finalVertexZ = weldedVertexZ.toIntArray()

            return ModelDefinition(
                id = combinedId,
                version = maxVersion,
                vertexCount = finalVertexX.size,
                faceCount = totalFaces,
                vertexX = finalVertexX,
                vertexY = finalVertexY,
                vertexZ = finalVertexZ,
                faceA = combinedFaceA,
                faceB = combinedFaceB,
                faceC = combinedFaceC,
                faceColors = combinedFaceColors,
                faceAlphas = combinedFaceAlphas,
                faceRenderTypes = combinedFaceRenderTypes,
                facePriorities = combinedFacePriorities,
                faceTextures = combinedFaceTextures,
                faceTexturePointers = combinedFaceTexturePointers,
                textureTriangleCount = totalTextures,
                texturesMapping = combinedTexturesMapping,
                textureFaceX = combinedTextureFaceX,
                textureFaceY = combinedTextureFaceY,
                textureFaceZ = combinedTextureFaceZ,
                textureCoordU = null,
                textureCoordV = null,
                vertexNormalsX = null,
                vertexNormalsY = null,
                vertexNormalsZ = null,
                texturesScaleX = combinedTexturesScaleX,
                texturesScaleY = combinedTexturesScaleY,
                texturesScaleZ = combinedTexturesScaleZ,
                texturesRotation = combinedTexturesRotation,
                texturesDirection = combinedTexturesDirection,
                texturesSpeed = combinedTexturesSpeed,
                texturesUtrans = combinedTexturesUtrans,
                texturesVtrans = combinedTexturesVtrans,
                billboards = combinedBillboards
            )
        }
    }
}
