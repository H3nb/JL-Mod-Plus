// SPDX-License-Identifier: Apache-2.0
precision mediump float;
varying vec2 v_texcoord0;
uniform sampler2D sampler0;
uniform float uBrightness, uContrast, uSaturation, uGamma;
uniform float uTemperature, uTint, uMonochrome;

void main() {
    vec4 source = texture2D(sampler0, v_texcoord0);
    vec3 rgb = (source.rgb + vec3(uBrightness) - 0.5) * uContrast + 0.5;
    float lightness = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
    rgb = mix(vec3(lightness), rgb, uSaturation);
    // Conservative color-channel balance, not an emulation of a display's ICC profile.
    rgb *= vec3(1.0 + 0.15 * uTemperature + 0.08 * uTint,
                1.0 - 0.16 * uTint,
                1.0 - 0.15 * uTemperature + 0.08 * uTint);
    rgb = pow(max(rgb, vec3(0.0)), vec3(1.0 / max(uGamma, 0.1)));
    float gray = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
    rgb = mix(rgb, vec3(gray), uMonochrome);
    gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), source.a);
}
