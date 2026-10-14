#version 330 core

in vec3 vColor;
in vec2 vTexCoord;
in vec3 vNormal;
in vec3 vFragPos;

in float vViewDist;

out vec4 FragColor;

uniform sampler2D textureSampler;

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
    vec4 texColor = texture(textureSampler, vTexCoord);

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
