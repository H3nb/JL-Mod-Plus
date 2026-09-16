/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.h3nb.jlmodplus.input

import java.util.ArrayDeque

/**
 * Identifies one logical producer of a key press.
 *
 * The token must be unique for the lifetime of a physical contact/press.  A
 * new contact or a new input session must use a new [sessionId] (or channel),
 * even when it comes from the same device.  This lets a late UP release the
 * binding captured by its DOWN without consulting a newer mapping.
 */
data class SourceToken(
    val deviceId: String,
    val sessionId: Long,
    val kind: String,
    val channel: String,
) : Comparable<SourceToken> {
    override fun compareTo(other: SourceToken): Int {
        compareValues(kind, other.kind).let { if (it != 0) return it }
        compareValues(deviceId, other.deviceId).let { if (it != 0) return it }
        compareValues(sessionId, other.sessionId).let { if (it != 0) return it }
        return compareValues(channel, other.channel)
    }
}

/** A target object and its lifecycle generation. */
data class OutputTarget(
    val id: String,
    val generation: Long,
) : Comparable<OutputTarget> {
    override fun compareTo(other: OutputTarget): Int {
        compareValues(id, other.id).let { if (it != 0) return it }
        return compareValues(generation, other.generation)
    }
}

/** A canonical emulator output key.  The ledger deliberately does not assign meaning to [code]. */
data class CanonicalOutputKey(
    val code: Int,
) : Comparable<CanonicalOutputKey> {
    val value: Int
        get() = code

    override fun compareTo(other: CanonicalOutputKey): Int = code.compareTo(other.code)
}

enum class KeyEventType {
    DOWN,
    UP,
    REPEAT,
}

/** An effective output transition delivered to the sink. */
data class KeyEvent(
    val type: KeyEventType,
    val target: OutputTarget,
    val key: CanonicalOutputKey,
)

/**
 * Receives effective output transitions.  The ledger calls [record] only
 * when aggregate ownership changes, or when a repeat is due.
 */
fun interface KeyEventSink {
    fun record(event: KeyEvent)
}

fun interface RepeatHandle {
    fun cancel()
}

/**
 * Host-clock scheduler used by the ledger.  Implementations should use a
 * monotonic clock and invoke a task at the requested delay and interval.
 */
interface RepeatScheduler {
    fun nowMillis(): Long

    fun schedule(
        initialDelayMillis: Long,
        intervalMillis: Long,
        task: () -> Unit,
    ): RepeatHandle
}

/** Repeat settings for one source binding. */
data class RepeatSpec(
    val initialDelayMillis: Long = DEFAULT_INITIAL_DELAY_MILLIS,
    val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
    val enabled: Boolean = true,
) {
    init {
        if (enabled) {
            require(initialDelayMillis >= 0L) { "initialDelayMillis must be non-negative" }
            require(intervalMillis > 0L) { "intervalMillis must be positive" }
        }
    }

    companion object {
        const val DEFAULT_INITIAL_DELAY_MILLIS: Long = 400L
        const val DEFAULT_INTERVAL_MILLIS: Long = 80L

        /** A binding that does not produce repeats. */
        val Disabled: RepeatSpec = RepeatSpec(
            initialDelayMillis = DEFAULT_INITIAL_DELAY_MILLIS,
            intervalMillis = DEFAULT_INTERVAL_MILLIS,
            enabled = false,
        )

        /** The controller repeat policy from the gamepad contract. */
        val Default: RepeatSpec = RepeatSpec()
    }
}

/** A key state qualified by its lifecycle target. */
data class TargetedOutputKey(
    val target: OutputTarget,
    val key: CanonicalOutputKey,
) : Comparable<TargetedOutputKey> {
    override fun compareTo(other: TargetedOutputKey): Int {
        val targetComparison = target.compareTo(other.target)
        return if (targetComparison != 0) targetComparison else key.compareTo(other.key)
    }
}

/** Immutable, inspectable distinction between requested ownership and delivered output. */
data class KeyOwnershipState(
    val requested: Map<OutputTarget, Map<CanonicalOutputKey, Set<SourceToken>>>,
    val delivered: Set<TargetedOutputKey>,
)

/**
 * Pure ownership ledger for controller, keyboard, keypad, and touch producers.
 *
 * A DOWN captures its target and output set in [SourceToken] state.  UP takes
 * only the token, so it cannot re-resolve a changed mapping.  The sink sees a
 * DOWN on the first owner, an UP on the last owner, and one repeat producer
 * per target/key.  All transition ordering is deterministic: releases are
 * sorted before presses, and keys/targets use their natural ordering.
 */
