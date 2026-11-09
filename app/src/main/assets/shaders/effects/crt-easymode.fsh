/*
 * Adapted from libretro/glsl-shaders/crt/shaders/crt-easymode.glsl
 * CRT shader by EasyMode. Original license: GPL.
 * Removed RetroArch vertex-stage and GLSLP compatibility dependencies;
 * keeps the original EasyMode sampling, mask, scanline and gamma equations.
 */


#if __VERSION__ >= 130
#define COMPAT_VARYING in
#define COMPAT_TEXTURE texture
out vec4 FragColor;
#else
#define COMPAT_VARYING varying
#define FragColor gl_FragColor
#define COMPAT_TEXTURE texture2D
#endif

#ifdef GL_ES
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#define COMPAT_PRECISION highp
#else
precision mediump float;
precision mediump int;
#define COMPAT_PRECISION mediump
#endif
#else
#define COMPAT_PRECISION
#endif

uniform COMPAT_PRECISION int FrameDirection;
uniform COMPAT_PRECISION int FrameCount;
uniform COMPAT_PRECISION vec2 uOutputSize;
uniform COMPAT_PRECISION vec2 uInputSize;
uniform COMPAT_PRECISION vec2 uContentSize;
uniform COMPAT_PRECISION vec2 uGameSize;
uniform COMPAT_PRECISION vec2 uDisplayUvScale;
uniform COMPAT_PRECISION vec2 uDisplayUvOffset;
uniform COMPAT_PRECISION vec2 uContentUvMax;
uniform sampler2D sampler0;
COMPAT_VARYING vec2 v_texcoord0;

// Keep the squared denominator representable even on minimum mediump GLES2.
#ifdef GL_FRAGMENT_PRECISION_HIGH
#define FIX(c) max(abs(c), 0.0001)
#else
#define FIX(c) max(abs(c), 0.02)
#endif
#define PI 3.141592653589

// Clamp to the valid guest image, not the padded backing texture.
#define TEX2D(c) dilate(texture2D(sampler0, clamp((c), vec2(0.5) / uInputSize, max(vec2(0.5) / uInputSize, uContentUvMax - vec2(0.5) / uInputSize))))

// compatibility #defines
#define Source Texture
#define vTexCoord v_texcoord0

#define SourceSize vec4(uInputSize, 1.0 / uInputSize)
#define outsize vec4(uOutputSize, 1.0 / uOutputSize)

#if 1
// All parameter floats need to have COMPAT_PRECISION in front of them
uniform COMPAT_PRECISION float SHARPNESS_H;
uniform COMPAT_PRECISION float SHARPNESS_V;
uniform COMPAT_PRECISION float MASK_STRENGTH;
uniform COMPAT_PRECISION float MASK_DOT_WIDTH;
uniform COMPAT_PRECISION float MASK_DOT_HEIGHT;
uniform COMPAT_PRECISION float MASK_STAGGER;
uniform COMPAT_PRECISION float MASK_SIZE;
uniform COMPAT_PRECISION float SCANLINE_STRENGTH;
uniform COMPAT_PRECISION float SCANLINE_BEAM_WIDTH_MIN;
uniform COMPAT_PRECISION float SCANLINE_BEAM_WIDTH_MAX;
uniform COMPAT_PRECISION float SCANLINE_BRIGHT_MIN;
uniform COMPAT_PRECISION float SCANLINE_BRIGHT_MAX;
uniform COMPAT_PRECISION float SCANLINE_CUTOFF;
uniform COMPAT_PRECISION float GAMMA_INPUT;
uniform COMPAT_PRECISION float GAMMA_OUTPUT;
uniform COMPAT_PRECISION float BRIGHT_BOOST;
uniform COMPAT_PRECISION float DILATION;
#else
#define SHARPNESS_H 0.5
#define SHARPNESS_V 1.0
#define MASK_STRENGTH 0.3
#define MASK_DOT_WIDTH 1.0
#define MASK_DOT_HEIGHT 1.0
#define MASK_STAGGER 0.0
#define MASK_SIZE 1.0
#define SCANLINE_STRENGTH 1.0
#define SCANLINE_BEAM_WIDTH_MIN 1.5
#define SCANLINE_BEAM_WIDTH_MAX 1.5
#define SCANLINE_BRIGHT_MIN 0.35
#define SCANLINE_BRIGHT_MAX 0.65
#define SCANLINE_CUTOFF 400.0
#define GAMMA_INPUT 2.0
#define GAMMA_OUTPUT 1.8
#define BRIGHT_BOOST 1.2
#define DILATION 1.0
#endif

// Set to 0 to use linear filter and gain speed
#define ENABLE_LANCZOS 1

vec4 dilate(vec4 col)
{
    vec4 x = mix(vec4(1.0), col, DILATION);

    return col * x;
}

