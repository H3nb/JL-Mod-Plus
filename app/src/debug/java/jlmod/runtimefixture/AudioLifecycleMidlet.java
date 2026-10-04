// SPDX-License-Identifier: Apache-2.0
package jlmod.runtimefixture;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Form;
import javax.microedition.media.Manager;
import javax.microedition.media.Player;
import javax.microedition.media.PlayerListener;
import javax.microedition.midlet.MIDlet;

/** Project-owned guest fixture, loaded by the real MicroLoader in the isolated runtime. */
public final class AudioLifecycleMidlet extends MIDlet {
    public static final String CLASS_NAME = "jlmod.runtimefixture.AudioLifecycleMidlet";
    public static final String ROOT_PROPERTY = "JLMod-Audio-Fixture-Root";
    private volatile boolean destroyed;
    private Player player;
    private final Player[] effects = new Player[2];
    private String lastCommand = "";
    private final AtomicInteger errors = new AtomicInteger();
    private final AtomicInteger closed = new AtomicInteger();
    private final AtomicInteger starts = new AtomicInteger();
    private final AtomicInteger ends = new AtomicInteger();

    @Override public synchronized void startApp() {
        if (player != null) return; // Host reattachment must not replace guest stop intent.
        File root = new File(getAppProperty(ROOT_PROPERTY));
        try {
            Display.getDisplay(this).setCurrent(new Form("Audio lifecycle qualification"));
            try (FileInputStream input = new FileInputStream(new File(root, "sustain.mid"))) {
                player = Manager.createPlayer(input, "audio/midi");
            }
            player.addPlayerListener((owner, event, value) -> {
                if (PlayerListener.ERROR.equals(event)) errors.incrementAndGet();
                if (PlayerListener.CLOSED.equals(event)) closed.incrementAndGet();
                if (PlayerListener.STARTED.equals(event)) starts.incrementAndGet();
                if (PlayerListener.END_OF_MEDIA.equals(event)) ends.incrementAndGet();
            });
            String[] names = {"effect.wav", "effect.mp3"};
            for (int i = 0; i < effects.length; ++i) {
                try (FileInputStream input = new FileInputStream(new File(root, names[i]))) {
                    effects[i] = Manager.createPlayer(input, i == 0 ? "audio/wav" : "audio/mpeg");
                }
                effects[i].addPlayerListener((owner, event, value) -> {
                    if (PlayerListener.ERROR.equals(event)) errors.incrementAndGet();
                });
                effects[i].setLoopCount(-1); effects[i].start();
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
            // Guest loading deliberately excludes Android APIs; observe through the host wrapper loader.
            Method nativeHeap = player.getClass().getClassLoader().loadClass("android.os.Debug")
                    .getMethod("getNativeHeapAllocatedSize");
            while (!destroyed) {
                File commandFile = new File(root, "command.properties");
                if (commandFile.isFile()) {
                    Properties command = new Properties();
                    try (FileInputStream input = new FileInputStream(commandFile)) { command.load(input); }
                    String request = command.getProperty("command", "");
                    if (!request.equals(lastCommand)) {
                        if (request.equals("stop")) player.stop();
                        if (request.equals("start")) player.start();
                        if (request.startsWith("seek:")) player.setMediaTime(Long.parseLong(request.substring(5)));
                        lastCommand = request;
                    }
                }
                Properties report = new Properties();
                report.setProperty("state", Integer.toString(player.getState()));
                report.setProperty("position", Long.toString(player.getMediaTime()));
                report.setProperty("command", lastCommand);
                report.setProperty("updatedAt", Long.toString(System.currentTimeMillis()));
                report.setProperty("errors", Integer.toString(errors.get()));
                report.setProperty("closed", Integer.toString(closed.get()));
                report.setProperty("starts", Integer.toString(starts.get()));
                report.setProperty("ends", Integer.toString(ends.get()));
                report.setProperty("nativeHeapBytes", nativeHeap.invoke(null).toString());
                Runtime runtime = Runtime.getRuntime();
                report.setProperty("javaHeapBytes", Long.toString(runtime.totalMemory() - runtime.freeMemory()));
                String[] threads = new File("/proc/self/task").list();
                report.setProperty("threadCount", Integer.toString(threads == null ? -1 : threads.length));
                // Diagnostics are observed from the actual parent-owned wrapper, not another backend.
                Field libraryField = player.getClass().getDeclaredField("library");
                Field handleField = player.getClass().getDeclaredField("handle");
                libraryField.setAccessible(true); handleField.setAccessible(true);
                Object library = libraryField.get(player);
                long[] stats = (long[]) library.getClass().getMethod("diagnostics", long.class)
                        .invoke(library, handleField.getLong(player));
                report.setProperty("frames", Long.toString(stats[0]));
                report.setProperty("nonzeroSamples", Long.toString(stats[2]));
                report.setProperty("outputOpens", Long.toString(stats[3]));
                report.setProperty("disconnects", Long.toString(stats[4]));
                report.setProperty("xruns", Long.toString(stats[5]));
                report.setProperty("sampleRate", Long.toString(stats[6]));
                report.setProperty("deviceId", Long.toString(stats[7]));
                long[] runtimeStats = (long[]) library.getClass().getMethod("runtimeDiagnostics", long.class)
                        .invoke(library, handleField.getLong(player));
                String[] runtimeKeys = {"outputGroup", "runtimeOpens", "activeOutputs", "sources", "activeSources",
                        "clippedSamples", "callbackMaxNanos", "callbackCount", "runtimeXruns", "outputEpoch",
                        "latencyMicros", "bufferFrames"};
                for (int i = 0; i < runtimeKeys.length; ++i)
                    report.setProperty(runtimeKeys[i], Long.toString(runtimeStats[i]));
                long pcmFrames = 0, underflows = 0, workers = 0;
                for (Player effect : effects) {
                    Object effectLibrary = libraryField.get(effect);
                    long effectHandle = handleField.getLong(effect);
                    long[] decoder = (long[]) effectLibrary.getClass().getMethod("decoderDiagnostics", long.class)
                            .invoke(effectLibrary, effectHandle);
                    pcmFrames += decoder[0]; underflows += decoder[1]; workers += decoder[3];
                }
                report.setProperty("pcmFrames", Long.toString(pcmFrames));
                report.setProperty("underflowFrames", Long.toString(underflows));
                report.setProperty("decoderWorkers", Long.toString(workers));
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
