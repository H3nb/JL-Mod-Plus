// SPDX-License-Identifier: Apache-2.0
precision mediump float;
varying vec2 v_texcoord0;
uniform sampler2D sampler0;
uniform float uThreshold;

void main() {
    vec3 rgb = texture2D(sampler0, v_texcoord0).rgb;
    float threshold = clamp(uThreshold, 0.0, 0.98);
    float intensity = smoothstep(threshold, 1.0,
        max(rgb.r, max(rgb.g, rgb.b)));
    gl_FragColor = vec4(rgb * intensity, 1.0);
}
