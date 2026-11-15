/*
 * Temporary instrumentation probe for coroutine screenshot migration (PR #247).
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package javax.microedition.shell

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.h3nb.jlmodplus.config.ProfileModel
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.lcdui.Canvas
import javax.microedition.lcdui.Graphics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoroutineScreenshotRuntimeTest {
    @Test fun glPixelsAreConvertedSavedAndReturnedOnTheCorrectThreads() {
        Canvas.setSettings(ProfileModel.fresh())
        Canvas.setScreenshotRawMode(false)
        val bitmap = captureAndRead(ProbeCanvas(gl = true))
        assertEquals(Color.RED, bitmap.getPixel(0, 0))
        assertEquals(Color.YELLOW, bitmap.getPixel(1, 0))
        assertEquals(Color.BLUE, bitmap.getPixel(0, 1))
        assertEquals(Color.GREEN, bitmap.getPixel(1, 1))
    }

    @Test fun softwarePixelsAreSavedOffTheMainThread() {
        Canvas.setSettings(ProfileModel.fresh())
        Canvas.setScreenshotRawMode(false)
        val bitmap = captureAndRead(ProbeCanvas(gl = false))
        assertEquals(Color.MAGENTA, bitmap.getPixel(0, 0))
        assertEquals(Color.CYAN, bitmap.getPixel(1, 0))
    }

    private fun captureAndRead(canvas: Canvas): Bitmap {
        val completed = CountDownLatch(1)
        val path = AtomicReference<String>()
        val error = AtomicReference<Throwable>()
        val callbackOnMain = AtomicReference<Boolean>()
        ScreenshotCapture.save(canvas, {
            callbackOnMain.set(Looper.myLooper() == Looper.getMainLooper())
            path.set(it)
            completed.countDown()
        }, {
            error.set(it)
            completed.countDown()
        })
        assertTrue("Screenshot callback timed out", completed.await(20, TimeUnit.SECONDS))
        error.get()?.let { throw AssertionError("Screenshot failed", it) }
        assertTrue("Completion must run on the UI thread", callbackOnMain.get() == true)
        val saved = File(requireNotNull(path.get()))
        try {
            assertTrue(saved.isFile && saved.length() > 0L)
            return requireNotNull(BitmapFactory.decodeFile(saved.absolutePath))
        } finally {
            saved.delete()
        }
    }

    private class ProbeCanvas(private val gl: Boolean) : Canvas(false) {
        override fun paint(g: Graphics) = Unit
        override fun needsGlScreenshot(): Boolean = gl

        override fun captureGlScreenshot(callback: Canvas.GlScreenshotCallback) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                callback.complete(null, 0, 0, IllegalStateException("GL preparation used the UI thread"))
                return
            }
            Thread {
                // GL_RGBA little-endian pixels, bottom row first.
                callback.complete(
                    intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt(),
                        0xff0000ff.toInt(), 0xff00ffff.toInt()),
                    2, 2, null,
                )
            }.start()
        }

        override fun captureSoftwareScreenshot(): Bitmap {
            check(Looper.myLooper() != Looper.getMainLooper())
            return Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888).also {
                it.setPixel(0, 0, Color.MAGENTA)
                it.setPixel(1, 0, Color.CYAN)
            }
        }
    }
}
