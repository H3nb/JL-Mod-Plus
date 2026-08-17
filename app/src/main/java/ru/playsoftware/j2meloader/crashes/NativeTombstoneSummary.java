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

import android.content.Context;
import android.net.Uri;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Reads only the small, stable subset of Android's tombstone protobuf needed for a concise report.
 * Unknown fields are skipped and malformed evidence fails open; raw tombstones remain authoritative.
 */
final class NativeTombstoneSummary {
	static final int MAX_INPUT_BYTES = ProcessExitStore.MAX_TRACE_BYTES;
	private static final int MAX_STRING_BYTES = 2048;
	private static final int MAX_THREAD_ENTRIES = 512;
	private static final int MAX_STORED_FRAMES = 128;
	private static final int MAX_RELEVANT_FRAMES = 12;
	private static final int LEADING_FRAMES = 6;
	private static final int MIN_RELEVANT_FRAMES = 8;

	private NativeTombstoneSummary() {}

	static String summarize(Context context, Uri traceUri) {
		if (context == null || traceUri == null) {
			return null;
		}
		try (InputStream input = context.getContentResolver().openInputStream(traceUri)) {
			if (input == null) {
				return null;
			}
			byte[] data = new byte[MAX_INPUT_BYTES + 1];
			int size = readBounded(input, data);
			if (size < 0) {
				return null;
			}
			return format(parse(data, size));
		} catch (IOException | RuntimeException ignored) {
			return null;
		}
	}

	static Summary parse(byte[] data) {
		if (data == null) {
			return null;
		}
		return parse(data, data.length);
	}

	private static Summary parse(byte[] data, int size) {
		if (size <= 0 || size > MAX_INPUT_BYTES) {
			return null;
		}
		Summary summary = new Summary();
		ArrayList<ThreadEntry> threadEntries = new ArrayList<>();
		Reader reader = new Reader(data, 0, size);
		try {
			while (reader.hasRemaining()) {
				int tag = reader.readTag();
				int field = tag >>> 3;
				int wire = tag & 7;
				switch (field) {
					case 1 -> {
						if (wire == 0) summary.architecture = (int) reader.readVarint();
						else reader.skipField(wire);
					}
					case 5 -> {
						if (wire == 0) summary.pid = (int) reader.readVarint();
						else reader.skipField(wire);
					}
					case 6 -> {
						if (wire == 0) summary.tid = (int) reader.readVarint();
						else reader.skipField(wire);
					}
					case 10 -> {
						if (wire == 2) {
							try {
								parseSignal(reader.readSlice().reader(), summary);
							} catch (ParseException e) {
								summary.partial = true;
							}
						} else {
							reader.skipField(wire);
						}
					}
					case 15 -> {
						if (wire == 2) {
							try {
								parseCause(reader.readSlice().reader(), summary);
							} catch (ParseException e) {
								summary.partial = true;
							}
						} else {
							reader.skipField(wire);
						}
					}
					case 16 -> {
						if (wire == 2) {
							summary.threadCount++;
							Slice entrySlice = reader.readSlice();
							if (threadEntries.size() < MAX_THREAD_ENTRIES) {
								try {
									ThreadEntry entry = parseThreadEntry(entrySlice.reader());
									if (entry != null && entry.thread != null) threadEntries.add(entry);
								} catch (ParseException e) {
									summary.partial = true;
								}
							}
						} else {
							reader.skipField(wire);
						}
					}
					default -> reader.skipField(wire);
				}
			}
		} catch (ParseException e) {
			summary.partial = true;
		}

		ThreadEntry crashingThread = findCrashingThread(threadEntries, summary.tid, summary);
		if (crashingThread != null) {
			try {
				parseCrashingThread(crashingThread.thread.reader(), summary);
			} catch (ParseException e) {
				summary.partial = true;
			}
		}
		return summary.hasUsefulData() ? summary : null;
	}

