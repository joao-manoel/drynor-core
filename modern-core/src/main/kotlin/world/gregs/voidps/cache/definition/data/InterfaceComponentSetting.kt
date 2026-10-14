package world.gregs.voidps.cache.definition.data

data class InterfaceComponentSetting(var setting: Int, var anInt7413: Int) {

    companion object {
        /**
         * Client `Component.getUseOptionFlags`: bits 11-17 of the setting hash.
         *
         * When any of them is set the component carries three extra shorts (the "use" cursors) after
         * the target verb, so both the decoder and the encoder have to gate on exactly this value.
         */
        fun useOptionFlags(setting: Int) = (setting shr 11) and 0x7f
    }
}