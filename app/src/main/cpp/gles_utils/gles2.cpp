#include <GLES2/gl2.h>
#include "utils.h"

unsigned int GLES2_glReadPixels(int x, int y, int w, int h, void *buffer) {
    glReadPixels(x, y, w, h, GL_RGBA, GL_UNSIGNED_BYTE, buffer);
    return glGetError();
}
