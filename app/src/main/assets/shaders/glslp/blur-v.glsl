#ifdef VERTEX
attribute vec4 VertexCoord;
attribute vec4 TexCoord;
uniform mat4 MVPMatrix;
varying vec2 uv;
void main() {
    uv = TexCoord.xy;
    gl_Position = MVPMatrix * VertexCoord;
}
#endif
#ifdef FRAGMENT
precision mediump float;
varying vec2 uv;
uniform sampler2D Texture;
uniform vec2 InputSize;
void main() {
    vec2 delta = vec2(0.0, 1.0 / InputSize.y);
    vec4 color = texture2D(Texture, uv) * 0.50;
    color += texture2D(Texture, uv - delta) * 0.25;
    color += texture2D(Texture, uv + delta) * 0.25;
    gl_FragColor = color;
}
#endif
