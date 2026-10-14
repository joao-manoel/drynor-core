package world.gregs.voidps.cache.definition

/**
 * A record a definition holds a list of, written as a nested object in a source tree.
 *
 * A definition field whose type is a record class needs a spelling in
 * [world.gregs.voidps.cache.source.codec.DefinitionJson], and rather than the mapper keeping a list of
 * the classes it knows, a record says so itself by implementing this. Nothing but the mapping depends on
 * it: it declares no members and changes nothing about the class.
 *
 * The one behaviour it buys is that a null field is **left out** of the object rather than written as
 * `null`. That is what makes a one-of-many record - a cutscene action, a loading screen element - readable
 * as JSON: the type is a field and only the fields that type uses appear, so an action reads as
 * `{"type": 30, "lengthInCycles": 6, "musicId": 62, "volume": 255}` rather than as thirty nulls. The
 * record classes that predate this ([world.gregs.voidps.cache.definition.data.InterfaceComponentSetting]
 * and friends) do not implement it and keep writing their nulls, so no tree that already exists changes.
 */
interface DefinitionRecord
