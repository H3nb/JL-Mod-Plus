/* SPDX-License-Identifier: Apache-2.0 */
package com.nokia.mid.ui;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

import javax.microedition.lcdui.GraphicsBitmapRebindTest;
import javax.microedition.lcdui.GraphicsCanvasLifecycleStressTest;
import javax.microedition.lcdui.GraphicsCanvasStateTest;
import javax.microedition.lcdui.GraphicsTest;
import javax.microedition.lcdui.GraphicsTriangleRenderingTest;

/** Explicit suite also includes Java ME packages excluded from runner discovery. */
@RunWith(Suite.class)
@Suite.SuiteClasses({DirectGraphicsRenderingTest.class,
		GraphicsTest.class, GraphicsCanvasStateTest.class,
		GraphicsCanvasLifecycleStressTest.class, GraphicsBitmapRebindTest.class,
		GraphicsTriangleRenderingTest.class})
public class DirectGraphicsTestSuite {
}
