/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.playsoftware.j2meloader.crashes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class NativeTombstoneSummaryTest {
	@Test
	public void extractsActionableNativeCrashSummary() {
		byte[] fixture = tombstoneFixture(false);

		NativeTombstoneSummary.Summary summary = NativeTombstoneSummary.parse(fixture);
		String text = NativeTombstoneSummary.format(summary);

		assertNotNull(summary);
		assertEquals(1, summary.architecture);
		assertEquals(16490, summary.pid);
		assertEquals(19119, summary.tid);
		assertEquals(11, summary.signalNumber);
		assertEquals("SIGSEGV", summary.signalName);
		assertEquals(1, summary.signalCode);
		assertEquals("SEGV_MAPERR", summary.signalCodeName);
		assertTrue(summary.hasFaultAddress);
		assertEquals(0, summary.faultAddress);
		assertEquals("null pointer dereference", summary.cause);
		assertEquals("MIDletEventQueu", summary.threadName);
		assertEquals(2, summary.threadCount);
		assertEquals(15, summary.frameCount);

		assertNotNull(text);
		assertTrue(text.contains("Architecture: ARM64"));
		assertTrue(text.contains("Signal: SIGSEGV (11)"));
		assertTrue(text.contains("Signal code: SEGV_MAPERR (1)"));
		assertTrue(text.contains("Fault address: 0x0"));
		assertTrue(text.contains("#00 SkCanvas::~SkCanvas()"));
		assertTrue(text.contains("/system/lib64/libhwui.so"));
		assertTrue(text.contains("#05 android.graphics.Canvas.setBitmap"));
		assertTrue(text.contains("#10 javax.microedition.lcdui.Graphics.reset"));
		assertTrue(text.contains("#14 javax.microedition.lcdui.Canvas$PaintEvent.process"));
		assertTrue(text.indexOf("#05 android.graphics.Canvas.setBitmap")
				< text.indexOf("#10 javax.microedition.lcdui.Graphics.reset"));
		assertTrue(text.indexOf("#10 javax.microedition.lcdui.Graphics.reset")
				< text.indexOf("#14 javax.microedition.lcdui.Canvas$PaintEvent.process"));
		assertFalse(text.contains("#06 art_quick_invoke_stub"));
	}

	@Test
	public void unknownFieldsAreSkipped() {
		NativeTombstoneSummary.Summary summary = NativeTombstoneSummary.parse(tombstoneFixture(false));

		assertNotNull(summary);
		assertEquals("SIGSEGV", summary.signalName);
		assertEquals("MIDletEventQueu", summary.threadName);
		assertFalse(summary.partial);
	}

	@Test
	public void truncatedTailKeepsParsedCrashEvidence() {
		NativeTombstoneSummary.Summary summary = NativeTombstoneSummary.parse(tombstoneFixture(true));
		String text = NativeTombstoneSummary.format(summary);

		assertNotNull(summary);
		assertTrue(summary.partial);
		assertNotNull(text);
		assertTrue(text.contains("javax.microedition.lcdui.Graphics.reset"));
		assertTrue(text.contains("structured summary is partial"));
	}

	@Test
	public void malformedOrOversizedInputFailsOpen() {
		assertNull(NativeTombstoneSummary.parse(new byte[] {(byte) 0x80}));
		assertNull(NativeTombstoneSummary.parse(
				new byte[NativeTombstoneSummary.MAX_INPUT_BYTES + 1]));
	}

	private static byte[] tombstoneFixture(boolean truncateTail) {
		Proto tombstone = new Proto();
		tombstone.varint(1, 1); // ARM64
		tombstone.varint(5, 16490);
		tombstone.varint(6, 19119);

		Proto signal = new Proto();
		signal.varint(1, 11);
		signal.string(2, "SIGSEGV");
		signal.varint(3, 1);
		signal.string(4, "SEGV_MAPERR");
		signal.varint(8, 1); // has_fault_address; zero address is omitted by proto3
		tombstone.message(10, signal.bytes());

		Proto cause = new Proto();
		cause.string(1, "null pointer dereference");
		tombstone.message(15, cause.bytes());
		tombstone.varint(22, 16384); // newer/unknown top-level field

		tombstone.message(16, threadEntry(19119, crashThread()));
		tombstone.message(16, threadEntry(19130, otherThread()));

		byte[] complete = tombstone.bytes();
		if (!truncateTail) return complete;

		Proto invalidTail = new Proto();
		invalidTail.tag(30, 2);
		invalidTail.rawVarint(8);
		invalidTail.raw(new byte[] {1, 2});
		byte[] tail = invalidTail.bytes();
		byte[] combined = Arrays.copyOf(complete, complete.length + tail.length);
		System.arraycopy(tail, 0, combined, complete.length, tail.length);
		return combined;
	}

	private static byte[] threadEntry(int tid, byte[] thread) {
		Proto entry = new Proto();
		entry.varint(1, tid);
		entry.message(2, thread);
		return entry.bytes();
	}

	private static byte[] crashThread() {
		Proto thread = new Proto();
		thread.varint(1, 19119);
		thread.string(2, "MIDletEventQueu");
		thread.string(7, "unknown thread note field");
		String[] frames = {
				"SkCanvas::~SkCanvas()",
				"SkCanvas::~SkCanvas()",
				"android::SkiaCanvas::setBitmap(SkBitmap const&)",
				"android::CanvasJNI::setBitmap(_JNIEnv*, _jobject*, long, long)",
				"art_jni_trampoline",
				"android.graphics.Canvas.setBitmap",
				"art_quick_invoke_stub",
				"interpreter DoCall",
				"ExecuteSwitchImplCpp",
				"ExecuteSwitchImplAsm",
				"javax.microedition.lcdui.Graphics.reset",
				"DoCall",
				"ExecuteSwitchImplCpp",
				"ExecuteSwitchImplAsm",
				"javax.microedition.lcdui.Canvas$PaintEvent.process"
		};
		for (int i = 0; i < frames.length; i++) {
			Proto frame = new Proto();
			frame.string(4, frames[i]);
			frame.string(6, i < 4 ? "/system/lib64/libhwui.so" : "[anon:dalvik-jit-code-cache]");
			frame.varint(99, i); // OEM/unknown frame field
			thread.message(4, frame.bytes());
		}
		return thread.bytes();
	}

	private static byte[] otherThread() {
		Proto thread = new Proto();
		thread.varint(1, 19130);
		thread.string(2, "Thread-7");
		Proto frame = new Proto();
		frame.string(4, "javax.microedition.lcdui.event.EventQueue.serviceRepaints");
		thread.message(4, frame.bytes());
		return thread.bytes();
	}

	private static final class Proto {
		private final ByteArrayOutputStream out = new ByteArrayOutputStream();

		void varint(int field, long value) {
			tag(field, 0);
			rawVarint(value);
		}

		void string(int field, String value) {
			message(field, value.getBytes(StandardCharsets.UTF_8));
		}

		void message(int field, byte[] value) {
			tag(field, 2);
			rawVarint(value.length);
			raw(value);
		}

		void tag(int field, int wire) {
			rawVarint(((long) field << 3) | wire);
		}

		void rawVarint(long value) {
			while ((value & ~0x7fL) != 0) {
				out.write(((int) value & 0x7f) | 0x80);
				value >>>= 7;
			}
			out.write((int) value);
		}

		void raw(byte[] value) {
			out.write(value, 0, value.length);
		}

		byte[] bytes() {
			return out.toByteArray();
		}
	}
}
