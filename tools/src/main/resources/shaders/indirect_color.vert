#version 460 core

layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec3 aColor;
layout(location = 3) in vec3 aNormal;

out vec3 vColor;
out vec3 vNormal;
out float vViewDist;
out vec3 vFragPos;
flat out int vMirrored;

// Camera uniform block (matches existing CameraBlock at binding 0)
layout(std140) uniform CameraBlock {
    mat4 view;
    mat4 projection;
    mat4 viewProjection;
    vec3 cameraPos;
};

// Instance data SSBO. The layout is shared with indirect_textured.vert so both shaders can
// read one instance buffer, which in turn lets the culling pass cover the whole scene in a
// single pair of compute dispatches. The texture layer is simply unused here.
struct InstanceInfo {
    mat4 model;
    int textureLayer;
    int textureArray;
    int _pad1;
    int _pad2;
};

layout(std430, binding = 1) buffer InstanceData {
    InstanceInfo instances[];
};

// Indirection written by the GPU culling pass: the slot reserved for this draw holds the
// index of the instance that actually survived. Without culling it is the identity map,
// so this path is the same fetch either way and both render exactly the same pixels.
layout(std430, binding = 2) readonly buffer VisibleData {
    uint visibleItems[];
};

void main() {
    // Fetch the model matrix from SSBO using gl_BaseInstance + gl_InstanceID
    mat4 model = instances[visibleItems[gl_BaseInstance + gl_InstanceID]].model;

    vFragPos = vec3(model * vec4(aPosition, 1.0));
    mat3 normalMatrix = mat3(model);
    vNormal = normalMatrix * aNormal;
    vColor = aColor;
    vViewDist = distance(vFragPos, cameraPos);
    gl_Position = viewProjection * vec4(vFragPos, 1.0);

    // Detect mirroring by checking if the determinant of the 3x3 rotation/scale matrix is negative
    // A negative determinant indicates a reflection (mirroring) transform
    float det = determinant(normalMatrix);
    vMirrored = (det < 0.0) ? 1 : 0;
}
