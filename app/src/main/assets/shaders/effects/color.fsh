// SPDX-License-Identifier: Apache-2.0
precision mediump float;
varying vec2 v_texcoord0;
uniform sampler2D sampler0;
uniform float uBrightness, uContrast, uSaturation;
void main() {
    vec4 c = texture2D(sampler0, v_texcoord0);
    vec3 rgb = (c.rgb + vec3(uBrightness) - 0.5) * uContrast + 0.5;
    float gray = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
    gl_FragColor = vec4(clamp(mix(vec3(gray), rgb, uSaturation), 0.0, 1.0), c.a);
}
