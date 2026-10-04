// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.runtime

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import android.widget.Toast
import androidx.core.content.IntentCompat
import io.github.h3nb.jlmodplus.R
import javax.microedition.shell.MicroActivity

/** Keeps a replacement request outside the runtime heap that is about to be killed. */
class RuntimeReplacementActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var runtime: IBinder? = null
    private var replacement: Intent? = null
    private var runtimeDead = false
    private var resumed = false
    private var deathRecipient: IBinder.DeathRecipient? = null
    private val timeout = Runnable {
        val observedRuntime = runtime ?: return@Runnable
        if (isFinishing || isDestroyed) return@Runnable
        if (!observedRuntime.isBinderAlive) {
            runtimeDead = true
            launchIfReady()
        } else if (!runtimeDead) {
            failReplacement()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        beginReplacement()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        detachRuntime()
        beginReplacement()
        launchIfReady()
    }

    private fun beginReplacement() {
        runtimeDead = false
        replacement = IntentCompat.getParcelableExtra(intent, EXTRA_LAUNCH, Intent::class.java)
        val control = IntentCompat.getParcelableExtra(intent, EXTRA_CONTROL, Messenger::class.java)
        if (replacement?.component != android.content.ComponentName(this, MicroActivity::class.java)
            || control == null
        ) {
            finish()
            return
        }
        val observedRuntime = control.binder
        runtime = observedRuntime
        val observer = IBinder.DeathRecipient {
            handler.post {
                if (runtime === observedRuntime) {
                    runtimeDead = true
                    launchIfReady()
                }
            }
        }
        try {
            observedRuntime.linkToDeath(observer, 0)
            deathRecipient = observer
        } catch (_: RemoteException) {
            runtimeDead = true
            return
        }
        handler.postDelayed(timeout, REPLACEMENT_TIMEOUT_MILLIS)
        try {
            control.send(Message.obtain(null, STOP_RUNTIME))
        } catch (error: RemoteException) {
            if (!control.binder.isBinderAlive) {
                runtimeDead = true
            } else {
                Log.w(TAG, "Unable to request runtime replacement", error)
                failReplacement()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        launchIfReady()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    private fun launchIfReady() {
        if (!resumed || !runtimeDead || isFinishing || isDestroyed) return
        val launch = replacement ?: return
        replacement = null
        handler.removeCallbacks(timeout)
        startActivity(launch)
        finish()
    }

    private fun failReplacement() {
        Toast.makeText(this, R.string.runtime_replacement_failed, Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onDestroy() {
        detachRuntime()
        super.onDestroy()
    }

    private fun detachRuntime() {
        handler.removeCallbacksAndMessages(null)
        deathRecipient?.let { runtime?.unlinkToDeath(it, 0) }
        deathRecipient = null
        runtime = null
    }

    companion object {
        const val EXTRA_LAUNCH = "runtime.replacement.launch"
        const val EXTRA_CONTROL = "runtime.replacement.control"
        const val STOP_RUNTIME = 1
        private const val REPLACEMENT_TIMEOUT_MILLIS = 5_000L
        private const val TAG = "RuntimeReplacement"
    }
}