float curve_distance(float x, float sharp)
{

/*
    apply half-circle s-curve to distance for sharper (more pixelated) interpolation
    single line formula for Graph Toy:
    0.5 - sqrt(0.25 - (x - step(0.5, x)) * (x - step(0.5, x))) * sign(0.5 - x)
*/

    float x_step = step(0.5, x);
    float curve = 0.5 - sqrt(0.25 - (x - x_step) * (x - x_step)) * sign(0.5 - x);

    return mix(x, curve, sharp);
}

mat4 get_color_matrix(vec2 co, vec2 dx)
{
    return mat4(TEX2D(co - dx), TEX2D(co), TEX2D(co + dx), TEX2D(co + 2.0 * dx));
}

vec3 filter_lanczos(vec4 coeffs, mat4 color_matrix)
{
    vec4 col        = color_matrix * coeffs;
    vec4 sample_min = min(color_matrix[1], color_matrix[2]);
    vec4 sample_max = max(color_matrix[1], color_matrix[2]);

    col = clamp(col, sample_min, sample_max);

    return col.rgb;
}

void main()
{
    vec2 dx     = vec2(SourceSize.z, 0.0);
    vec2 dy     = vec2(0.0, SourceSize.w);
    vec2 pix_co = vTexCoord * SourceSize.xy - vec2(0.5, 0.5);
    vec2 tex_co = (floor(pix_co) + vec2(0.5, 0.5)) * SourceSize.zw;
    vec2 dist   = fract(pix_co);
    vec2 display_uv = v_texcoord0 * uDisplayUvScale + uDisplayUvOffset;
    float curve_x;
    vec3 col, col2;

#if ENABLE_LANCZOS
    curve_x = curve_distance(dist.x, SHARPNESS_H * SHARPNESS_H);

    vec4 coeffs = PI * vec4(1.0 + curve_x, curve_x, 1.0 - curve_x, 2.0 - curve_x);

    coeffs = FIX(coeffs);
    coeffs = 2.0 * sin(coeffs) * sin(coeffs * 0.5) / (coeffs * coeffs);
    coeffs /= dot(coeffs, vec4(1.0));

    col  = filter_lanczos(coeffs, get_color_matrix(tex_co, dx));
    col2 = filter_lanczos(coeffs, get_color_matrix(tex_co + dy, dx));
#else
    curve_x = curve_distance(dist.x, SHARPNESS_H);

    col  = mix(TEX2D(tex_co).rgb,      TEX2D(tex_co + dx).rgb,      curve_x);
    col2 = mix(TEX2D(tex_co + dy).rgb, TEX2D(tex_co + dx + dy).rgb, curve_x);
#endif

    col = mix(col, col2, curve_distance(dist.y, SHARPNESS_V));
    col = pow(col, vec3(GAMMA_INPUT / (DILATION + 1.0)));

    float luma        = dot(vec3(0.2126, 0.7152, 0.0722), col);
    float bright      = (max(col.r, max(col.g, col.b)) + luma) * 0.5;
    float min_bright = min(SCANLINE_BRIGHT_MIN, SCANLINE_BRIGHT_MAX);
    float max_bright = max(SCANLINE_BRIGHT_MIN, SCANLINE_BRIGHT_MAX);
    float min_beam = min(SCANLINE_BEAM_WIDTH_MIN, SCANLINE_BEAM_WIDTH_MAX);
    float max_beam = max(SCANLINE_BEAM_WIDTH_MIN, SCANLINE_BEAM_WIDTH_MAX);
    float scan_bright = clamp(bright, min_bright, max_bright);
    float scan_beam = clamp(bright * max_beam, min_beam, max_beam);
    // Use the guest's logical resolution, independent of the previous pass.
    float scan_weight = 1.0 - pow(cos(display_uv.y * 2.0 * PI * uGameSize.y) * 0.5 + 0.5, scan_beam) * SCANLINE_STRENGTH;

    vec3 mask_weight = vec3(1.0);
#ifdef GL_FRAGMENT_PRECISION_HIGH
    // RGB mask is screen-pixel aligned and requires highp on wide displays.
    float mask = 1.0 - MASK_STRENGTH;
    vec2 mod_fac = floor(display_uv * uOutputSize /
        vec2(MASK_SIZE, MASK_DOT_HEIGHT * MASK_SIZE));
    int dot_no = int(mod((mod_fac.x + mod(mod_fac.y, 2.0) * MASK_STAGGER) /
        MASK_DOT_WIDTH, 3.0));
    if      (dot_no == 0) mask_weight = vec3(1.0, mask, mask);
    else if (dot_no == 1) mask_weight = vec3(mask, 1.0, mask);
    else                  mask_weight = vec3(mask, mask, 1.0);
#endif

    if (uGameSize.y >= SCANLINE_CUTOFF) 
        scan_weight = 1.0;

    col2 = col.rgb;
    col *= vec3(scan_weight);
    col  = mix(col, col2, scan_bright);
    col *= mask_weight;
    col  = pow(col, vec3(1.0 / GAMMA_OUTPUT));

    FragColor = vec4(col * BRIGHT_BOOST, 1.0);
} 
