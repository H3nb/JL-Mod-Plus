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

float resolvedPattern(float guestPixelsPerOutputPixel) {
    // A pixel-integrated first harmonic: do not alias a subpixel grid at 1x.
    float x = PI * guestPixelsPerOutputPixel;
    return guestPixelsPerOutputPixel >= 1.0 ? 0.0 :
        clamp(x < 0.001 ? 1.0 : sin(x) / x, 0.0, 1.0);
}

void main() {
    vec2 screenUv = v_texcoord0 * uDisplayUvScale + uDisplayUvOffset;
    vec2 halfTexel = vec2(0.5) / uInputSize;
    vec2 sampleUv = clamp(v_texcoord0, halfTexel,
        max(halfTexel, uContentUvMax - halfTexel));
    vec4 source = texture2D(sampler0, sampleUv);
    vec2 logicalPixel = screenUv * uGameSize;
    vec2 coverage = vec2(resolvedPattern(uGameSize.x / uOutputSize.x),
                         resolvedPattern(uGameSize.y / uOutputSize.y));
    // Place the dark border on the final subpixel row/column. A symmetric
    // boundary-centered cosine samples as flat gray at an integer 2x scale.
    vec2 border = 0.5 + 0.5 * cos(TWO_PI * (logicalPixel - vec2(0.75)));
    border = coverage * border * border;
    float grid = 1.0 - uGridStrength *
        (1.0 - (1.0 - border.x) * (1.0 - border.y));
    // On mediump-only GLES2, a logical-pixel triad avoids large coordinates
    // while keeping this setting functional instead of silently disabling it.
#ifdef GL_FRAGMENT_PRECISION_HIGH
    float stripe = mod(floor(screenUv.x * uOutputSize.x), 3.0);
#else
    float stripe = mod(floor(screenUv.x * uGameSize.x), 3.0);
#endif
    float maskVisibility = clamp((uOutputSize.x / uGameSize.x - 1.0) * 0.5, 0.0, 1.0);
    vec3 subpixel = stripe < 1.0 ? vec3(1.0, 0.68, 0.68) :
        (stripe < 2.0 ? vec3(0.68, 1.0, 0.68) : vec3(0.68, 0.68, 1.0));
    vec3 rgb = source.rgb * grid * mix(vec3(1.0), subpixel, uSubpixelStrength * maskVisibility)
        * uLcdBrightness;
    gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), source.a);
}
