package org.darkan.core.model

enum class UseFlag(val flag: Int) {
    GROUND_ITEM(0x1),
    NPC(0x2),
    WORLD_OBJECT(0x4),
    PLAYER(0x8),
    SELF(0x10),
    ICOMPONENT(0x20),
    WORLD_TILE(0x40);

    companion object {
        @JvmStatic
        fun getUseOptionFlags(settings: Int): Int = (settings shr 11) and 0x7f
    }
}

class IFEvents {
    var interfaceId: Int = 0
        private set
    var componentId: Int = 0
        private set
    var fromSlot: Int = 0
        private set
    var toSlot: Int = 0
        private set
    var settings: Int = 0
        private set

    constructor(interfaceId: Int, componentId: Int, fromSlot: Int, toSlot: Int, settings: Int) {
        this.interfaceId = interfaceId
        this.componentId = componentId
        this.fromSlot = fromSlot
        this.toSlot = toSlot
        this.settings = settings
    }

    constructor(settings: Int, interfaceId: Int) {
        this.settings = settings
        this.interfaceId = interfaceId
    }

    constructor(interfaceId: Int, componentId: Int, fromSlot: Int, toSlot: Int) : this(interfaceId, componentId, fromSlot, toSlot, 0)

    constructor(component: Int, fromSlot: Int, toSlot: Int) : this(component shr 16, component and 0xFFFF, fromSlot, toSlot, 0)

    // Right-click options (bits 1..10)
    fun clickOptionEnabled(i: Int): Boolean = ((settings shr (1 + i)) and 0x1) != 0

    fun enableRightClickOption(id: Int): IFEvents {
        require(id in 0..9) { "Right-click option id must be 0..9" }
        settings = settings or (1 shl (id + 1))
        return this
    }

    fun enableRightClickOptions(vararg ids: Int): IFEvents {
        for (id in ids) enableRightClickOption(id)
        return this
    }

    // Use-options (bits 11..17)
    fun useOptionEnabled(flag: UseFlag): Boolean = ((settings shr 11) and flag.flag) != 0

    fun enableUseOption(flag: UseFlag): IFEvents {
        settings = settings or (flag.flag shl 11)
        return this
    }

    fun enableUseOptions(vararg flags: UseFlag): IFEvents {
        for (flag in flags) enableUseOption(flag)
        return this
    }

    fun getUseOptionFlags(): Int = (settings shr 11) and 0x7f

    // Drag (bit 21)
    fun dragEnabled(): Boolean = ((settings shr 21) and 0x1) != 0

    fun enableDrag(): IFEvents {
        settings = settings or (1 shl 21)
        return this
    }

    // Continue button (bit 0)
    fun continueOptionEnabled(): Boolean = (settings and 0x1) != 0

    fun enableContinueButton(): IFEvents {
        settings = settings or 1
        return this
    }

    // Depth (bits 18..20)
    fun getDepth(): Int = (settings shr 18) and 0x7

    fun setDepth(depth: Int): IFEvents {
        settings = (settings and (0x7 shl 18).inv()) or ((depth and 0x7) shl 18)
        return this
    }

    // Depth-flag ignoring (bit 23)
    fun ignoresDepthFlags(): Boolean = ((settings shr 23) and 0x1) != 0

    fun enableDepthFlagIgnoring(): IFEvents {
        settings = settings or (1 shl 23)
        return this
    }

    // Use-targetability (bit 22)
    fun isTargetableByUse(): Boolean = ((settings shr 22) and 0x1) != 0

    fun enableUseTargetability(): IFEvents {
        settings = settings or (1 shl 22)
        return this
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IFEvents) return false
        return settings == other.settings
    }

    override fun hashCode(): Int = settings
}
