#version 330 core

in vec3 vColor;
in vec3 vNormal;
in vec3 vFragPos;
flat in int vMirrored;

out vec4 FragColor;

uniform vec3 ambientLight;
uniform vec3 lightDirection;
uniform vec3 lightColor;

void main() {
    // Flip normal for back faces (two-sided lighting).
    //
    // The vMirrored branch below is the opposite case to the one deleted from
    // indirect_*.frag and ubo_*.frag, which is why it stays here. Those shaders draw
    // mirrored instances from geometry whose winding was already reversed on the CPU
    // (RegionManager.registerMirroredVariants -> StaticBatcher.reverseWinding), so
    // gl_FrontFacing is correct as it stands and inverting it again negated the normal
    // on every visible mirrored surface. Here vMirrored means something different: the
    // vertex stage sets it from determinant(mat3(model)) < 0, a reflection baked into the
    // model matrix, which really does invert raster winding - so compensating is correct.
    //
    // In practice the branch is unreachable. Only ModelViewer and InterfaceViewer's
    // InterfaceModelRenderer load this program, and both build `model` from translate,
    // rotate and positive scale only, so the determinant is never negative and vMirrored
    // is always 0. Leave it in place for whoever draws a reflected model matrix next.
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
