package ru.playsoftware.j2meloader.memory

/** Kotlin-friendly aliases used by the compact runtime Memory Editor formatter. */
internal fun Float.Companion.intBitsToFloat(bits: Int): Float = Float.fromBits(bits)
internal fun Double.Companion.longBitsToDouble(bits: Long): Double = Double.fromBits(bits)
