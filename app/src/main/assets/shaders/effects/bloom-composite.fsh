// SPDX-License-Identifier: Apache-2.0
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 v_texcoord0;
uniform sampler2D sampler0;
uniform sampler2D uBase;
uniform vec2 uBaseUvScale, uBaseUvOffset, uBaseSize, uBaseUvMax;
uniform float uIntensity;

void main() {
    vec2 baseUv = v_texcoord0 * uBaseUvScale + uBaseUvOffset;
    vec2 halfTexel = vec2(0.5) / uBaseSize;
    baseUv = clamp(baseUv, halfTexel, max(halfTexel, uBaseUvMax - halfTexel));
    vec4 original = texture2D(uBase, baseUv);
    vec3 blurred = texture2D(sampler0, v_texcoord0).rgb;
    gl_FragColor = vec4(clamp(original.rgb + blurred * uIntensity, 0.0, 1.0), original.a);
}
