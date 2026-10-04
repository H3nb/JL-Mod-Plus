// SPDX-License-Identifier: Apache-2.0
package jlmod.runtimefixture;

import java.io.*;
import java.lang.reflect.Field;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import javax.microedition.amms.control.PanControl;
import javax.microedition.lcdui.*;
import javax.microedition.media.*;
import javax.microedition.media.control.*;
import javax.microedition.midlet.MIDlet;

/** Owned guest for real Canvas/Item and lifecycle qualification, debug builds only. */
public final class VideoLifecycleMidlet extends MIDlet {
    public static final String CLASS_NAME = "jlmod.runtimefixture.VideoLifecycleMidlet";
    public static final String ROOT_PROPERTY = "JLMod-Video-Fixture-Root";
    private volatile boolean destroyed;
    private Player video, peer, midi;
    private VideoControl control;
    private Display display;
    private Form form;
    private final AtomicInteger ends = new AtomicInteger(),
            errors = new AtomicInteger(),
            keys = new AtomicInteger(),
            pointers = new AtomicInteger(),
            commands = new AtomicInteger();
    private final Canvas canvas =
            new Canvas() {
                protected void paint(Graphics g) {
                    g.setColor(0x184878);
                    g.fillRect(0, 0, getWidth(), getHeight());
                    g.setColor(0xffffff);
                    g.drawString("Video + MIDI + PCM", 5, 3, Graphics.TOP | Graphics.LEFT);
                    g.drawRect(15, 23, 146, 120);
                }

                protected void keyPressed(int key) {
                    keys.incrementAndGet();
                }

                protected void pointerPressed(int x, int y) {
                    pointers.incrementAndGet();
                }
            };
    private String lastCommand = "", mode;

    @Override
    public synchronized void startApp() {
        if (video != null) return;
        File root = new File(getAppProperty(ROOT_PROPERTY));
        mode = getAppProperty("JLMod-Video-Mode");
        try {
            display = Display.getDisplay(this);
            form = new Form("Video Form");
            Command command = new Command("Check input", Command.OK, 1);
            canvas.addCommand(command);
            canvas.setCommandListener((c, s) -> commands.incrementAndGet());
            form.addCommand(command);
            form.setCommandListener((c, s) -> commands.incrementAndGet());
            video = load(root, "video.mp4");
            video.realize();
            control = (VideoControl) video.getControl("VideoControl");
            if (control == null) throw new IllegalStateException("No video control");
            if ("gui".equals(mode)) {
                form.append("Before video");
                form.append((Item) control.initDisplayMode(VideoControl.USE_GUI_PRIMITIVE, null));
                form.append("After video: scrolling and commands remain owned by Form");
                for (int i = 0; i < 24; i++) form.append("Scroll row " + i);
                display.setCurrent(form);
            } else {
                control.initDisplayMode(VideoControl.USE_DIRECT_VIDEO, canvas);
                control.setDisplayLocation(16, 24);
                control.setDisplaySize(144, 118);
                display.setCurrent(canvas);
            }
            video.addPlayerListener(
                    (p, e, v) -> {
                        if (PlayerListener.END_OF_MEDIA.equals(e)) ends.incrementAndGet();
                        if (PlayerListener.ERROR.equals(e)) errors.incrementAndGet();
                    });
            peer = load(root, "effect.mp3");
            peer.setLoopCount(-1);
            peer.start();
            midi = Manager.createPlayer(Manager.MIDI_DEVICE_LOCATOR);
            midi.prefetch();
            ((MIDIControl) midi.getControl("MIDIControl")).shortMidiEvent(0x90, 60, 48);
            video.setLoopCount(-1);
            video.start();
            new Thread(() -> monitor(root), "VideoFixtureTelemetry").start();
        } catch (Exception e) {
            publishError(root, e);
            throw new IllegalStateException(e);
        }
    }

    private static Player load(File root, String name) throws Exception {
        try (FileInputStream input = new FileInputStream(new File(root, name))) {
            return Manager.createPlayer(input, null);
        }
    }

    @Override
    public void pauseApp() {}

    @Override
    public void destroyApp(boolean unconditional) {
        destroyed = true;
    }

