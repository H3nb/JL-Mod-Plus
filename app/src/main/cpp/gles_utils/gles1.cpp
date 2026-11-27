#include <GLES/gl.h>
#include "utils.h"

unsigned int GLES1_glReadPixels(int x, int y, int w, int h, void *buffer) {
    GLint alignment = 4;
    glGetIntegerv(GL_PACK_ALIGNMENT, &alignment);
    glPixelStorei(GL_PACK_ALIGNMENT, 4);
    glReadPixels(x, y, w, h, GL_RGBA, GL_UNSIGNED_BYTE, buffer);
    const GLenum error = glGetError();
    glPixelStorei(GL_PACK_ALIGNMENT, alignment);
    return error;
}
