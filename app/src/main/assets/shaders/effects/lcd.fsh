// SPDX-License-Identifier: Apache-2.0
// LCD-style grid and RGB subpixels. Original GLES2 implementation inspired by
// LCD3x (Gigaherz, public domain); no upstream code copied.
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 v_texcoord0;
uniform sampler2D sampler0;
uniform vec2 uInputSize, uContentUvMax;
uniform vec2 uDisplayUvScale, uDisplayUvOffset;
uniform vec2 uGameSize, uOutputSize;
uniform float uGridStrength, uSubpixelStrength, uLcdBrightness;
const float PI = 3.14159265359;
const float TWO_PI = 6.28318530718;

float filteredGridBorder(float guestPixel, float footprint) {
    if (footprint >= 1.0) return 0.0;
    // (0.5 + 0.5*cos(t))^2 = 3/8 + cos(t)/2 + cos(2*t)/8.
    // Integrate both harmonics over one output pixel.
    float x = PI * footprint;
    float first = x < 0.001 ? 1.0 : sin(x) / x;
    float phase = cos(TWO_PI * (guestPixel - 0.75));
    float average = 0.375 + 0.5 * first * phase
        + 0.125 * first * cos(x) * (2.0 * phase * phase - 1.0);
    float visibility = clamp(2.0 * (1.0 - footprint), 0.0, 1.0);
    return clamp(average * visibility, 0.0, 1.0);
}

void main() {
    vec2 screenUv = v_texcoord0 * uDisplayUvScale + uDisplayUvOffset;
    vec2 halfTexel = vec2(0.5) / uInputSize;
    vec2 sampleUv = clamp(v_texcoord0, halfTexel,
        max(halfTexel, uContentUvMax - halfTexel));
    vec4 source = texture2D(sampler0, sampleUv);
    vec2 logicalPixel = screenUv * uGameSize;
    vec2 border = vec2(
        filteredGridBorder(logicalPixel.x, uGameSize.x / uOutputSize.x),
        filteredGridBorder(logicalPixel.y, uGameSize.y / uOutputSize.y));
    float grid = 1.0 - uGridStrength *
        (1.0 - (1.0 - border.x) * (1.0 - border.y));
#ifdef GL_FRAGMENT_PRECISION_HIGH
    float stripe = mod(floor(logicalPixel.x * 3.0), 3.0);
#else
    float stripe = mod(floor(logicalPixel.x), 3.0);
#endif
    float maskVisibility = clamp((uOutputSize.x / uGameSize.x - 1.0) * 0.5, 0.0, 1.0);
    vec3 subpixel = stripe < 1.0 ? vec3(1.0, 0.68, 0.68) :
        (stripe < 2.0 ? vec3(0.68, 1.0, 0.68) : vec3(0.68, 0.68, 1.0));
    vec3 rgb = source.rgb * grid * mix(vec3(1.0), subpixel, uSubpixelStrength * maskVisibility)
        * uLcdBrightness;
    gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), source.a);
}
