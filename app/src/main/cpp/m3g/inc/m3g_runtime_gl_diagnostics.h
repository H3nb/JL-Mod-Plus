#ifndef M3G_RUNTIME_GL_DIAGNOSTICS_H
#define M3G_RUNTIME_GL_DIAGNOSTICS_H

#include <GLES/gl.h>
#include <android/log.h>
#include <stdlib.h>

static inline void m3gRuntimeGlFail(const char *call,
                                    GLenum argument,
                                    GLenum error,
                                    const char *file,
                                    int line)
{
    __android_log_print(ANDROID_LOG_ERROR, "M3G-GL",
                        "%s(0x%X) -> GL error 0x%X at %s:%d",
                        call, (unsigned)argument, (unsigned)error, file, line);
    abort();
}

static inline void m3gRuntimeGlCheck(const char *call,
                                     GLenum argument,
                                     const char *file,
                                     int line)
{
    GLenum error = glGetError();
    if (error != GL_NO_ERROR) {
        m3gRuntimeGlFail(call, argument, error, file, line);
    }
}

static inline void m3gRuntimeGlEnable(GLenum cap, const char *file, int line)
{
    glEnable(cap);
    m3gRuntimeGlCheck("glEnable", cap, file, line);
}

static inline void m3gRuntimeGlDisable(GLenum cap, const char *file, int line)
{
    glDisable(cap);
    m3gRuntimeGlCheck("glDisable", cap, file, line);
}

static inline void m3gRuntimeGlLightModelfv(GLenum pname, const GLfloat *params,
                                            const char *file, int line)
{
    glLightModelfv(pname, params);
    m3gRuntimeGlCheck("glLightModelfv", pname, file, line);
}

static inline void m3gRuntimeGlPixelStorei(GLenum pname, GLint param,
                                           const char *file, int line)
{
    glPixelStorei(pname, param);
    m3gRuntimeGlCheck("glPixelStorei", pname, file, line);
}

static inline void m3gRuntimeGlMatrixMode(GLenum mode,
                                          const char *file, int line)
{
    glMatrixMode(mode);
    m3gRuntimeGlCheck("glMatrixMode", mode, file, line);
}

static inline void m3gRuntimeGlClear(GLbitfield mask,
                                     const char *file, int line)
{
    glClear(mask);
    m3gRuntimeGlCheck("glClear", (GLenum)mask, file, line);
}

#define glEnable(cap) \
    m3gRuntimeGlEnable((cap), __FILE__, __LINE__)
#define glDisable(cap) \
    m3gRuntimeGlDisable((cap), __FILE__, __LINE__)
#define glLightModelfv(pname, params) \
    m3gRuntimeGlLightModelfv((pname), (params), __FILE__, __LINE__)
#define glPixelStorei(pname, param) \
    m3gRuntimeGlPixelStorei((pname), (param), __FILE__, __LINE__)
#define glMatrixMode(mode) \
    m3gRuntimeGlMatrixMode((mode), __FILE__, __LINE__)
#define glClear(mask) \
    m3gRuntimeGlClear((mask), __FILE__, __LINE__)

#endif /* M3G_RUNTIME_GL_DIAGNOSTICS_H */
