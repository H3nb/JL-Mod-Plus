// SPDX-License-Identifier: Apache-2.0
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 v_texcoord0;
uniform sampler2D sampler0;
uniform vec2 uInputSize, uContentUvMax;
uniform vec2 uDisplayUvScale, uDisplayUvOffset, uGameSize, uOutputSize;
uniform float uLineStrength, uLineWidth;
const float TWO_PI = 6.28318530718;

float lineCoverage(float guestRow) {
    float stripe = 0.5 + 0.5 * cos(TWO_PI * (guestRow - 0.75));
    return pow(stripe, uLineWidth);
}

void main() {
    vec2 halfTexel = vec2(0.5) / uInputSize;
    vec2 safeUv = clamp(v_texcoord0, halfTexel,
        max(halfTexel, uContentUvMax - halfTexel));
    vec4 source = texture2D(sampler0, safeUv);
    vec2 uv = v_texcoord0 * uDisplayUvScale + uDisplayUvOffset;
    float pixelFootprint = uGameSize.y / uOutputSize.y;
    // Three-point Gauss-Legendre sampling reduces aliasing from narrow lines
    // without any additional texture reads.
    float visibility = clamp(2.0 * (1.0 - pixelFootprint), 0.0, 1.0);
    float row = uv.y * uGameSize.y;
    float offset = 0.3872983346 * pixelFootprint;
    float coverage = (5.0 / 18.0) *
        (lineCoverage(row - offset) + lineCoverage(row + offset))
        + (4.0 / 9.0) * lineCoverage(row);
    float darkness = uLineStrength * visibility * coverage;
    gl_FragColor = vec4(source.rgb * (1.0 - darkness), source.a);
}
