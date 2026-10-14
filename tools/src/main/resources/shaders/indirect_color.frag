#version 460 core

in vec3 vColor;
in vec3 vNormal;
in vec3 vFragPos;
flat in int vMirrored;

in float vViewDist;

out vec4 FragColor;

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
    // Flip normal for back faces (two-sided lighting).
    //
    // gl_FrontFacing is trusted as-is, including for mirrored instances. A mirrored
    // instance is drawn from a winding-reversed copy of the geometry (see
    // RegionManager.registerMirroredVariants and StaticBatcher.reverseWinding), so by the
    // time it reaches the rasteriser its triangles wind the same way as everything else
    // and gl_FrontFacing already means what it says. Inverting it again on vMirrored --
    // which this shader used to do, from before those variants existed -- negated the
    // normal of every visible mirrored surface, so the backlight term in applySunLight hit
    // the surfaces the sun was actually on and shaded them to near-black. On Lumbridge's
    // roofs that read as square holes punched through the thatch.
    vec3 norm = normalize(vNormal);
    if (!gl_FrontFacing) {
        norm = -norm;
    }

    // Model face colours already carry the definition's ambient lightness blend
    // (the client's blendColorLightness with 64 + ObjectDefinition.ambient), which is
    // a material property. The sun on top of it comes from the environment block.
    vec3 result = applySunLight(vColor, norm);
    FragColor = vec4(applyFog(result, vViewDist), 1.0);
}
