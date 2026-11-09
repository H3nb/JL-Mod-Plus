// SPDX-License-Identifier: Apache-2.0
// Five bilinear samples implement a nine-tap Gaussian at radius 1.
// At other radii the offsets scale the blur's spatial spread.
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 v_texcoord0;
uniform sampler2D sampler0;
uniform vec2 uInputSize;
uniform float uRadius;

void main() {
    vec2 d = vec2(0.0, 1.0) * (uRadius / uInputSize.y);
    vec3 c = texture2D(sampler0, v_texcoord0).rgb * 0.2270270270;
    vec2 a = d * 1.3846153846;
    vec2 b = d * 3.2307692308;
    c += (texture2D(sampler0, v_texcoord0 - a).rgb +
          texture2D(sampler0, v_texcoord0 + a).rgb) * 0.3162162162;
    c += (texture2D(sampler0, v_texcoord0 - b).rgb +
          texture2D(sampler0, v_texcoord0 + b).rgb) * 0.0702702703;
    gl_FragColor = vec4(c, 1.0);
}