class KeyOwnershipLedger(
    private val sink: KeyEventSink,
    private val repeatScheduler: RepeatScheduler? = null,
) {
    private data class Binding(
        val target: OutputTarget,
        val outputs: List<CanonicalOutputKey>,
        val repeat: RepeatSpec,
    )

    private class RepeatRegistration(
        val targetedKey: TargetedOutputKey,
        val owner: SourceToken,
        val spec: RepeatSpec,
        var nextDueMillis: Long,
        var handle: RepeatHandle? = null,
    )

    private val bindings = LinkedHashMap<SourceToken, Binding>()
    private val requestedOwners = LinkedHashMap<
        OutputTarget,
        LinkedHashMap<CanonicalOutputKey, LinkedHashSet<SourceToken>>,
    >()
    private val stateLock = Any()
    private val deliveredOutputs = LinkedHashSet<TargetedOutputKey>()
    /** Projected state after queued transitions; callbacks only observe [deliveredOutputs]. */
    private val plannedDeliveredOutputs = LinkedHashSet<TargetedOutputKey>()
    private val pendingEvents = ArrayDeque<KeyEvent>()
    private val repeats = LinkedHashMap<TargetedOutputKey, RepeatRegistration>()
    private var draining = false

    /**
     * Records a logical DOWN. Repeating the same DOWN is idempotent.
     *
     * [outputs] is normalized to a distinct, ascending list. An empty output
     * set removes an existing binding for the token, which is useful when a
     * resolver changes a held input to `none`.
     */
    fun down(
        source: SourceToken,
        target: OutputTarget,
        outputs: Iterable<CanonicalOutputKey>,
        repeat: RepeatSpec = RepeatSpec.Disabled,
    ) {
        requireSchedulerIfNeeded(repeat)
        val normalizedOutputs = normalize(outputs)
        synchronized(stateLock) {
            val existing = bindings[source]
            if (existing == null) {
                if (normalizedOutputs.isNotEmpty()) {
                    val binding = Binding(target, normalizedOutputs, repeat)
                    val affected = LinkedHashSet<TargetedOutputKey>()
                    bindings[source] = binding
                    addBinding(source, binding, affected)
                    reconcileRepeats(affected)
                    reconcileDelivered(affected)
                }
            } else if (
                existing.target != target ||
                existing.outputs != normalizedOutputs ||
                existing.repeat != repeat
            ) {
                replaceBinding(source, existing, normalizedOutputs, target, repeat)
            }
        }
        dispatchPending()
    }

    /**
     * Replaces an active binding, applying a set-diff for same-target updates.
     * If [repeat] is omitted, the existing repeat policy is retained.
     */
    fun update(
        source: SourceToken,
        target: OutputTarget,
        outputs: Iterable<CanonicalOutputKey>,
        repeat: RepeatSpec? = null,
    ) {
        val existingRepeat = synchronized(stateLock) { bindings[source]?.repeat }
        val effectiveRepeat = repeat ?: existingRepeat ?: RepeatSpec.Disabled
        requireSchedulerIfNeeded(effectiveRepeat)
        val normalizedOutputs = normalize(outputs)
        synchronized(stateLock) {
            val existing = bindings[source]
            if (existing == null) {
                if (normalizedOutputs.isNotEmpty()) {
                    val binding = Binding(target, normalizedOutputs, effectiveRepeat)
                    val affected = LinkedHashSet<TargetedOutputKey>()
                    bindings[source] = binding
                    addBinding(source, binding, affected)
                    reconcileRepeats(affected)
                    reconcileDelivered(affected)
                }
            } else if (
                existing.target != target ||
                existing.outputs != normalizedOutputs ||
                existing.repeat != effectiveRepeat
            ) {
                replaceBinding(source, existing, normalizedOutputs, target, effectiveRepeat)
            }
        }
        dispatchPending()
    }

    /** Releases the binding captured by this source's DOWN. Unmatched UP is ignored. */
    fun up(source: SourceToken) {
        synchronized(stateLock) {
            val binding = bindings.remove(source)
            if (binding != null) {
                val affected = LinkedHashSet<TargetedOutputKey>()
                removeBinding(source, binding, affected)
                reconcileRepeats(affected)
                reconcileDelivered(affected)
            }
        }
        dispatchPending()
    }

    /** Alias that makes producer code read naturally at call sites. */
    fun release(source: SourceToken) = up(source)

    /**
     * Releases every source currently attached to exactly [target]. A target
     * generation is part of equality, so this cannot affect a newer target
     * generation with the same id.
     */
    fun releaseTarget(target: OutputTarget) {
        synchronized(stateLock) {
            val sources = bindings.entries
                .asSequence()
                .filter { it.value.target == target }
                .map { it.key }
                .sorted()
                .toList()
            val affected = LinkedHashSet<TargetedOutputKey>()
            for (source in sources) {
                val binding = bindings.remove(source) ?: continue
                removeBinding(source, binding, affected)
            }
            deliveredOutputs
                .asSequence()
                .filter { it.target == target }
                .forEach(affected::add)
            plannedDeliveredOutputs
                .asSequence()
                .filter { it.target == target }
                .forEach(affected::add)
            reconcileRepeats(affected)
            reconcileDelivered(affected)
        }
        dispatchPending()
    }

    /** Releases every source and every delivered output, preserving target isolation. */
    fun clear() {
        synchronized(stateLock) {
            val affected = LinkedHashSet<TargetedOutputKey>()
            for ((source, binding) in bindings.entries.sortedBy { it.key }) {
                removeBinding(source, binding, affected)
            }
            bindings.clear()
            requestedOwners.clear()
            affected.addAll(deliveredOutputs)
            affected.addAll(plannedDeliveredOutputs)
            cancelAllRepeats()
            reconcileDelivered(affected)
        }
        dispatchPending()
    }

    /** Retries only sink transitions that are still requested but not delivered, or vice versa. */
    fun flush() {
        synchronized(stateLock) {
            val affected = LinkedHashSet<TargetedOutputKey>()
            affected.addAll(deliveredOutputs)
            affected.addAll(plannedDeliveredOutputs)
            requestedOwners.forEach { (target, keys) ->
                keys.keys.forEach { key -> affected += TargetedOutputKey(target, key) }
            }
            reconcileDelivered(affected)
        }
        dispatchPending()
    }

    fun isActive(source: SourceToken): Boolean = synchronized(stateLock) {
        bindings.containsKey(source)
    }

    fun requestedOwners(
        target: OutputTarget,
        key: CanonicalOutputKey,
    ): Set<SourceToken> = synchronized(stateLock) {
        requestedOwners[target]?.get(key)?.let(::orderedSet) ?: emptySet()
    }

    fun isRequested(target: OutputTarget, key: CanonicalOutputKey): Boolean = synchronized(stateLock) {
        requestedOwners[target]?.get(key)?.isNotEmpty() == true
    }

    fun isDelivered(target: OutputTarget, key: CanonicalOutputKey): Boolean = synchronized(stateLock) {
        deliveredOutputs.contains(TargetedOutputKey(target, key))
    }

    fun requestedState(): Map<OutputTarget, Map<CanonicalOutputKey, Set<SourceToken>>> = synchronized(stateLock) {
        requestedStateLocked()
    }

    private fun requestedStateLocked(): Map<OutputTarget, Map<CanonicalOutputKey, Set<SourceToken>>> {
        val snapshot = LinkedHashMap<OutputTarget, Map<CanonicalOutputKey, Set<SourceToken>>>()
        for ((target, keys) in requestedOwners.entries.sortedBy { it.key }) {
            val keySnapshot = LinkedHashMap<CanonicalOutputKey, Set<SourceToken>>()
            for ((key, owners) in keys.entries.sortedBy { it.key }) {
                keySnapshot[key] = orderedSet(owners)
            }
            snapshot[target] = keySnapshot.toMap()
        }
        return snapshot.toMap()
    }

    fun deliveredState(): Set<TargetedOutputKey> = synchronized(stateLock) {
        deliveredOutputs.sorted().toCollection(LinkedHashSet())
    }

    fun state(): KeyOwnershipState = synchronized(stateLock) {
        KeyOwnershipState(
            requested = requestedStateLocked(),
            delivered = deliveredOutputs.sorted().toCollection(LinkedHashSet()),
        )
    }

    private fun replaceBinding(
        source: SourceToken,
        existing: Binding,
        outputs: List<CanonicalOutputKey>,
        target: OutputTarget,
        repeat: RepeatSpec,
    ) {
        val affected = LinkedHashSet<TargetedOutputKey>()
        removeBinding(source, existing, affected)
        bindings.remove(source)
        if (outputs.isNotEmpty()) {
            val replacement = Binding(target, outputs, repeat)
            bindings[source] = replacement
            addBinding(source, replacement, affected)
        }
        reconcileRepeats(affected)
        reconcileDelivered(affected)
    }

    private fun addBinding(
        source: SourceToken,
        binding: Binding,
        affected: MutableSet<TargetedOutputKey>,
    ) {
        val targetKeys = requestedOwners.getOrPut(binding.target) { LinkedHashMap() }
        for (key in binding.outputs) {
            targetKeys.getOrPut(key) { LinkedHashSet() }.add(source)
            affected += TargetedOutputKey(binding.target, key)
        }
    }

    private fun removeBinding(
        source: SourceToken,
        binding: Binding,
        affected: MutableSet<TargetedOutputKey>,
    ) {
        val targetKeys = requestedOwners[binding.target]
        for (key in binding.outputs) {
            val owners = targetKeys?.get(key)
            owners?.remove(source)
            affected += TargetedOutputKey(binding.target, key)
            if (owners?.isEmpty() == true) targetKeys?.remove(key)
        }
        if (targetKeys?.isEmpty() == true) requestedOwners.remove(binding.target)
    }

    private fun reconcileDelivered(affected: Set<TargetedOutputKey>) {
        val ordered = affected.sorted()

        // Releasing first makes target replacement safe and prevents a stale
        // output from overlapping the newly selected target generation.
        for (targetedKey in ordered) {
            if (!isRequestedInternal(targetedKey) && plannedDeliveredOutputs.contains(targetedKey)) {
                enqueue(KeyEvent(KeyEventType.UP, targetedKey.target, targetedKey.key))
            }
        }
        for (targetedKey in ordered) {
            if (isRequestedInternal(targetedKey) && !plannedDeliveredOutputs.contains(targetedKey)) {
                enqueue(KeyEvent(KeyEventType.DOWN, targetedKey.target, targetedKey.key))
            }
        }
    }

    /** Queues a transition and advances the projected state without invoking application code. */
    private fun enqueue(event: KeyEvent) {
        val targetedKey = TargetedOutputKey(event.target, event.key)
        when (event.type) {
            KeyEventType.DOWN -> if (plannedDeliveredOutputs.add(targetedKey)) pendingEvents.addLast(event)
            KeyEventType.UP -> if (plannedDeliveredOutputs.remove(targetedKey)) pendingEvents.addLast(event)
            KeyEventType.REPEAT -> pendingEvents.addLast(event)
        }
    }

    private fun reconcileRepeats(affected: Set<TargetedOutputKey>) {
        for (targetedKey in affected.sorted()) {
            val owners = requestedOwners[targetedKey.target]?.get(targetedKey.key).orEmpty()
            val desiredOwner = owners
                .asSequence()
                .filter { bindings[it]?.repeat?.enabled == true }
                .minWithOrNull(compareBy<SourceToken> { it })
            val existing = repeats[targetedKey]

            if (desiredOwner == null) {
                existing?.handle?.cancel()
                repeats.remove(targetedKey)
                continue
            }

            val desiredSpec = bindings.getValue(desiredOwner).repeat
            if (existing != null && existing.owner == desiredOwner && existing.spec == desiredSpec) {
                continue
            }

            val scheduler = repeatScheduler
                ?: error("A RepeatScheduler is required for enabled repeats")
            val now = scheduler.nowMillis()
            val firstDelay = if (existing == null) {
                desiredSpec.initialDelayMillis
            } else {
                existing.handle?.cancel()
                // Preserve cadence when ownership transfers, but never emit a
                // transfer-time burst. A new owner waits at least one interval.
                val remaining = (existing.nextDueMillis - now).coerceAtLeast(0L)
                maxOf(desiredSpec.intervalMillis, remaining)
            }
            val registration = RepeatRegistration(
                targetedKey = targetedKey,
                owner = desiredOwner,
                spec = desiredSpec,
                nextDueMillis = safeAdd(now, firstDelay),
            )
            registration.handle = scheduler.schedule(
                initialDelayMillis = firstDelay,
                intervalMillis = desiredSpec.intervalMillis,
            ) {
                repeatFired(registration)
            }
            repeats[targetedKey] = registration
        }
    }

    private fun repeatFired(registration: RepeatRegistration) {
        synchronized(stateLock) {
            if (repeats[registration.targetedKey] !== registration ||
                !isRequestedInternal(registration.targetedKey)
            ) return@synchronized
            val scheduler = repeatScheduler ?: return@synchronized
            registration.nextDueMillis = safeAdd(scheduler.nowMillis(), registration.spec.intervalMillis)
            if (deliveredOutputs.contains(registration.targetedKey)) {
                enqueue(
                    KeyEvent(
                        KeyEventType.REPEAT,
                        registration.targetedKey.target,
                        registration.targetedKey.key,
                    ),
                )
            }
        }
        dispatchPending()
    }

    /** Delivers queued events without holding the state lock, including during re-entrant sinks. */
    private fun dispatchPending() {
        if (Thread.holdsLock(stateLock)) return
        synchronized(stateLock) {
            if (draining || pendingEvents.isEmpty()) return
            draining = true
        }
        while (true) {
            val event = synchronized(stateLock) { pendingEvents.peekFirst() }
            if (event == null) {
                synchronized(stateLock) {
                    if (pendingEvents.isEmpty()) {
                        draining = false
                        return
                    }
                }
                continue
            }
            try {
                sink.record(event)
            } catch (failure: Throwable) {
                synchronized(stateLock) { draining = false }
                throw failure
            }
            synchronized(stateLock) {
                pendingEvents.removeFirst()
                val targetedKey = TargetedOutputKey(event.target, event.key)
                when (event.type) {
                    KeyEventType.DOWN -> deliveredOutputs.add(targetedKey)
                    KeyEventType.UP -> deliveredOutputs.remove(targetedKey)
                    KeyEventType.REPEAT -> Unit
                }
                if (pendingEvents.isEmpty()) {
                    draining = false
                    return
                }
            }
        }
    }

    private fun cancelAllRepeats() {
        repeats.values.forEach { it.handle?.cancel() }
        repeats.clear()
    }

    private fun requireSchedulerIfNeeded(repeat: RepeatSpec) {
        if (repeat.enabled && repeatScheduler == null) {
            error("A RepeatScheduler is required for enabled repeats")
        }
    }

    private fun isRequestedInternal(targetedKey: TargetedOutputKey): Boolean =
        requestedOwners[targetedKey.target]?.get(targetedKey.key)?.isNotEmpty() == true

    private fun normalize(outputs: Iterable<CanonicalOutputKey>): List<CanonicalOutputKey> =
        outputs.toSet().sorted()

    private fun <T : Comparable<T>> orderedSet(values: Iterable<T>): Set<T> =
        values.sorted().toCollection(LinkedHashSet())

    private fun safeAdd(first: Long, second: Long): Long =
        if (second > 0L && first > Long.MAX_VALUE - second) Long.MAX_VALUE else first + second
}

