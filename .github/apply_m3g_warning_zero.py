from pathlib import Path


def replace(path: str, old: str, new: str, count: int = 1) -> None:
    p = Path(path)
    text = p.read_text()
    actual = text.count(old)
    if actual != count:
        raise SystemExit(f"{path}: expected {count} match(es), found {actual}: {old[:80]!r}")
    p.write_text(text.replace(old, new, count))


# Pointer-width-safe native/EGL target handles.
replace(
    "app/src/main/cpp/m3g/inc/m3g_core.h",
    "typedef /*@abstract@*/ M3Guint M3GNativeBitmap;\ntypedef /*@abstract@*/ M3Guint M3GNativeWindow;\ntypedef /*@abstract@*/ M3Guint M3GEGLSurface;",
    "/* Native Android/EGL handles may be pointers on 64-bit targets. */\ntypedef /*@abstract@*/ M3Gpointer M3GNativeBitmap;\ntypedef /*@abstract@*/ M3Gpointer M3GNativeWindow;\ntypedef /*@abstract@*/ M3Gpointer M3GEGLSurface;",
)
replace(
    "app/src/main/cpp/m3g/inc/m3g_gl.h",
    "M3Gbool m3gglGetNativeBitmapParams(M3GNativeBitmap bitmap,\n                                   M3GPixelFormat *format,\n                                   M3Gint *width, M3Gint *height, M3Gint *pixels);",
    "M3Gbool m3gglGetNativeBitmapParams(M3GNativeBitmap bitmap,\n                                   M3GPixelFormat *format,\n                                   M3Gint *width, M3Gint *height,\n                                   void **pixels);",
)

# Legacy Android native-window/bitmap hooks are not the active JSR-184 target
# path. Do not report success while leaving output parameters uninitialized.
replace(
    "app/src/main/cpp/m3g/src/m3g_android_gl.cpp",
    "M3Gbool m3gglLockNativeBitmap(M3GNativeBitmap bitmap,\n                              M3Gubyte **ptr,\n                              M3Gsizei *stride) \n{\n    return M3G_TRUE;\n}",
    "M3Gbool m3gglLockNativeBitmap(M3GNativeBitmap bitmap,\n                              M3Gubyte **ptr,\n                              M3Gsizei *stride) \n{\n    (void) bitmap;\n    (void) ptr;\n    (void) stride;\n    return M3G_FALSE;\n}",
)
replace(
    "app/src/main/cpp/m3g/src/m3g_android_gl.cpp",
    "void m3gglReleaseNativeBitmap(M3GNativeBitmap bitmap) \n{    \n}",
    "void m3gglReleaseNativeBitmap(M3GNativeBitmap bitmap) \n{\n    (void) bitmap;\n}",
)
replace(
    "app/src/main/cpp/m3g/src/m3g_android_gl.cpp",
    "extern \"C\" M3Gbool m3gglGetNativeBitmapParams(M3GNativeBitmap bitmap,\n                                              M3GPixelFormat *format,\n                                              M3Gint *width, M3Gint *height, M3Gint *pixels)\n{\n    return M3G_TRUE;\n}",
    "extern \"C\" M3Gbool m3gglGetNativeBitmapParams(M3GNativeBitmap bitmap,\n                                              M3GPixelFormat *format,\n                                              M3Gint *width, M3Gint *height,\n                                              void **pixels)\n{\n    (void) bitmap;\n    (void) format;\n    (void) width;\n    (void) height;\n    (void) pixels;\n    return M3G_FALSE;\n}",
)
replace(
    "app/src/main/cpp/m3g/src/m3g_android_gl.cpp",
    "extern \"C\" M3Gbool m3gglGetNativeWindowParams(M3GNativeWindow wnd,\n                                              M3GPixelFormat *format,\n                                              M3Gint *width, M3Gint *height)\n{\n    return M3G_TRUE;\n}",
    "extern \"C\" M3Gbool m3gglGetNativeWindowParams(M3GNativeWindow wnd,\n                                              M3GPixelFormat *format,\n                                              M3Gint *width, M3Gint *height)\n{\n    (void) wnd;\n    (void) format;\n    (void) width;\n    (void) height;\n    return M3G_FALSE;\n}",
)