    private void monitor(File root) {
        try {
            Field libraryField = video.getClass().getDeclaredField("library");
            libraryField.setAccessible(true);
            Field handleField = video.getClass().getDeclaredField("handle");
            handleField.setAccessible(true);
            Object library = libraryField.get(video);
            while (!destroyed) {
                File request = new File(root, "command.properties");
                if (request.isFile()) {
                    Properties p = new Properties();
                    try (FileInputStream input = new FileInputStream(request)) {
                        p.load(input);
                    }
                    String next = p.getProperty("command", "");
                    if (!next.equals(lastCommand)) {
                        if (next.startsWith("seek:"))
                            video.setMediaTime(Long.parseLong(next.substring(5)));
                        else if ("stop".equals(next)) video.stop();
                        else if ("start".equals(next)) video.start();
                        else if ("hide".equals(next)) control.setVisible(false);
                        else if ("show".equals(next)) control.setVisible(true);
                        else if ("away".equals(next))
                            display.setCurrent("gui".equals(mode) ? canvas : form);
                        else if ("back".equals(next))
                            display.setCurrent("gui".equals(mode) ? form : canvas);
                        else if ("full".equals(next)) control.setDisplayFullScreen(true);
                        else if ("small".equals(next)) {
                            control.setDisplayFullScreen(false);
                            control.setDisplayLocation(16, 24);
                            control.setDisplaySize(144, 118);
                        } else if ("clip".equals(next)) {
                            control.setDisplayLocation(-24, 40);
                            control.setDisplaySize(300, 160);
                        } else if ("mute".equals(next))
                            ((VolumeControl) video.getControl("VolumeControl")).setMute(true);
                        else if ("pan".equals(next)) {
                            ((VolumeControl) video.getControl("VolumeControl")).setMute(false);
                            ((PanControl) video.getControl(PanControl.class.getName())).setPan(50);
                            ((VolumeControl) video.getControl("VolumeControl")).setLevel(60);
                        } else if ("close".equals(next)) video.close();
                        lastCommand = next;
                    }
                }
                Properties report = new Properties();
                report.setProperty("command", lastCommand);
                report.setProperty("state", Integer.toString(video.getState()));
                report.setProperty("ends", Integer.toString(ends.get()));
                report.setProperty("errors", Integer.toString(errors.get()));
                report.setProperty("keys", Integer.toString(keys.get()));
                report.setProperty("commands", Integer.toString(commands.get()));
                report.setProperty("pointers", Integer.toString(pointers.get()));
                if (video.getState() != Player.CLOSED) {
                    report.setProperty("position", Long.toString(video.getMediaTime()));
                    report.setProperty("displayWidth", Integer.toString(control.getDisplayWidth()));
                    report.setProperty(
                            "displayHeight", Integer.toString(control.getDisplayHeight()));
                    long[] stats =
                            (long[]) library.getClass().getMethod("diagnostics").invoke(library);
                    String[] names = {
                        "rendered",
                        "dropped",
                        "worstSkew",
                        "meanSkew",
                        "timestamps",
                        "fallbacks",
                        "uncertainty",
                        "generation"
                    };
                    for (int i = 0; i < names.length; i++)
                        report.setProperty(names[i], Long.toString(stats[i]));
                    report.setProperty(
                            "skewHistogram",
                            java.util.Arrays.toString(java.util.Arrays.copyOfRange(stats, 8, 16)));
                    report.setProperty("unmappedFrames", Long.toString(stats[16]));
                    report.setProperty("maxDrift", Long.toString(stats[17]));
                    report.setProperty("lastDrift", Long.toString(stats[18]));
                    report.setProperty("driftInterval", Long.toString(stats[19]));
                }
                Object peerLibrary = libraryField.get(peer);
                long peerHandle = handleField.getLong(peer);
                long[] runtime =
                        (long[])
                                peerLibrary
                                        .getClass()
                                        .getMethod("runtimeDiagnostics", long.class)
                                        .invoke(peerLibrary, peerHandle);
                report.setProperty("outputs", Long.toString(runtime[2]));
                report.setProperty("sources", Long.toString(runtime[3]));
                report.setProperty("xruns", Long.toString(runtime[8]));
                report.setProperty("clipped", Long.toString(runtime[5]));
                long[] peerStats =
                        (long[])
                                peerLibrary
                                        .getClass()
                                        .getMethod("diagnostics", long.class)
                                        .invoke(peerLibrary, peerHandle);
                report.setProperty("peerFrames", Long.toString(peerStats[0]));
                long[] decoder =
                        (long[])
                                peerLibrary
                                        .getClass()
                                        .getMethod("decoderDiagnostics", long.class)
                                        .invoke(peerLibrary, peerHandle);
                report.setProperty("underflow", Long.toString(decoder[1]));
                publish(root, report);
                Thread.sleep(50);
            }
        } catch (Exception e) {
            publishError(root, e);
        }
    }

    private static void publishError(File root, Exception e) {
        Properties report = new Properties();
        report.setProperty("error", e.toString());
        try {
            publish(root, report);
        } catch (Exception ignored) {
        }
    }

    private static void publish(File root, Properties report) throws Exception {
        File tmp = new File(root, "report.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            report.store(out, "Video fixture");
        }
        if (!tmp.renameTo(new File(root, "report.properties")))
            throw new IOException("Cannot publish telemetry");
    }
}
