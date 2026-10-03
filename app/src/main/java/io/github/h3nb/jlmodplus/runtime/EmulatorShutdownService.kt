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
                for (pid in otherProcessShutdownOrder(
                    processes, context.packageName, ownPid, Process.myUid()
                )) {
                    Process.killProcess(pid)
                }
            } finally {
                // The caller must survive to finish cleanup. Normally it is main and dies last.
                // If dispatch failed in :midlet, main dies immediately before this runtime caller.
                Process.killProcess(ownPid)
            }
        }

        /** Auxiliary processes die before runtime/main peers, regardless of Android's list order. */
        internal fun otherProcessShutdownOrder(
            processes: List<ActivityManager.RunningAppProcessInfo>,
            packageName: String,
            ownPid: Int,
            ownUid: Int,
        ): List<Int> {
            val order = ArrayList<Int>(processes.size)
            var runtimePid: Int? = null
            var mainPid: Int? = null
            for (process in processes) {
                if (process.pid == ownPid || process.uid != ownUid) continue
                when (process.processName) {
                    packageName -> mainPid = process.pid
                    "$packageName:midlet" -> runtimePid = process.pid
                    else -> if (process.processName.startsWith("$packageName:")) {
                        order.add(process.pid)
                    }
                }
            }
            runtimePid?.let { order.add(it) }
            mainPid?.let { order.add(it) }
            return order
        }
    }
}