/**
 * Deterministic monotonic-clock scheduler for JVM tests and pure-model
 * callers. Advancing to a time runs every due task in due-time then creation
 * order; a task may cancel itself or schedule other tasks.
 */
class ManualRepeatScheduler(
    startTimeMillis: Long = 0L,
) : RepeatScheduler {
    private class Task(
        val id: Long,
        var dueMillis: Long,
        val intervalMillis: Long,
        val action: () -> Unit,
    ) {
        var cancelled: Boolean = false
    }

    private val tasks = LinkedHashMap<Long, Task>()
    private var nextId = 0L
    private var currentTimeMillis = startTimeMillis

    @Synchronized
    override fun nowMillis(): Long = currentTimeMillis

    @Synchronized
    override fun schedule(
        initialDelayMillis: Long,
        intervalMillis: Long,
        task: () -> Unit,
    ): RepeatHandle {
        require(initialDelayMillis >= 0L) { "initialDelayMillis must be non-negative" }
        require(intervalMillis > 0L) { "intervalMillis must be positive" }
        val id = nextId++
        val created = Task(
            id = id,
            dueMillis = safeAdd(currentTimeMillis, initialDelayMillis),
            intervalMillis = intervalMillis,
            action = task,
        )
        tasks[id] = created
        return RepeatHandle {
            synchronized(this) {
                created.cancelled = true
                tasks.remove(id)
            }
        }
    }

    @Synchronized
    fun advanceBy(deltaMillis: Long) {
        require(deltaMillis >= 0L) { "deltaMillis must be non-negative" }
        advanceToInternal(safeAdd(currentTimeMillis, deltaMillis))
    }

    @Synchronized
    fun advanceTo(timeMillis: Long) {
        require(timeMillis >= currentTimeMillis) { "Manual time cannot move backwards" }
        advanceToInternal(timeMillis)
    }

    @Synchronized
    fun pendingTaskCount(): Int = tasks.values.count { !it.cancelled }

    private fun advanceToInternal(timeMillis: Long) {
        while (true) {
            val next = tasks.values
                .asSequence()
                .filter { !it.cancelled }
                .minWithOrNull(compareBy<Task> { it.dueMillis }.thenBy { it.id })
                ?: break
            if (next.dueMillis > timeMillis) break

            currentTimeMillis = next.dueMillis
            if (next.cancelled || tasks[next.id] !== next) continue
            next.action()
            if (!next.cancelled && tasks[next.id] === next) {
                next.dueMillis = safeAdd(next.dueMillis, next.intervalMillis)
            }
        }
        currentTimeMillis = timeMillis
    }

    private fun safeAdd(first: Long, second: Long): Long = Math.addExact(first, second)
}
