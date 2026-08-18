/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.playsoftware.j2meloader.librarydb

import java.util.concurrent.locks.ReentrantLock

/**
 * Short-lived lease used only for the filesystem publish point of an install/reinstall.
 * Conversion, copying, scanning and database work must stay outside this lease.
 */
class LibraryGenerationLease internal constructor(
    private val lock: ReentrantLock,
) : AutoCloseable {
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        lock.unlock()
    }
}
