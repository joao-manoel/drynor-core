#version 330 core

layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec3 aColor;
layout(location = 2) in vec2 aTexCoord;
layout(location = 3) in vec3 aNormal;

// Instance matrix (4 vec4 columns = mat4)
layout(location = 4) in vec4 instanceMatrix0;
layout(location = 5) in vec4 instanceMatrix1;
layout(location = 6) in vec4 instanceMatrix2;
layout(location = 7) in vec4 instanceMatrix3;

out vec3 vColor;
out vec2 vTexCoord;
out vec3 vNormal;
out vec3 vFragPos;

uniform mat4 view;
uniform mat4 projection;

// Atlas UV transform: (u0, v0, uScale, vScale)
uniform vec4 atlasTransform;

void main() {
    // Reconstruct the model matrix from instance data
    mat4 model = mat4(instanceMatrix0, instanceMatrix1, instanceMatrix2, instanceMatrix3);

    vFragPos = vec3(model * vec4(aPosition, 1.0));
    vNormal = mat3(transpose(inverse(model))) * aNormal;
    vColor = aColor;

    // Transform local UVs to atlas UVs
    vTexCoord = atlasTransform.xy + aTexCoord * atlasTransform.zw;

    gl_Position = projection * view * vec4(vFragPos, 1.0);
}
