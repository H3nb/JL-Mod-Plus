// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.runtime

import android.app.ActivityManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.util.Log

/** Performs explicit emulator shutdown in the main process, after guest state was finalized. */
class EmulatorShutdownService : Service() {
    private var shutdownStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!shutdownStarted) {
            shutdownStarted = true
            // Process death can occur before onStartCommand returns START_NOT_STICKY to Android.
            stopSelf()
            terminateProcesses(this)
        }
        return START_NOT_STICKY
    }

    companion object {
        @JvmStatic
        fun request(context: Context) {
            try {
                context.startService(Intent(context, EmulatorShutdownService::class.java))
            } catch (error: RuntimeException) {
                Log.w("EmulatorShutdown", "Unable to dispatch emulator shutdown", error)
                terminateProcesses(context)
            }
        }

        private fun terminateProcesses(context: Context) {
            val ownPid = Process.myPid()
            try {
                val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                for (task in manager.appTasks) {
                    try {
                        task.finishAndRemoveTask()
                    } catch (error: RuntimeException) {
                        // Another task-removal callback can already have removed this task.
                        Log.w("EmulatorShutdown", "Unable to remove emulator task", error)
                    }
                }
                val processes = manager.runningAppProcesses ?: emptyList()
                val runtime = processes.firstOrNull {
                    it.pid != ownPid && it.uid == Process.myUid()
                        && it.processName == context.packageName + ":midlet"
                }
                for (process in processes) {
                    if (process.pid != ownPid && process.pid != runtime?.pid
                        && process.uid == Process.myUid()
                        && (process.processName == context.packageName
                            || process.processName.startsWith(context.packageName + ":"))
                    ) {
                        Process.killProcess(process.pid)
                    }
                }
                // Engine death releases its target binding before the runtime process is killed.
                runtime?.let { Process.killProcess(it.pid) }
            } finally {
                // Main dies last: no surviving runtime binding can restart its authority services.
                Process.killProcess(ownPid)
            }
        }
    }
}
