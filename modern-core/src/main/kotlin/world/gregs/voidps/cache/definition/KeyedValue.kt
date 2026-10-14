package world.gregs.voidps.cache.definition

/**
 * One key/value record of a parameter list (opcode 249) or of an enum's entry list, in the order the file
 * carried it.
 *
 * The decoders build a map from those records, which drops a key the file listed twice - 60 objs, 62 npcs,
 * 37 structs and 12 enums in the 727 cache do exactly that. The records are kept in file order alongside
 * the map so the file can be written back with the same number of records.
 */
data class KeyedValue(val key: Int, val value: Any)
