// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.memory

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import io.github.h3nb.jlmodplus.crashes.MidletSessionStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** R&D-only bridge to the default-process write authority; called on the controller's IO executor. */
internal object SavedVariablesPrototypeClient {
    private fun withAuthority(
        context: Context,
        operation: (ISavedVariablesPrototype, MidletSessionStore.State) -> String,
    ): JSONObject {
        val runtime = MidletSessionStore.read(context)
            ?: return JSONObject().put("ok", false).put("error", "No active MIDlet")
        if (runtime.generation.isNullOrBlank() || runtime.mainClass.isNullOrBlank() ||
            runtime.appId <= 0L
        ) return JSONObject().put("ok", false).put("error", "Installed MIDlet identity unavailable")
        val latch = CountDownLatch(1)
        var authority: ISavedVariablesPrototype? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                authority = ISavedVariablesPrototype.Stub.asInterface(binder)
                latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) {
                authority = null
            }
        }
        val bound = context.bindService(
            Intent(context, SavedVariablesPrototypeService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        if (!bound) return JSONObject().put("ok", false).put("error", "Storage service unavailable")
        return try {
            if (!latch.await(5, TimeUnit.SECONDS) || authority == null) {
                JSONObject().put("ok", false).put("error", "Storage service did not connect")
            } else {
                JSONObject(operation(authority!!, runtime))
            }
        } finally {
            context.unbindService(connection)
        }
    }

    fun list(context: Context): JSONObject = withAuthority(context) { api, state ->
        api.list(state.generation, state.appPath, state.appId, state.mainClass)
    }

    fun save(context: Context, label: String, descriptor: JSONObject): JSONObject =
        withAuthority(context) { api, state ->
            api.save(state.generation, state.appPath, state.appId, state.mainClass,
                label, descriptor.toString())
        }

    fun remove(context: Context, id: String): JSONObject = withAuthority(context) { api, state ->
        api.remove(state.generation, state.appPath, state.appId, state.mainClass, id)
    }

    fun rows(response: JSONObject): List<SavedVariablePrototypeRow> {
        val items = response.optJSONArray("items") ?: JSONArray()
        return (0 until items.length()).mapNotNull { index ->
            val row = items.optJSONObject(index) ?: return@mapNotNull null
            val id = row.optString("id")
            if (id.isBlank()) return@mapNotNull null
            SavedVariablePrototypeRow(
                id = id,
                label = row.optString("label"),
                description = row.optJSONArray("names")?.let {
                    if (it.length() > 0) it.optString(it.length() - 1) else ""
                }.orEmpty(),
            )
        }
    }

    fun descriptor(response: JSONObject, id: String): JSONObject? {
        val items = response.optJSONArray("items") ?: return null
        for (index in 0 until items.length()) {
            val entry = items.optJSONObject(index) ?: continue
            if (entry.optString("id") == id) return entry
        }
        return null
    }

    fun toJson(descriptor: android.os.Bundle): JSONObject {
        fun array(name: String) = JSONArray().also { out ->
            descriptor.getStringArray(name)?.forEach(out::put)
        }
        return JSONObject().put("rootKind", descriptor.getInt("researchRootKind"))
            .put("rootClass", descriptor.getString("researchRootClass"))
            .put("classes", array("researchClasses"))
            .put("names", array("researchNames"))
            .put("types", array("researchTypes"))
    }

    fun toBundle(descriptor: JSONObject): android.os.Bundle {
        fun strings(key: String): Array<String> {
            val items = descriptor.getJSONArray(key)
            return Array(items.length()) { index -> items.getString(index) }
        }
        return android.os.Bundle().apply {
            putInt("researchSchema", 1)
            putInt("researchRootKind", descriptor.getInt("rootKind"))
            putString("researchRootClass", descriptor.getString("rootClass"))
            putStringArray("researchClasses", strings("classes"))
            putStringArray("researchNames", strings("names"))
            putStringArray("researchTypes", strings("types"))
        }
    }
}

internal data class SavedVariablePrototypeRow(
    val id: String,
    val label: String,
    val description: String,
)
