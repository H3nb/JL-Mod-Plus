/* Licensed under the Apache License, Version 2.0. */
package io.github.h3nb.jlmodplus.config

import io.github.h3nb.jlmodplus.EmulatorApplication
import javax.microedition.util.ContextHolder

/** Canonical per-key overrides: null removes a factory property; absence inherits it. */
object SystemPropertyLayers {
    // Immutable historical input baseline. Never update it when changing the live factory.
    private val historicalFactory = """
        microedition.configuration: CDLC-1.1
        microedition.profiles: MIDP-2.0
        microedition.encoding: ISO-8859-1
        microedition.platform: Nokia 6233
        microedition.io.file.FileConnection.version: 1.0
        microedition.sensor.version: 1
        microedition.m3g.version: 1.1
        microedition.media.version: 1.0
        microedition.pim.version: 1.0
        supports.mixing: true
        supports.audio.capture: true
        supports.video.capture: false
        supports.recording: false
        com.siemens.mp.systemfolder.ringingtone: fs/MyStuff/Ringtones
        com.siemens.mp.systemfolder.pictures: fs/MyStuff/Pictures
        com.siemens.OSVersion: 11
        device.imei: 000000000000000
        com.siemens.IMEI: 000000000000000
        com.sonyericsson.imei: IMEI 00460101-501594-5-00
        com.nokia.mid.impl.isa.visual_radio_operator_id: 0
        com.nokia.mid.impl.isa.visual_radio_channel_freq: 0
        com.nokia.mid.ui.DirectGraphics.PIXEL_FORMAT: 565
    """.trimIndent()

    @JvmStatic fun currentFactory(): String =
        if (EmulatorApplication.getInstance() == null) historicalFactory
        else ContextHolder.getAssetAsString("defaults/system.props")

    private fun parse(text: String?): LinkedHashMap<String, String> {
        val result = linkedMapOf<String, String>()
        text?.lineSequence()?.forEach { line ->
            val i = line.indexOf(':')
            if (i > 0) {
                val key = line.substring(0, i).trim()
                if (key.isNotEmpty()) result[key] = line.substring(i + 1).trim()
            }
        }
        return result
    }

    private fun format(properties: Map<String, String>): String =
        properties.toSortedMap().entries.joinToString("") { (k, v) -> "$k: $v\n" }

    /** Convert historical full snapshots to differences; omitted historical keys stay omitted. */
    @JvmStatic fun migrate(legacy: String?): Map<String, String?> {
        if (legacy == null) return emptyMap()
        val old = parse(historicalFactory)
        val supplied = parse(legacy)
        val result = linkedMapOf<String, String?>()
        for ((key, value) in supplied) if (old[key] != value) result[key] = value
        for (key in old.keys) if (!supplied.containsKey(key)) result[key] = null
        return result
    }

    @JvmStatic fun effective(overrides: Map<String, String?>, factory: String): String {
        val resolved = parse(factory)
        for ((key, value) in overrides) {
            if (value == null) resolved.remove(key) else resolved[key] = value
        }
        return format(resolved)
    }

    /** Full text is a nonpersistent projection of factory and persisted overrides. */
    @JvmStatic @JvmOverloads
    fun hydrate(model: ProfileModel, factory: String = currentFactory()) {
        model.systemPropertyDefaults = factory
        model.systemProperties = effective(model.systemPropertyOverrides, factory)
    }

    /** Saving an unchanged draft must not freeze the inherited factory into overrides. */
    @JvmStatic fun applyEditorText(model: ProfileModel, edited: String?) {
        val factory = model.systemPropertyDefaults ?: currentFactory()
        val existing = effective(model.systemPropertyOverrides, factory)
        val supplied = parse(edited)
        if (format(supplied) == existing) {
            model.systemProperties = existing
            return
        }
        val defaults = parse(factory)
        val overrides = linkedMapOf<String, String?>()
        for ((key, value) in supplied) if (defaults[key] != value) overrides[key] = value
        for (key in defaults.keys) if (!supplied.containsKey(key)) overrides[key] = null
        model.systemPropertyOverrides = overrides
        model.systemPropertyDefaults = factory
        model.systemProperties = effective(overrides, factory)
    }
}
