// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.mmapi.synth;

import static io.github.h3nb.jlmodplus.mmapi.synth.SynthPlayerContractTest.await;

import static org.junit.Assert.*;

import android.app.ActivityManager;
import android.content.*;
import android.net.Uri;
import android.os.*;

import androidx.preference.PreferenceManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import io.github.h3nb.jlmodplus.config.*;
import io.github.h3nb.jlmodplus.util.Constants;

import jlmod.runtimefixture.VideoLifecycleMidlet;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import javax.microedition.media.Player;
import javax.microedition.shell.*;

@RunWith(AndroidJUnit4.class)
public class VideoMidletRuntimeTest {
    @Test
    public void directCanvasVideoKeepsInputAndPeersAcrossDisplayAndHostTransitions()
            throws Exception {
        qualify("direct");
    }

    @Test
    public void guiItemVideoLivesInRealFormAndReattaches() throws Exception {
        qualify("gui");
    }

    private void qualify(String mode) throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals(
                "Use the qualification init script",
                "io.github.h3nb.jlmodplus.audioqualification.debug",
                context.getPackageName());
        String process = context.getPackageName() + ":midlet";
        assertEquals("Another guest is active", 0, pid(context, process));
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean hadRoot = prefs.contains(Constants.PREF_EMULATOR_DIR);
        String oldRoot = prefs.getString(Constants.PREF_EMULATOR_DIR, null);
        boolean hadKeep = prefs.contains(Constants.PREF_KEEP_SCREEN),
                oldKeep = prefs.getBoolean(Constants.PREF_KEEP_SCREEN, false);
        File root = new File(context.getFilesDir(), "video-runtime-" + System.nanoTime()),
                app = new File(root, "converted/fixture");
        try {
            assertTrue(app.mkdirs());
            File config = new File(root, "configs/fixture");
            assertTrue(config.mkdirs());
            copy(new File(context.getApplicationInfo().sourceDir), new File(app, "converted.zip"));
            ProfileModel profile = new ProfileModel(config);
            profile.soundBank = "";
            profile.showKeyboard = true;
            profile.touchInput = true;
            profile.graphicsMode =
                    Integer.parseInt(
                            InstrumentationRegistry.getArguments()
                                    .getString("videoGraphicsMode", "0"));
            assertTrue(ProfilesManager.saveConfig(profile));
            for (String[] media :
                    new String[][] {
                        {"video/markers.mp4", "video.mp4"}, {"audio/effect.mp3", "effect.mp3"}
                    }) {
                try (InputStream input =
                                InstrumentationRegistry.getInstrumentation()
                                        .getContext()
                                        .getAssets()
                                        .open(media[0]);
                        FileOutputStream output = new FileOutputStream(new File(root, media[1]))) {
                    input.transferTo(output);
                }
            }
            String manifest =
                    "Manifest-Version: 1.0\n"
                            + "MIDlet-Name: Video Fixture\n"
                            + "MIDlet-Vendor: JL-Mod Plus\n"
                            + "MIDlet-Version: 1.0\n"
                            + "MIDlet-1: Video Fixture,,"
                            + VideoLifecycleMidlet.CLASS_NAME
                            + "\n"
                            + VideoLifecycleMidlet.ROOT_PROPERTY
                            + ": "
                            + root.getAbsolutePath()
                            + "\nJLMod-Video-Mode: "
                            + mode
                            + "\n";
            write(new File(app, "converted.dex.conf"), manifest);
            long id = AudioFixtureCatalog.install(context, root);
            assertTrue(id > 0);
            assertTrue(
                    prefs.edit()
                            .putString(Constants.PREF_EMULATOR_DIR, root.getAbsolutePath())
                            .putBoolean(Constants.PREF_KEEP_SCREEN, true)
                            .commit());
            launch(context, app, id, true);
            if ("direct".equals(mode)) {
                await(
                        () ->
                                number(report(root), "position") > 200000
                                        || report(root).containsKey("error"),
                        20000,
                        "Hidden direct video did not advance");
                assertFalse(report(root).toString(), report(root).containsKey("error"));
                assertEquals(
                        "Direct video must default hidden", 0, number(report(root), "rendered"));
                command(root, "show");
            }
            await(
                    () -> number(report(root), "rendered") > 5 || report(root).containsKey("error"),
                    20000,
                    "No video telemetry " + root);
            Properties initial = report(root);
            assertFalse(initial.toString(), initial.containsKey("error"));
            assertEquals(1, number(initial, "outputs"));
            assertEquals(3, number(initial, "sources"));
            assertEquals(0, number(initial, "errors"));
            capture(context, mode + "-" + profile.graphicsMode + "-initial");
            if ("direct".equals(mode)) {
                shell("input keyevent 23");
                await(
                        () -> number(report(root), "keys") > 0,
                        3000,
                        "Video intercepted Canvas keys");
                android.graphics.Bitmap screen =
                        InstrumentationRegistry.getInstrumentation()
                                .getUiAutomation()
                                .takeScreenshot();
                assertNotNull(screen);
                int x = screen.getWidth() / 2, y = screen.getHeight() / 4;
                screen.recycle();
                shell("input tap " + x + " " + y);
                await(
                        () -> number(report(root), "pointers") > 0,
                        3000,
                        "Video intercepted Canvas touch");
                shell("input keyevent 1");
                await(
                        () -> number(report(root), "commands") > 0,
                        3000,
                        "Video obscured Canvas soft command");
                command(root, "clip");
                SystemClock.sleep(150);
                capture(context, mode + "-" + profile.graphicsMode + "-clipped");
                command(root, "small");
            } else {
                android.graphics.Bitmap screen =
                        InstrumentationRegistry.getInstrumentation()
                                .getUiAutomation()
                                .takeScreenshot();
                assertNotNull(screen);
                // Physical portrait qualification: the sole ScreenSoftBar action
                // is visibly at the lower left, above the navigation inset.
                shell(
                        "input tap "
                                + (screen.getWidth() / 10)
                                + " "
                                + (screen.getHeight() * 95 / 100));
                await(
                        () -> number(report(root), "commands") > 0,
                        3000,
                        "Form command did not dispatch");
                int x = screen.getWidth() / 2, y = screen.getHeight() / 2;
                screen.recycle();
                shell("input swipe " + x + " " + (y + 300) + " " + x + " " + (y - 300) + " 300");
                capture(context, mode + "-" + profile.graphicsMode + "-scrolled");
                shell("input swipe " + x + " " + (y - 300) + " " + x + " " + (y + 300) + " 300");
            }
            command(root, "hide");
            long hiddenAt = number(report(root), "position");
            long hiddenEnds = number(report(root), "ends");
            await(
                    () ->
                            number(report(root), "position") > hiddenAt
                                    || number(report(root), "ends") > hiddenEnds,
                    3000,
                    "Hiding stopped playback");
            command(root, "show");
            long frames = number(report(root), "rendered");
            await(
                    () -> number(report(root), "rendered") > frames,
                    4000,
                    "Visible video did not reattach");
            command(root, "away");
            SystemClock.sleep(200);
            command(root, "back");
            long backFrames = number(report(root), "rendered");
            await(
                    () -> number(report(root), "rendered") > backFrames,
                    4000,
                    "Display switch lost video");
            command(root, "full");
            SystemClock.sleep(150);
            capture(context, mode + "-" + profile.graphicsMode + "-fullscreen");
            command(root, "small");
            shell("input keyevent 82");
            SystemClock.sleep(200);
            SystemClock.sleep(500);
            capture(context, mode + "-" + profile.graphicsMode + "-menu");
            shell("input keyevent 4");
            command(root, "mute");
            command(root, "pan");
            long peerBefore = number(report(root), "peerFrames");
            command(root, "seek:1500000");
            await(
                    () -> number(report(root), "peerFrames") > peerBefore + 1024,
                    3000,
                    "Video seek stopped PCM peer");
            long ends = number(report(root), "ends");
            await(() -> number(report(root), "ends") > ends, 6000, "Video loop missing EOM");
            shell("am start -a android.intent.action.MAIN -c android.intent.category.HOME");
            SystemClock.sleep(300);
            long frozen = number(report(root), "position");
            SystemClock.sleep(300);
            assertEquals("Home failed to freeze video", frozen, number(report(root), "position"));
            command(root, "stop");
            launch(context, app, id, false);
            SystemClock.sleep(350);
            assertEquals(Player.PREFETCHED, number(report(root), "state"));
            assertEquals(frozen, number(report(root), "position"));
            command(root, "start");
            long beforeRecreate = number(report(root), "rendered");
            context.startActivity(
                    new Intent(context, AudioRuntimeRecreationActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(
                    () -> number(report(root), "rendered") > beforeRecreate + 3,
                    6000,
                    "Recreation lost video Surface");
            long beforeRotate = number(report(root), "rendered");
            context.startActivity(
                    new Intent(context, AudioRuntimeRecreationActivity.class)
                            .putExtra(
                                    AudioRuntimeRecreationActivity.EXTRA_ORIENTATION,
                                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(
                    () -> number(report(root), "rendered") > beforeRotate + 3,
                    6000,
                    "Rotation lost video Surface");
            SystemClock.sleep(300);
            capture(context, mode + "-" + profile.graphicsMode + "-landscape");
            context.startActivity(
                    new Intent(context, AudioRuntimeRecreationActivity.class)
                            .putExtra(
                                    AudioRuntimeRecreationActivity.EXTRA_ORIENTATION,
                                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            SystemClock.sleep(300);
            Properties finalReport = report(root);
            assertFalse(finalReport.toString(), finalReport.containsKey("error"));
            assertEquals(0, number(finalReport, "errors"));
            assertEquals(0, number(finalReport, "underflow"));
            Bundle status = new Bundle();
            status.putString("stream", "Video MIDlet " + mode + " " + finalReport + "\n");
            InstrumentationRegistry.getInstrumentation().sendStatus(0, status);
            command(root, "close");
            long closeFrames = number(report(root), "peerFrames");
            await(
                    () -> number(report(root), "peerFrames") > closeFrames + 1024,
                    3000,
                    "Close stopped peer");
            context.startActivity(
                    new Intent(context, CrashRuntimeLifecycleControlActivity.class)
                            .putExtra(
                                    CrashRuntimeLifecycleControlActivity.EXTRA_COMMAND,
                                    CrashRuntimeLifecycleControlActivity.COMMAND_DESTROY)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> pid(context, process) == 0, 10000, "Video runtime did not terminate");
        } finally {
            int remaining = pid(context, process);
            if (remaining != 0) {
                android.os.Process.killProcess(remaining);
                await(() -> pid(context, process) == 0, 5000, "Runtime did not close");
            }
            SharedPreferences.Editor restore = prefs.edit();
            if (hadRoot) restore.putString(Constants.PREF_EMULATOR_DIR, oldRoot);
            else restore.remove(Constants.PREF_EMULATOR_DIR);
            assertTrue(restore.commit());
            if (hadKeep) restore.putBoolean(Constants.PREF_KEEP_SCREEN, oldKeep);
            else restore.remove(Constants.PREF_KEEP_SCREEN);
            assertTrue(restore.commit());
            remove(root, context.getFilesDir());
        }
    }

    private static void capture(Context context, String name) throws Exception {
        File dir = new File(context.getFilesDir(), "video-qualification");
        if (!dir.isDirectory()) assertTrue(dir.mkdirs());
        android.graphics.Bitmap image =
                InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(image);
        try (FileOutputStream out = new FileOutputStream(new File(dir, name + ".png"))) {
            assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out));
        } finally {
            image.recycle();
        }
    }

    private static void launch(Context c, File app, long id, boolean fresh) {
        Intent intent =
                new Intent(
                                Intent.ACTION_DEFAULT,
                                Uri.parse(app.getAbsolutePath()),
                                c,
                                MicroActivity.class)
                        .putExtra(Constants.KEY_MIDLET_NAME, "Video Fixture")
                        .putExtra(Constants.KEY_LIBRARY_APP_ID, id)
                        .addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK
                                        | (fresh ? Intent.FLAG_ACTIVITY_CLEAR_TASK : 0));
        c.startActivity(intent);
    }

    private static void command(File root, String command) throws Exception {
        File tmp = new File(root, "command.tmp");
        write(tmp, "command=" + command + "\n");
        assertTrue(tmp.renameTo(new File(root, "command.properties")));
        await(
                () ->
                        command.equals(report(root).getProperty("command"))
                                || report(root).containsKey("error"),
                4000,
                "Guest command timed out " + command);
        assertFalse(report(root).toString(), report(root).containsKey("error"));
    }

    private static Properties report(File root) {
        Properties p = new Properties();
        try (FileInputStream in = new FileInputStream(new File(root, "report.properties"))) {
            p.load(in);
        } catch (IOException ignored) {
        }
        return p;
    }

    private static long number(Properties p, String key) {
        return Long.parseLong(p.getProperty(key, "0"));
    }

    private static int pid(Context c, String name) {
        for (ActivityManager.RunningAppProcessInfo p :
                ((ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE))
                        .getRunningAppProcesses()) if (name.equals(p.processName)) return p.pid;
        return 0;
    }

    private static void shell(String cmd) throws Exception {
        ParcelFileDescriptor fd =
                InstrumentationRegistry.getInstrumentation()
                        .getUiAutomation()
                        .executeShellCommand(cmd);
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
        }
    }

    private static void copy(File from, File to) throws Exception {
        try (FileInputStream in = new FileInputStream(from);
                FileOutputStream out = new FileOutputStream(to)) {
            in.transferTo(out);
        }
    }

    private static void write(File file, String value) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(value.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void remove(File file, File owner) throws Exception {
        assertTrue(file.getCanonicalPath().startsWith(owner.getCanonicalPath() + File.separator));
        File[] children = file.listFiles();
        if (children != null) for (File child : children) remove(child, owner);
        if (file.exists()) assertTrue(file.delete());
    }
}
