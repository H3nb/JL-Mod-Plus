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
    vec4 color = texture2D(Texture, uv);
    float scan = fract(uv.y * InputSize.y);
    color.rgb *= mix(0.72, 1.0, smoothstep(0.1, 0.9, scan));
    gl_FragColor = color;
}
#endif
