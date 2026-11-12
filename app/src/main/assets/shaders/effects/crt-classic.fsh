// SPDX-License-Identifier: Apache-2.0
// Original JL-Mod Plus CRT-inspired presentation effect, independently authored.
// This is a stylized single-pass display effect, not a hardware CRT simulation.
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 v_texcoord0;
uniform sampler2D sampler0;
uniform vec2 uInputSize, uContentUvMax;
uniform vec2 uGameSize, uOutputSize, uDisplayUvScale, uDisplayUvOffset;
uniform float SCANLINE_STRENGTH, MASK_STRENGTH, BRIGHT_BOOST, BEAM_NARROWNESS;

const float TWO_PI = 6.28318530718;

float beamShade(float row, float exponent) {
    float wave = 0.5 + 0.5 * cos(TWO_PI * (row - 0.5));
    return pow(wave, exponent);
}

void main() {
    vec2 halfTexel = vec2(0.5) / uInputSize;
    vec2 safeUv = clamp(v_texcoord0, halfTexel,
        max(halfTexel, uContentUvMax - halfTexel));
    vec4 source = texture2D(sampler0, safeUv);
    vec2 displayUv = v_texcoord0 * uDisplayUvScale + uDisplayUvOffset;
    vec2 scale = uOutputSize / uGameSize;

    float guestFootprint = uGameSize.y / uOutputSize.y;
    float visibleLine = clamp(2.0 * (1.0 - guestFootprint), 0.0, 1.0);
    float luma = dot(source.rgb, vec3(0.2126, 0.7152, 0.0722));
    float exponent = BEAM_NARROWNESS * mix(1.4, 0.6, luma);
    float row = displayUv.y * uGameSize.y;
    float beam = 0.5 * (beamShade(row - 0.25 * guestFootprint, exponent)
                      + beamShade(row + 0.25 * guestFootprint, exponent));
    float beamShadow = mix(0.65, 0.05, beam);
    float scan = 1.0 - SCANLINE_STRENGTH * visibleLine * beamShadow;

    // A RGB triad approximation, attenuated if output cannot resolve the pattern.
    float maskVisibility = clamp((scale.x - 1.0) * 0.5, 0.0, 1.0);
#ifdef GL_FRAGMENT_PRECISION_HIGH
    float column = floor(displayUv.x * uOutputSize.x);
#else
    // Use the smaller guest coordinate range on GLES2 mediump-only fragments.
    float column = floor(displayUv.x * uGameSize.x);
#endif
    float triad = mod(column, 3.0);
    vec3 mask = triad < 1.0 ? vec3(1.0, 0.65, 0.65) :
        (triad < 2.0 ? vec3(0.65, 1.0, 0.65) : vec3(0.65, 0.65, 1.0));
    vec3 rgb = source.rgb * scan
        * mix(vec3(1.0), mask, MASK_STRENGTH * maskVisibility)
        * BRIGHT_BOOST;
    gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), source.a);
}
