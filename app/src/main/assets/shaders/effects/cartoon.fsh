// SPDX-License-Identifier: Apache-2.0
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 v_texcoord0;
uniform sampler2D sampler0;
uniform vec2 uInputSize, uContentUvMax;
uniform float uLevels, uEdge;

vec3 readColor(vec2 uv) {
    vec2 halfTexel = 0.5 / uInputSize;
    return texture2D(sampler0, clamp(uv, halfTexel,
        max(halfTexel, uContentUvMax - halfTexel))).rgb;
}
void main() {
    vec2 d = 1.0 / uInputSize;
    vec2 halfTexel = 0.5 / uInputSize;
    vec2 centerUv = clamp(v_texcoord0, halfTexel,
        max(halfTexel, uContentUvMax - halfTexel));
    vec4 source = texture2D(sampler0, centerUv);
    vec3 dx = readColor(v_texcoord0 + vec2(d.x, 0.0)) -
              readColor(v_texcoord0 - vec2(d.x, 0.0));
    vec3 dy = readColor(v_texcoord0 + vec2(0.0, d.y)) -
              readColor(v_texcoord0 - vec2(0.0, d.y));
    float luma = dot(source.rgb, vec3(0.2126, 0.7152, 0.0722));
    // Continuous, softly terraced luminance: a hard round-to-nearest step
    // creates 1/uLevels jumps and artificial seams across broad gradients.
    float scaled = luma * uLevels;
    float phase = fract(scaled);
    float softened = (floor(scaled) + smoothstep(0.18, 0.82, phase)) / uLevels;
    // Scale all channels together to retain their ratios where in gamut.
    vec3 bands = source.rgb * (softened + 0.05) / (luma + 0.05);
    // Edge strength is an upper bound on darkening, not a multiplier
    // that can saturate an entire high-contrast boundary to black.
    float edge = uEdge * smoothstep(0.08, 0.75, length(dx) + length(dy));
    // Protect dark regions so outlines do not merge into solid black blobs.
    edge *= smoothstep(0.06, 0.40, luma);
    gl_FragColor = vec4(clamp(bands * (1.0 - edge), 0.0, 1.0), source.a);
}