# EGL native pixmap matching takes EGLint attributes. Android native handles
# are pointer-width and cannot be losslessly encoded there; the active Android
# binding uses the memory/Image2D paths, so select configs without that legacy
# native-pixmap match attribute on Android.
replace(
    "app/src/main/cpp/m3g/src/m3g_rendercontext.inl",
    "    if (bitmapHandle) {\n        /* This attribute is matched only for pixmap targets */\n        attribs[6].attrib = EGL_MATCH_NATIVE_PIXMAP;\n        attribs[6].value = bitmapHandle;\n\n        /* Try to get multisampling if requested */\n\n        attribs[7].attrib = EGL_SAMPLE_BUFFERS;\n        attribs[8].attrib = EGL_SAMPLES;\n\n        attribs[9].attrib = EGL_NONE;\n    } else {\n        /* Try to get multisampling if requested */\n\n        attribs[6].attrib = EGL_SAMPLE_BUFFERS;\n        attribs[7].attrib = EGL_SAMPLES;\n\n        attribs[8].attrib = EGL_NONE;\n    }",
    "#if defined(M3G_TARGET_ANDROID)\n    M3G_UNREF(bitmapHandle);\n    attribs[6].attrib = EGL_SAMPLE_BUFFERS;\n    attribs[7].attrib = EGL_SAMPLES;\n    attribs[8].attrib = EGL_NONE;\n#else\n    if (bitmapHandle) {\n        /* This attribute is matched only for pixmap targets */\n        attribs[6].attrib = EGL_MATCH_NATIVE_PIXMAP;\n        attribs[6].value = bitmapHandle;\n\n        /* Try to get multisampling if requested */\n\n        attribs[7].attrib = EGL_SAMPLE_BUFFERS;\n        attribs[8].attrib = EGL_SAMPLES;\n        attribs[9].attrib = EGL_NONE;\n    } else {\n        attribs[6].attrib = EGL_SAMPLE_BUFFERS;\n        attribs[7].attrib = EGL_SAMPLES;\n        attribs[8].attrib = EGL_NONE;\n    }\n#endif",
)
replace(
    "app/src/main/cpp/m3g/src/m3g_rendercontext.inl",
    "        if (bitmapHandle) {\n            if (samples > 1) {\n                attribs[7].value = 1;\n                attribs[8].value = samples;\n            }\n            else {\n                attribs[7].value = EGL_FALSE;\n                attribs[8].value = 0;\n            }\n        } else {\n            if (samples > 1) {\n                attribs[6].value = 1;\n                attribs[7].value = samples;\n            }\n            else {\n                attribs[6].value = EGL_FALSE;\n                attribs[7].value = 0;\n            }\n        }",
    "#if !defined(M3G_TARGET_ANDROID)\n        if (bitmapHandle) {\n            if (samples > 1) {\n                attribs[7].value = 1;\n                attribs[8].value = samples;\n            }\n            else {\n                attribs[7].value = EGL_FALSE;\n                attribs[8].value = 0;\n            }\n        } else\n#endif\n        {\n            if (samples > 1) {\n                attribs[6].value = 1;\n                attribs[7].value = samples;\n            }\n            else {\n                attribs[6].value = EGL_FALSE;\n                attribs[7].value = 0;\n            }\n        }",
)

