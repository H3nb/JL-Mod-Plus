/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.github.h3nb.jlmodplus.config;

import static io.github.h3nb.jlmodplus.util.Constants.PREF_DEFAULT_PROFILE;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.RemoteException;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import java.io.File;
import java.io.IOException;

import io.github.h3nb.jlmodplus.EmulatorApplication;

/** Debug-only control surface for setting up and observing authority-process test state. */
public final class PresetAuthorityMainProbeService extends Service {
	public static final int MSG_RESET_OWNERSHIP = 1;
	public static final int MSG_LINK = 2;
	public static final int MSG_READ = 3;
	public static final int MSG_RENAME = 4;
	public static final int MSG_PREPARE_MAIN_LOAD = 5;

	public static final String KEY_PRESET_NAME = "presetName";
	public static final String KEY_NEW_PRESET_NAME = "newPresetName";
	public static final String KEY_SET_DEFAULT = "setDefault";
	public static final String KEY_ORIGIN = "origin";
	public static final String KEY_LINKED = "linked";
	public static final String KEY_DEFAULT_PROFILE = "defaultProfile";
	public static final String KEY_REMOTE_PID = "remotePid";
	public static final String KEY_PROCESS_NAME = "processName";

	private final Messenger messenger =
			new Messenger(new Handler(Looper.getMainLooper(), this::handleMessage));

	@Nullable
	@Override
	public IBinder onBind(Intent intent) {
		return messenger.getBinder();
	}

	private boolean handleMessage(Message message) {
		Messenger reply = message.replyTo;
		if (reply == null) return true;

		Bundle result = new Bundle();
		result.putInt(KEY_REMOTE_PID, Process.myPid());
		result.putString(KEY_PROCESS_NAME, EmulatorApplication.getProcessName());
		try {
			Bundle request = message.getData();
			PresetAuthority.InstalledPath path = PresetAuthority.InstalledPath.parse(
					request.getString(PresetAuthorityContract.KEY_APP_PATH, ""));
			if (path == null) {
				result.putInt(PresetAuthorityContract.KEY_RESULT, PresetAuthorityContract.RESULT_INVALID);
				send(reply, message.what, result);
				return true;
			}

			File configDir = new File(new File(path.workDir, "configs"), path.storageKey);
			File profilesRoot = new File(path.workDir, "templates");
			SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
			PresetLinkage linkage = new PresetLinkage(preferences, configDir);
			boolean success;
			switch (message.what) {
				case MSG_RESET_OWNERSHIP -> success = linkage.clear()
						&& preferences.edit()
						.remove(ProfileModel.builtInThemePreferenceKey(configDir))
						.remove(PREF_DEFAULT_PROFILE)
						.commit();
				case MSG_LINK -> {
					String name = request.getString(KEY_PRESET_NAME);
					success = name != null && Profile.isValidName(name) && linkage.linkTo(name);
					if (success && request.getBoolean(KEY_SET_DEFAULT, false)) {
						success = preferences.edit().putString(PREF_DEFAULT_PROFILE, name).commit();
					}
				}
				case MSG_READ -> {
					String origin = linkage.getOrigin();
					if (origin != null) result.putString(KEY_ORIGIN, origin);
					result.putBoolean(KEY_LINKED, linkage.isLinked());
					String defaultProfile = preferences.getString(PREF_DEFAULT_PROFILE, null);
					if (defaultProfile != null) result.putString(KEY_DEFAULT_PROFILE, defaultProfile);
					success = true;
				}
				case MSG_RENAME -> {
					String oldName = request.getString(KEY_PRESET_NAME);
					String newName = request.getString(KEY_NEW_PRESET_NAME);
					success = oldName != null && newName != null
							&& PresetLifecycle.rename(
									preferences, profilesRoot, oldName, newName)
							== PresetLifecycle.Result.SUCCESS;
				}
				case MSG_PREPARE_MAIN_LOAD -> success =
						MidletConfigLoadBoundary.prepare(preferences, configDir, profilesRoot);
				default -> success = false;
			}
			result.putInt(
					PresetAuthorityContract.KEY_RESULT,
					success ? PresetAuthorityContract.RESULT_OK : PresetAuthorityContract.RESULT_FAILED);
		} catch (IOException | RuntimeException failure) {
			result.putInt(PresetAuthorityContract.KEY_RESULT, PresetAuthorityContract.RESULT_FAILED);
		}
		send(reply, message.what, result);
		return true;
	}

	private static void send(Messenger reply, int what, Bundle data) {
		Message response = Message.obtain();
		response.what = what;
		response.setData(data);
		try {
			reply.send(response);
		} catch (RemoteException ignored) {
			// Instrumentation caller disappeared.
		}
	}
}
