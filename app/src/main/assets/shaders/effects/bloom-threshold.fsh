// SPDX-License-Identifier: Apache-2.0
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 v_texcoord0;
uniform sampler2D sampler0;
uniform vec2 uInputSize, uOutputSize, uContentUvMax;
uniform float uThreshold;

vec3 extractBright(vec2 uv) {
    vec2 halfTexel = 0.5 / uInputSize;
    vec2 safeUv = clamp(uv, halfTexel,
        max(halfTexel, uContentUvMax - halfTexel));
    vec3 rgb = texture2D(sampler0, safeUv).rgb;
    float intensity = smoothstep(uThreshold, 1.0,
        max(rgb.r, max(rgb.g, rgb.b)));
    return rgb * intensity;
}

void main() {
    // Extract *before* combining source samples: thresholding a bilinearly
    // downsampled texel can erase an isolated bright pixel.
    vec2 quarterFootprint = 0.25 * uContentUvMax / uOutputSize;
    vec3 bright = extractBright(v_texcoord0 + vec2(-quarterFootprint.x, -quarterFootprint.y));
    bright += extractBright(v_texcoord0 + vec2(quarterFootprint.x, -quarterFootprint.y));
    bright += extractBright(v_texcoord0 + vec2(-quarterFootprint.x, quarterFootprint.y));
    bright += extractBright(v_texcoord0 + vec2(quarterFootprint.x, quarterFootprint.y));
    gl_FragColor = vec4(bright * 0.25, 1.0);
}
