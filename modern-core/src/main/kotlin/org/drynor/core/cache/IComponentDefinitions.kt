package org.drynor.core.cache

import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.definition.data.InterfaceComponentDefinition
import world.gregs.voidps.cache.definition.data.InterfaceDefinition

object IComponentDefinitions {
    @JvmStatic
    fun getInterfaceComponent(interfaceId: Int, componentId: Int): InterfaceComponentDefinition? {
        val iface = Cache.interfaces.getOrNull(interfaceId) ?: return null
        return iface.components?.getOrNull(componentId)
    }

    @JvmStatic
    fun getInterface(interfaceId: Int): Array<InterfaceComponentDefinition>? {
        val iface = Cache.interfaces.getOrNull(interfaceId) ?: return null
        return iface.components
    }
}

enum class ComponentType(val id: Int) {
    LAYER(0),
    UNKNOWN1(1),
    UNKNOWN2(2),
    RECTANGLE(3),
    TEXT(4),
    GRAPHIC(5),
    MODEL(6),
    UNKNOWN7(7),
    LINE(9);

    companion object {
        private val map = entries.associateBy { it.id }
        @JvmStatic
        fun forId(id: Int) = map[id]
    }
}
