package world.gregs.voidps.cache.definition.data

import world.gregs.voidps.cache.Definition
import world.gregs.voidps.cache.definition.Extra

data class InterfaceComponentDefinition(
    override var id: Int = -1,
    var type: Int = 0,
    var unknown: String? = null,
    var contentType: Int = 0,
    var basePositionX: Int = 0,
    var basePositionY: Int = 0,
    var baseWidth: Int = 0,
    var baseHeight: Int = 0,
    var horizontalSizeMode: Byte = 0,
    var verticalSizeMode: Byte = 0,
    var horizontalPositionMode: Byte = 0,
    var verticalPositionMode: Byte = 0,
    var parent: Int = -1,
    var hidden: Boolean = false,
    var disableHover: Boolean = false,
    var scrollWidth: Int = 0,
    var scrollHeight: Int = 0,
    var colour: Int = 0,
    var filled: Boolean = false,
    var alpha: Int = 0,
    var fontId: Int = -1,
    var monochrome: Boolean = true,
    var text: String = "",
    var lineHeight: Int = 0,
    var horizontalTextAlign: Int = 0,
    var verticalTextAlign: Int = 0,
    var shaded: Boolean = false,
    var lineCount: Int = 0,
    var defaultImage: Int = -1,
    var imageRotation: Int = 0,
    var aBoolean4861: Boolean = false,
    var imageRepeat: Boolean = false,
    var rotation: Int = 0,
    var backgroundColour: Int = 0,
    var flipVertical: Boolean = false,
    var flipHorizontal: Boolean = false,
    var aBoolean4782: Boolean = true,
    var defaultMediaType: Int = 1,
    var defaultMediaId: Int = 0,
    var animated: Boolean = false,
    var centreType: Boolean = false,
    var ignoreZBuffer: Boolean = false,
    var viewportX: Int = 0,
    var viewportY: Int = 0,
    var viewportZ: Int = 0,
    var graphicPitch: Int = 0,
    var graphicRoll: Int = 0,
    var graphicYaw: Int = 0,
    var graphicScale: Int = 100,
    var animation: Int = -1,
    var viewportWidth: Int = 0,
    var viewportHeight: Int = 0,
    var lineWidth: Int = 1,
    var lineMirrored: Boolean = false,
    var keyRepeats: ByteArray? = null,
    var keyCodes: ByteArray? = null,
    var keyModifiers: IntArray? = null,
    var clickable: Boolean = false,
    var name: String = "",
    var options: Array<String>? = null,
    var mouseIcon: IntArray? = null,
    var optionOverride: String? = null,
    var anInt4708: Int = 0,// Drag type
    var anInt4795: Int = 0,// Drag slider?
    var anInt4860: Int = 0,// Friends list icons/buttons?
    var useOption: String = "",
    var anInt4698: Int = -1,
    var anInt4839: Int = -1,// Unused
    var anInt4761: Int = -1,// Unused
    var setting: InterfaceComponentSetting = InterfaceComponentSetting(0, -1),
    val clientParams: HashMap<Long, Any>? = null,
    var onLoadScript: InterfaceComponentHook? = null,
    var onMouseOver: InterfaceComponentHook? = null,
    var onMouseLeaveScript: InterfaceComponentHook? = null,
    var hookParams: InterfaceComponentHook? = null,
    var onTargetEnter: InterfaceComponentHook? = null,
    var onVarpTransmit: InterfaceComponentHook? = null,
    var mouseLeaveScript: InterfaceComponentHook? = null,
    var onStatTransmit: InterfaceComponentHook? = null,
    var onTimer: InterfaceComponentHook? = null,
    var params: InterfaceComponentHook? = null,
    var onTargetLeave: InterfaceComponentHook? = null,// Component formats 0 and above only
    var popupScript: InterfaceComponentHook? = null,
    var onClick: InterfaceComponentHook? = null,
    var onClickRepeat: InterfaceComponentHook? = null,
    var onRelease: InterfaceComponentHook? = null,
    var onHold: InterfaceComponentHook? = null,
    var onDrag: InterfaceComponentHook? = null,
    var onDragComplete: InterfaceComponentHook? = null,
    var onMouseMove: InterfaceComponentHook? = null,
    var onKey: InterfaceComponentHook? = null,
    var onScrollWheel: InterfaceComponentHook? = null,
    var varps: IntArray? = null,// onVarpTransmit filter
    var mouseLeaveArrayParams: IntArray? = null,// mouseLeaveScript filter
    var statTransmitFilter: IntArray? = null,// onStatTransmit filter
    var keyPressArray: IntArray? = null,// onKey filter
    var mouseWheelArray: IntArray? = null,// onScrollWheel filter
    var hasScript: Boolean = false,
    override var stringId: String = "",
    override var extras: Map<String, Any>? = null
) : Definition, Extra {

    /**
     * Every event hook set on this component, keyed by the name of the field it was decoded into.
     */
    fun hooks(): Map<String, InterfaceComponentHook> {
        val hooks = LinkedHashMap<String, InterfaceComponentHook>()
        onLoadScript?.let { hooks["onLoadScript"] = it }
        onMouseOver?.let { hooks["onMouseOver"] = it }
        onMouseLeaveScript?.let { hooks["onMouseLeaveScript"] = it }
        hookParams?.let { hooks["hookParams"] = it }
        onTargetEnter?.let { hooks["onTargetEnter"] = it }
        onVarpTransmit?.let { hooks["onVarpTransmit"] = it }
        mouseLeaveScript?.let { hooks["mouseLeaveScript"] = it }
        onStatTransmit?.let { hooks["onStatTransmit"] = it }
        onTimer?.let { hooks["onTimer"] = it }
        params?.let { hooks["params"] = it }
        onTargetLeave?.let { hooks["onTargetLeave"] = it }
        popupScript?.let { hooks["popupScript"] = it }
        onClick?.let { hooks["onClick"] = it }
        onClickRepeat?.let { hooks["onClickRepeat"] = it }
        onRelease?.let { hooks["onRelease"] = it }
        onHold?.let { hooks["onHold"] = it }
        onDrag?.let { hooks["onDrag"] = it }
        onDragComplete?.let { hooks["onDragComplete"] = it }
        onMouseMove?.let { hooks["onMouseMove"] = it }
        onKey?.let { hooks["onKey"] = it }
        onScrollWheel?.let { hooks["onScrollWheel"] = it }
        return hooks
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as InterfaceComponentDefinition

        if (id != other.id) return false
        if (type != other.type) return false
        if (unknown != other.unknown) return false
        if (contentType != other.contentType) return false
        if (basePositionX != other.basePositionX) return false
        if (basePositionY != other.basePositionY) return false
        if (baseWidth != other.baseWidth) return false
        if (baseHeight != other.baseHeight) return false
        if (horizontalSizeMode != other.horizontalSizeMode) return false
        if (verticalSizeMode != other.verticalSizeMode) return false
        if (horizontalPositionMode != other.horizontalPositionMode) return false
        if (verticalPositionMode != other.verticalPositionMode) return false
        if (parent != other.parent) return false
        if (hidden != other.hidden) return false
        if (disableHover != other.disableHover) return false
        if (scrollWidth != other.scrollWidth) return false
        if (scrollHeight != other.scrollHeight) return false
        if (colour != other.colour) return false
        if (filled != other.filled) return false
        if (alpha != other.alpha) return false
        if (fontId != other.fontId) return false
        if (monochrome != other.monochrome) return false
        if (text != other.text) return false
        if (lineHeight != other.lineHeight) return false
        if (horizontalTextAlign != other.horizontalTextAlign) return false
        if (verticalTextAlign != other.verticalTextAlign) return false
        if (shaded != other.shaded) return false
        if (lineCount != other.lineCount) return false
        if (defaultImage != other.defaultImage) return false
        if (imageRotation != other.imageRotation) return false
        if (aBoolean4861 != other.aBoolean4861) return false
        if (imageRepeat != other.imageRepeat) return false
        if (rotation != other.rotation) return false
        if (backgroundColour != other.backgroundColour) return false
        if (flipVertical != other.flipVertical) return false
        if (flipHorizontal != other.flipHorizontal) return false
        if (aBoolean4782 != other.aBoolean4782) return false
        if (defaultMediaType != other.defaultMediaType) return false
        if (defaultMediaId != other.defaultMediaId) return false
        if (animated != other.animated) return false
        if (centreType != other.centreType) return false
        if (ignoreZBuffer != other.ignoreZBuffer) return false
        if (viewportX != other.viewportX) return false
        if (viewportY != other.viewportY) return false
        if (viewportZ != other.viewportZ) return false
        if (graphicPitch != other.graphicPitch) return false
        if (graphicRoll != other.graphicRoll) return false
        if (graphicYaw != other.graphicYaw) return false
        if (graphicScale != other.graphicScale) return false
        if (animation != other.animation) return false
        if (viewportWidth != other.viewportWidth) return false
        if (viewportHeight != other.viewportHeight) return false
        if (lineWidth != other.lineWidth) return false
        if (lineMirrored != other.lineMirrored) return false
        if (keyRepeats != null) {
            if (other.keyRepeats == null) return false
            if (!keyRepeats.contentEquals(other.keyRepeats)) return false
        } else if (other.keyRepeats != null) return false
        if (keyCodes != null) {
            if (other.keyCodes == null) return false
            if (!keyCodes.contentEquals(other.keyCodes)) return false
        } else if (other.keyCodes != null) return false
        if (keyModifiers != null) {
            if (other.keyModifiers == null) return false
            if (!keyModifiers.contentEquals(other.keyModifiers)) return false
        } else if (other.keyModifiers != null) return false
        if (clickable != other.clickable) return false
        if (name != other.name) return false
        if (options != null) {
            if (other.options == null) return false
            if (!options.contentEquals(other.options)) return false
        } else if (other.options != null) return false
        if (mouseIcon != null) {
            if (other.mouseIcon == null) return false
            if (!mouseIcon.contentEquals(other.mouseIcon)) return false
        } else if (other.mouseIcon != null) return false
        if (optionOverride != other.optionOverride) return false
        if (anInt4708 != other.anInt4708) return false
        if (anInt4795 != other.anInt4795) return false
        if (anInt4860 != other.anInt4860) return false
        if (useOption != other.useOption) return false
        if (anInt4698 != other.anInt4698) return false
        if (anInt4839 != other.anInt4839) return false
        if (anInt4761 != other.anInt4761) return false
        if (setting != other.setting) return false
        if (clientParams != other.clientParams) return false
        if (onLoadScript != other.onLoadScript) return false
        if (onMouseOver != other.onMouseOver) return false
        if (onMouseLeaveScript != other.onMouseLeaveScript) return false
        if (hookParams != other.hookParams) return false
        if (onTargetEnter != other.onTargetEnter) return false
        if (onVarpTransmit != other.onVarpTransmit) return false
        if (mouseLeaveScript != other.mouseLeaveScript) return false
        if (onStatTransmit != other.onStatTransmit) return false
        if (onTimer != other.onTimer) return false
        if (params != other.params) return false
        if (onTargetLeave != other.onTargetLeave) return false
        if (popupScript != other.popupScript) return false
        if (onClick != other.onClick) return false
        if (onClickRepeat != other.onClickRepeat) return false
        if (onRelease != other.onRelease) return false
        if (onHold != other.onHold) return false
        if (onDrag != other.onDrag) return false
        if (onDragComplete != other.onDragComplete) return false
        if (onMouseMove != other.onMouseMove) return false
        if (onKey != other.onKey) return false
        if (onScrollWheel != other.onScrollWheel) return false
        if (varps != null) {
            if (other.varps == null) return false
            if (!varps.contentEquals(other.varps)) return false
        } else if (other.varps != null) return false
        if (mouseLeaveArrayParams != null) {
            if (other.mouseLeaveArrayParams == null) return false
            if (!mouseLeaveArrayParams.contentEquals(other.mouseLeaveArrayParams)) return false
        } else if (other.mouseLeaveArrayParams != null) return false
        if (statTransmitFilter != null) {
            if (other.statTransmitFilter == null) return false
            if (!statTransmitFilter.contentEquals(other.statTransmitFilter)) return false
        } else if (other.statTransmitFilter != null) return false
        if (keyPressArray != null) {
            if (other.keyPressArray == null) return false
            if (!keyPressArray.contentEquals(other.keyPressArray)) return false
        } else if (other.keyPressArray != null) return false
        if (mouseWheelArray != null) {
            if (other.mouseWheelArray == null) return false
            if (!mouseWheelArray.contentEquals(other.mouseWheelArray)) return false
        } else if (other.mouseWheelArray != null) return false
        if (hasScript != other.hasScript) return false
        if (stringId != other.stringId) return false
        if (extras != other.extras) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + type
        result = 31 * result + (unknown?.hashCode() ?: 0)
        result = 31 * result + contentType
        result = 31 * result + basePositionX
        result = 31 * result + basePositionY
        result = 31 * result + baseWidth
        result = 31 * result + baseHeight
        result = 31 * result + horizontalSizeMode
        result = 31 * result + verticalSizeMode
        result = 31 * result + horizontalPositionMode
        result = 31 * result + verticalPositionMode
        result = 31 * result + parent
        result = 31 * result + hidden.hashCode()
        result = 31 * result + disableHover.hashCode()
        result = 31 * result + scrollWidth
        result = 31 * result + scrollHeight
        result = 31 * result + colour
        result = 31 * result + filled.hashCode()
        result = 31 * result + alpha
        result = 31 * result + fontId
        result = 31 * result + monochrome.hashCode()
        result = 31 * result + text.hashCode()
        result = 31 * result + lineHeight
        result = 31 * result + horizontalTextAlign
        result = 31 * result + verticalTextAlign
        result = 31 * result + shaded.hashCode()
        result = 31 * result + lineCount
        result = 31 * result + defaultImage
        result = 31 * result + imageRotation
        result = 31 * result + aBoolean4861.hashCode()
        result = 31 * result + imageRepeat.hashCode()
        result = 31 * result + rotation
        result = 31 * result + backgroundColour
        result = 31 * result + flipVertical.hashCode()
        result = 31 * result + flipHorizontal.hashCode()
        result = 31 * result + aBoolean4782.hashCode()
        result = 31 * result + defaultMediaType
        result = 31 * result + defaultMediaId
        result = 31 * result + animated.hashCode()
        result = 31 * result + centreType.hashCode()
        result = 31 * result + ignoreZBuffer.hashCode()
        result = 31 * result + viewportX
        result = 31 * result + viewportY
        result = 31 * result + viewportZ
        result = 31 * result + graphicPitch
        result = 31 * result + graphicRoll
        result = 31 * result + graphicYaw
        result = 31 * result + graphicScale
        result = 31 * result + animation
        result = 31 * result + viewportWidth
        result = 31 * result + viewportHeight
        result = 31 * result + lineWidth
        result = 31 * result + lineMirrored.hashCode()
        result = 31 * result + (keyRepeats?.contentHashCode() ?: 0)
        result = 31 * result + (keyCodes?.contentHashCode() ?: 0)
        result = 31 * result + (keyModifiers?.contentHashCode() ?: 0)
        result = 31 * result + clickable.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + (options?.contentHashCode() ?: 0)
        result = 31 * result + (mouseIcon?.contentHashCode() ?: 0)
        result = 31 * result + (optionOverride?.hashCode() ?: 0)
        result = 31 * result + anInt4708
        result = 31 * result + anInt4795
        result = 31 * result + anInt4860
        result = 31 * result + useOption.hashCode()
        result = 31 * result + anInt4698
        result = 31 * result + anInt4839
        result = 31 * result + anInt4761
        result = 31 * result + setting.hashCode()
        result = 31 * result + (clientParams?.hashCode() ?: 0)
        result = 31 * result + (onLoadScript?.hashCode() ?: 0)
        result = 31 * result + (onMouseOver?.hashCode() ?: 0)
        result = 31 * result + (onMouseLeaveScript?.hashCode() ?: 0)
        result = 31 * result + (hookParams?.hashCode() ?: 0)
        result = 31 * result + (onTargetEnter?.hashCode() ?: 0)
        result = 31 * result + (onVarpTransmit?.hashCode() ?: 0)
        result = 31 * result + (mouseLeaveScript?.hashCode() ?: 0)
        result = 31 * result + (onStatTransmit?.hashCode() ?: 0)
        result = 31 * result + (onTimer?.hashCode() ?: 0)
        result = 31 * result + (params?.hashCode() ?: 0)
        result = 31 * result + (onTargetLeave?.hashCode() ?: 0)
        result = 31 * result + (popupScript?.hashCode() ?: 0)
        result = 31 * result + (onClick?.hashCode() ?: 0)
        result = 31 * result + (onClickRepeat?.hashCode() ?: 0)
        result = 31 * result + (onRelease?.hashCode() ?: 0)
        result = 31 * result + (onHold?.hashCode() ?: 0)
        result = 31 * result + (onDrag?.hashCode() ?: 0)
        result = 31 * result + (onDragComplete?.hashCode() ?: 0)
        result = 31 * result + (onMouseMove?.hashCode() ?: 0)
        result = 31 * result + (onKey?.hashCode() ?: 0)
        result = 31 * result + (onScrollWheel?.hashCode() ?: 0)
        result = 31 * result + (varps?.contentHashCode() ?: 0)
        result = 31 * result + (mouseLeaveArrayParams?.contentHashCode() ?: 0)
        result = 31 * result + (statTransmitFilter?.contentHashCode() ?: 0)
        result = 31 * result + (keyPressArray?.contentHashCode() ?: 0)
        result = 31 * result + (mouseWheelArray?.contentHashCode() ?: 0)
        result = 31 * result + hasScript.hashCode()
        result = 31 * result + stringId.hashCode()
        result = 31 * result + extras.hashCode()
        return result
    }
}