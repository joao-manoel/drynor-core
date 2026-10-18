package org.drynor.tools.ui

import imgui.ImGui
import imgui.flag.ImGuiChildFlags
import imgui.flag.ImGuiInputTextFlags
import imgui.flag.ImGuiWindowFlags
import imgui.type.ImInt
import imgui.type.ImString
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.ItemDefinition
import world.gregs.voidps.cache.definition.data.NPCDefinition
import world.gregs.voidps.cache.definition.data.ObjectDefinition
import world.gregs.voidps.cache.definition.decoder.ItemDecoder
import world.gregs.voidps.cache.definition.decoder.NPCDecoder
import world.gregs.voidps.cache.definition.decoder.ObjectDecoder

/**
 * Asset browser panel for searching and selecting models, items, NPCs, and objects.
 */
class AssetBrowser(private val cache: Cache) {
    var visible = true

    private val searchQuery = ImString(256)
    private val modelIdInput = ImInt(0)
    private val regionXInput = ImInt(50)
    private val regionYInput = ImInt(50)

    private var selectedTab = 0
    private val tabs = listOf("Models", "Items", "NPCs", "Objects", "Regions")

    private var itemDefs: Array<ItemDefinition>? = null
    private var npcDefs: Array<NPCDefinition>? = null
    private var objectDefs: Array<ObjectDefinition>? = null

    private var filteredItems: List<Pair<Int, String>> = emptyList()
    private var filteredNpcs: List<Pair<Int, String>> = emptyList()
    private var filteredObjects: List<Pair<Int, String>> = emptyList()

    private var onModelSelected: ((Int) -> Unit)? = null
    private var onItemSelected: ((Int, ItemDefinition) -> Unit)? = null
    private var onNpcSelected: ((Int, NPCDefinition) -> Unit)? = null
    private var onObjectSelected: ((Int, ObjectDefinition) -> Unit)? = null
    private var onRegionSelected: ((Int, Int) -> Unit)? = null

    private var modelCount = 0
    private var lastSearchQuery = ""

    fun init() {
        modelCount = cache.lastArchiveId(Index.MODELS) + 1
    }

    fun setOnModelSelected(callback: (Int) -> Unit) {
        onModelSelected = callback
    }

    fun setOnItemSelected(callback: (Int, ItemDefinition) -> Unit) {
        onItemSelected = callback
    }

    fun setOnNpcSelected(callback: (Int, NPCDefinition) -> Unit) {
        onNpcSelected = callback
    }

    fun setOnObjectSelected(callback: (Int, ObjectDefinition) -> Unit) {
        onObjectSelected = callback
    }

    fun setOnRegionSelected(callback: (Int, Int) -> Unit) {
        onRegionSelected = callback
    }

    fun render() {
        if (!visible) return

        ImGui.setNextWindowPos(ImGui.getIO().displaySizeX - 320f, 10f)
        ImGui.setNextWindowSize(310f, 500f)

        val flags = ImGuiWindowFlags.NoMove or ImGuiWindowFlags.NoResize

        if (ImGui.begin("Asset Browser", flags)) {
            // Tab bar
            if (ImGui.beginTabBar("AssetTabs")) {
                for ((index, tab) in tabs.withIndex()) {
                    if (ImGui.beginTabItem(tab)) {
                        selectedTab = index
                        ImGui.endTabItem()
                    }
                }
                ImGui.endTabBar()
            }

            ImGui.separator()

            when (selectedTab) {
                0 -> renderModelTab()
                1 -> renderItemTab()
                2 -> renderNpcTab()
                3 -> renderObjectTab()
                4 -> renderRegionTab()
            }
        }
        ImGui.end()
    }

    private fun renderModelTab() {
        ImGui.text("Model ID (0 - ${modelCount - 1}):")
        ImGui.setNextItemWidth(150f)
        if (ImGui.inputInt("##modelId", modelIdInput)) {
            modelIdInput.set(modelIdInput.get().coerceIn(0, modelCount - 1))
        }
        ImGui.sameLine()
        if (ImGui.button("Load##model")) {
            onModelSelected?.invoke(modelIdInput.get())
        }

        ImGui.separator()
        ImGui.text("Quick Select:")
        val quickModels = listOf(
            0 to "Model 0",
            1 to "Model 1",
            10 to "Model 10",
            100 to "Model 100",
            1000 to "Model 1000"
        )
        for ((id, name) in quickModels) {
            if (id < modelCount && ImGui.selectable(name)) {
                modelIdInput.set(id)
                onModelSelected?.invoke(id)
            }
        }
    }

