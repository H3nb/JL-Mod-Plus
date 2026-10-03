// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.mmapi.synth;

import static io.github.h3nb.jlmodplus.mmapi.synth.SynthPlayerContractTest.await;
import static org.junit.Assert.*;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;

import androidx.preference.PreferenceManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

import javax.microedition.media.Player;
import javax.microedition.shell.AudioRuntimeRecreationActivity;
import javax.microedition.shell.CrashRuntimeLifecycleControlActivity;
import javax.microedition.shell.MicroActivity;

import io.github.h3nb.jlmodplus.config.ProfileModel;
import io.github.h3nb.jlmodplus.config.ProfilesManager;
import io.github.h3nb.jlmodplus.crashes.MidletSessionStore;
import io.github.h3nb.jlmodplus.util.Constants;
import jlmod.runtimefixture.AudioLifecycleMidlet;

/** MicroLoader/MidletThread/real Activity lifecycle qualification in the separate probe installation. */
@RunWith(AndroidJUnit4.class)
public class AudioMidletRuntimeTest {
    @Test public void realMidletRetainsAudioAcrossHostReturnAndRecreationAndGuestStop() throws Exception {
        qualify(null);
    }

    @Test public void realMidletUsesSuppliedLocalBankSnapshot() throws Exception {
        String path = InstrumentationRegistry.getArguments().getString("sonivoxBankPath");
        Assume.assumeTrue("No private local bank supplied", path != null);
        File bank = new File(path);
        assertTrue("local bank missing", bank.isFile());
        qualify(bank);
    }

    private void qualify(File bank) throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Assume.assumeTrue("Run through audio-qualification.init.gradle to protect the normal installation",
                "io.github.h3nb.jlmodplus.audioqualification.debug".equals(context.getPackageName()));
        String processName = context.getPackageName() + ":midlet";
        assertEquals("another runtime is active in qualification installation", 0, pid(context, processName));
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        boolean hadRoot = preferences.contains(Constants.PREF_EMULATOR_DIR);
        String previousRoot = preferences.getString(Constants.PREF_EMULATOR_DIR, null);
        File root = new File(context.getFilesDir(), "audio-runtime-" + System.nanoTime());
        File app = new File(root, "converted/fixture");
        try {
            assertTrue(app.mkdirs());
            File config = new File(root, "configs/fixture");
            assertTrue(config.mkdirs());
            copy(new File(context.getApplicationInfo().sourceDir), new File(app, "converted.zip"));
            ProfileModel profile = new ProfileModel(config);
            profile.showKeyboard = false;
            profile.touchInput = false;
            profile.soundBank = "";
            if (bank != null) {
                File banks = new File(root, "soundbanks");
                assertTrue(banks.mkdirs());
                copy(bank, new File(banks, "qualification-bank"));
                profile.soundBank = "qualification-bank";
            }
            assertTrue("fixture profile was not saved", ProfilesManager.saveConfig(profile));
            write(new File(root, "sustain.mid"), SonivoxRuntimeTest.midi(56640, 57600));
            String manifest = "Manifest-Version: 1.0\nMIDlet-Name: Audio Lifecycle Fixture\n"
                    + "MIDlet-Vendor: JL-Mod Plus\nMIDlet-Version: 1.0\n"
                    + "MIDlet-1: Audio Lifecycle Fixture,," + AudioLifecycleMidlet.CLASS_NAME + "\n"
                    + AudioLifecycleMidlet.ROOT_PROPERTY + ": " + root.getAbsolutePath() + "\n";
            write(new File(app, "converted.dex.conf"), manifest.getBytes(StandardCharsets.UTF_8));
            long appId = AudioFixtureCatalog.install(context, root);
            assertTrue("fixture installed identity missing", appId > 0);
            assertTrue(preferences.edit().putString(Constants.PREF_EMULATOR_DIR, root.getAbsolutePath()).commit());
            launch(context, app, appId, true);
            await(() -> report(root).getProperty("nonzeroSamples", "0").matches("[1-9][0-9]*"),
                    20000, "real MIDlet failed to produce PCM: " + root);
            Properties initial = report(root);
            assertFalse(initial.toString(), initial.containsKey("error"));
            assertEquals(Player.STARTED, number(initial, "state"));
            assertEquals(Boolean.toString(bank != null), initial.getProperty("customBank"));
            int initialPid = pid(context, processName);
            assertTrue(initialPid > 0);
            MidletSessionStore.State session = MidletSessionStore.read(context);
            assertNotNull(session);
            String generation = session.getGeneration();

            shell("am start -W -a android.intent.action.MAIN -c android.intent.category.HOME");
            SystemClock.sleep(300);
            long frozen = number(report(root), "position");
            long frames = number(report(root), "frames");
            SystemClock.sleep(200);
            assertEquals("Android Home did not suspend media", frozen, number(report(root), "position"));
            assertEquals("Android Home did not suspend output", frames, number(report(root), "frames"));
            assertEquals(Player.STARTED, number(report(root), "state"));
            launch(context, app, appId, false);
            await(() -> number(report(root), "position") > frozen + 100000, 6000, "host return did not resume");
            assertEquals(initialPid, pid(context, processName));
            assertEquals(generation, MidletSessionStore.read(context).getGeneration());

            long beforeRecreate = number(report(root), "position");
            context.startActivity(new Intent(context, AudioRuntimeRecreationActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> number(report(root), "position") > beforeRecreate + 200000,
                    6000, "Activity recreation did not restore output");
            assertEquals(initialPid, pid(context, processName));
            assertEquals(generation, MidletSessionStore.read(context).getGeneration());
            assertEquals(1, number(report(root), "liveHandles"));

            command(root, "stop");
            await(() -> "stop".equals(report(root).getProperty("command")), 3000, "guest stop did not execute");
            long stopped = number(report(root), "position");
            shell("am start -W -a android.intent.action.MAIN -c android.intent.category.HOME");
            SystemClock.sleep(100);
            launch(context, app, appId, false);
            SystemClock.sleep(300);
            assertEquals("host return restarted a guest-stopped Player", Player.PREFETCHED, number(report(root), "state"));
            assertEquals(stopped, number(report(root), "position"));
            command(root, "start");
            await(() -> number(report(root), "position") > stopped + 100000, 6000, "fresh guest start failed");

            context.startActivity(new Intent(context, CrashRuntimeLifecycleControlActivity.class)
                    .putExtra(CrashRuntimeLifecycleControlActivity.EXTRA_COMMAND,
                            CrashRuntimeLifecycleControlActivity.COMMAND_DESTROY)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> pid(context, processName) == 0, 10000, "MIDlet runtime did not terminate");
            assertNull("runtime selection was retained after termination", MidletSessionStore.read(context));
        } finally {
            int remainingPid = pid(context, processName);
            if (remainingPid != 0) {
                Process.killProcess(remainingPid);
                await(() -> pid(context, processName) == 0, 5000, "fixture process did not stop before cleanup");
            }
            SharedPreferences.Editor restore = preferences.edit();
            if (hadRoot) restore.putString(Constants.PREF_EMULATOR_DIR, previousRoot);
            else restore.remove(Constants.PREF_EMULATOR_DIR);
            restore.commit();
            removeFixture(root, context.getFilesDir());
        }
    }

