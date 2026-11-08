// SPDX-License-Identifier: Apache-2.0
precision mediump float;
varying vec2 v_texcoord0;
uniform sampler2D sampler0;
uniform float uStrength;
void main() {
    vec4 c = texture2D(sampler0, v_texcoord0);
    vec2 d = (v_texcoord0 - 0.5) * 2.0;
    float rim = clamp(dot(d, d) * 0.25, 0.0, 1.0);
    gl_FragColor = vec4(c.rgb * (1.0 - uStrength * rim), c.a);
}
