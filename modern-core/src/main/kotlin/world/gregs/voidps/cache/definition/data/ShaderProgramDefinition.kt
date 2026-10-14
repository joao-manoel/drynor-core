package world.gregs.voidps.cache.definition.data

/**
 * A shader **program definition**: one file of index 31 that names a program, the uniforms it
 * binds and the source files it is built from.
 *
 * It is the archive's own manifest. The client asks for a program by name
 * (`fetchShader("gl", "model")`), reads this, and then asks the same archive for each
 * [ShaderProgramMapping]'s vertex and fragment source by name - which is why index 31's file names
 * can be recovered from the archives themselves and are not a formula: the definitions spell them
 * out, and the reference table hashes the lower case form of exactly those strings.
 *
 * Every scalar in the file is a signed 16 bit little endian int and every string is one of those
 * as a length followed by that many bytes, with -1 for null. `docs/cache-source.md` has the layout.
 */
class ShaderProgramDefinition(
    /** The file's first field, always 4; the client throws when it is not, so it is kept. */
    var header: Int = HEADER,
    /** The program's name, in the case the file spells it - `Model`, `FilterBloom`. */
    var name: String? = null,
    /** The uniforms the client always binds. */
    var variables: List<ShaderProgramVariable> = emptyList(),
    /** The uniforms it binds only when a mapping uses them; the client's comment calls them unused. */
    var optionalVariables: List<ShaderProgramVariable> = emptyList(),
    /** The techniques - one per permutation of the program, each with its own two sources. */
    var mappings: List<ShaderProgramMapping> = emptyList()
) {

    companion object {
        /** The header length the client demands. */
        const val HEADER = 4
    }
}
