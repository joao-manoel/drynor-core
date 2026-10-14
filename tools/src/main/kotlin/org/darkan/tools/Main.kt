package org.darkan.tools

import org.darkan.core.map.MapXteas
import org.darkan.core.map.RegionLoader
import org.darkan.tools.cache.NameLookup
import org.darkan.tools.cache.runCache
import org.darkan.tools.cs2.runCs2
import org.darkan.tools.gameval.runGameval
import org.darkan.tools.rs3port.runIcon
import org.darkan.tools.rs3port.runRs3Port
import org.darkan.tools.cache.map.DisplaceProbe
import org.darkan.tools.cache.map.ObjectRenderer
import org.darkan.tools.cache.map.TiltProbe
import org.darkan.tools.cache.map.environment.runEnvironmentDiagnostic
import world.gregs.voidps.cache.definition.decoder.BillboardTypeLoader
import world.gregs.voidps.cache.definition.decoder.ModelDecoder
import org.darkan.tools.cache.model.ModelLoader
import world.gregs.voidps.cache.definition.decoder.ModelUnavailableReason
import org.darkan.tools.cache.texture.*
import org.darkan.tools.cache.texture.ops.*
import org.darkan.tools.cache.terrain.TerrainLoader
import org.darkan.tools.util.runTileMapDump
import org.darkan.tools.viewer.InterfaceStartup
import org.darkan.tools.viewer.InterfaceViewer
import org.darkan.tools.viewer.MapViewer
import org.darkan.tools.viewer.ModelViewer
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import java.io.File
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Main entry point for Darkan Tools.
 * Launches the selected viewer application.
 */
fun main(args: Array<String>) {
    val mode = args.firstOrNull() ?: "model"

    // The cache source tree modes come first because most of them have no prepacked cache to
    // open: `pack`, `build`, `verify` and `status` work from the tree alone, and `unpack` opens
    // the directory it is pointed at rather than the one Cache.get() would.
    if (mode.lowercase() == "cache") {
        runCache(args.drop(1))
        return
    }

    if (mode.lowercase() == "rs3port") {
        runRs3Port(args.drop(1))
        return
    }

    println("Darkan Tools - Loading cache...")
    val cache = Cache.get()
    println("Cache loaded successfully.")

    when (mode.lowercase()) {
        "model", "models" -> {
            val modelId = args.getOrNull(1)?.toIntOrNull()
            args.indexOf("--glb").takeIf { it >= 0 && it + 1 < args.size }?.let { ModelLoader.glbDirectory = Path.of(args[it + 1]) }
            println("Starting Model Viewer..." + (modelId?.let { " with model ID $it" } ?: ""))
            ModelViewer(cache, modelId).run()
        }
        "map", "maps", "terrain" -> {
            println("Starting Map Viewer...")
            MapViewer(cache).run()
        }
        "interface", "interfaces", "ui" -> {
            val interfaceId = args.getOrNull(1)?.toIntOrNull() ?: 0
            println("Starting Interface Editor with interface $interfaceId...")
            InterfaceViewer(cache, interfaceId, interfaceStartup(args.drop(1))).run()
        }
        "diagnose", "debug" -> {
            // Diagnostic mode: diagnose <regionX> <regionY> <localX> <localY> [plane]
            val regionX = args.getOrNull(1)?.toIntOrNull() ?: 50
            val regionY = args.getOrNull(2)?.toIntOrNull() ?: 50
            val localX = args.getOrNull(3)?.toIntOrNull() ?: 39
            val localY = args.getOrNull(4)?.toIntOrNull() ?: 25
            val plane = args.getOrNull(5)?.toIntOrNull() ?: 0
            runDiagnostic(cache, regionX, regionY, localX, localY, plane)
        }
        "displaceprobe" -> {
            // Audit ground-contour-1 vertex displacement for a region, headlessly.
            // displaceprobe <regionX> <regionY> [objectId]
            val rx = args.getOrNull(1)?.toIntOrNull() ?: 52
            val ry = args.getOrNull(2)?.toIntOrNull() ?: 50
            val objectId = args.getOrNull(3)?.toIntOrNull()
            DisplaceProbe.run(cache, rx, ry, objectId)
        }
        "displacejoint" -> {
            // Compare a region's y=63 edge against the y=0 edge of the region above.
            // displacejoint <regionX> <regionY> <objectId>
            val rx = args.getOrNull(1)?.toIntOrNull() ?: 52
            val ry = args.getOrNull(2)?.toIntOrNull() ?: 50
            val objectId = args.getOrNull(3)?.toIntOrNull() ?: 3125
            DisplaceProbe.runJoint(cache, rx, ry, objectId)
        }
        "tiltprobe" -> {
            // Audit contour-type-3 tilt sampling at region edges, headlessly.
            // tiltprobe scan [maxRegions] | tiltprobe whatif <regionX> <regionY>
            when (args.getOrNull(1)?.lowercase() ?: "scan") {
                "whatif" -> TiltProbe.whatIf(
                    cache,
                    args.getOrNull(2)?.toIntOrNull() ?: 52,
                    args.getOrNull(3)?.toIntOrNull() ?: 50
                )
                else -> TiltProbe.scan(cache, args.getOrNull(2)?.toIntOrNull())
            }
        }
        "roofscan" -> {
            val rx = args.getOrNull(1)?.toIntOrNull() ?: 54
            val ry = args.getOrNull(2)?.toIntOrNull() ?: 54
            runRoofScan(cache, rx, ry)
        }
        "object", "obj" -> {
            val objectId = args.getOrNull(1)?.toIntOrNull() ?: 45149
            runObjectDiagnostic(cache, objectId)
        }
        "uv", "uvdebug" -> {
            val modelId = args.getOrNull(1)?.toIntOrNull() ?: 48861
            runUVDiagnostic(cache, modelId)
        }
        "texture", "tex" -> {
            val texId = args.getOrNull(1)?.toIntOrNull() ?: 725
            runTextureDiagnostic(cache, texId)
        }
        "icon", "icons" -> {
            runIcon(cache, args.drop(1))
        }
        "texdump" -> {
            val texId = args.getOrNull(1)?.toIntOrNull() ?: 725
            val outputPath = args.getOrNull(2) ?: "texture_$texId.png"
            TextureLoader.initHeadless(cache)
            TextureLoader.dumpTextureImage(cache, texId, outputPath)
        }
        "faces", "facegeom" -> {
            val modelId = args.getOrNull(1)?.toIntOrNull() ?: 68750
            runFaceGeometryDiagnostic(cache, modelId)
        }
        "modelscan" -> {
            runModelScan(cache)
        }
        "tilemap", "tiles" -> {
            runTileMapDump(cache, args.drop(1))
        }
        "lookup", "name", "names", "hash" -> {
            runNameLookup(cache, args)
        }
        "cs2" -> {
            runCs2(cache, args.drop(1))
        }
        "gameval", "gamevals" -> {
            runGameval(cache, args.drop(1))
        }
        "env", "environment", "lighting" -> {
            runEnvironmentDiagnostic(cache, args.drop(1))
        }
        "help", "-h", "--help" -> {
            printHelp()
        }
        else -> {
            println("Unknown mode: $mode")
            printHelp()
        }
    }
}

/**
 * Run diagnostic on a specific tile to find what objects are there and what textures they use.
 */