	static String format(Summary summary) {
		if (summary == null || !summary.hasUsefulData()) {
			return null;
		}
		StringBuilder text = new StringBuilder("Native crash details\n");
		appendLine(text, "Architecture", architectureName(summary.architecture));
		appendLine(text, "Signal", signalLabel(summary));
		appendLine(text, "Signal code", signalCodeLabel(summary));
		appendLine(text, "Cause", summary.cause);
		if (summary.hasFaultAddress) {
			appendLine(text, "Fault address", "0x" + Long.toUnsignedString(summary.faultAddress, 16));
		}
		appendLine(text, "Crashing thread", summary.threadName);
		if (summary.tid >= 0) {
			appendLine(text, "Thread ID", Integer.toString(summary.tid));
		}
		if (summary.threadCount > 0) {
			appendLine(text, "Threads captured", Integer.toString(summary.threadCount));
		}
		if (summary.frameCount > 0) {
			appendLine(text, "Crashing-thread frames", Integer.toString(summary.frameCount));
		}
		Frame topProject = topProjectFrame(summary.frames);
		if (topProject != null) {
			appendLine(text, "Top JL-Mod frame", topProject.functionName);
		}
		List<Frame> relevant = relevantFrames(summary.frames);
		if (!relevant.isEmpty()) {
			text.append("\nRelevant backtrace:\n");
			for (Frame frame : relevant) {
				appendFrame(text, frame);
			}
		}
		if (summary.partial) {
			text.append("Evidence note: structured summary is partial; raw tombstone is retained.\n");
		}
		return text.toString().trim();
	}

	private static int readBounded(InputStream input, byte[] target) throws IOException {
		int total = 0;
		while (total < target.length) {
			int count = input.read(target, total, target.length - total);
			if (count < 0) {
				break;
			}
			if (count == 0) {
				int next = input.read();
				if (next < 0) break;
				target[total++] = (byte) next;
				continue;
			}
			total += count;
		}
		return total > MAX_INPUT_BYTES ? -1 : total;
	}

	private static void parseSignal(Reader reader, Summary summary) throws ParseException {
		while (reader.hasRemaining()) {
			int tag = reader.readTag();
			int field = tag >>> 3;
			int wire = tag & 7;
			switch (field) {
				case 1 -> {
					if (wire == 0) summary.signalNumber = (int) reader.readVarint();
					else reader.skipField(wire);
				}
				case 2 -> {
					if (wire == 2) summary.signalName = reader.readString(MAX_STRING_BYTES);
					else reader.skipField(wire);
				}
				case 3 -> {
					if (wire == 0) summary.signalCode = (int) reader.readVarint();
					else reader.skipField(wire);
				}
				case 4 -> {
					if (wire == 2) summary.signalCodeName = reader.readString(MAX_STRING_BYTES);
					else reader.skipField(wire);
				}
				case 8 -> {
					if (wire == 0) summary.hasFaultAddress = reader.readVarint() != 0;
					else reader.skipField(wire);
				}
				case 9 -> {
					if (wire == 0) {
						summary.faultAddress = reader.readVarint();
						summary.hasFaultAddress = true;
					} else reader.skipField(wire);
				}
				default -> reader.skipField(wire);
			}
		}
	}

	private static void parseCause(Reader reader, Summary summary) throws ParseException {
		while (reader.hasRemaining()) {
			int tag = reader.readTag();
			int field = tag >>> 3;
			int wire = tag & 7;
			if (field == 1 && wire == 2) {
				String cause = reader.readString(MAX_STRING_BYTES);
				if (summary.cause == null && cause != null && !cause.isEmpty()) summary.cause = cause;
			} else {
				reader.skipField(wire);
			}
		}
	}

	private static ThreadEntry parseThreadEntry(Reader reader) throws ParseException {
		long key = -1;
		Slice thread = null;
		while (reader.hasRemaining()) {
			int tag = reader.readTag();
			int field = tag >>> 3;
			int wire = tag & 7;
			if (field == 1 && wire == 0) {
				key = reader.readVarint();
			} else if (field == 2 && wire == 2) {
				thread = reader.readSlice();
			} else {
				reader.skipField(wire);
			}
		}
		return thread == null ? null : new ThreadEntry(key, thread);
	}

	private static ThreadEntry findCrashingThread(List<ThreadEntry> entries, int tid, Summary summary) {
		if (tid < 0) return null;
		for (ThreadEntry entry : entries) {
			if (entry.key == Integer.toUnsignedLong(tid)) return entry;
		}
		for (ThreadEntry entry : entries) {
			try {
				if (readThreadId(entry.thread.reader()) == tid) return entry;
			} catch (ParseException e) {
				summary.partial = true;
			}
		}
		return null;
	}

	private static int readThreadId(Reader reader) throws ParseException {
		while (reader.hasRemaining()) {
			int tag = reader.readTag();
			int field = tag >>> 3;
			int wire = tag & 7;
			if (field == 1 && wire == 0) return (int) reader.readVarint();
			reader.skipField(wire);
		}
		return -1;
	}

