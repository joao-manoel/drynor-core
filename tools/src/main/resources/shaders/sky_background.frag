#version 330 core

// Panoramic skybox background.
//
// Reproduces the reference client's screen-space scroll (graphics/light/Skybox.kt):
//   yOffset = rotationY * height / -4096
//   xOffset = (rotationX + skyboxAngle) * height /  4096 + (width - height) / 2
// with the square material graphic drawn at edge length == viewport height and
// tiled from those offsets. Here that becomes one texture fetch per pixel.

out vec4 fragColor;

uniform vec2 skyViewport;    // viewport size in pixels
uniform vec2 skyOffset;      // xOffset / yOffset in pixels, already wrapped
uniform int  skyMode;        // 0 = solid fill, 1 = repeating, 2 = colour transition
uniform vec3 skyColor;       // fog / background colour
uniform vec3 skyTopColor;    // material's first pixel (transition mode)
uniform vec3 skyBottomColor; // material's last pixel (transition mode)
uniform float skyAlpha;
uniform sampler2D skySampler;

void main() {
    if (skyMode == 0) {
        fragColor = vec4(skyColor, skyAlpha);
        return;
    }

    // The client works in top-down window pixels; gl_FragCoord is bottom-up.
    vec2 pixel = vec2(gl_FragCoord.x, skyViewport.y - gl_FragCoord.y);
    vec2 uv = (pixel - skyOffset) / skyViewport.y;

    if (skyMode == 1) {
        fragColor = vec4(texture(skySampler, uv).rgb, skyAlpha);
        return;
    }

    vec3 color;
    if (uv.y < 0.0) {
        color = skyTopColor;
    } else if (uv.y > 1.0) {
        color = skyBottomColor;
    } else {
        color = texture(skySampler, uv).rgb;
    }
    fragColor = vec4(color, skyAlpha);
}
