// SPDX-License-Identifier: Apache-2.0
// A normalized 17-tap discrete Gaussian, paired into nine GL_LINEAR fetches.
// Weights and offsets are computed once per configured effect on the GL thread.
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 v_texcoord0;
uniform sampler2D sampler0;
uniform vec2 uInputSize;
uniform float uBlurCenter;
uniform vec4 uBlurWeights, uBlurOffsets;

void main() {
    vec2 d = vec2(1.0 / uInputSize.x, 0.0);
    vec3 color = texture2D(sampler0, v_texcoord0).rgb * uBlurCenter;
    color += (texture2D(sampler0, v_texcoord0 - d * uBlurOffsets.x).rgb +
              texture2D(sampler0, v_texcoord0 + d * uBlurOffsets.x).rgb) * uBlurWeights.x;
    color += (texture2D(sampler0, v_texcoord0 - d * uBlurOffsets.y).rgb +
              texture2D(sampler0, v_texcoord0 + d * uBlurOffsets.y).rgb) * uBlurWeights.y;
    color += (texture2D(sampler0, v_texcoord0 - d * uBlurOffsets.z).rgb +
              texture2D(sampler0, v_texcoord0 + d * uBlurOffsets.z).rgb) * uBlurWeights.z;
    color += (texture2D(sampler0, v_texcoord0 - d * uBlurOffsets.w).rgb +
              texture2D(sampler0, v_texcoord0 + d * uBlurOffsets.w).rgb) * uBlurWeights.w;
    gl_FragColor = vec4(color, 1.0);
}
