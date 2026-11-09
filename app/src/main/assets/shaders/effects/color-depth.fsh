// SPDX-License-Identifier: Apache-2.0
// Ordered dither in guest-pixel coordinates; cosmetic only (does not change MIDlet color APIs).
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 v_texcoord0;
uniform sampler2D sampler0;
uniform vec2 uInputSize, uContentUvMax;
uniform vec2 uDisplayUvScale, uDisplayUvOffset, uGameSize;
uniform float uColorMode, uDitherMode, uDitherStrength;

float bayer2(vec2 pixel) {
    vec2 bit = mod(pixel, 2.0);
    if (bit.y < 1.0) return bit.x < 1.0 ? 0.0 : 2.0;
    return bit.x < 1.0 ? 3.0 : 1.0;
}

float ditherOffset(vec2 pixel) {
    if (uDitherMode < 0.5) return 0.0;
    vec2 cell = floor(pixel);
    float rank = 4.0 * bayer2(cell) + bayer2(floor(cell * 0.5));
    float count = 16.0;
    if (uDitherMode > 1.5) {
        rank = 16.0 * bayer2(cell)
             + 4.0 * bayer2(floor(cell * 0.5))
             + bayer2(floor(cell * 0.25));
        count = 64.0;
    }
    // Center the threshold in one quantization step, avoiding systematic bias.
    return ((rank + 0.5) / count - 0.5) * uDitherStrength;
}

void main() {
    vec2 halfTexel = vec2(0.5) / uInputSize;
    vec2 sampleUv = clamp(v_texcoord0, halfTexel,
        max(halfTexel, uContentUvMax - halfTexel));
    vec4 source = texture2D(sampler0, sampleUv);
    vec2 displayUv = v_texcoord0 * uDisplayUvScale + uDisplayUvOffset;
    vec3 maxCode = uColorMode < 0.5 ? vec3(15.0) :
                   (uColorMode < 1.5 ? vec3(31.0, 63.0, 31.0) : vec3(63.0));
    float offset = ditherOffset(displayUv * uGameSize);
    vec3 color = floor(source.rgb * maxCode + vec3(0.5 + offset)) / maxCode;
    gl_FragColor = vec4(clamp(color, 0.0, 1.0), source.a);
}
