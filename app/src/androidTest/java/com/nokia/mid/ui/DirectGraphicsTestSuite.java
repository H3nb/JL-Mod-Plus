/* SPDX-License-Identifier: Apache-2.0 */
package com.nokia.mid.ui;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

import javax.microedition.lcdui.GraphicsBitmapRebindTest;
import javax.microedition.lcdui.GraphicsCanvasStateTest;

/** Explicit suite also includes Java ME packages excluded from runner discovery. */
@RunWith(Suite.class)
@Suite.SuiteClasses({DirectGraphicsRenderingTest.class,
		GraphicsCanvasStateTest.class, GraphicsBitmapRebindTest.class})
public class DirectGraphicsTestSuite {
}
