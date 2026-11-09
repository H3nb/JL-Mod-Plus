// SPDX-License-Identifier: Apache-2.0
precision mediump float;
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
    vec4 source = texture2D(sampler0, v_texcoord0);
    vec3 dx = readColor(v_texcoord0 + vec2(d.x, 0.0)) -
              readColor(v_texcoord0 - vec2(d.x, 0.0));
    vec3 dy = readColor(v_texcoord0 + vec2(0.0, d.y)) -
              readColor(v_texcoord0 - vec2(0.0, d.y));
    float luma = dot(source.rgb, vec3(0.2126, 0.7152, 0.0722));
    float stepped = floor(luma * uLevels + 0.5) / uLevels;
    // Quantize luminance, retaining the pixel's hue and most texture color detail.
    vec3 bands = source.rgb * (stepped + 0.05) / (luma + 0.05);
    float edge = clamp((length(dx) + length(dy)) * uEdge, 0.0, 1.0);
    // Protect dark regions so outlines do not merge into solid black blobs.
    edge *= smoothstep(0.06, 0.40, luma);
    gl_FragColor = vec4(clamp(bands * (1.0 - edge), 0.0, 1.0), source.a);
}
