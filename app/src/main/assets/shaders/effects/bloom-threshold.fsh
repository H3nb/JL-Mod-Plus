// SPDX-License-Identifier: Apache-2.0
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 v_texcoord0;
uniform sampler2D sampler0;
uniform vec2 uInputSize, uContentSize, uOutputSize, uContentUvMax;
uniform float uThreshold;

vec3 extractBright(vec2 uv) {
    vec2 halfTexel = vec2(0.5) / uInputSize;
    vec2 safeUv = clamp(uv, halfTexel, max(halfTexel, uContentUvMax - halfTexel));
    vec3 rgb = texture2D(sampler0, safeUv).rgb;
    float intensity = smoothstep(uThreshold, 1.0, max(rgb.r, max(rgb.g, rgb.b)));
    return rgb * intensity;
}

void main() {
    // Threshold each sample before reduction so isolated highlights contribute
    // even when their average color would fall below the threshold.
    vec2 footprint = uContentUvMax / uOutputSize;
    vec3 bright = vec3(0.0);
    vec2 ratio = uContentSize / uOutputSize;
    if (max(ratio.x, ratio.y) > 2.0) {
        // A 4x4 footprint covers source texel centers at aligned reductions
        // up to 4:1, but is not a lossless arbitrary minification filter.
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                vec2 offset = (vec2(float(x), float(y)) + vec2(0.5)) * 0.25 - vec2(0.5);
                bright += extractBright(v_texcoord0 + offset * footprint);
            }
        }
        bright *= 0.0625;
    } else {
        vec2 quarter = footprint * 0.25;
        bright += extractBright(v_texcoord0 + vec2(-quarter.x, -quarter.y));
        bright += extractBright(v_texcoord0 + vec2(quarter.x, -quarter.y));
        bright += extractBright(v_texcoord0 + vec2(-quarter.x, quarter.y));
        bright += extractBright(v_texcoord0 + vec2(quarter.x, quarter.y));
        bright *= 0.25;
    }
    gl_FragColor = vec4(bright, 1.0);
}