    private static void launch(Context context, File app, long appId, boolean fresh) {
        int flags = Intent.FLAG_ACTIVITY_NEW_TASK;
        if (fresh) flags |= Intent.FLAG_ACTIVITY_CLEAR_TASK;
        context.startActivity(new Intent(Intent.ACTION_DEFAULT, Uri.parse(app.getAbsolutePath()), context, MicroActivity.class)
                .putExtra(Constants.KEY_MIDLET_NAME, "Audio Lifecycle Fixture")
                .putExtra(Constants.KEY_LIBRARY_APP_ID, appId).addFlags(flags));
    }

    private static Properties report(File root) throws Exception {
        Properties report = new Properties();
        File file = new File(root, "report.properties");
        if (file.isFile()) try (FileInputStream input = new FileInputStream(file)) { report.load(input); }
        if (report.containsKey("error")) fail("Guest fixture: " + report.getProperty("error"));
        return report;
    }
    private static long number(Properties report, String key) { return Long.parseLong(report.getProperty(key, "0")); }
    private static void command(File root, String command) throws Exception {
        File temporary = new File(root, "command.properties.tmp");
        write(temporary, ("command=" + command + "\n").getBytes(StandardCharsets.UTF_8));
        assertTrue(temporary.renameTo(new File(root, "command.properties")));
    }
    private static int pid(Context context, String name) {
        List<ActivityManager.RunningAppProcessInfo> processes = ((ActivityManager) context
                .getSystemService(Context.ACTIVITY_SERVICE)).getRunningAppProcesses();
        if (processes != null) for (ActivityManager.RunningAppProcessInfo process : processes)
            if (name.equals(process.processName)) return process.pid;
        return 0;
    }
    private static void shell(String command) throws Exception {
        ParcelFileDescriptor result = InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command);
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(result)) {
            byte[] buffer = new byte[1024]; while (input.read(buffer) != -1) { }
        }
    }
    private static void copy(File source, File target) throws Exception {
        try (FileInputStream input = new FileInputStream(source); FileOutputStream output = new FileOutputStream(target)) {
            input.transferTo(output);
        }
    }
    private static void write(File file, byte[] bytes) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(bytes); }
    }
    private static void removeFixture(File file, File owner) throws Exception {
        assertTrue("refusing cleanup outside private fixture", file.getCanonicalPath().startsWith(owner.getCanonicalPath() + File.separator));
        File[] children = file.listFiles();
        if (children != null) for (File child : children) removeFixture(child, owner);
        if (file.exists()) assertTrue("fixture cleanup failed", file.delete());
    }
}
