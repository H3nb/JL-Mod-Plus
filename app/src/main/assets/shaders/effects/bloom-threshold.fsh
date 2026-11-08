// SPDX-License-Identifier: Apache-2.0
precision mediump float;
varying vec2 v_texcoord0;
uniform sampler2D sampler0;
uniform float uThreshold;
void main() {
    vec3 c = texture2D(sampler0, v_texcoord0).rgb;
    float strength = smoothstep(uThreshold, 1.0, max(c.r, max(c.g, c.b)));
    gl_FragColor = vec4(c * strength, 1.0);
}
