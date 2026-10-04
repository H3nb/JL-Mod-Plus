// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.mmapi.synth;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Minimal project-owned single-program banks, containing a generated sine wave. */
final class AudioBankFixtures {
    private static final int FRAMES = 256;
    private AudioBankFixtures() { }

    static byte[] dls() {
        byte[] wsmp = concat(ints(20), shorts(60, 0), ints(0, 0, 1, 16, 0, 0, FRAMES));
        byte[] wave = list("wave", chunk("fmt ", concat(shorts(1, 1), ints(22050, 44100), shorts(2, 16))),
                chunk("wsmp", wsmp), chunk("data", samples(0)));
        byte[] region = list("rgn ", chunk("rgnh", shorts(0, 127, 0, 127, 0, 0)),
                chunk("wlnk", concat(shorts(0, 0), ints(1, 0))));
        byte[] instrument = list("ins ", chunk("insh", ints(1, 0, 40)), list("lrgn", region));
        return chunk("RIFF", concat(ascii("DLS "), chunk("colh", ints(1)),
                list("lins", instrument), chunk("ptbl", ints(8, 1, 0)), list("wvpl", wave)));
    }

    static byte[] sf2() {
        byte[] phdr = concat(name("Sine"), shorts(40, 0, 0), ints(0, 0, 0),
                name("EOP"), shorts(0, 0, 1), ints(0, 0, 0));
        byte[] inst = concat(name("Sine"), shorts(0), name("EOI"), shorts(1));
        byte[] shdr = concat(name("Sine"), ints(0, FRAMES, 0, FRAMES, 22050),
                new byte[]{60, 0}, shorts(0, 1), name("EOS"), ints(FRAMES, FRAMES, FRAMES, FRAMES, 22050),
                new byte[]{0, 0}, shorts(0, 1));
        byte[] pdta = list("pdta", chunk("phdr", phdr), chunk("pbag", shorts(0, 0, 1, 0)),
                chunk("pmod", new byte[10]), chunk("pgen", shorts(41, 0, 0, 0)),
                chunk("inst", inst), chunk("ibag", shorts(0, 0, 3, 0)), chunk("imod", new byte[10]),
                chunk("igen", shorts(34, -12000, 54, 1, 53, 0, 0, 0)), chunk("shdr", shdr));
        return chunk("RIFF", concat(ascii("sfbk"),
                list("INFO", chunk("ifil", shorts(2, 1)), chunk("isng", ascii("EMU8000\0")),
                        chunk("INAM", ascii("JL-Mod qualification\0"))),
                list("sdta", chunk("smpl", samples(46))), pdta));
    }

    private static byte[] samples(int guard) {
        ByteBuffer data = ByteBuffer.allocate((FRAMES + guard) * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int index = 0; index < FRAMES; index++)
            data.putShort((short) Math.round(Math.sin(index * 2 * Math.PI / 32) * 12000));
        return data.array();
    }
    private static byte[] ascii(String value) { return value.getBytes(StandardCharsets.US_ASCII); }
    private static byte[] name(String value) {
        byte[] name = new byte[20]; byte[] text = ascii(value);
        System.arraycopy(text, 0, name, 0, Math.min(text.length, name.length)); return name;
    }
    private static byte[] ints(int... values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int value : values) buffer.putInt(value); return buffer.array();
    }
    private static byte[] shorts(int... values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int value : values) buffer.putShort((short) value); return buffer.array();
    }
    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] part : parts) output.write(part, 0, part.length); return output.toByteArray();
    }
    private static byte[] chunk(String id, byte[] data) {
        return concat(ascii(id), ints(data.length), data, new byte[data.length & 1]);
    }
    private static byte[] list(String type, byte[]... parts) { return chunk("LIST", concat(ascii(type), concat(parts))); }
}
