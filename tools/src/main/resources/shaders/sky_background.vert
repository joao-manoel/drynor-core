#version 330 core

// Attribute-less full-screen triangle. Bind an empty VAO and issue
// glDrawArrays(GL_TRIANGLES, 0, 3).
//
// z = w = 1 puts every fragment exactly on the far plane, so a GL_LEQUAL depth
// test against a depth buffer cleared to 1.0 passes only where the scene has
// not already written closer geometry.

void main() {
    vec2 corner = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    gl_Position = vec4(corner * 2.0 - 1.0, 1.0, 1.0);
}
