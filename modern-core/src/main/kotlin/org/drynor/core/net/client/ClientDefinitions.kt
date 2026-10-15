package org.drynor.core.net.client

import org.drynor.core.cache.ItemDefinitions
import org.drynor.core.cache.NPCDefinitions
import org.drynor.core.cache.ObjectDefinitions
import org.drynor.core.model.ObjectShape
import org.drynor.core.net.prot.LocCustomisationSizes
import org.drynor.core.net.prot.LocCustomisationSizing
import org.drynor.core.net.prot.update.AppearanceLayout
import org.drynor.core.net.prot.update.EquipmentDefaults
import org.drynor.core.net.prot.update.NpcCustomisationLayout
import org.drynor.core.net.prot.update.NpcCustomisationShape
import org.drynor.core.net.prot.update.WornModelLayout
import world.gregs.voidps.cache.Cache

object ClientDefinitions {
    val locCustomisation = LocCustomisationSizing { objectId, shape ->
        val loc = ObjectDefinitions.getDefs(objectId)
        val modelShape = if (shape == ObjectShape.GROUND_INTERACT.id) ObjectShape.SCENERY_INTERACT.id else shape
        val models = loc.modelTypes?.indexOfFirst { it.toInt() == modelShape }?.takeIf { it >= 0 }?.let { loc.modelIds?.getOrNull(it)?.size } ?: 0
        LocCustomisationSizes(models, loc.modifiedColours?.size ?: 0, loc.modifiedTextureColours?.size ?: 0)
    }

    val npcCustomisation = NpcCustomisationLayout { npcId ->
        val npc = NPCDefinitions.getDefs(npcId)
        NpcCustomisationShape(
            models = npc.modelIds?.size ?: 0,
            headModels = npc.dialogueModels?.size ?: 0,
            recolours = npc.modifiedColours?.size ?: 0,
            retextures = npc.modifiedTextureColours?.size ?: 0,
        )
    }

    fun appearanceLayout(cache: Cache = Cache.get()): AppearanceLayout {
        EquipmentDefaults.load(cache)
        return AppearanceLayout(EquipmentDefaults.customizableObjSlots) { itemId ->
            val item = ItemDefinitions.getDefs(itemId)
            WornModelLayout(
                secondaryBody = item.secondaryMaleModel != -1 || item.secondaryFemaleModel != -1,
                tertiaryBody = item.tertiaryMaleModel != -1 || item.tertiaryFemaleModel != -1,
                secondaryHead = item.secondaryMaleDialogueHead != -1 || item.secondaryFemaleDialogueHead != -1,
            )
        }
    }
}