# Pointer-safe EGL conversions and logs.
for old, new in [
    ("M3G_LOG1(M3G_LOG_OBJECTS, \"Destroyed GL context 0x%08X\\n\",\n             (unsigned) ctx);", "M3G_LOG1(M3G_LOG_OBJECTS, \"Destroyed GL context %p\\n\", (void *) ctx);"),
    ("(NativeWindowType) wnd", "(NativeWindowType) (uintptr_t) wnd"),
    ("M3G_LOG1(M3G_LOG_OBJECTS, \"New GL window surface 0x%08X\\n\",\n                 (unsigned) surf);", "M3G_LOG1(M3G_LOG_OBJECTS, \"New GL window surface %p\\n\", (void *) surf);"),
    ("(NativePixmapType) bmp", "(NativePixmapType) (uintptr_t) bmp"),
    ("M3G_LOG1(M3G_LOG_OBJECTS, \"New GL pixmap surface 0x%08X\\n\",\n                 (unsigned) surf);", "M3G_LOG1(M3G_LOG_OBJECTS, \"New GL pixmap surface %p\\n\", (void *) surf);"),
    ("M3G_LOG1(M3G_LOG_OBJECTS, \"New GL pbuffer surface 0x%08X\\n\",\n                 (unsigned) surf);", "M3G_LOG1(M3G_LOG_OBJECTS, \"New GL pbuffer surface %p\\n\", (void *) surf);"),
    ("M3G_LOG1(M3G_LOG_OBJECTS, \"Destroyed GL surface 0x%08X\\n\",\n             (unsigned) surface);", "M3G_LOG1(M3G_LOG_OBJECTS, \"Destroyed GL surface %p\\n\", (void *) surface);"),
]:
    replace("app/src/main/cpp/m3g/src/m3g_rendercontext.inl", old, new)

replace("app/src/main/cpp/m3g/src/m3g_rendercontext.inl", "             -1 << 16, 1 << 16);", "             -(1 << 16), 1 << 16);")
replace(
    "app/src/main/cpp/m3g/src/m3g_rendercontext.inl",
    "    M3GPixelFormat format;\n    M3Gint width, height, pixels;",
    "    M3GPixelFormat format;\n    M3Gint width, height;\n    void *pixels;",
)
replace("app/src/main/cpp/m3g/src/m3g_rendercontext.inl", "M3G_LOG1(M3G_LOG_RENDERING, \"Binding bitmap 0x%08X\\n\", (unsigned) hBitmap);", "M3G_LOG1(M3G_LOG_RENDERING, \"Binding bitmap %p\\n\", (void *)(uintptr_t)hBitmap);")
replace("app/src/main/cpp/m3g/src/m3g_rendercontext.inl", "    ctx->target.pixels = (void*)pixels;", "    ctx->target.pixels = pixels;")
replace("app/src/main/cpp/m3g/src/m3g_rendercontext.inl", "    M3G_LOG1(M3G_LOG_RENDERING, \"Binding EGL surface 0x%08X\\n\", (unsigned) surface);", "    M3G_LOG1(M3G_LOG_RENDERING, \"Binding EGL surface %p\\n\", (void *)(uintptr_t)surface);")
replace("app/src/main/cpp/m3g/src/m3g_rendercontext.inl", "        EGLSurface surf = (EGLSurface) surface;", "        EGLSurface surf = (EGLSurface) (uintptr_t) surface;")
replace("app/src/main/cpp/m3g/src/m3g_rendercontext.inl", "    M3G_LOG1(M3G_LOG_RENDERING, \"Binding memory buffer 0x%08X\\n\",\n             (unsigned) pixels);", "    M3G_LOG1(M3G_LOG_RENDERING, \"Binding memory buffer %p\\n\", pixels);")
replace("app/src/main/cpp/m3g/src/m3g_rendercontext.inl", "    M3G_LOG1(M3G_LOG_RENDERING, \"Binding window 0x%08X\\n\", (unsigned) hWindow);", "    M3G_LOG1(M3G_LOG_RENDERING, \"Binding window %p\\n\",\n             (void *)(uintptr_t)hWindow);")
replace("app/src/main/cpp/m3g/src/m3g_rendercontext.inl", "    M3G_LOG1(M3G_LOG_RENDERING, \"Invalidating bitmap 0x%08X\\n\",\n             (unsigned) hBitmap);", "    M3G_LOG1(M3G_LOG_RENDERING, \"Invalidating bitmap %p\\n\",\n             (void *)(uintptr_t)hBitmap);")
replace("app/src/main/cpp/m3g/src/m3g_rendercontext.inl", "    M3G_LOG1(M3G_LOG_RENDERING, \"Invalidating window 0x%08X\\n\",\n             (unsigned) hWindow);", "    M3G_LOG1(M3G_LOG_RENDERING, \"Invalidating window %p\\n\",\n             (void *)(uintptr_t)hWindow);")
replace("app/src/main/cpp/m3g/src/m3g_rendercontext.inl", "    M3G_LOG1(M3G_LOG_RENDERING, \"Invalidating memory target 0x%08X\\n\",\n             (unsigned) pixels);", "    M3G_LOG1(M3G_LOG_RENDERING, \"Invalidating memory target %p\\n\", pixels);")

