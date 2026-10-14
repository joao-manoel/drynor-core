package world.gregs.voidps.cache.definition.data

/**
 * One technique of a [ShaderProgramDefinition]: the two source files it compiles and the
 * preprocessor defines it compiles them with.
 *
 * [vertexSource] and [fragmentSource] are file names in the same index 31 archive, in the case the
 * definition spells them; the reference table hashes their lower case form. The defines are
 * prepended to the source as `#define <name> <value>` lines, which is how one GLSL source serves
 * eighteen Direct3D permutations.
 */
class ShaderProgramMapping(
    var name: String?,
    var vertexSource: String?,
    var fragmentSource: String?,
    var vertexDefines: List<ShaderProgramDefine>,
    var fragmentDefines: List<ShaderProgramDefine>
)