private fun runDiagnostic(cache: Cache, regionX: Int, regionY: Int, localX: Int, localY: Int, plane: Int) {
    println("\n=== TILE DIAGNOSTIC ===")
    println("Region: ($regionX, $regionY)")
    println("Local: ($localX, $localY)")
    println("Plane: $plane")
    println()

    // Load definitions
    TerrainLoader.loadDefinitions(cache)
    ObjectRenderer.loadDefinitions(cache)

    // Load XTEA keys
    if (!MapXteas.isLoaded()) {
        MapXteas.load()
    }

    // Load terrain data
    val terrainData = TerrainLoader.loadRegion(cache, regionX, regionY)
    if (terrainData == null) {
        println("ERROR: Could not load terrain data for region ($regionX, $regionY)")
        return
    }

    // Get tile info
    val tile = terrainData.tiles[plane][localX][localY]
    println("--- TERRAIN TILE DATA ---")
    println("  Height: ${tile.height}")
    println("  Overlay ID: ${tile.overlayId}")
    println("  Overlay Shape: ${tile.overlayShape}")
    println("  Overlay Rotation: ${tile.overlayRotation}")
    println("  Underlay ID: ${tile.underlayId}")
    println("  Flags: 0x${tile.flags.toString(16)}")

    // Get overlay definition if present
    if (tile.overlayId > 0) {
        val overlayDef = TerrainLoader.getOverlayDefinition(tile.overlayId)
        if (overlayDef != null) {
            println("  Overlay Definition:")
            println("    Texture: ${overlayDef.texture}")
            println("    Scale: ${overlayDef.scale}")
            println("    Colour: ${overlayDef.colour}")
            println("    BlendColour: ${overlayDef.blendColour}")
            println("    UnderlayOverrides: ${overlayDef.underlayOverrides}")
        }
    }

    // Get underlay definition if present
    if (tile.underlayId > 0) {
        val underlayDef = TerrainLoader.getUnderlayDefinition(tile.underlayId)
        if (underlayDef != null) {
            println("  Underlay Definition:")
            println("    Texture: ${underlayDef.texture}")
            println("    Scale: ${underlayDef.scale}")
            println("    Colour: ${underlayDef.colour}")
        }
    }

    // Load region objects
    println("\n--- OBJECTS AT THIS TILE ---")
    val regionData = RegionLoader.loadRegion(cache, regionX, regionY)
    if (regionData == null) {
        println("ERROR: Could not load region data for ($regionX, $regionY)")
        return
    }

    val baseWorldX = regionX * 64
    val baseWorldY = regionY * 64
    val worldX = baseWorldX + localX
    val worldY = baseWorldY + localY

    // Find objects at or near this tile
    var foundObjects = 0
    for (obj in regionData.objects) {
        // Check if object is at or overlaps this tile
        val def = ObjectRenderer.getDefinition(obj.id)
        val sizeX = def?.sizeX ?: 1
        val sizeY = def?.sizeY ?: 1

        // Calculate object footprint
        val objMinX = obj.x
        val objMaxX = obj.x + sizeX - 1
        val objMinY = obj.y
        val objMaxY = obj.y + sizeY - 1

        // Check if our tile is within the object's footprint
        if (worldX in objMinX..objMaxX && worldY in objMinY..objMaxY) {
            foundObjects++
            println("\nObject #$foundObjects:")
            println("  ID: ${obj.id}")
            println("  Position: (${obj.x}, ${obj.y}) - Local: (${obj.x - baseWorldX}, ${obj.y - baseWorldY})")
            println("  Plane: ${obj.level}")
            println("  Shape: ${obj.shape}")
            println("  Rotation: ${obj.rotation}")

            if (def != null) {
                println("  Definition:")
                println("    Name: ${def.name}")
                println("    Size: ${def.sizeX} x ${def.sizeY}")
                println("    Options: ${def.options?.toList()}")

                // Get model IDs for this object
                val modelIds = ObjectRenderer.getModelIds(def, obj.shape)
                println("    Model IDs: $modelIds")

                // Check each model for textures
                for (modelId in modelIds) {
                    val modelDef = ModelLoader.loadDefinition(cache, modelId)
                    if (modelDef != null) {
                        println("\n    Model $modelId:")
                        println("      Vertices: ${modelDef.vertexCount}")
                        println("      Faces: ${modelDef.faceCount}")
                        println("      Texture Triangles: ${modelDef.textureTriangleCount}")

                        // Find all textures used by this model
                        val usedTextures = mutableSetOf<Int>()
                        if (modelDef.faceTextures != null) {
                            for (f in 0 until modelDef.faceCount) {
                                val texId = modelDef.faceTextures!![f].toInt()
                                if (texId != -1) {
                                    usedTextures.add(texId and 0xffff)
                                }
                            }
                        }
                        if (usedTextures.isNotEmpty()) {
                            println("      Textures used: $usedTextures")

                            // Check if texture 928 is used
                            if (928 in usedTextures) {
                                println("      *** TEXTURE 928 FOUND IN THIS MODEL ***")

                                // Get detailed UV info for faces with texture 928
                                println("      Faces with texture 928:")
                                for (f in 0 until modelDef.faceCount) {
                                    val texId = modelDef.faceTextures?.get(f)?.toInt() ?: -1
                                    if ((texId and 0xffff) == 928) {
                                        val texPointer = modelDef.faceTexturePointers?.get(f)?.toInt()?.and(0xff) ?: 255
                                        println("        Face $f: texturePointer=$texPointer")

                                        if (texPointer != 255 && texPointer < modelDef.textureTriangleCount) {
                                            val mapping = modelDef.texturesMapping?.get(texPointer)?.toInt()?.and(0xff) ?: 0
                                            println("          mappingType=$mapping")
                                            if (modelDef.textureFaceX != null) {
                                                val tA = modelDef.textureFaceX!![texPointer].toInt()
                                                val tB = modelDef.textureFaceY?.get(texPointer)?.toInt() ?: 0
                                                val tC = modelDef.textureFaceZ?.get(texPointer)?.toInt() ?: 0
                                                println("          textureTriangle: ($tA, $tB, $tC)")
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            println("      Textures: (none - vertex colors only)")
                        }
                    }
                }
            }
        }
    }

    if (foundObjects == 0) {
        println("  (No objects found at this tile)")
    }

    // Also check nearby tiles (within 3 tile radius) for large objects
    println("\n--- NEARBY OBJECTS (3 tile radius) USING TEXTURE 928 ---")
    var nearbyWithTexture928 = 0
    for (obj in regionData.objects) {
        val objLocalX = obj.x - baseWorldX
        val objLocalY = obj.y - baseWorldY

        // Check if object is within 3 tiles of our target
        if (abs(objLocalX - localX) <= 3 && abs(objLocalY - localY) <= 3) {
            val def = ObjectRenderer.getDefinition(obj.id) ?: continue
            val modelIds = ObjectRenderer.getModelIds(def, obj.shape)

            for (modelId in modelIds) {
                val modelDef = ModelLoader.loadDefinition(cache, modelId) ?: continue
                if (modelDef.faceTextures == null) continue

                var hasTexture928 = false
                for (f in 0 until modelDef.faceCount) {
                    val texId = modelDef.faceTextures!![f].toInt() and 0xffff
                    if (texId == 928) {
                        hasTexture928 = true
                        break
                    }
                }

                if (hasTexture928) {
                    nearbyWithTexture928++
                    println("\nNearby Object #$nearbyWithTexture928:")
                    println("  ID: ${obj.id} (${def.name})")
                    println("  Position: Local (${objLocalX}, ${objLocalY})")
                    println("  Plane: ${obj.level}")
                    println("  Shape: ${obj.shape}")
                    println("  Model: $modelId")
                    break
                }
            }
        }
    }

    if (nearbyWithTexture928 == 0) {
        println("  (No nearby objects using texture 928)")
    }

    // Check ALL objects in the region for texture 928
    println("\n--- ALL OBJECTS IN REGION USING TEXTURE 928 ---")
    var allWithTexture928 = 0
    for (obj in regionData.objects) {
        val def = ObjectRenderer.getDefinition(obj.id) ?: continue
        val modelIds = ObjectRenderer.getModelIds(def, obj.shape)

        for (modelId in modelIds) {
            val modelDef = ModelLoader.loadDefinition(cache, modelId) ?: continue
            if (modelDef.faceTextures == null) continue

            var hasTexture928 = false
            for (f in 0 until modelDef.faceCount) {
                val texId = modelDef.faceTextures!![f].toInt() and 0xffff
                if (texId == 928) {
                    hasTexture928 = true
                    break
                }
            }

            if (hasTexture928) {
                allWithTexture928++
                val objLocalX = obj.x - baseWorldX
                val objLocalY = obj.y - baseWorldY
                println("\n  Object #$allWithTexture928:")
                println("    ID: ${obj.id} (${def.name})")
                println("    Position: Local ($objLocalX, $objLocalY)")
                println("    Size: ${def.sizeX} x ${def.sizeY}")
                println("    Plane: ${obj.level}")
                println("    Shape: ${obj.shape}")
                println("    Model: $modelId")
                break
            }
        }
    }

    if (allWithTexture928 == 0) {
        println("  (No objects in this region use texture 928)")
    }

    // Print underlay/overlay info for nearby tiles to check texture continuity
    println("\n--- NEARBY TILE TEXTURES (3x3 around target) ---")
    for (dy in -1..1) {
        for (dx in -1..1) {
            val checkX = localX + dx
            val checkY = localY + dy
            if (checkX < 0 || checkX >= 64 || checkY < 0 || checkY >= 64) continue

            val checkTile = terrainData.tiles[plane][checkX][checkY]
            val overlayTex = if (checkTile.overlayId > 0) {
                TerrainLoader.getOverlayDefinition(checkTile.overlayId)?.texture ?: -1
            } else -1
            val underlayTex = if (checkTile.underlayId > 0) {
                TerrainLoader.getUnderlayDefinition(checkTile.underlayId)?.texture ?: -1
            } else -1

            println("  Tile ($checkX, $checkY): overlay=${checkTile.overlayId}(tex=$overlayTex), underlay=${checkTile.underlayId}(tex=$underlayTex)")
        }
    }

    println("\n=== DIAGNOSTIC COMPLETE ===")
}

/**
 * Run UV debugging on a specific model to trace through the UV calculation.
 */
private fun runUVDiagnostic(cache: Cache, modelId: Int) {
    println("\n=== UV DIAGNOSTIC: Model $modelId ===")

    // Clear cache to force recomputation
    ModelLoader.clearCache()

    // Load the model
    val meshDataList = ModelLoader.createMeshDataList(cache, modelId, debug = true)
    if (meshDataList == null) {
        printModelUnavailable(cache, modelId)
        return
    }

    println("\nMeshData batches: ${meshDataList.size}")
    for ((i, meshData) in meshDataList.withIndex()) {
        println("  Batch $i: textureId=${meshData.textureId}, vertices=${meshData.positions.size / 3}, indices=${meshData.indices.size}")

        // Print first 9 vertices (3 faces) with their UVs
        if (meshData.textureCoords != null) {
            val numVerts = minOf(9, meshData.positions.size / 3)
            println("    First $numVerts vertices:")
            for (v in 0 until numVerts) {
                val px = meshData.positions[v * 3]
                val py = meshData.positions[v * 3 + 1]
                val pz = meshData.positions[v * 3 + 2]
                val u = meshData.textureCoords!![v * 2]
                val vCoord = meshData.textureCoords!![v * 2 + 1]
                println("      V$v: pos=($px, $py, $pz) uv=($u, $vCoord)")
            }
        }
    }

    println("\n=== UV DIAGNOSTIC COMPLETE ===")
}

/**
 * Diagnose a specific object ID to dump its definition and model texture info.
 */
private fun runObjectDiagnostic(cache: Cache, objectId: Int) {
    println("\n=== OBJECT DIAGNOSTIC: ID $objectId ===")

    // Load definitions
    ObjectRenderer.loadDefinitions(cache)

    val def = ObjectRenderer.getDefinition(objectId)
    if (def == null) {
        println("ERROR: Object $objectId not found")
        return
    }

    println("Name: ${def.name}")
    println("Size: ${def.sizeX} x ${def.sizeY}")
    println("Options: ${def.options?.toList()}")
    println("ModelTypes: ${def.modelTypes?.map { it.toInt() }}")
    println("ModelIds: ${def.modelIds?.contentDeepToString()}")
    println("Interactive: ${def.interactive}")
    println("Solid: ${def.solid}")
    println("BlocksLand: ${def.blocksLand}")
    println("Brightness: ${def.brightness}")
    println("Contrast: ${def.contrast}")
    println("DelayShading (merge normals across objects): ${def.delayShading}")
    println("ContouredGround: ${def.contouredGround} (modifier ${def.anInt3023})")
    println("Mirrored: ${def.mirrored}")

    // Check for retexturing
    if (def.originalTextureColours != null || def.modifiedTextureColours != null) {
        println("RETEXTURING: src=${def.originalTextureColours?.map { it.toInt() and 0xffff }}, dst=${def.modifiedTextureColours?.map { it.toInt() and 0xffff }}")
    }
    if (def.originalColours != null || def.modifiedColours != null) {
        println("RECOLORING: src=${def.originalColours?.map { it.toInt() and 0xffff }}, dst=${def.modifiedColours?.map { it.toInt() and 0xffff }}")
    }

    // Get all model IDs for this object (all shapes combined)
    val allModelIds = mutableSetOf<Int>()
    def.modelIds?.forEach { modelIdArray ->
        modelIdArray.forEach { modelId -> allModelIds.add(modelId) }
    }

    println("\n--- MODELS ---")
    for (modelId in allModelIds) {
        println("\nModel $modelId:")
        val modelDef = ModelLoader.loadDefinition(cache, modelId)
        if (modelDef == null) {
            printModelUnavailable(cache, modelId, indent = "  ")
            continue
        }

        println("  Version: ${modelDef.version}")
        println("  Vertices: ${modelDef.vertexCount}")
        println("  Faces: ${modelDef.faceCount}")
        println("  Texture Triangles: ${modelDef.textureTriangleCount}")

        // Count texture mapping types
        var type0Count = 0
        var type1Count = 0
        var type2Count = 0
        var type3Count = 0
        if (modelDef.texturesMapping != null) {
            for (i in 0 until modelDef.textureTriangleCount) {
                when (modelDef.texturesMapping!![i].toInt() and 0xff) {
                    0 -> type0Count++
                    1 -> type1Count++
                    2 -> type2Count++
                    3 -> type3Count++
                }
            }
        }
        println("  Mapping types: type0(planar)=$type0Count, type1(cylindrical)=$type1Count, type2(cube)=$type2Count, type3(spherical)=$type3Count")

        // Show texture triangles details
        if (modelDef.textureTriangleCount > 0) {
            println("  Texture Triangles:")
            for (t in 0 until modelDef.textureTriangleCount) {
                val mapping = modelDef.texturesMapping?.get(t)?.toInt()?.and(0xff) ?: 0
                val tA = modelDef.textureFaceX?.get(t)?.toInt() ?: 0
                val tB = modelDef.textureFaceY?.get(t)?.toInt() ?: 0
                val tC = modelDef.textureFaceZ?.get(t)?.toInt() ?: 0
                println("    [$t] mapping=$mapping, vertices=($tA, $tB, $tC)")
            }
        }

        // Show complex texture data if present
        if (modelDef.texturesScaleX != null) {
            println("  Complex Texture Data (indexed by complex texture index):")
            for (i in modelDef.texturesScaleX!!.indices) {
                val scaleX = modelDef.texturesScaleX!![i]
                val scaleY = modelDef.texturesScaleY?.get(i) ?: 0
                val scaleZ = modelDef.texturesScaleZ?.get(i) ?: 0
                val rotation = modelDef.texturesRotation?.get(i)?.toInt()?.and(0xff) ?: 0
                val direction = modelDef.texturesDirection?.get(i)?.toInt()?.and(0xff) ?: 0
                val speed = modelDef.texturesSpeed?.get(i) ?: 0
                println("    Complex[$i]: scale=($scaleX, $scaleY, $scaleZ), rot=$rotation, dir=$direction, speed=$speed")
            }
        }

        // Show cube texture data if present
        if (modelDef.texturesUtrans != null) {
            println("  Cube Texture Data (indexed by cube texture index):")
            for (i in modelDef.texturesUtrans!!.indices) {
                val uTrans = modelDef.texturesUtrans!![i]
                val vTrans = modelDef.texturesVtrans?.get(i) ?: 0
                println("    Cube[$i]: uTrans=$uTrans, vTrans=$vTrans")
            }
        }

        // Find all textures used by faces
        val usedTextures = mutableMapOf<Int, Int>() // textureId -> count
        val textureMappings = mutableMapOf<Int, MutableSet<Int>>() // textureId -> set of mapping types used
        if (modelDef.faceTextures != null) {
            for (f in 0 until modelDef.faceCount) {
                val texId = modelDef.faceTextures!![f].toInt()
                if (texId != -1) {
                    val actualTexId = texId and 0xffff
                    usedTextures[actualTexId] = (usedTextures[actualTexId] ?: 0) + 1

                    val texPointer = modelDef.faceTexturePointers?.get(f)?.toInt()?.and(0xff) ?: 255
                    if (texPointer != 255 && texPointer < modelDef.textureTriangleCount) {
                        val mapping = modelDef.texturesMapping?.get(texPointer)?.toInt()?.and(0xff) ?: 0
                        textureMappings.getOrPut(actualTexId) { mutableSetOf() }.add(mapping)
                    }
                }
            }
        }

        if (usedTextures.isNotEmpty()) {
            println("  Face Textures Used:")
            for ((texId, count) in usedTextures.entries.sortedBy { it.key }) {
                val mappings = textureMappings[texId] ?: emptySet()
                println("    Texture $texId: $count faces, mappings=${mappings.sorted()}")
            }
        }

        // Detailed face analysis for first 10 textured faces
        println("  First 10 textured faces:")
        var texturedCount = 0
        for (f in 0 until modelDef.faceCount) {
            if (texturedCount >= 10) break

            val texId = modelDef.faceTextures?.get(f)?.toInt() ?: -1
            if (texId == -1) continue

            texturedCount++
            val actualTexId = texId and 0xffff
            val texPointer = modelDef.faceTexturePointers?.get(f)?.toInt()?.and(0xff) ?: 255
            val aIdx = modelDef.faceA[f]
            val bIdx = modelDef.faceB[f]
            val cIdx = modelDef.faceC[f]

            println("    Face $f: tex=$actualTexId, pointer=$texPointer, verts=($aIdx, $bIdx, $cIdx)")

            if (texPointer != 255 && texPointer < modelDef.textureTriangleCount) {
                val mapping = modelDef.texturesMapping?.get(texPointer)?.toInt()?.and(0xff) ?: 0
                println("      mapping=$mapping")

                // Show vertex positions for the face
                if (aIdx < modelDef.vertexCount && bIdx < modelDef.vertexCount && cIdx < modelDef.vertexCount) {
                    println("      A: (${modelDef.vertexX[aIdx]}, ${modelDef.vertexY[aIdx]}, ${modelDef.vertexZ[aIdx]})")
                    println("      B: (${modelDef.vertexX[bIdx]}, ${modelDef.vertexY[bIdx]}, ${modelDef.vertexZ[bIdx]})")
                    println("      C: (${modelDef.vertexX[cIdx]}, ${modelDef.vertexY[cIdx]}, ${modelDef.vertexZ[cIdx]})")
                }
            }
        }

        // Show ALL untextured faces and their details
        println("  Untextured faces (faceTextures == -1):")
        var untexturedCount = 0
        for (f in 0 until modelDef.faceCount) {
            val texId = modelDef.faceTextures?.get(f)?.toInt() ?: -1
            if (texId != -1) continue

            untexturedCount++
            val rawHsl = modelDef.faceColors[f].toInt() and 0xffff
            val renderTypeRaw = modelDef.faceRenderTypes?.get(f)
            val renderType = renderTypeRaw?.toInt()?.and(0xff) ?: 0
            val alpha = modelDef.faceAlphas?.get(f)?.toInt()?.and(0xff) ?: 0
            val texPointer = modelDef.faceTexturePointers?.get(f)?.toInt() ?: -999
            val rawFaceColor = modelDef.faceColors[f]
            println("    Face $f: HSL=$rawHsl (raw=$rawFaceColor), renderType=$renderType (raw=$renderTypeRaw), alpha=$alpha, texPointer=$texPointer")
        }
        if (untexturedCount == 0) {
            println("    (None - all faces are textured)")
        } else {
            println("    Total untextured: $untexturedCount")
        }

        // Verify texture transition point - show faces around the textured/untextured boundary
        if (modelDef.faceTextures != null) {
            println("  Texture boundary analysis (faces 760-774):")
            for (f in 760 until minOf(774, modelDef.faceCount)) {
                val texIdSigned = modelDef.faceTextures!![f].toInt()
                val texId = if (texIdSigned == -1) -1 else (texIdSigned and 0xffff)
                val rawHsl = modelDef.faceColors[f].toInt() and 0xffff
                println("    Face $f: texId=$texId, HSL=$rawHsl")
            }
        }

        // Check footer flags for billboard/particle effects data
        val rawModelData = cache.data(Index.MODELS, modelId)
        if (rawModelData != null && rawModelData.size >= 24) {
            val footerFlags = rawModelData[rawModelData.size - 18].toInt() and 0xff
            val hasFaceTypes = (footerFlags and 0x1) == 1
            val hasParticleEffects = (footerFlags and 0x2) == 2
            val hasBillboards = (footerFlags and 0x4) == 4
            val hasVersion = (footerFlags and 0x8) == 8
            println("  Footer flags: 0x${footerFlags.toString(16)}")
            println("    hasFaceTypes=$hasFaceTypes, hasParticleEffects=$hasParticleEffects, hasBillboards=$hasBillboards, hasVersion=$hasVersion")

            if (hasBillboards) {
                println("  MODEL HAS BILLBOARD DATA - these faces may be billboard anchors!")
            }

            // Print decoded billboard data from ModelDefinition
            if (modelDef.billboards != null) {
                println("  Decoded billboards (${modelDef.billboards!!.size}):")
                for ((bi, bb) in modelDef.billboards!!.withIndex()) {
                    println("    [$bi] id=${bb.billboardId}, face=${bb.appliesOnFace}, depth=${bb.depth}, distance=${bb.distance}")
                    // Load the BillboardType config
                    val bbType = BillboardTypeLoader.get(cache, bb.billboardId)
                    println("         type: materialId=${bbType.materialId}, size2d=${bbType.size2d}, size3d=${bbType.size3d}, shape=${bbType.shape}, blend=${bbType.blendType}, stationary=${bbType.stationary}, hasUid=${bbType.hasUid}")
                }
            } else {
                println("  Decoded billboards: null (no billboard data decoded)")
            }
        }

        // Specific face 769 analysis (if it exists)
        if (modelDef.faceCount > 769) {
            println("  === FACE 769 DETAILED ANALYSIS ===")
            val f = 769
            val rawHsl = modelDef.faceColors[f].toInt() and 0xffff
            val renderType = modelDef.faceRenderTypes?.get(f)?.toInt()?.and(0xff) ?: 0
            val alpha = modelDef.faceAlphas?.get(f)?.toInt()?.and(0xff) ?: 0
            val rawTexIdSigned = modelDef.faceTextures?.get(f)?.toInt() ?: -1
            val rawTexId = if (rawTexIdSigned == -1) -1 else (rawTexIdSigned and 0xffff)
            val texPointer = modelDef.faceTexturePointers?.get(f)?.toInt() ?: -999
            val aIdx = modelDef.faceA[f]
            val bIdx = modelDef.faceB[f]
            val cIdx = modelDef.faceC[f]
            println("    HSL: $rawHsl (raw short: ${modelDef.faceColors[f]})")
            println("    RenderType: $renderType")
            println("    Alpha: $alpha")
            println("    TextureId (signed): $rawTexIdSigned")
            println("    TextureId (unsigned): $rawTexId")
            println("    TexturePointer: $texPointer")
            println("    Vertices: A=$aIdx, B=$bIdx, C=$cIdx")
            if (aIdx < modelDef.vertexCount && bIdx < modelDef.vertexCount && cIdx < modelDef.vertexCount) {
                println("    A pos: (${modelDef.vertexX[aIdx]}, ${modelDef.vertexY[aIdx]}, ${modelDef.vertexZ[aIdx]})")
                println("    B pos: (${modelDef.vertexX[bIdx]}, ${modelDef.vertexY[bIdx]}, ${modelDef.vertexZ[bIdx]})")
                println("    C pos: (${modelDef.vertexX[cIdx]}, ${modelDef.vertexY[cIdx]}, ${modelDef.vertexZ[cIdx]})")
            }
            // Check surrounding faces for context
            println("  === FACES 765-773 (around face 769) ===")
            for (nearby in 765..773) {
                if (nearby < 0 || nearby >= modelDef.faceCount) continue
                val nHsl = modelDef.faceColors[nearby].toInt() and 0xffff
                val nRender = modelDef.faceRenderTypes?.get(nearby)?.toInt()?.and(0xff) ?: 0
                val nTexSigned = modelDef.faceTextures?.get(nearby)?.toInt() ?: -1
                val nTex = if (nTexSigned == -1) -1 else (nTexSigned and 0xffff)
                val nPtr = modelDef.faceTexturePointers?.get(nearby)?.toInt() ?: -999
                val nAlpha = modelDef.faceAlphas?.get(nearby)?.toInt()?.and(0xff) ?: 0
                println("    Face $nearby: HSL=$nHsl, tex=$nTex, ptr=$nPtr, render=$nRender, alpha=$nAlpha")
            }
        }
    }

    println("\n=== DIAGNOSTIC COMPLETE ===")
}

/**
 * Run a texture diagnostic without requiring OpenGL.
 * Loads the texture type from cache, decodes its operations, loads graphics,
 * generates pixels, and prints detailed information about each step.
 */
private fun runTextureDiagnostic(cache: Cache, textureId: Int) {
    println("\n=== TEXTURE DIAGNOSTIC: ID $textureId ===\n")

    // Step 1: Check TEXTURES index structure
    val textureArchives = cache.archives(Index.TEXTURES)
    println("TEXTURES index (${Index.TEXTURES}):")
    println("  Total archives: ${textureArchives.size}")
    println("  Archive $textureId exists: ${textureId in textureArchives}")
    val filesInArchive = cache.files(Index.TEXTURES, textureId)
    println("  Files in archive $textureId: ${filesInArchive.toList()}")

    // Step 2: Load raw data
    val rawData = cache.data(Index.TEXTURES, textureId)
    if (rawData == null) {
        println("  ERROR: cache.data(TEXTURES, $textureId) returned NULL")
        // Try alternate addressing
        val altData = cache.data(Index.TEXTURES, 0, textureId)
        println("  Alternate: cache.data(TEXTURES, 0, $textureId) returned ${if (altData != null) "${altData.size} bytes" else "NULL"}")
        println("\n=== DIAGNOSTIC COMPLETE ===")
        return
    }
    println("  Raw data: ${rawData.size} bytes")
    println("  First 32 bytes: ${rawData.take(32).map { "0x${(it.toInt() and 0xFF).toString(16).padStart(2, '0')}" }}")

    // Step 3: Decode TextureType
    val textureType: TextureType
    try {
        textureType = TextureType(rawData)
    } catch (e: Exception) {
        println("  ERROR decoding TextureType: ${e.message}")
        e.printStackTrace()
        println("\n=== DIAGNOSTIC COMPLETE ===")
        return
    }

    println("\n--- OPERATIONS (${textureType.textureOperations.size}) ---")
    for ((i, op) in textureType.textureOperations.withIndex()) {
        val labels = mutableListOf<String>()
        if (op === textureType.opaqueOperation) labels.add("OPAQUE")
        if (op === textureType.opacityOperation) labels.add("OPACITY")
        if (op === textureType.hdrOperation) labels.add("HDR")
        val labelStr = if (labels.isNotEmpty()) " <== ${labels.joinToString(", ")}" else ""

        val extra = when (op) {
            is TextureOpTiledGraphic -> " (TiledGraphic) graphicId=${op.graphicId}"
            is TextureOpGraphic -> " graphicId=${op.graphicId}"
            is TextureOpTexture -> " materialId=${op.getMaterialId()}"
            else -> ""
        }
        val childIndices = op.operations.mapIndexed { ci, child ->
            if (child != null) {
                val childIdx = textureType.textureOperations.indexOf(child)
                "$ci->op[$childIdx]"
            } else "$ci->null"
        }
        val childStr = if (childIndices.isNotEmpty()) " children=[${childIndices.joinToString(", ")}]" else ""
        println("  [$i] ${op::class.simpleName} monochrome=${op.monochrome} cache=${op.imageCacheCapacity}$extra$childStr$labelStr")
    }

    println("\n  textureFileIds (graphics): ${textureType.textureFileIds.toList()}")
    println("  materialFileIds (nested textures): ${textureType.materialFileIds.toList()}")

    // Step 4: Load graphics
    println("\n--- GRAPHIC LOADING ---")
    for (op in textureType.textureOperations) {
        if (op is TextureOpGraphic && op.graphicId >= 0) {
            val graphicId = op.graphicId
            val graphicData = cache.data(Index.GRAPHICS, graphicId)
            if (graphicData == null) {
                println("  Graphic $graphicId: cache.data(GRAPHICS, $graphicId) returned NULL")
                // Try alternate addressing
                val altData = cache.data(Index.GRAPHICS, 0, graphicId)
                println("  Graphic $graphicId: alternate cache.data(GRAPHICS, 0, $graphicId) returned ${if (altData != null) "${altData.size} bytes" else "NULL"}")
                continue
            }
            println("  Graphic $graphicId: ${graphicData.size} bytes raw data")

            // Decode graphic header
            try {
                val buf = BufferReader(graphicData)
                buf.position(graphicData.size - 2)
                val graphicCount = buf.readShort() and 0xFFFF
                println("    graphicCount: $graphicCount")

                if (graphicCount > 0) {
                    buf.position(graphicData.size - 7 - graphicCount * 8)
                    val biggestWidth = buf.readShort()
                    val biggestHeight = buf.readShort()
                    val paletteSize = buf.readUnsignedByte() + 1
                    println("    biggestWidth=$biggestWidth, biggestHeight=$biggestHeight, paletteSize=$paletteSize")

                    val minX = IntArray(graphicCount) { buf.readShort() }
                    // skip minY, widths, heights
                    buf.position(graphicData.size - 7 - graphicCount * 8 + 5 + graphicCount)
                    val minY = IntArray(graphicCount) { buf.readShort() }
                    val widths = IntArray(graphicCount) { buf.readShort() }
                    val heights = IntArray(graphicCount) { buf.readShort() }

                    // Re-read correctly: after palette size, we have minX[N], minY[N], widths[N], heights[N]
                    buf.position(graphicData.size - 7 - graphicCount * 8 + 5)
                    for (j in 0 until graphicCount) { minX[j] = buf.readShort() }
                    for (j in 0 until graphicCount) { minY[j] = buf.readShort() }
                    for (j in 0 until graphicCount) { widths[j] = buf.readShort() }
                    for (j in 0 until graphicCount) { heights[j] = buf.readShort() }

                    println("    sub[0]: minX=${minX[0]}, minY=${minY[0]}, width=${widths[0]}, height=${heights[0]}")

                    // Read pixel flags
                    buf.position(0)
                    val flags = buf.readUnsignedByte()
                    val hasAlpha = (flags and 0x2) != 0
                    val columnMajor = (flags and 0x1) != 0
                    println("    sub[0] flags: hasAlpha=$hasAlpha, columnMajor=$columnMajor")

                    val fullWidth = if (biggestWidth > 0) biggestWidth else widths[0]
                    val fullHeight = if (biggestHeight > 0) biggestHeight else heights[0]
                    println("    fullCanvas: ${fullWidth}x${fullHeight}")

                    // Try full decode
                    val decoded = TextureLoader.decodeGraphicToDataPublic(graphicData, 0)
                    if (decoded != null) {
                        val nonZero = decoded.pixels.count { it != 0 }
                        val nonBlackRgb = decoded.pixels.count { (it and 0xFFFFFF) != 0 }
                        println("    DECODED: ${decoded.width}x${decoded.height}, ${decoded.pixels.size} pixels, $nonZero non-zero, $nonBlackRgb non-black-RGB")
                        val samples = decoded.pixels.filter { (it and 0xFFFFFF) != 0 }.take(5)
                        println("    sample non-black pixels: ${samples.map { "0x${it.toUInt().toString(16)}" }}")
                    } else {
                        println("    DECODE RETURNED NULL")
                    }
                }
            } catch (e: Exception) {
                println("    ERROR parsing graphic header: ${e.message}")
            }
        }
    }

    // Step 5: Generate pixels without OpenGL
    println("\n--- PIXEL GENERATION ---")
    TextureRaster.calculateTable()

    // Load graphics into operations
    for (op in textureType.textureOperations) {
        if (op is TextureOpGraphic && op.graphicId >= 0) {
            val graphicRawData = cache.data(Index.GRAPHICS, op.graphicId) ?: continue
            val decoded = TextureLoader.decodeGraphicToDataPublic(graphicRawData, 0)
            if (decoded != null) {
                op.loadGraphic(decoded.pixels, decoded.width, decoded.height)
                println("  Loaded graphic ${op.graphicId} into ${op::class.simpleName}: ${decoded.width}x${decoded.height}")
            } else {
                println("  FAILED to decode graphic ${op.graphicId} for ${op::class.simpleName}")
            }
        }
    }

    // Load nested textures
    for (op in textureType.textureOperations) {
        if (op is TextureOpTexture && op.getMaterialId() >= 0) {
            val nestedId = op.getMaterialId()
            val nestedData = cache.data(Index.TEXTURES, nestedId)
            if (nestedData != null) {
                try {
                    val nestedType = TextureType(nestedData)
                    // Load graphics for nested texture
                    for (nestedOp in nestedType.textureOperations) {
                        if (nestedOp is TextureOpGraphic && nestedOp.graphicId >= 0) {
                            val graphicRawData = cache.data(Index.GRAPHICS, nestedOp.graphicId) ?: continue
                            val decoded = TextureLoader.decodeGraphicToDataPublic(graphicRawData, 0)
                            if (decoded != null) {
                                nestedOp.loadGraphic(decoded.pixels, decoded.width, decoded.height)
                            }
                        }
                    }
                    val nestedPixels = nestedType.getPixelsArgb(128, 128, { sid ->
                        val sData = cache.data(Index.GRAPHICS, sid) ?: return@getPixelsArgb null
                        TextureLoader.decodeGraphicToDataPublic(sData, 0)
                    })
                    op.texturePixels = nestedPixels
                    op.textureWidth = 128
                    op.textureHeight = 128
                    val nonZero = nestedPixels.count { it != 0 }
                    println("  Loaded nested texture $nestedId: $nonZero non-zero pixels")
                } catch (e: Exception) {
                    println("  FAILED to load nested texture $nestedId: ${e.message}")
                }
            } else {
                println("  Nested texture $nestedId: cache data is NULL")
            }
        }
    }

    // Generate pixels
    val w = 128
    val h = 128
    try {
        val pixels = textureType.getPixelsArgb(w, h, { sid ->
            val sData = cache.data(Index.GRAPHICS, sid) ?: return@getPixelsArgb null
            TextureLoader.decodeGraphicToDataPublic(sData, 0)
        })

        val nonTransparent = pixels.count { (it shr 24) and 0xFF > 0 }
        val nonBlack = pixels.count { (it and 0xFFFFFF) != 0 }
        println("\n  RESULT: ${pixels.size} pixels")
        println("  Non-transparent (alpha > 0): $nonTransparent")
        println("  Non-black (RGB != 0): $nonBlack")

        if (nonBlack > 0) {
            val samples = pixels.filter { (it and 0xFFFFFF) != 0 }.take(10)
            println("  Sample non-black: ${samples.map { "A=${(it shr 24) and 0xFF} R=${(it shr 16) and 0xFF} G=${(it shr 8) and 0xFF} B=${it and 0xFF}" }}")
        }

        if (nonTransparent == 0 && nonBlack == 0) {
            println("\n  *** ALL PIXELS ARE BLACK/TRANSPARENT ***")
            println("  This confirms the bug. Checking intermediate operations...")

            // Re-run with per-operation tracing
            val raster = TextureRaster.context(w, h)
            for (op in textureType.textureOperations) {
                op.bindRaster(raster, 0L)
                op.createImageCache(w, h)
            }

            // Check each operation's output for row 0
            for ((i, op) in textureType.textureOperations.withIndex()) {
                try {
                    val hasOutput: Boolean
                    if (op.monochrome) {
                        val row = op.getMonochromeOutput(0)
                        val nonZero = row.count { it != 0 }
                        hasOutput = nonZero > 0
                        println("    Op[$i] ${op::class.simpleName} (mono): $nonZero/${row.size} non-zero values. First 5: ${row.take(5).toList()}")
                    } else {
                        val palette = op.getColorOutput(0)
                        val nonZeroR = palette[0].count { it != 0 }
                        val nonZeroG = palette[1].count { it != 0 }
                        val nonZeroB = palette[2].count { it != 0 }
                        hasOutput = nonZeroR > 0 || nonZeroG > 0 || nonZeroB > 0
                        println("    Op[$i] ${op::class.simpleName} (color): R=$nonZeroR/$w G=$nonZeroG/$w B=$nonZeroB/$w non-zero. First 5 R: ${palette[0].take(5).toList()}")
                    }
                    if (!hasOutput) {
                        println("    *** Op[$i] ${op::class.simpleName} PRODUCES ALL ZEROS ***")
                    }
                } catch (e: Exception) {
                    println("    Op[$i] ${op::class.simpleName} THREW: ${e.message}")
                }
            }

            // Clean up
            for (op in textureType.textureOperations) {
                op.clearImageCache()
            }
        }
    } catch (e: Exception) {
        println("  ERROR generating pixels: ${e.message}")
        e.printStackTrace()
    }

    println("\n=== TEXTURE DIAGNOSTIC COMPLETE ===")
}

/**
 * Print why [modelId] produced no definition, distinguishing a gap in the cache data from a
 * failure in our decoder.
 *
 * A bare "could not load model" reads like a decoder bug and sent one investigation down the
 * wrong path already: the ids it was reported for turned out to be empty placeholders that
 * the reference client cannot draw either. Anything the decoder is genuinely responsible for
 * is labelled BUG so it stands out from data that was never there.
 */
private fun printModelUnavailable(cache: Cache, modelId: Int, indent: String = "") {
    val unavailable = ModelDecoder.describeUnavailable(cache, modelId)
    if (unavailable == null) {
        // describeUnavailable decodes it but the caller could not, so the two disagree.
        println("${indent}ERROR: model $modelId decodes on its own but failed to load here")
        return
    }
    val label = when (unavailable.reason) {
        ModelUnavailableReason.DECODE_ERROR -> "BUG"
        else -> "DATA GAP"
    }
    println("$indent$label [${unavailable.reason}]: model $modelId ${unavailable.message}")
}

/**
 * Decode every model archive in the cache and report which ids yield no geometry, grouped by
 * [ModelUnavailableReason].
 *
 * This exists to answer "is this a decoder gap or a data gap?" with a number instead of an
 * assumption. If the decoder ever regresses on a format variant, DECODE_ERROR and TRUNCATED
 * counts move; EMPTY_PLACEHOLDER is a property of the cache and should stay put.
 */
private fun runModelScan(cache: Cache) {
    val total = ModelDecoder.getModelCount(cache)
    println("\n=== MODEL SCAN: $total archives in index ${Index.MODELS} ===")

    val byReason = LinkedHashMap<ModelUnavailableReason, MutableList<Int>>()
    var decoded = 0
    for (modelId in 0 until total) {
        // Decode directly rather than via ModelLoader so the sweep does not fill the
        // definition cache with every model in the game.
        if (ModelDecoder.decode(cache, modelId) != null) {
            decoded++
            continue
        }
        val unavailable = ModelDecoder.describeUnavailable(cache, modelId) ?: continue
        byReason.getOrPut(unavailable.reason) { mutableListOf() }.add(modelId)
    }

    println("Decoded with geometry: $decoded of $total")
    if (byReason.isEmpty()) {
        println("No models failed to produce geometry.")
        return
    }
    for ((reason, ids) in byReason.entries.sortedBy { it.key.name }) {
        val label = if (reason == ModelUnavailableReason.DECODE_ERROR) "BUG" else "DATA GAP"
        println("\n$label [$reason]: ${ids.size} model(s)")
        println("  ids: ${ids.take(MODEL_SCAN_ID_LIMIT)}${if (ids.size > MODEL_SCAN_ID_LIMIT) " ..." else ""}")
        ModelDecoder.describeUnavailable(cache, ids.first())?.let { println("  example: ${it.message}") }
    }
}

/** How many ids [runModelScan] names per reason before truncating. */
private const val MODEL_SCAN_ID_LIMIT = 32

/**
 * Diagnostic to investigate face geometry for coplanarity and render-order occlusion.
 * Prints vertex positions for untextured faces and their nearby textured neighbors
 * to determine if untextured faces are hidden by depth test ordering.
 */
private fun runFaceGeometryDiagnostic(cache: Cache, modelId: Int) {
    println("\n=== FACE GEOMETRY DIAGNOSTIC: Model $modelId ===")

    val def = ModelLoader.loadDefinition(cache, modelId)
    if (def == null) {
        printModelUnavailable(cache, modelId)
        return
    }

    println("Vertices: ${def.vertexCount}, Faces: ${def.faceCount}")
    println("Texture Triangles: ${def.textureTriangleCount}")

    // Find untextured faces
    println("\n--- UNTEXTURED FACES ---")
    val untexturedFaces = mutableListOf<Int>()
    for (f in 0 until def.faceCount) {
        val texId = def.faceTextures?.get(f)?.toInt() ?: -1
        if (texId == -1) {
            untexturedFaces.add(f)
        }
    }

    if (untexturedFaces.isEmpty()) {
        println("No untextured faces found.")
        println("\n=== DIAGNOSTIC COMPLETE ===")
        return
    }

    println("Found ${untexturedFaces.size} untextured faces: $untexturedFaces")

    for (f in untexturedFaces) {
        val aIdx = def.faceA[f]
        val bIdx = def.faceB[f]
        val cIdx = def.faceC[f]
        val hsl = def.faceColors[f].toInt() and 0xffff
        val renderType = def.faceRenderTypes?.get(f)?.toInt()?.and(0xff) ?: 0
        val alpha = def.faceAlphas?.get(f)?.toInt()?.and(0xff) ?: 0

        println("\n  Face $f: HSL=$hsl, renderType=$renderType, alpha=$alpha")
        println("    A[$aIdx]: (${def.vertexX[aIdx]}, ${def.vertexY[aIdx]}, ${def.vertexZ[aIdx]})")
        println("    B[$bIdx]: (${def.vertexX[bIdx]}, ${def.vertexY[bIdx]}, ${def.vertexZ[bIdx]})")
        println("    C[$cIdx]: (${def.vertexX[cIdx]}, ${def.vertexY[cIdx]}, ${def.vertexZ[cIdx]})")

        // Compute face normal
        val dx1 = (def.vertexX[bIdx] - def.vertexX[aIdx]).toFloat()
        val dy1 = (def.vertexY[bIdx] - def.vertexY[aIdx]).toFloat()
        val dz1 = (def.vertexZ[bIdx] - def.vertexZ[aIdx]).toFloat()
        val dx2 = (def.vertexX[cIdx] - def.vertexX[aIdx]).toFloat()
        val dy2 = (def.vertexY[cIdx] - def.vertexY[aIdx]).toFloat()
        val dz2 = (def.vertexZ[cIdx] - def.vertexZ[aIdx]).toFloat()
        val nx = dy1 * dz2 - dz1 * dy2
        val ny = dz1 * dx2 - dx1 * dz2
        val nz = dx1 * dy2 - dy1 * dx2
        val len = sqrt(nx * nx + ny * ny + nz * nz)
        if (len > 0) {
            println("    Normal: (${nx/len}, ${ny/len}, ${nz/len})")
        }

        // Compute face area
        val area = len / 2f
        println("    Area: $area sq units")

        // Compute centroid
        val cx = (def.vertexX[aIdx] + def.vertexX[bIdx] + def.vertexX[cIdx]) / 3f
        val cy = (def.vertexY[aIdx] + def.vertexY[bIdx] + def.vertexY[cIdx]) / 3f
        val cz = (def.vertexZ[aIdx] + def.vertexZ[bIdx] + def.vertexZ[cIdx]) / 3f
        println("    Centroid: ($cx, $cy, $cz)")

        // Find nearby textured faces (sharing at least one vertex)
        val faceVertices = setOf(aIdx, bIdx, cIdx)
        println("    Nearby textured faces sharing vertices:")
        var nearbyCount = 0
        for (other in 0 until def.faceCount) {
            if (other == f) continue
            val otherTexId = def.faceTextures?.get(other)?.toInt() ?: -1
            if (otherTexId == -1) continue // Skip other untextured faces

            val otherVerts = setOf(def.faceA[other], def.faceB[other], def.faceC[other])
            val shared = faceVertices.intersect(otherVerts)
            if (shared.isNotEmpty()) {
                nearbyCount++
                val otherTex = otherTexId and 0xffff
                println("      Face $other (tex=$otherTex): shares vertices $shared")
                println("        A[${def.faceA[other]}]: (${def.vertexX[def.faceA[other]]}, ${def.vertexY[def.faceA[other]]}, ${def.vertexZ[def.faceA[other]]})")
                println("        B[${def.faceB[other]}]: (${def.vertexX[def.faceB[other]]}, ${def.vertexY[def.faceB[other]]}, ${def.vertexZ[def.faceB[other]]})")
                println("        C[${def.faceC[other]}]: (${def.vertexX[def.faceC[other]]}, ${def.vertexY[def.faceC[other]]}, ${def.vertexZ[def.faceC[other]]})")
            }
        }
        if (nearbyCount == 0) {
            println("      (none)")
        }
    }

    println("\n=== DIAGNOSTIC COMPLETE ===")
}

/**
 * Look up cache archives and files by named hash (or by id), and compute named hashes.
 *
 * Sub-commands:
 *   lookup hash <name>                       - print the named hash for <name>
 *   lookup scan                              - report name-hash support per index
 *   lookup <index> <archiveNameOrId>         - resolve an archive and list its files
 *   lookup <index> <archiveNameOrId> <fileNameOrId> - resolve a file and report its size
 *
 * For the index/archive/file args, numeric values are treated as ids and non-numeric
 * values are resolved via the named hash. Hashes are reported in decimal and hex.
 */
private fun runNameLookup(cache: Cache, args: Array<String>) {
    // Sub-command: hash a name without touching the cache.
    if (args.getOrNull(1)?.lowercase() == "hash") {
        val name = args.getOrNull(2)
        if (name == null) {
            println("Usage: lookup hash <name>")
            return
        }
        val hash = NameLookup.nameHash(name)
        println("\n=== NAME HASH ===")
        println("  Name: \"$name\"")
        println("  Hash (dec): $hash")
        println("  Hash (hex): 0x${Integer.toHexString(hash)}")
        println("\n=== LOOKUP COMPLETE ===")
        return
    }

    // Sub-command: scan every index for name-hash support.
    val sub = args.getOrNull(1)?.lowercase()
    if (sub == "scan" || sub == "indices") {
        runNameScan(cache)
        return
    }

    val index = args.getOrNull(1)?.toIntOrNull()
    if (index == null) {
        println("Usage:")
        println("  lookup hash <name>")
        println("  lookup <index> <archiveNameOrId> [fileNameOrId]")
        return
    }

    val archiveArg = args.getOrNull(2)
    if (archiveArg == null) {
        println("Usage: lookup <index> <archiveNameOrId> [fileNameOrId]")
        return
    }
    val fileArg = args.getOrNull(3)

    if (fileArg == null) {
        runArchiveLookup(cache, index, archiveArg)
    } else {
        runFileLookup(cache, index, archiveArg, fileArg)
    }
}

/**
 * Resolve an archive (by id or by name) in [index] and print its file ids.
 */
private fun runArchiveLookup(cache: Cache, index: Int, archiveArg: String) {
    println("\n=== ARCHIVE LOOKUP ===")
    println("  Index: $index")

    val numericArchive = archiveArg.toIntOrNull()
    val archiveId: Int
    if (numericArchive != null) {
        // Treated as an id. The named hash is not reversible from an id, so we can't
        // report a name for it - just confirm whether data exists.
        archiveId = numericArchive
        println("  Archive (id): $archiveId")
        val data = NameLookup.archive(cache, index, archiveId)
        println("  Data exists: ${data != null}${data?.let { " (${it.size} bytes)" } ?: ""}")
        println("  (Note: a name cannot be recovered from a numeric id - the named hash is one-way.)")
    } else {
        // Treated as a name - resolve via the named hash.
        val hash = NameLookup.nameHash(archiveArg)
        println("  Archive (name): \"$archiveArg\"")
        println("  Name hash: $hash (0x${Integer.toHexString(hash)})")
        archiveId = NameLookup.archiveId(cache, index, archiveArg)
        if (archiveId == -1) {
            println("  Resolved archive id: not found")
            println("\n=== LOOKUP COMPLETE ===")
            return
        }
        println("  Resolved archive id: $archiveId")
    }

    val files = cache.files(index, archiveId)
    println("  File count: ${files.size}")
    println("  File ids: ${files.toList()}")
    println("\n=== LOOKUP COMPLETE ===")
}

/**
 * Resolve an archive then a file (each by id or name) and print the resulting data size.
 */
private fun runFileLookup(cache: Cache, index: Int, archiveArg: String, fileArg: String) {
    println("\n=== FILE LOOKUP ===")
    println("  Index: $index")

    // Resolve archive id (by id or name).
    val numericArchive = archiveArg.toIntOrNull()
    val archiveId: Int
    if (numericArchive != null) {
        archiveId = numericArchive
        println("  Archive (id): $archiveId")
    } else {
        val archiveHash = NameLookup.nameHash(archiveArg)
        println("  Archive (name): \"$archiveArg\"")
        println("  Archive name hash: $archiveHash (0x${Integer.toHexString(archiveHash)})")
        archiveId = NameLookup.archiveId(cache, index, archiveArg)
        if (archiveId == -1) {
            println("  Resolved archive id: not found")
            println("\n=== LOOKUP COMPLETE ===")
            return
        }
    }
    println("  Resolved archive id: $archiveId")

    // Resolve file id (by id or name).
    val numericFile = fileArg.toIntOrNull()
    val fileId: Int
    if (numericFile != null) {
        fileId = numericFile
        println("  File (id): $fileId")
    } else {
        val fileHash = NameLookup.nameHash(fileArg)
        println("  File (name): \"$fileArg\"")
        println("  File name hash: $fileHash (0x${Integer.toHexString(fileHash)})")
        fileId = NameLookup.fileId(cache, index, archiveId, fileArg)
        if (fileId == -1) {
            println("  Resolved file id: not found")
            println("\n=== LOOKUP COMPLETE ===")
            return
        }
    }
    println("  Resolved file id: $fileId")

    val data = NameLookup.file(cache, index, archiveId, fileId)
    if (data == null) {
        println("  Data: not found (null)")
    } else {
        println("  Data size: ${data.size} bytes")
    }
    println("\n=== LOOKUP COMPLETE ===")
}

/**
 * Scan every cache index and print a table of its name-hash support, followed by
 * summary lines listing which indices carry archive names and which carry file names.
 */
private fun runNameScan(cache: Cache) {
    println("\n=== INDEX NAME-HASH SCAN ===\n")

    val report = NameLookup.scanNames(cache)

    // Aligned table. Columns: idx, name, nameFlag, archiveNames(count), fileNames(count).
    println("  %-4s %-22s %-9s %-15s %-15s".format("idx", "name", "nameFlag", "archiveNames", "fileNames"))
    println("  %-4s %-22s %-9s %-15s %-15s".format("---", "----", "--------", "------------", "---------"))
    for (entry in report) {
        val archiveCol = "${entry.hasArchiveNames} (${entry.archiveNameCount})"
        val fileCol = "${entry.hasFileNames} (${entry.fileNameCount})"
        println(
            "  %-4d %-22s %-9s %-15s %-15s".format(
                entry.index,
                indexLabel(entry.index),
                entry.hasNameFlag.toString(),
                archiveCol,
                fileCol
            )
        )
    }

    val archiveIndices = report.filter { it.hasArchiveNames }.map { it.index }
    val fileIndices = report.filter { it.hasFileNames }.map { it.index }
    println("\n  Indices with archive names: $archiveIndices")
    println("  Indices with file names:    $fileIndices")
    println("\n=== SCAN COMPLETE ===")
}

/**
 * Human-readable label for a cache [index] id, derived from the [Index] constants.
 * Falls back to "?" for ids that have no named constant.
 */
private fun indexLabel(index: Int): String = when (index) {
    Index.ANIMATION_FRAMES -> "animation_frames"
    Index.ANIMATION_SKELETONS -> "animation_skeletons"
    Index.CONFIGS -> "configs"
    Index.INTERFACES -> "interfaces"
    Index.SYNTH_SOUNDS -> "synth_sounds"
    Index.MAPS -> "maps"
    Index.MIDI_SONGS -> "midi_songs"
    Index.MODELS -> "models"
    Index.GRAPHICS -> "graphics"
    Index.TEXTURES -> "textures"
    Index.HUFFMAN -> "huffman"
    Index.MIDI_JINGLES -> "midi_jingles"
    Index.CLIENT_SCRIPTS -> "client_scripts"
    Index.FONT_METRICS -> "font_metrics"
    Index.VORBIS -> "vorbis"
    Index.MIDI_INSTRUMENTS -> "midi_instruments"
    Index.OBJECTS -> "objects"
    Index.ENUMS -> "enums"
    Index.NPCS -> "npcs"
    Index.ITEMS -> "items"
    Index.ANIMATIONS -> "animations"
    Index.SPOTANIMS -> "spotanims"
    Index.VAR_BIT -> "var_bit"
    Index.WORLD_MAP -> "world_map"
    Index.QUICK_CHAT_MESSAGES -> "quick_chat_messages"
    Index.QUICK_CHAT_MENUS -> "quick_chat_menus"
    Index.TEXTURE_DEFINITIONS -> "texture_definitions"
    Index.PARTICLES -> "particles"
    Index.DEFAULTS -> "defaults"
    Index.BILLBOARDS -> "billboards"
    Index.NATIVE_LIBRARIES -> "native_libraries"
    Index.SHADERS -> "shaders"
    Index.LOADING_GRAPHICS -> "loading_graphics"
    Index.GAME_TIPS -> "game_tips"
    Index.LOADING_GRAPHICS_RAW -> "loading_graphics_raw"
    Index.CUTSCENES -> "cutscenes"
    Index.INDEX_36 -> "index36"
    else -> "?"
}

private fun interfaceStartup(args: List<String>): InterfaceStartup {
    var source: File? = null
    var packedOnly = false
    val vars = ArrayList<String>()
    val stats = ArrayList<String>()
    val runs = ArrayList<String>()
    var index = 0
    while (index < args.size) {
        val flag = args[index]
        val value = args.getOrNull(index + 1)
        when (flag) {
            "--source" -> {
                if (value == null) error("--source needs a cache source tree, or 'none'")
                if (value == "none") packedOnly = true else source = File(value)
                index++
            }
            "--var" -> {
                vars.add(value ?: error("--var needs <name>=<value>"))
                index++
            }
            "--stat" -> {
                stats.add(value ?: error("--stat needs <stat>=<level> or all=<level>"))
                index++
            }
            "--run" -> {
                runs.add(value ?: error("--run needs a clientscript name or id"))
                index++
            }
            "--capture" -> {
                System.setProperty("darkan.capture", value ?: error("--capture needs an output PNG path"))
                index++
            }
            "--settle" -> {
                System.setProperty("darkan.captureAfter", value ?: error("--settle needs seconds"))
                index++
            }
        }
        index++
    }
    return InterfaceStartup(source, packedOnly, vars, stats, runs)
}

private fun printHelp() {
    println("""
        Darkan Tools - Game Asset Viewer

        Usage: ./gradlew :tools:run --args="<mode> [options]"

        Modes:
          model [id]  - View 3D models from the cache (default)
                        Optional: specify a model ID to load on startup
          map        - View terrain/map regions with full scene rendering
          interface  - View UI interfaces and components. Live source mode reads
                        components and clientscripts straight from a cache source
                        tree, so an edit shows up without packing:
                          interface <id> [--source <tree>|none] [--var <name>=<value>]
                                         [--stat <id|all>=<level>] [--run <script>]
                                         [--capture <out.png>] [--settle <seconds>]
                        A cache-source tree beside the working directory is used
                        automatically; --source none reads only the packed cache.
          faces [id] - Print face geometry diagnostic for a model
                        Shows untextured face positions, normals, and
                        nearby textured faces for coplanarity analysis
                        Default model: 68750 (signpost)
          modelscan  - Decode every model archive and report the ids that yield
                        no geometry, grouped by reason. Separates data gaps
                        (empty placeholders in the cache) from decoder bugs.
          roofscan   - List the roof-shaped objects in a region, grouped by id
                        and plane, with each cluster's local tile extent and
                        its delayShading (cross-object normal merge) flag.
                        Usage: roofscan <regionX> <regionY>  (default 54 54)
          texdump    - Generate a material's procedural texture and write it out
                        as PNG for visual inspection. Writes <out>.png (colour)
                        and <out>_alpha.png (alpha channel as greyscale).
                        Usage: texdump <materialId> [outputPath]
          lookup     - Look up archives/files by named hash (or id), and
                        compute named hashes. Sub-commands:
                          lookup hash <name>
                          lookup scan
                          lookup <index> <archiveNameOrId>
                          lookup <index> <archiveNameOrId> <fileNameOrId>
                        Numeric args are ids; non-numeric args resolve via
                        the named hash. Reports resolved ids, hashes, and
                        data sizes. "not found" / null are handled gracefully.
                        'scan' (alias 'indices') reports, per index, whether
                        it carries archive/file name hashes.
          gameval    - Dump the whole cache as one JSONL file per type. One
                        JSON object per line, ascending id, arrays always
                        inline.
                        Also writes manifest.json (per-type counts) and
                        index-name-hashes.json.
                        Usage: gameval dump <outDir>
          cache      - The cache source tree (docs/cache-source.md): the whole
                        packed cache unpacked into one file per asset, and the
                        packed cache built back out of it. Sub-commands:
                          cache unpack <tree> [packedDir]
                          cache pack <tree> <outDir>
                          cache build <tree> <outDir>
                          cache verify <tree> <packedDir>
                          cache status <tree>
                        Only 'unpack' reads a packed cache; the rest work from
                        the tree. 'verify' exits 1 on any difference.

        Controls (Model Viewer):
          WASD        - Move camera
          Space/Shift - Move up/down
          Right Click - Rotate camera
          Scroll      - Zoom
          R           - Toggle auto-rotate
          < >         - Previous/next model
          F1          - Toggle debug panel
          F2          - Toggle asset browser
          F3          - Toggle wireframe
          ESC         - Release mouse

        Controls (Map Viewer):
          WASD        - Move camera
          Space/Shift - Move up/down
          Right Click - Look around
          Scroll      - Zoom
          Ctrl        - Fast movement
          Arrow Keys  - Navigate regions
          PgUp/PgDn   - Change plane level
          1-4         - Select plane directly
          M           - Toggle multi/single region mode
          T           - Toggle terrain
          F1          - Toggle debug panel
          F2          - Toggle navigation panel
          F3          - Toggle wireframe

        Examples:
          ./gradlew :tools:run
          ./gradlew :tools:run --args="model"
          ./gradlew :tools:run --args="model 12345"
          ./gradlew :tools:run --args="map"
          ./gradlew :tools:run --args="interface"
          ./gradlew :tools:run --args="interface 499 --source cache-source --var varbit_skill_guide_skill_v2=11 --run cs2_23"
          ./gradlew :tools:run --args="lookup hash water"
          ./gradlew :tools:run --args="lookup scan"
          ./gradlew :tools:run --args="lookup 9 725"
          ./gradlew :tools:run --args="lookup 7 mymodel"
          ./gradlew :tools:run --args="lookup 12 myscript myfile"
          ./gradlew :tools:run --args="cache unpack cache-source"
          ./gradlew :tools:run --args="cache verify cache-source ../cache"
    """.trimIndent())
}

/**
 * List the roof-shaped objects in a region, grouped by object id and plane, with the local
 * tile extent of each cluster. Used to aim the capture camera at a roof field.
 */
private fun runRoofScan(cache: Cache, regionX: Int, regionY: Int) {
    if (!MapXteas.isLoaded()) MapXteas.load()
    val data = RegionLoader.loadRegion(cache, regionX, regionY)
    if (data == null) {
        println("Region $regionX,$regionY failed to load")
        return
    }
    val roofs = data.objects.filter { it.shape in 12..21 }
    println("Region $regionX,$regionY: ${data.objects.size} objects, ${roofs.size} roof-shaped")
    roofs.groupBy { it.id to it.level }.entries
        .sortedByDescending { it.value.size }
        .take(8)
        .forEach { (key, list) ->
            val (id, level) = key
            val xs = list.map { it.x - regionX * 64 }
            val ys = list.map { it.y - regionY * 64 }
            val def = ObjectRenderer.getRawDefinition(id)
            println(
                "  id=$id level=$level count=${list.size} " +
                    "localX=${xs.min()}..${xs.max()} localY=${ys.min()}..${ys.max()} " +
                    "delayShading=${def?.delayShading} shapes=${list.map { it.shape }.distinct().sorted()}"
            )
        }
}