# Remaining render-context signed shifts and pointer diagnostics.
replace("app/src/main/cpp/m3g/src/m3g_rendercontext.c", "                 -1 << 16, 1 << 16);", "                 -(1 << 16), 1 << 16);")
replace("app/src/main/cpp/m3g/src/m3g_rendercontext.c", "        glOrthox(0, w << 16, 0, h << 16, -1 << 16, 1 << 16);", "        glOrthox(0, w << 16, 0, h << 16, -(1 << 16), 1 << 16);")
for old, new in [
    ("M3G_LOG1(M3G_LOG_RENDERING, \"Binding image target 0x%08X\\n\",\n             (unsigned) img);", "M3G_LOG1(M3G_LOG_RENDERING, \"Binding image target %p\\n\", (void *) img);"),
    ("M3G_LOG1(M3G_LOG_STAGES, \"Rendering World 0x%08X\\n\", (unsigned) world);", "M3G_LOG1(M3G_LOG_STAGES, \"Rendering World %p\\n\", (void *) world);"),
    ("M3G_LOG1(M3G_LOG_STAGES, \"Rendering Node 0x%08X\\n\", (unsigned) node);", "M3G_LOG1(M3G_LOG_STAGES, \"Rendering Node %p\\n\", (void *) node);"),
    ("M3G_LOG1(M3G_LOG_STAGES, \"Rendering vertex buffer 0x%08X\\n\",\n             (unsigned) vb);", "M3G_LOG1(M3G_LOG_STAGES, \"Rendering vertex buffer %p\\n\",\n             (const void *) vb);"),
]:
    replace("app/src/main/cpp/m3g/src/m3g_rendercontext.c", old, new)

# Integer M3Gulong handle slots use zero as the null value.
for old, new in [
    ("mesh->appearances[i] != NULL", "mesh->appearances[i] != 0"),
    ("mesh->appearances[i] == NULL", "mesh->appearances[i] == 0"),
    ("mesh->indexBuffers[i] == NULL", "mesh->indexBuffers[i] == 0"),
    ("hTriangles[i] == NULL", "hTriangles[i] == 0"),
    ("mesh->indexBuffers[i] != NULL", "mesh->indexBuffers[i] != 0"),
]:
    p = "app/src/main/cpp/m3g/src/m3g_mesh.c"
    text = Path(p).read_text()
    if old not in text:
        raise SystemExit(f"{p}: no match for {old}")
    Path(p).write_text(text.replace(old, new))
for old, new in [
    ("mmesh->targets[i] != NULL", "mmesh->targets[i] != 0"),
    ("hTargets[i] == NULL", "hTargets[i] == 0"),
]:
    p = "app/src/main/cpp/m3g/src/m3g_morphingmesh.c"
    text = Path(p).read_text()
    if old not in text:
        raise SystemExit(f"{p}: no match for {old}")
    Path(p).write_text(text.replace(old, new))

