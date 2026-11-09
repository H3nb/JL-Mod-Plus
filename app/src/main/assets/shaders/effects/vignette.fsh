// SPDX-License-Identifier: Apache-2.0
precision mediump float;
varying vec2 v_texcoord0;
uniform sampler2D sampler0;
uniform vec2 uDisplayUvScale, uDisplayUvOffset, uOutputSize;
uniform float uStrength;

void main() {
    vec4 source = texture2D(sampler0, v_texcoord0);
    vec2 uv = v_texcoord0 * uDisplayUvScale + uDisplayUvOffset;
    float aspect = uOutputSize.x / max(1.0, uOutputSize.y);
    vec2 distanceToCenter = (uv - vec2(0.5)) * vec2(aspect, 1.0);
    float rim = clamp(dot(distanceToCenter, distanceToCenter) /
        (0.5 * (aspect * aspect + 1.0)), 0.0, 1.0);
    gl_FragColor = vec4(source.rgb * (1.0 - uStrength * rim), source.a);
}
