#version 330 core

// Not loaded by any code path. Every ShaderProgram in the tool is built from an explicit
// "/shaders/<name>" literal (Engine.kt, MapViewer.kt, InterfaceViewer.kt, SkyRenderer.kt)
// and none of them names this file, so nothing here reaches a GPU today.
//
// It keeps the vMirrored front-face inversion that was deleted from indirect_*.frag and
// ubo_*.frag. That deletion does not apply here. Those shaders draw mirrored instances
// from geometry whose winding was already reversed on the CPU
// (RegionManager.registerMirroredVariants -> StaticBatcher.reverseWinding), so
// gl_FrontFacing was already correct and inverting it again negated the normal on every
// visible mirrored surface. This file pairs with a vertex stage that derives vMirrored
// from determinant(mat3(model)) < 0 - a reflection in the model matrix, which really does
// invert raster winding - so for that input the compensation is the correct behaviour.

in vec3 vColor;
in vec3 vNormal;
in vec3 vFragPos;
flat in int vMirrored;

out vec4 FragColor;

uniform vec3 ambientLight;
uniform vec3 lightDirection;
uniform vec3 lightColor;

void main() {
    // Flip normal for back faces (two-sided lighting)
    // For mirrored objects, the winding order is reversed, so gl_FrontFacing is inverted
    // We need to account for this to get correct lighting
    vec3 norm = normalize(vNormal);
    bool isFrontFace = gl_FrontFacing;
    if (vMirrored == 1) {
        // For mirrored objects, the notion of front/back is reversed
        isFrontFace = !isFrontFace;
    }
    if (!isFrontFace) {
        norm = -norm;
    }

    // Ambient
    vec3 ambient = ambientLight * vColor;

    // Diffuse
    vec3 lightDir = normalize(-lightDirection);
    float diff = max(dot(norm, lightDir), 0.0);
    vec3 diffuse = diff * lightColor * vColor;

    vec3 result = ambient + diffuse;
    FragColor = vec4(result, 1.0);
}