# JSR-184 VertexArray constructors only admit byte/short component storage;
# the validation above has already established that domain.
replace(
    "app/src/main/cpp/m3g/src/m3g_vertexarray.c",
    "        switch (type) {\n        case M3G_BYTE:\n            /* always padded to 4 bytes */\n            array->stride = 4;\n            break;\n        case M3G_SHORT:\n            array->stride = size * sizeof(M3Gshort);\n            break;\n        }",
    "        if (type == M3G_BYTE) {\n            /* always padded to 4 bytes */\n            array->stride = 4;\n        }\n        else {\n            M3G_ASSERT(type == M3G_SHORT);\n            array->stride = size * sizeof(M3Gshort);\n        }",
)

# Pointer diagnostics that are logging only: preserve full pointer values.
replace("app/src/main/cpp/m3g/inc/m3g_camera.h", "                 (unsigned) camera);", "                 (const void *) camera);")
replace("app/src/main/cpp/m3g/inc/m3g_camera.h", "\"Warning: Invalid projection for camera 0x%08X\\n\"", "\"Warning: Invalid projection for camera %p\\n\"")

replace("app/src/main/cpp/m3g/src/m3g_group.c", "M3G_LOG1(M3G_LOG_STAGES, \"Picking group 0x%08X\\n\", (unsigned) group);", "M3G_LOG1(M3G_LOG_STAGES, \"Picking group %p\\n\", (void *) group);")
replace("app/src/main/cpp/m3g/src/m3g_group.c", "M3G_LOG2(M3G_LOG_STAGES, \"Picking group 0x%08X via camera 0x%08X\\n\",\n             (unsigned) group, (unsigned) hCamera);", "M3G_LOG2(M3G_LOG_STAGES, \"Picking group %p via camera %p\\n\",\n             (void *) group, (void *) hCamera);")

for old, new in [
    ("M3G_LOG1(M3G_LOG_IMAGES, \"Freeing copy of image 0x%08X\\n\",\n             (unsigned) img);", "M3G_LOG1(M3G_LOG_IMAGES, \"Freeing copy of image %p\\n\", (void *) img);"),
    ("M3G_LOG3(M3G_LOG_IMAGES, \"Image 0x%08X is %d x %d\",\n                 (unsigned) img, width, height);", "M3G_LOG3(M3G_LOG_IMAGES, \"Image %p is %d x %d\",\n                 (void *) img, width, height);"),
    ("M3G_LOG1(M3G_LOG_IMAGES, \"Image 0x%08X made immutable\\n\",\n             (unsigned) image);", "M3G_LOG1(M3G_LOG_IMAGES, \"Image %p made immutable\\n\", (void *) image);"),
]:
    replace("app/src/main/cpp/m3g/src/m3g_image.c", old, new)

