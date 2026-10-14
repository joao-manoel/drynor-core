#version 330 core

layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec3 aColor;  // RGB vertex color
layout(location = 3) in vec3 aNormal;

out vec3 vColor;
out vec3 vNormal;
out float vViewDist;
out vec3 vFragPos;

uniform mat4 model;

// Camera uniform block
layout(std140) uniform CameraBlock {
    mat4 view;
    mat4 projection;
    mat4 viewProjection;
    vec3 cameraPos;
};

void main() {
    vec4 worldPos = model * vec4(aPosition, 1.0);
    vFragPos = worldPos.xyz;
    vNormal = aNormal;  // No transpose(inverse()) needed - model is translation only
    vColor = aColor;
    vViewDist = distance(worldPos.xyz, cameraPos);
    gl_Position = viewProjection * worldPos;
}
