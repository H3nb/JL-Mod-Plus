// SPDX-License-Identifier: Apache-2.0
// Artistic two- or four-tone monochrome LCD palette, not a device color calibration.
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 v_texcoord0;
uniform sampler2D sampler0;
uniform float uMonoPalette, uToneSteps;

void main() {
    vec4 source = texture2D(sampler0, v_texcoord0);
    vec3 ink = vec3(0.035);
    vec3 paper = vec3(0.96);
    if (uMonoPalette > 0.5 && uMonoPalette < 1.5) {
        ink = vec3(0.13, 0.22, 0.10);
        paper = vec3(0.75, 0.83, 0.58);
    } else if (uMonoPalette >= 1.5) {
        ink = vec3(0.23, 0.12, 0.035);
        paper = vec3(0.95, 0.74, 0.37);
    }
    float luma = dot(source.rgb, vec3(0.2126, 0.7152, 0.0722));
    float intervals = uToneSteps < 0.5 ? 1.0 : 3.0;
    float tone = floor(luma * intervals + 0.5) / intervals;
    gl_FragColor = vec4(mix(ink, paper, tone), source.a);
}
