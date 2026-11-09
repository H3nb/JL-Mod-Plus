// SPDX-License-Identifier: Apache-2.0
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 v_texcoord0;
uniform sampler2D sampler0;
uniform vec2 uDisplayUvScale, uDisplayUvOffset, uGameSize;
uniform float uLineStrength, uLineWidth;
const float TWO_PI = 6.28318530718;

void main() {
    vec4 source = texture2D(sampler0, v_texcoord0);
    vec2 uv = v_texcoord0 * uDisplayUvScale + uDisplayUvOffset;
    float border = 0.5 + 0.5 * cos(TWO_PI * uv.y * uGameSize.y);
    float darkness = uLineStrength * pow(border, uLineWidth);
    gl_FragColor = vec4(source.rgb * (1.0 - darkness), source.a);
}
