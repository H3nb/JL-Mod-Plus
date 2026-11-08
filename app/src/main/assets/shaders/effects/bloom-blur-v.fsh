// SPDX-License-Identifier: Apache-2.0
precision mediump float;
varying vec2 v_texcoord0;
uniform sampler2D sampler0;
uniform vec2 uInputSize;
uniform float uRadius;
void main() {
    vec2 d = vec2(0.0, uRadius / uInputSize.y);
    vec3 c = texture2D(sampler0, v_texcoord0).rgb * 0.227027;
    c += (texture2D(sampler0, v_texcoord0 - d).rgb +
          texture2D(sampler0, v_texcoord0 + d).rgb) * 0.316216;
    c += (texture2D(sampler0, v_texcoord0 - 2.0*d).rgb +
          texture2D(sampler0, v_texcoord0 + 2.0*d).rgb) * 0.070270;
    gl_FragColor = vec4(c, 1.0);
}
