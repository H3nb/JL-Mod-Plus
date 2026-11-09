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
uniform vec2 uDisplayUvScale, uDisplayUvOffset;
uniform vec2 uGameSize, uOutputSize;
uniform float uGridStrength, uSubpixelStrength, uLcdBrightness;
const float TWO_PI = 6.28318530718;

void main() {
    vec2 screenUv = v_texcoord0 * uDisplayUvScale + uDisplayUvOffset;
    vec4 source = texture2D(sampler0, v_texcoord0);
    vec2 logicalPixel = screenUv * uGameSize;
    // Darker gaps near logical pixel borders; stays anchored to the game's
    // original resolution even after an earlier full-viewport effect.
    float gapX = 0.5 + 0.5 * cos(TWO_PI * logicalPixel.x);
    float gapY = 0.5 + 0.5 * cos(TWO_PI * logicalPixel.y);
    float grid = 1.0 - uGridStrength * (1.0 - (1.0 - gapX) * (1.0 - gapY));
    vec3 subpixel = vec3(1.0);
#ifdef GL_FRAGMENT_PRECISION_HIGH
    // At large displays mediump cannot address individual physical RGB stripes.
    float stripe = mod(floor(screenUv.x * uOutputSize.x), 3.0);
    subpixel = stripe < 1.0 ? vec3(1.0, 0.68, 0.68) :
        (stripe < 2.0 ? vec3(0.68, 1.0, 0.68) : vec3(0.68, 0.68, 1.0));
#endif
    vec3 rgb = source.rgb * grid * mix(vec3(1.0), subpixel, uSubpixelStrength)
        * uLcdBrightness;
    gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), source.a);
}