    private fun renderItemTab() {
        if (itemDefs == null) {
            if (ImGui.button("Load Item Definitions")) {
                itemDefs = ItemDecoder().load(cache)
                updateFilteredItems()
            }
            return
        }

        ImGui.setNextItemWidth(-1f)
        if (ImGui.inputText("Search##items", searchQuery, ImGuiInputTextFlags.None)) {
            updateFilteredItems()
        }

        ImGui.separator()
        ImGui.text("${filteredItems.size} items found")

        ImGui.beginChild("ItemList", 0f, 350f, ImGuiChildFlags.Borders)
        for ((id, name) in filteredItems.take(100)) {
            if (ImGui.selectable("$id: $name##item$id")) {
                itemDefs?.get(id)?.let { onItemSelected?.invoke(id, it) }
            }
        }
        if (filteredItems.size > 100) {
            ImGui.text("... and ${filteredItems.size - 100} more")
        }
        ImGui.endChild()
    }

    private fun renderNpcTab() {
        if (npcDefs == null) {
            if (ImGui.button("Load NPC Definitions")) {
                npcDefs = NPCDecoder(true).load(cache)
                updateFilteredNpcs()
            }
            return
        }

        ImGui.setNextItemWidth(-1f)
        if (ImGui.inputText("Search##npcs", searchQuery, ImGuiInputTextFlags.None)) {
            updateFilteredNpcs()
        }

        ImGui.separator()
        ImGui.text("${filteredNpcs.size} NPCs found")

        ImGui.beginChild("NpcList", 0f, 350f, ImGuiChildFlags.Borders)
        for ((id, name) in filteredNpcs.take(100)) {
            if (ImGui.selectable("$id: $name##npc$id")) {
                npcDefs?.get(id)?.let { onNpcSelected?.invoke(id, it) }
            }
        }
        if (filteredNpcs.size > 100) {
            ImGui.text("... and ${filteredNpcs.size - 100} more")
        }
        ImGui.endChild()
    }

    private fun renderObjectTab() {
        if (objectDefs == null) {
            if (ImGui.button("Load Object Definitions")) {
                objectDefs = ObjectDecoder(true).load(cache)
                updateFilteredObjects()
            }
            return
        }

        ImGui.setNextItemWidth(-1f)
        if (ImGui.inputText("Search##objects", searchQuery, ImGuiInputTextFlags.None)) {
            updateFilteredObjects()
        }

        ImGui.separator()
        ImGui.text("${filteredObjects.size} objects found")

        ImGui.beginChild("ObjectList", 0f, 350f, ImGuiChildFlags.Borders)
        for ((id, name) in filteredObjects.take(100)) {
            if (ImGui.selectable("$id: $name##obj$id")) {
                objectDefs?.get(id)?.let { onObjectSelected?.invoke(id, it) }
            }
        }
        if (filteredObjects.size > 100) {
            ImGui.text("... and ${filteredObjects.size - 100} more")
        }
        ImGui.endChild()
    }

    private fun renderRegionTab() {
        ImGui.text("Region Coordinates:")
        ImGui.setNextItemWidth(100f)
        ImGui.inputInt("X##regionX", regionXInput)
        ImGui.sameLine()
        ImGui.setNextItemWidth(100f)
        ImGui.inputInt("Y##regionY", regionYInput)

        regionXInput.set(regionXInput.get().coerceIn(0, 255))
        regionYInput.set(regionYInput.get().coerceIn(0, 255))

        if (ImGui.button("Load Region")) {
            onRegionSelected?.invoke(regionXInput.get(), regionYInput.get())
        }

        ImGui.separator()
        ImGui.text("Common Regions:")
        val commonRegions = listOf(
            Triple(50, 50, "Lumbridge"),
            Triple(48, 48, "Varrock"),
            Triple(46, 50, "Falador"),
            Triple(44, 52, "Ardougne"),
            Triple(55, 52, "Edgeville")
        )
        for ((x, y, name) in commonRegions) {
            if (ImGui.selectable("$name ($x, $y)")) {
                regionXInput.set(x)
                regionYInput.set(y)
                onRegionSelected?.invoke(x, y)
            }
        }
    }

    private fun updateFilteredItems() {
        val query = searchQuery.get().lowercase()
        filteredItems = itemDefs?.mapIndexedNotNull { id, def ->
            if (def.name != "null" && (query.isEmpty() || def.name.lowercase().contains(query))) {
                id to def.name
            } else null
        } ?: emptyList()
    }

    private fun updateFilteredNpcs() {
        val query = searchQuery.get().lowercase()
        filteredNpcs = npcDefs?.mapIndexedNotNull { id, def ->
            if (def.name != "null" && (query.isEmpty() || def.name.lowercase().contains(query))) {
                id to def.name
            } else null
        } ?: emptyList()
    }

    private fun updateFilteredObjects() {
        val query = searchQuery.get().lowercase()
        filteredObjects = objectDefs?.mapIndexedNotNull { id, def ->
            if (def.name != "null" && (query.isEmpty() || def.name.lowercase().contains(query))) {
                id to def.name
            } else null
        } ?: emptyList()
    }

    fun toggleVisibility() {
        visible = !visible
    }
}
