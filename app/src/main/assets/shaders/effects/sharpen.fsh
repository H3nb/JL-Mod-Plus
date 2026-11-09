// SPDX-License-Identifier: Apache-2.0
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 v_texcoord0;
uniform sampler2D sampler0;
uniform vec2 uInputSize, uContentUvMax;
uniform float uSharpness;

vec3 readColor(vec2 uv) {
    vec2 halfTexel = 0.5 / uInputSize;
    return texture2D(sampler0, clamp(uv, halfTexel,
        max(halfTexel, uContentUvMax - halfTexel))).rgb;
}
void main() {
    vec2 texel = 1.0 / uInputSize;
    vec2 halfTexel = 0.5 / uInputSize;
    vec2 centerUv = clamp(v_texcoord0, halfTexel,
        max(halfTexel, uContentUvMax - halfTexel));
    vec4 center = texture2D(sampler0, centerUv);
    vec3 n = readColor(v_texcoord0 + vec2(0.0, texel.y));
    vec3 s = readColor(v_texcoord0 - vec2(0.0, texel.y));
    vec3 e = readColor(v_texcoord0 + vec2(texel.x, 0.0));
    vec3 w = readColor(v_texcoord0 - vec2(texel.x, 0.0));
    vec3 low = min(center.rgb, min(min(n, s), min(e, w)));
    vec3 high = max(center.rgb, max(max(n, s), max(e, w)));
    vec3 localRange = high - low;
    vec3 result = center.rgb + uSharpness *
        (center.rgb - (n + s + e + w) * 0.25);
    // Limit ringing to a small fraction of the neighborhood's observed range.
    result = clamp(result, low - 0.10 * localRange,
        high + 0.10 * localRange);
    gl_FragColor = vec4(clamp(result, 0.0, 1.0), center.a);
}
