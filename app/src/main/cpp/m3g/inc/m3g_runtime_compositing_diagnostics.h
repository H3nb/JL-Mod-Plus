#ifndef M3G_RUNTIME_COMPOSITING_DIAGNOSTICS_H
#define M3G_RUNTIME_COMPOSITING_DIAGNOSTICS_H

#include <GLES/gl.h>
#include <android/log.h>
#include <stdlib.h>

static inline void m3gRuntimeCompositingCheck(const char *call,
                                               const char *file,
                                               int line)
{
    GLenum error = glGetError();
    if (error != GL_NO_ERROR) {
        __android_log_print(ANDROID_LOG_ERROR, "M3G-GL",
                            "%s -> GL error 0x%X at %s:%d",
                            call, (unsigned)error, file, line);
        abort();
    }
}

static inline void m3gRuntimeDepthFunc(GLenum func,
                                       const char *file, int line)
{
    glDepthFunc(func);
    m3gRuntimeCompositingCheck("glDepthFunc", file, line);
}

static inline void m3gRuntimeDepthMask(GLboolean flag,
                                       const char *file, int line)
{
    glDepthMask(flag);
    m3gRuntimeCompositingCheck("glDepthMask", file, line);
}

static inline void m3gRuntimeColorMask(GLboolean r, GLboolean g,
                                       GLboolean b, GLboolean a,
                                       const char *file, int line)
{
    glColorMask(r, g, b, a);
    m3gRuntimeCompositingCheck("glColorMask", file, line);
}

static inline void m3gRuntimeAlphaFunc(GLenum func, GLclampf ref,
                                       const char *file, int line)
{
    glAlphaFunc(func, ref);
    m3gRuntimeCompositingCheck("glAlphaFunc", file, line);
}

static inline void m3gRuntimeBlendFunc(GLenum sfactor, GLenum dfactor,
                                       const char *file, int line)
{
    glBlendFunc(sfactor, dfactor);
    m3gRuntimeCompositingCheck("glBlendFunc", file, line);
}

static inline void m3gRuntimePolygonOffset(GLfloat factor, GLfloat units,
                                           const char *file, int line)
{
    glPolygonOffset(factor, units);
    m3gRuntimeCompositingCheck("glPolygonOffset", file, line);
}

#define glDepthFunc(func) \
    m3gRuntimeDepthFunc((func), __FILE__, __LINE__)
#define glDepthMask(flag) \
    m3gRuntimeDepthMask((flag), __FILE__, __LINE__)
#define glColorMask(r, g, b, a) \
    m3gRuntimeColorMask((r), (g), (b), (a), __FILE__, __LINE__)
#define glAlphaFunc(func, ref) \
    m3gRuntimeAlphaFunc((func), (ref), __FILE__, __LINE__)
#define glBlendFunc(sfactor, dfactor) \
    m3gRuntimeBlendFunc((sfactor), (dfactor), __FILE__, __LINE__)
#define glPolygonOffset(factor, units) \
    m3gRuntimePolygonOffset((factor), (units), __FILE__, __LINE__)

#endif
