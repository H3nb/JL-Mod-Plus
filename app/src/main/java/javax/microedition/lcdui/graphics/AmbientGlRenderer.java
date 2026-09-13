/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package javax.microedition.lcdui.graphics;

import static android.opengl.GLES20.*;

import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/** Owns the small GLES 2.0 ambient pass and all resources used by the GL thread. */
public final class AmbientGlRenderer {
    private static final String TAG = AmbientGlRenderer.class.getName();
    private static final String VERTEX_SHADER =
            "attribute vec2 a_position;\n"
                    + "attribute vec3 a_color;\n"
                    + "varying mediump vec3 v_color;\n"
                    + "void main() {\n"
                    + "  gl_Position = vec4(a_position, 0.0, 1.0);\n"
                    + "  v_color = a_color;\n"
                    + "}\n";
    private static final String FRAGMENT_SHADER =
            "precision mediump float;\n"
                    + "varying mediump vec3 v_color;\n"
                    + "void main() { gl_FragColor = vec4(v_color, 1.0); }\n";

    private final FloatBuffer vertices = ByteBuffer.allocateDirect(AmbientMesh.MAX_VERTEX_COUNT * 5 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();
    private final ShortBuffer indices = ByteBuffer.allocateDirect(AmbientMesh.MAX_INDEX_COUNT * 2)
            .order(ByteOrder.nativeOrder()).asShortBuffer();
    private final float[] colors = new float[AmbientMesh.MAX_VERTEX_COUNT
            * AmbientColorField.CHANNEL_COUNT];
    private final int[] status = new int[1];
    private int program;
    private int aPosition;
    private int aColor;
    private boolean valid;

    public boolean initialize() {
        release();
        int vertex = compile(GL_VERTEX_SHADER, VERTEX_SHADER);
        int fragment = compile(GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
        if (vertex == 0 || fragment == 0) {
            if (vertex != 0) glDeleteShader(vertex);
            if (fragment != 0) glDeleteShader(fragment);
            return false;
        }
        program = glCreateProgram();
        glAttachShader(program, vertex);
        glAttachShader(program, fragment);
        glLinkProgram(program);
        glGetProgramiv(program, GL_LINK_STATUS, status, 0);
        glDeleteShader(vertex);
        glDeleteShader(fragment);
        if (status[0] == 0) {
            Log.e(TAG, "Ambient program link failed: " + glGetProgramInfoLog(program));
            release();
            return false;
        }
        aPosition = glGetAttribLocation(program, "a_position");
        aColor = glGetAttribLocation(program, "a_color");
        valid = aPosition >= 0 && aColor >= 0;
        if (!valid) release();
        return valid;
    }

    public void draw(AmbientColorField field, AmbientMesh mesh, long nowNs,
            int displayWidth, int displayHeight) {
        if (!valid || field == null || mesh == null || mesh.indexCount() == 0
                || displayWidth <= 0 || displayHeight <= 0) return;
        field.renderNodes(nowNs, colors);
        vertices.clear();
        for (int i = 0; i < mesh.vertexCount(); i++) {
            vertices.put(mesh.vertexClipX(i, displayWidth));
            vertices.put(mesh.vertexClipY(i, displayHeight));
            int colorOffset = i * AmbientColorField.CHANNEL_COUNT;
            vertices.put(colors[colorOffset]);
            vertices.put(colors[colorOffset + 1]);
            vertices.put(colors[colorOffset + 2]);
        }
        vertices.flip();
        indices.clear();
        for (int i = 0; i < mesh.indexCount(); i++) indices.put(mesh.indexAt(i));
        indices.flip();

        glUseProgram(program);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
        glDisable(GL_BLEND);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glEnableVertexAttribArray(aPosition);
        glVertexAttribPointer(aPosition, 2, GL_FLOAT, false, 5 * 4, vertices);
        vertices.position(2);
        glEnableVertexAttribArray(aColor);
        glVertexAttribPointer(aColor, 3, GL_FLOAT, false, 5 * 4, vertices);
        glDrawElements(GL_TRIANGLES, mesh.indexCount(), GL_UNSIGNED_SHORT, indices);
        glDisableVertexAttribArray(aPosition);
        glDisableVertexAttribArray(aColor);
    }

    public void release() {
        if (program != 0) glDeleteProgram(program);
        program = 0;
        valid = false;
    }

    private int compile(int type, String source) {
        int shader = glCreateShader(type);
        if (shader == 0) return 0;
        glShaderSource(shader, source);
        glCompileShader(shader);
        glGetShaderiv(shader, GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            Log.e(TAG, "Ambient shader compile failed: " + glGetShaderInfoLog(shader));
            glDeleteShader(shader);
            return 0;
        }
        return shader;
    }
}
