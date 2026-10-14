#version 330 core

in vec3 vColor;
in vec2 vTexCoord;
in vec3 vNormal;
in vec3 vFragPos;
in vec4 vAtlasTransform;

in float vViewDist;

out vec4 FragColor;

uniform sampler2D atlasTexture;

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
    // Emulate GL_REPEAT tiling within the atlas sub-region.
    // The original terrain UVs can exceed [0,1] for tiled textures;
    // fract() maps them back into a single texture period.
    //
    // IMPORTANT: Compute UV derivatives BEFORE fract() to avoid mip level discontinuity.
    // When fract() wraps from ~1.0 back to ~0.0, dFdx/dFdy see a massive UV jump,
    // causing the sampler to select a very coarse mip level that bleeds into adjacent
    // atlas textures. Using textureGrad() with pre-fract derivatives fixes this.
    vec2 atlasScale = vAtlasTransform.zw;
    vec2 dx = dFdx(vTexCoord) * atlasScale;
    vec2 dy = dFdy(vTexCoord) * atlasScale;

    vec2 tiledUV = fract(vTexCoord);
    vec2 atlasUV = vAtlasTransform.xy + tiledUV * atlasScale;
    vec4 texColor = textureGrad(atlasTexture, atlasUV, dx, dy);

    // Vertex colours carry the RS palette with the ground's flat base light
    // strength already folded in; the sun is applied here so it stays editable.
    vec3 baseColor = applySunLight(texColor.rgb * vColor, normalize(vNormal));
    float alpha = texColor.a;

    // Discard fully transparent fragments
    if (alpha < 0.01) {
        discard;
    }

    FragColor = vec4(applyFog(baseColor, vViewDist), alpha);
}
