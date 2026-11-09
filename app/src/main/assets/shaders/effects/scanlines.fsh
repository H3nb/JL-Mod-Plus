// SPDX-License-Identifier: Apache-2.0
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 v_texcoord0;
uniform sampler2D sampler0;
uniform vec2 uDisplayUvScale, uDisplayUvOffset, uGameSize, uOutputSize;
uniform float uLineStrength, uLineWidth;
const float PI = 3.14159265359;
const float TWO_PI = 6.28318530718;

void main() {
    vec4 source = texture2D(sampler0, v_texcoord0);
    vec2 uv = v_texcoord0 * uDisplayUvScale + uDisplayUvOffset;
    float guestPixelsPerOutputPixel = uGameSize.y / uOutputSize.y;
    float x = PI * guestPixelsPerOutputPixel;
    float coverage = guestPixelsPerOutputPixel >= 1.0 ? 0.0 :
        clamp(x < 0.001 ? 1.0 : sin(x) / x, 0.0, 1.0);
    // Asymmetric phase makes a distinct dark sub-row at 2x; footprint
    // attenuation suppresses lines that cannot be resolved at 1x or below.
    float scan = 0.5 + 0.5 * cos(TWO_PI * (uv.y * uGameSize.y - 0.75));
    float darkness = uLineStrength * coverage * pow(scan, uLineWidth);
    gl_FragColor = vec4(source.rgb * (1.0 - darkness), source.a);
}