for old, new in [
    ("\"Alloc 0x%08X, %d bytes (%s, line %d)\\n\",\n             (unsigned) ptr, bytes, file, line", "\"Alloc %p, %d bytes (%s, line %d)\\n\",\n             ptr, bytes, file, line"),
    ("M3G_LOG2(M3G_LOG_MEMORY_BLOCKS, \"Alloc 0x%08X, %d bytes\\n\",\n             (unsigned) ptr, bytes);", "M3G_LOG2(M3G_LOG_MEMORY_BLOCKS, \"Alloc %p, %d bytes\\n\", ptr, bytes);"),
    ("\"Free 0x%08X, %d bytes (%s, line %d)\\n\",\n                     (unsigned) ptr, PAYLOAD_SIZE(ptr), file, line", "\"Free %p, %d bytes (%s, line %d)\\n\",\n                     ptr, PAYLOAD_SIZE(ptr), file, line"),
    ("\"Free 0x%08X (%s, line %d)\\n\",\n                     (unsigned) ptr, file, line", "\"Free %p (%s, line %d)\\n\",\n                     ptr, file, line"),
    ("M3G_LOG1(M3G_LOG_MEMORY_BLOCKS, \"Free 0x%08X\\n\", (unsigned) ptr);", "M3G_LOG1(M3G_LOG_MEMORY_BLOCKS, \"Free %p\\n\", ptr);"),
    ("M3G_LOG2(M3G_LOG_MEMORY_MAPPING, \"MapObj 0x%08X -> 0x%08X\\n\",\n                 (unsigned) handle, (unsigned) ptr);", "M3G_LOG2(M3G_LOG_MEMORY_MAPPING, \"MapObj 0x%llX -> %p\\n\",\n                 (unsigned long long)handle, ptr);"),
    ("M3G_LOG1(M3G_LOG_INTERFACE, \"New interface 0x%08X\\n\", (unsigned) m3g);", "M3G_LOG1(M3G_LOG_INTERFACE, \"New interface %p\\n\", (void *) m3g);"),
    ("\"Interface 0x%08X initialized\\n\", (unsigned) m3g", "\"Interface %p initialized\\n\", (void *) m3g"),
    ("\"Shutting down interface 0x%08X...\\n\", (unsigned) m3g", "\"Shutting down interface %p...\\n\", (void *) m3g"),
    ("\"Interface 0x%08X destroyed\\n\", (unsigned) m3g", "\"Interface %p destroyed\\n\", (void *) m3g"),
]:
    replace("app/src/main/cpp/m3g/src/m3g_interface.c", old, new)

# Object lifecycle/stage logging.
for old, new in [
    ("M3G_LOG2(M3G_LOG_OBJECTS, \"New %s 0x%08X\\n\",\n             m3gClassName((M3GClass) obj->classID),\n             (unsigned) obj);", "M3G_LOG2(M3G_LOG_OBJECTS, \"New %s %p\\n\",\n             m3gClassName((M3GClass) obj->classID),\n             (void *) obj);"),
    ("M3G_LOG2(M3G_LOG_OBJECTS, \"Destroyed %s 0x%08X\\n\",\n             m3gClassName((M3GClass) obj->classID),\n             (unsigned) obj);", "M3G_LOG2(M3G_LOG_OBJECTS, \"Destroyed %s %p\\n\",\n             m3gClassName((M3GClass) obj->classID),\n             (void *) obj);"),
    ("\"Deleting %s 0x%08X\\n\",\n                     m3gClassName((M3GClass) obj->classID),\n                     (unsigned) obj", "\"Deleting %s %p\\n\",\n                     m3gClassName((M3GClass) obj->classID),\n                     (void *) obj"),
    ("\"Adding ref to 0x%08X (%s), new count %u\\n\",\n             (unsigned) obj,", "\"Adding ref to %p (%s), new count %u\\n\",\n             (void *) obj,"),
    ("\"Deleting ref to 0x%08X (%s), new count %u\\n\",\n             (unsigned) obj,", "\"Deleting ref to %p (%s), new count %u\\n\",\n             (void *) obj,"),
    ("m3gClassName((M3GClass) obj->classID), (unsigned) obj);", "m3gClassName((M3GClass) obj->classID), (void *) obj);"),
    ("\"Duplicating %s 0x%08X\\n\",\n             m3gClassName((M3GClass) obj->classID), (unsigned) obj", "\"Duplicating %s %p\\n\",\n             m3gClassName((M3GClass) obj->classID), (const void *) obj"),
    ("M3G_LOG3(M3G_LOG_STAGES, \"Finding ID 0x%08X (%d) in 0x%08X\\n\",\n             (unsigned) userID, userID, (unsigned) obj);", "M3G_LOG3(M3G_LOG_STAGES, \"Finding ID 0x%08X (%d) in %p\\n\",\n             (unsigned) userID, userID, (void *) obj);"),
]:
    replace("app/src/main/cpp/m3g/src/m3g_object.c", old, new)

print("M3G warning-zero source edits staged successfully")
