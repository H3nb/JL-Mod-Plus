/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package javax.microedition.shell

import android.graphics.Bitmap
import io.github.h3nb.jlmodplus.config.Config
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.microedition.lcdui.Canvas
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Copies the canvas without blocking the UI or doing pixel conversion on the GL thread. */
object ScreenshotCapture {
    fun interface Callback<T> {
        fun accept(value: T)
    }

    @JvmStatic
    fun save(canvas: Canvas, completed: Callback<String>, failed: Callback<Throwable>): Job =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            val savedPath = try {
                val bitmap = capture(canvas)
                withContext(Dispatchers.IO) { saveBitmap(bitmap) }
            } catch (error: Exception) {
                failed.accept(error)
                return@launch
            }
            completed.accept(savedPath)
        }

    private suspend fun capture(canvas: Canvas): Bitmap {
        if (!canvas.needsGlScreenshot()) {
            return withContext(Dispatchers.Default) { canvas.captureSoftwareScreenshot() }
        }
        return withContext(Dispatchers.Default) {
            val frame = withTimeout(3_000L) {
                suspendCancellableCoroutine<GlPixels> { continuation ->
                    try {
                        canvas.captureGlScreenshot { pixels, width, height, error ->
                            when {
                                error != null -> continuation.resumeWithException(error)
                                pixels == null -> continuation.resumeWithException(
                                    IOException("GL screenshot returned no pixels"),
                                )
                                else -> continuation.resume(GlPixels(pixels, width, height))
                            }
                        }
                    } catch (error: Exception) {
                        continuation.resumeWithException(error)
                    }
                }
            }
            val pixels = frame.pixels
            for (index in pixels.indices) {
                val pixel = pixels[index]
                pixels[index] = (pixel and 0xff00ff00.toInt()) or
                    ((pixel and 0x00ff0000) ushr 16) or
                    ((pixel and 0x000000ff) shl 16)
            }
            Bitmap.createBitmap(
                pixels,
                frame.width * (frame.height - 1),
                -frame.width,
                frame.width,
                frame.height,
                Bitmap.Config.ARGB_8888,
            )
        }
    }

    private fun saveBitmap(bitmap: Bitmap): String {
        val name = "Screenshot_" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
            .format(Date()) + ".png"
        val directory = File(Config.SCREENSHOTS_DIR)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Can't create directory: $directory")
        }
        val file = File(directory, name)
        BufferedOutputStream(FileOutputStream(file)).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        return file.absolutePath
    }

    private data class GlPixels(val pixels: IntArray, val width: Int, val height: Int)
}
