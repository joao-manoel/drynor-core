#version 330 core

layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec3 aColor;
layout(location = 3) in vec3 aNormal;

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

uniform mat4 model;

void main() {
    vFragPos = vec3(model * vec4(aPosition, 1.0));
    vNormal = mat3(transpose(inverse(model))) * aNormal;
    vColor = aColor;
    vViewDist = distance(vFragPos, cameraPos);
    gl_Position = viewProjection * vec4(vFragPos, 1.0);

    // Detect mirroring by checking if the determinant of the 3x3 rotation/scale matrix is negative
    // A negative determinant indicates a reflection (mirroring) transform
    mat3 normalMatrix = mat3(model);
    float det = determinant(normalMatrix);
    vMirrored = (det < 0.0) ? 1 : 0;
}
