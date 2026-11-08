// SPDX-License-Identifier: Apache-2.0
precision mediump float;
varying vec2 v_texcoord0;
uniform sampler2D sampler0;
uniform vec2 uInputSize;
uniform float uLevels, uEdge;
void main() {
    vec2 d = 1.0 / uInputSize;
    vec4 c = texture2D(sampler0, v_texcoord0);
    vec3 x = texture2D(sampler0, v_texcoord0 + vec2(d.x, 0.0)).rgb -
             texture2D(sampler0, v_texcoord0 - vec2(d.x, 0.0)).rgb;
    vec3 y = texture2D(sampler0, v_texcoord0 + vec2(0.0, d.y)).rgb -
             texture2D(sampler0, v_texcoord0 - vec2(0.0, d.y)).rgb;
    float edge = clamp((length(x) + length(y)) * uEdge, 0.0, 1.0);
    vec3 stepped = floor(c.rgb * uLevels + 0.5) / uLevels;
    gl_FragColor = vec4(stepped * (1.0 - edge), c.a);
}