	private static void parseCrashingThread(Reader reader, Summary summary) throws ParseException {
		int frameIndex = 0;
		while (reader.hasRemaining()) {
			int tag = reader.readTag();
			int field = tag >>> 3;
			int wire = tag & 7;
			switch (field) {
				case 1 -> {
					if (wire == 0) {
						int threadId = (int) reader.readVarint();
						if (summary.tid < 0) summary.tid = threadId;
					} else reader.skipField(wire);
				}
				case 2 -> {
					if (wire == 2) summary.threadName = reader.readString(MAX_STRING_BYTES);
					else reader.skipField(wire);
				}
				case 4 -> {
					if (wire == 2) {
						Slice frameSlice = reader.readSlice();
						summary.frameCount++;
						if (summary.frames.size() < MAX_STORED_FRAMES) {
							try {
								summary.frames.add(parseFrame(frameSlice.reader(), frameIndex));
							} catch (ParseException e) {
								summary.partial = true;
							}
						}
						frameIndex++;
					} else reader.skipField(wire);
				}
				default -> reader.skipField(wire);
			}
		}
	}

	private static Frame parseFrame(Reader reader, int index) throws ParseException {
		String functionName = null;
		String fileName = null;
		while (reader.hasRemaining()) {
			int tag = reader.readTag();
			int field = tag >>> 3;
			int wire = tag & 7;
			switch (field) {
				case 4 -> {
					if (wire == 2) functionName = reader.readString(MAX_STRING_BYTES);
					else reader.skipField(wire);
				}
				case 6 -> {
					if (wire == 2) fileName = reader.readString(MAX_STRING_BYTES);
					else reader.skipField(wire);
				}
				default -> reader.skipField(wire);
			}
		}
		return new Frame(index, functionName, fileName);
	}

	private static List<Frame> relevantFrames(List<Frame> frames) {
		if (frames.isEmpty()) return Collections.emptyList();
		LinkedHashMap<Integer, Frame> selected = new LinkedHashMap<>();
		for (int i = 0; i < Math.min(LEADING_FRAMES, frames.size()); i++) {
			Frame frame = frames.get(i);
			selected.put(frame.index, frame);
		}
		for (Frame frame : frames) {
			if (selected.size() >= MAX_RELEVANT_FRAMES) break;
			if (isProjectFrame(frame)) selected.put(frame.index, frame);
		}
		for (Frame frame : frames) {
			if (selected.size() >= MIN_RELEVANT_FRAMES || selected.size() >= MAX_RELEVANT_FRAMES) break;
			selected.put(frame.index, frame);
		}
		ArrayList<Frame> result = new ArrayList<>(selected.values());
		Collections.sort(result, (left, right) -> left.index - right.index);
		return result;
	}

	private static Frame topProjectFrame(List<Frame> frames) {
		for (Frame frame : frames) {
			if (isProjectFrame(frame)) return frame;
		}
		return null;
	}

	private static boolean isProjectFrame(Frame frame) {
		if (frame == null || frame.functionName == null) return false;
		return frame.functionName.startsWith("ru.playsoftware.j2meloader.")
				|| frame.functionName.startsWith("javax.microedition.");
	}

	private static void appendFrame(StringBuilder text, Frame frame) {
		text.append('#');
		if (frame.index < 10) text.append('0');
		text.append(frame.index).append(' ');
		if (frame.functionName != null && !frame.functionName.isEmpty()) {
			text.append(frame.functionName);
		} else if (frame.fileName != null && !frame.fileName.isEmpty()) {
			text.append(frame.fileName);
		} else {
			text.append("<unknown>");
		}
		text.append('\n');
		String fileLabel = frameFileLabel(frame.fileName);
		if (fileLabel != null) text.append("    ").append(fileLabel).append('\n');
	}

	private static String frameFileLabel(String fileName) {
		if (fileName == null || fileName.isEmpty() || fileName.charAt(0) == '[') return null;
		if (fileName.startsWith("/system/") || fileName.startsWith("/apex/")
				|| fileName.startsWith("/vendor/")) return fileName;
		int slash = fileName.lastIndexOf('/');
		return slash >= 0 && slash + 1 < fileName.length() ? fileName.substring(slash + 1) : fileName;
	}

	private static void appendLine(StringBuilder text, String label, String value) {
		if (value != null && !value.isEmpty()) text.append(label).append(": ").append(value).append('\n');
	}

