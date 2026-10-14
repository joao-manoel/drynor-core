package world.gregs.voidps.cache.definition.data

/**
 * One uniform of a [ShaderProgramDefinition], and where each technique binds it.
 *
 * [elementType] and [arraySize] are in the file only for an array ([ARRAY]), so they are null for
 * every other type and the encoder writes them exactly when the type says to. [program] and [slot]
 * are per technique and parallel to [ShaderProgramDefinition.mappings]: the Direct3D constant
 * register and sampler slot, `-1` where the technique does not bind the uniform. The OpenGL
 * renderer ignores both and looks the uniform up by name, but they are the file's and are kept.
 */
class ShaderProgramVariable(
    /** The type's index in the client's `ShaderVariableType` table - 24 float, 27 vec4, 43 mat4. */
    var type: Int,
    var elementType: Int?,
    var arraySize: Int?,
    var name: String?,
    /** A second name for the same uniform. Empty in every 727 file, and written all the same. */
    var alias: String?,
    var program: IntArray,
    var slot: IntArray
) {

    companion object {
        /** The type whose element type and size follow it. */
        const val ARRAY = 2
    }
}
