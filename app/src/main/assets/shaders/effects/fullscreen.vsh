// SPDX-License-Identifier: Apache-2.0
attribute vec2 a_position;
attribute vec2 a_texcoord0;
varying vec2 v_texcoord0;
void main() {
    gl_Position = vec4(a_position, 0.0, 1.0);
    v_texcoord0 = a_texcoord0;
}
