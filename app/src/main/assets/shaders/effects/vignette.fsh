// SPDX-License-Identifier: Apache-2.0
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 v_texcoord0;
uniform sampler2D sampler0;
uniform vec2 uInputSize, uContentUvMax;
uniform vec2 uDisplayUvScale, uDisplayUvOffset, uOutputSize;
uniform float uStrength;

void main() {
    vec2 halfTexel = vec2(0.5) / uInputSize;
    vec2 sampleUv = clamp(v_texcoord0, halfTexel,
        max(halfTexel, uContentUvMax - halfTexel));
    vec4 source = texture2D(sampler0, sampleUv);
    vec2 uv = v_texcoord0 * uDisplayUvScale + uDisplayUvOffset;
    float aspect = uOutputSize.x / max(1.0, uOutputSize.y);
    vec2 distanceToCenter = (uv - vec2(0.5)) * vec2(aspect, 1.0);
    float rim = clamp(dot(distanceToCenter, distanceToCenter) /
        (0.25 * (aspect * aspect + 1.0)), 0.0, 1.0);
    gl_FragColor = vec4(source.rgb * (1.0 - uStrength * rim), source.a);
}
