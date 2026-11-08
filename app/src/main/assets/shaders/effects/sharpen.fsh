// SPDX-License-Identifier: Apache-2.0
precision mediump float;
varying vec2 v_texcoord0;
uniform sampler2D sampler0;
uniform vec2 uInputSize;
uniform float uSharpness;
void main() {
    vec2 d = 1.0 / uInputSize;
    vec4 c = texture2D(sampler0, v_texcoord0);
    vec3 n = texture2D(sampler0, v_texcoord0 + vec2(0.0, d.y)).rgb;
    vec3 s = texture2D(sampler0, v_texcoord0 - vec2(0.0, d.y)).rgb;
    vec3 e = texture2D(sampler0, v_texcoord0 + vec2(d.x, 0.0)).rgb;
    vec3 w = texture2D(sampler0, v_texcoord0 - vec2(d.x, 0.0)).rgb;
    gl_FragColor = vec4(clamp(c.rgb * (1.0 + 4.0 * uSharpness) -
        (n + s + e + w) * uSharpness, 0.0, 1.0), c.a);
}