	private static String architectureName(int architecture) {
		return switch (architecture) {
			case 0 -> "ARM32";
			case 1 -> "ARM64";
			case 2 -> "X86";
			case 3 -> "X86_64";
			case 4 -> "RISCV64";
			case 5 -> "NONE";
			default -> architecture < 0 ? null : "architecture " + architecture;
		};
	}

	private static String signalLabel(Summary summary) {
		if (summary.signalName != null) {
			return summary.signalNumber == Integer.MIN_VALUE
					? summary.signalName : summary.signalName + " (" + summary.signalNumber + ")";
		}
		return summary.signalNumber == Integer.MIN_VALUE ? null : Integer.toString(summary.signalNumber);
	}

	private static String signalCodeLabel(Summary summary) {
		if (summary.signalCodeName != null) {
			return summary.signalCode == Integer.MIN_VALUE
					? summary.signalCodeName : summary.signalCodeName + " (" + summary.signalCode + ")";
		}
		return summary.signalCode == Integer.MIN_VALUE ? null : Integer.toString(summary.signalCode);
	}

	static final class Summary {
		int architecture = -1;
		int pid = -1;
		int tid = -1;
		int signalNumber = Integer.MIN_VALUE;
		String signalName;
		int signalCode = Integer.MIN_VALUE;
		String signalCodeName;
		boolean hasFaultAddress;
		long faultAddress;
		String cause;
		String threadName;
		int threadCount;
		int frameCount;
		boolean partial;
		final ArrayList<Frame> frames = new ArrayList<>();

		boolean hasUsefulData() {
			return signalNumber != Integer.MIN_VALUE || signalName != null || cause != null
					|| threadName != null || !frames.isEmpty();
		}
	}

	static final class Frame {
		final int index;
		final String functionName;
		final String fileName;

		Frame(int index, String functionName, String fileName) {
			this.index = index;
			this.functionName = functionName;
			this.fileName = fileName;
		}
	}

	private static final class ThreadEntry {
		final long key;
		final Slice thread;

		ThreadEntry(long key, Slice thread) {
			this.key = key;
			this.thread = thread;
		}
	}

	private static final class Slice {
		final byte[] data;
		final int start;
		final int end;

		Slice(byte[] data, int start, int end) {
			this.data = data;
			this.start = start;
			this.end = end;
		}

		Reader reader() {
			return new Reader(data, start, end);
		}
	}

	private static final class Reader {
		private final byte[] data;
		private final int limit;
		private int position;

		Reader(byte[] data, int start, int limit) {
			this.data = data;
			this.position = start;
			this.limit = limit;
		}

		boolean hasRemaining() {
			return position < limit;
		}

		int readTag() throws ParseException {
			long tag = readVarint();
			if (tag <= 0 || tag > Integer.MAX_VALUE) throw new ParseException();
			return (int) tag;
		}

		long readVarint() throws ParseException {
			long value = 0;
			for (int i = 0; i < 10; i++) {
				if (position >= limit) throw new ParseException();
				int next = data[position++] & 0xff;
				if (i == 9 && (next & 0xfe) != 0) throw new ParseException();
				value |= (long) (next & 0x7f) << (i * 7);
				if ((next & 0x80) == 0) return value;
			}
			throw new ParseException();
		}

		Slice readSlice() throws ParseException {
			long lengthValue = readVarint();
			if (lengthValue < 0 || lengthValue > Integer.MAX_VALUE) throw new ParseException();
			int length = (int) lengthValue;
			if (length > limit - position) throw new ParseException();
			int start = position;
			position += length;
			return new Slice(data, start, position);
		}

		String readString(int maxBytes) throws ParseException {
			Slice slice = readSlice();
			int length = slice.end - slice.start;
			if (length == 0 || length > maxBytes) return null;
			String value = new String(data, slice.start, length, StandardCharsets.UTF_8)
					.replace('\u0000', ' ').replace('\r', ' ').replace('\n', ' ').trim();
			return value.isEmpty() ? null : value;
		}

		void skipField(int wireType) throws ParseException {
			switch (wireType) {
				case 0 -> readVarint();
				case 1 -> advance(8);
				case 2 -> readSlice();
				case 5 -> advance(4);
				default -> throw new ParseException();
			}
		}

		private void advance(int count) throws ParseException {
			if (count < 0 || count > limit - position) throw new ParseException();
			position += count;
		}
	}

	private static final class ParseException extends Exception {}
}
