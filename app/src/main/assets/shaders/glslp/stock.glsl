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
void main() {
    gl_FragColor = texture2D(Texture, uv);
}
#endif
