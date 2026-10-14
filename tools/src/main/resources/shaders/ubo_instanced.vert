#version 330 core

layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec3 aColor;
layout(location = 3) in vec3 aNormal;

// Instance matrix (4 vec4 columns = mat4)
layout(location = 4) in vec4 instanceMatrix0;
layout(location = 5) in vec4 instanceMatrix1;
layout(location = 6) in vec4 instanceMatrix2;
layout(location = 7) in vec4 instanceMatrix3;

out vec3 vColor;
out vec3 vNormal;
out float vViewDist;
out vec3 vFragPos;
flat out int vMirrored;

// Camera uniform block
layout(std140) uniform CameraBlock {
    mat4 view;
    mat4 projection;
    mat4 viewProjection;
    vec3 cameraPos;
};

void main() {
    // Reconstruct the model matrix from instance data
    mat4 model = mat4(instanceMatrix0, instanceMatrix1, instanceMatrix2, instanceMatrix3);

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
