// SPDX-License-Identifier: Apache-2.0
precision mediump float;
varying vec2 v_texcoord0;
uniform sampler2D sampler0;
void main() { gl_FragColor = texture2D(sampler0, v_texcoord0); }
