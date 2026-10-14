#version 460 core

in vec3 vColor;
in vec2 vTexCoord;
in vec3 vNormal;
in vec3 vFragPos;
flat in int vMirrored;
flat in int vTextureLayer;
flat in int vTextureArray;

in float vViewDist;

out vec4 FragColor;

// Array textures must have every layer at one size, and RS materials come in two (64 and
// 128), so the materials cannot all live in a single array. Binding every array at once and
// choosing per instance keeps that a detail of sampling rather than a reason to split the
// draw -- which matters because the pass blends with depth writes on, so splitting it by
// material size would force every object's 64px batches to be drawn before any object's
// 128px ones and a tree's foliage would then blend over a different tree's trunk.
//
// [MAX_TEXTURE_ARRAYS] mirrors the constant of the same name in IndirectRenderer, which
// falls back to one draw per array if a scene ever needs more.
const int MAX_TEXTURE_ARRAYS = 4;
uniform sampler2DArray textureArrays[MAX_TEXTURE_ARRAYS];

// Sampler indices must be dynamically uniform, so the array index is resolved by an explicit
// chain of constant lookups rather than by indexing the sampler array with vTextureArray.
vec4 sampleMaterial(vec3 uvw) {
    if (vTextureArray <= 0) return texture(textureArrays[0], uvw);
    if (vTextureArray == 1) return texture(textureArrays[1], uvw);
    if (vTextureArray == 2) return texture(textureArrays[2], uvw);
    return texture(textureArrays[3], uvw);
}

// Environment uniform block (binding 1). Layout mirrors EnvironmentSettings.writeStd140.
//   sunDirection.xyz : unit vector pointing at the sun, in engine space
//   sunColour.rgb    : sun colour, 0..1
//   sunTerms         : x ambient (0.7 * sunAmbient), y sunLight, z sunBacklight,
//                      w 1 when the mesh colour is unlit and the shader must light it
//   fogColour        : rgb fog colour, w 1 when fog is enabled
//   fogRange         : x start, y end, z 1 / (end - start)
layout(std140) uniform EnvironmentBlock {
    vec4 sunDirection;
    vec4 sunColour;
    vec4 sunTerms;
    vec4 fogColour;
    vec4 fogRange;
};

// Ambient plus two directional lights, matching the reference client: the back
// light carries a negative diffuse colour, so it darkens surfaces facing away
// from the sun instead of lighting them. See docs/tools-environment-lighting.md.
vec3 applySunLight(vec3 base, vec3 normal) {
    if (sunTerms.w < 0.5) {
        return base;
    }
    float ndl = dot(normal, sunDirection.xyz);
    vec3 lit = sunTerms.x * sunColour.rgb
             + max(ndl, 0.0) * sunTerms.y * sunColour.rgb
             - max(-ndl, 0.0) * sunTerms.z * sunColour.rgb;
    return base * max(lit, vec3(0.0));
}

// Linear distance fog: factor = (end - distance) / (end - start), clamped.
vec3 applyFog(vec3 colour, float viewDistance) {
    if (fogColour.w < 0.5) {
        return colour;
    }
    float factor = clamp((fogRange.y - viewDistance) * fogRange.z, 0.0, 1.0);
    return mix(fogColour.rgb, colour, factor);
}

void main() {
    vec4 texColor = sampleMaterial(vec3(vTexCoord, float(vTextureLayer)));
    float alpha = texColor.a;
    if (alpha < 0.01) discard;
    // Multiplicative blend: texture color modulated by vertex color
    // Matches RS client: color = texColor * vColor
    vec3 baseColor = texColor.rgb * vColor;

    // Flip normal for back faces (two-sided lighting).
    //
    // gl_FrontFacing is trusted as-is, including for mirrored instances -- they are drawn
    // from a winding-reversed copy of the geometry, so their triangles already wind like
    // everything else. See indirect_color.frag for the full note.
    vec3 norm = normalize(vNormal);
    if (!gl_FrontFacing) {
        norm = -norm;
    }

    // Model face colours already carry the definition's ambient lightness blend
    // (the client's blendColorLightness with 64 + ObjectDefinition.ambient), which is
    // a material property. The sun on top of it comes from the environment block.
    vec3 result = applySunLight(baseColor, norm);
    FragColor = vec4(applyFog(result, vViewDist), alpha);
}
