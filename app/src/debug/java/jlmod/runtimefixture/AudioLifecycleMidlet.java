// SPDX-License-Identifier: Apache-2.0
package jlmod.runtimefixture;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.Properties;

import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Form;
import javax.microedition.media.Manager;
import javax.microedition.media.Player;
import javax.microedition.midlet.MIDlet;

/** Project-owned guest fixture, loaded by the real MicroLoader in the isolated runtime. */
public final class AudioLifecycleMidlet extends MIDlet {
    public static final String CLASS_NAME = "jlmod.runtimefixture.AudioLifecycleMidlet";
    public static final String ROOT_PROPERTY = "JLMod-Audio-Fixture-Root";
    private volatile boolean destroyed;
    private Player player;
    private String lastCommand = "";

    @Override public synchronized void startApp() {
        if (player != null) return; // Host reattachment must not replace guest stop intent.
        File root = new File(getAppProperty(ROOT_PROPERTY));
        try {
            Display.getDisplay(this).setCurrent(new Form("Audio lifecycle qualification"));
            try (FileInputStream input = new FileInputStream(new File(root, "sustain.mid"))) {
                player = Manager.createPlayer(input, "audio/midi");
            }
            player.setLoopCount(-1);
            player.start();
            new Thread(() -> monitor(root), "AudioFixtureTelemetry").start();
        } catch (Exception error) {
            Properties report = new Properties();
            report.setProperty("error", "startApp: " + error);
            try { publish(root, report); } catch (Exception ignored) { }
            throw new IllegalStateException("Audio fixture start failed", error);
        }
    }

    @Override public void pauseApp() { }

    @Override public void destroyApp(boolean unconditional) {
        destroyed = true;
        // Intentionally leave the Player owned by runtime cleanup, as ordinary MIDlets may do.
    }

    private void monitor(File root) {
        try {
            while (!destroyed) {
                File commandFile = new File(root, "command.properties");
                if (commandFile.isFile()) {
                    Properties command = new Properties();
                    try (FileInputStream input = new FileInputStream(commandFile)) { command.load(input); }
                    String request = command.getProperty("command", "");
                    if (!request.equals(lastCommand)) {
                        if (request.equals("stop")) player.stop();
                        if (request.equals("start")) player.start();
                        lastCommand = request;
                    }
                }
                Properties report = new Properties();
                report.setProperty("state", Integer.toString(player.getState()));
                report.setProperty("position", Long.toString(player.getMediaTime()));
                report.setProperty("command", lastCommand);
                report.setProperty("updatedAt", Long.toString(System.currentTimeMillis()));
                // Diagnostics are observed from the actual parent-owned wrapper, not another backend.
                Field libraryField = player.getClass().getDeclaredField("library");
                Field handleField = player.getClass().getDeclaredField("handle");
                libraryField.setAccessible(true); handleField.setAccessible(true);
                Object library = libraryField.get(player);
                long[] stats = (long[]) library.getClass().getMethod("diagnostics", long.class)
                        .invoke(library, handleField.getLong(player));
                report.setProperty("frames", Long.toString(stats[0]));
                report.setProperty("nonzeroSamples", Long.toString(stats[2]));
                Field bankField = library.getClass().getDeclaredField("soundBank");
                bankField.setAccessible(true);
                report.setProperty("customBank", Boolean.toString(bankField.get(library) != null));
                report.setProperty("liveHandles", library.getClass().getMethod("liveHandles").invoke(null).toString());
                publish(root, report);
                Thread.sleep(50);
            }
        } catch (Exception error) {
            Properties report = new Properties();
            report.setProperty("error", error.toString());
            try { publish(root, report); } catch (Exception ignored) { }
        }
    }

    private static void publish(File root, Properties report) throws Exception {
        File temporary = new File(root, "report.properties.tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) { report.store(output, "Audio fixture"); }
        if (!temporary.renameTo(new File(root, "report.properties")))
            throw new IllegalStateException("Cannot publish audio fixture report");
    }
}
