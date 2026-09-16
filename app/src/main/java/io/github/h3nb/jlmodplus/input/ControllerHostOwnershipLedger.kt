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

/** A host-side output that can be shared by several controller sources. */
sealed interface ControllerHostOutput {
    /** A host command is independent from MIDP/Canvas key codes. */
    data class Command(val command: HostCommand) : ControllerHostOutput
}

data class ControllerHostTarget(
    val id: String,
    val generation: Long,
) : Comparable<ControllerHostTarget> {
    override fun compareTo(other: ControllerHostTarget): Int {
        compareValues(id, other.id).let { if (it != 0) return it }
        return compareValues(generation, other.generation)
    }
}

enum class ControllerHostEventType {
    DOWN,
    UP,
    REPEAT,
}

data class ControllerHostEvent(
    val type: ControllerHostEventType,
    val target: ControllerHostTarget,
    val output: ControllerHostOutput,
)

fun interface ControllerHostOutputSink {
    fun record(event: ControllerHostEvent)
}

/**
 * Ownership ledger for controller output delivered to a native host Screen.
 *
 * Canvas already owns an instance of [KeyOwnershipLedger]. Screens cannot use
 * Canvas callbacks, so this typed counterpart keeps the same first-owner/
 * last-owner, target-generation, set-diff, and repeat guarantees at the host
 * boundary. Host actions deliberately use disabled repeat at call sites.
 */
class ControllerHostOwnershipLedger(
    private val sink: ControllerHostOutputSink,
    private val repeatScheduler: RepeatScheduler? = null,
) {
    private data class Binding(
        val target: ControllerHostTarget,
        val outputs: List<ControllerHostOutput>,
        val repeat: RepeatSpec,
    )

    private class RepeatRegistration(
        val target: ControllerHostTarget,
        val output: ControllerHostOutput,
        val owner: SourceToken,
        val spec: RepeatSpec,
        var nextDueMillis: Long,
        var handle: RepeatHandle? = null,
    )

    private val bindings = LinkedHashMap<SourceToken, Binding>()
    private val requestedOwners = LinkedHashMap<
        ControllerHostTarget,
        LinkedHashMap<ControllerHostOutput, LinkedHashSet<SourceToken>>,
    >()
    private val stateLock = Any()
    private val delivered = LinkedHashSet<ControllerHostTargetedOutput>()
    /** Projected state after queued transitions; callbacks only observe [delivered]. */
    private val plannedDelivered = LinkedHashSet<ControllerHostTargetedOutput>()
    private val pendingEvents = ArrayDeque<ControllerHostEvent>()
    private val repeats = LinkedHashMap<ControllerHostTargetedOutput, RepeatRegistration>()
    private var draining = false

    fun down(
        source: SourceToken,
        target: ControllerHostTarget,
        outputs: Iterable<ControllerHostOutput>,
        repeat: RepeatSpec = RepeatSpec.Disabled,
    ) {
        requireSchedulerIfNeeded(repeat)
        val normalized = normalize(outputs)
        synchronized(stateLock) {
            val existing = bindings[source]
            if (existing == null) {
                if (normalized.isNotEmpty()) {
                    val binding = Binding(target, normalized, repeat)
                    val affected = LinkedHashSet<ControllerHostTargetedOutput>()
                    bindings[source] = binding
                    addBinding(source, binding, affected)
                    reconcileRepeats(affected)
                    reconcileDelivered(affected)
                }
            } else if (existing.target != target || existing.outputs != normalized ||
                existing.repeat != repeat
            ) {
                replace(source, existing, target, normalized, repeat)
            }
        }
        dispatchPending()
    }

    fun update(
        source: SourceToken,
        target: ControllerHostTarget,
        outputs: Iterable<ControllerHostOutput>,
        repeat: RepeatSpec? = null,
    ) {
        val existingRepeat = synchronized(stateLock) { bindings[source]?.repeat }
        val effectiveRepeat = repeat ?: existingRepeat ?: RepeatSpec.Disabled
        requireSchedulerIfNeeded(effectiveRepeat)
        val normalized = normalize(outputs)
        synchronized(stateLock) {
            val existing = bindings[source]
            if (existing == null) {
                if (normalized.isNotEmpty()) {
                    val binding = Binding(target, normalized, effectiveRepeat)
                    val affected = LinkedHashSet<ControllerHostTargetedOutput>()
                    bindings[source] = binding
                    addBinding(source, binding, affected)
                    reconcileRepeats(affected)
                    reconcileDelivered(affected)
                }
            } else if (existing.target != target || existing.outputs != normalized ||
                existing.repeat != effectiveRepeat
            ) {
                replace(source, existing, target, normalized, effectiveRepeat)
            }
        }
        dispatchPending()
    }

    fun up(source: SourceToken) {
        synchronized(stateLock) {
            val binding = bindings.remove(source)
            if (binding != null) {
                val affected = LinkedHashSet<ControllerHostTargetedOutput>()
                removeBinding(source, binding, affected)
                reconcileRepeats(affected)
                reconcileDelivered(affected)
            }
        }
        dispatchPending()
    }

    fun releaseTarget(target: ControllerHostTarget) {
        synchronized(stateLock) {
            val sources = bindings.entries
                .asSequence()
                .filter { it.value.target == target }
                .map { it.key }
                .sorted()
                .toList()
            val affected = LinkedHashSet<ControllerHostTargetedOutput>()
            sources.forEach { source ->
                val binding = bindings.remove(source) ?: return@forEach
                removeBinding(source, binding, affected)
            }
            delivered.filterTo(affected) { it.target == target }
            plannedDelivered.filterTo(affected) { it.target == target }
            reconcileRepeats(affected)
            reconcileDelivered(affected)
        }
        dispatchPending()
    }

    fun clear() {
        synchronized(stateLock) {
            val affected = LinkedHashSet<ControllerHostTargetedOutput>()
            bindings.entries.sortedBy { it.key }.forEach { (source, binding) ->
                removeBinding(source, binding, affected)
            }
            bindings.clear()
            requestedOwners.clear()
            affected += delivered
            affected += plannedDelivered
            repeats.values.forEach { it.handle?.cancel() }
            repeats.clear()
            reconcileDelivered(affected)
        }
        dispatchPending()
    }

    fun isRequested(target: ControllerHostTarget, output: ControllerHostOutput): Boolean = synchronized(stateLock) {
        requestedOwners[target]?.get(output)?.isNotEmpty() == true
    }

    fun requestedOwners(
        target: ControllerHostTarget,
        output: ControllerHostOutput,
    ): Set<SourceToken> = synchronized(stateLock) {
        requestedOwners[target]?.get(output)
            ?.sorted()
            ?.toCollection(LinkedHashSet())
            ?: emptySet()
    }

    fun deliveredState(): Set<ControllerHostTargetedOutput> = synchronized(stateLock) {
        delivered.sortedWith(targetedComparator).toCollection(LinkedHashSet())
    }

    private fun replace(
        source: SourceToken,
        existing: Binding,
        target: ControllerHostTarget,
        outputs: List<ControllerHostOutput>,
        repeat: RepeatSpec,
    ) {
        val affected = LinkedHashSet<ControllerHostTargetedOutput>()
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
        affected: MutableSet<ControllerHostTargetedOutput>,
    ) {
        val targetOutputs = requestedOwners.getOrPut(binding.target) { LinkedHashMap() }
        binding.outputs.forEach { output ->
            targetOutputs.getOrPut(output) { LinkedHashSet() }.add(source)
            affected += ControllerHostTargetedOutput(binding.target, output)
        }
    }

    private fun removeBinding(
        source: SourceToken,
        binding: Binding,
        affected: MutableSet<ControllerHostTargetedOutput>,
    ) {
        val targetOutputs = requestedOwners[binding.target]
        binding.outputs.forEach { output ->
            val owners = targetOutputs?.get(output)
            owners?.remove(source)
            affected += ControllerHostTargetedOutput(binding.target, output)
            if (owners?.isEmpty() == true) targetOutputs?.remove(output)
        }
        if (targetOutputs?.isEmpty() == true) requestedOwners.remove(binding.target)
    }

    private fun reconcileDelivered(affected: Set<ControllerHostTargetedOutput>) {
        val ordered = affected.sortedWith(targetedComparator)
        ordered.filter { !isRequestedInternal(it) && plannedDelivered.contains(it) }
            .forEach { enqueue(ControllerHostEvent(ControllerHostEventType.UP, it.target, it.output)) }
        ordered.filter { isRequestedInternal(it) && !plannedDelivered.contains(it) }
            .forEach { enqueue(ControllerHostEvent(ControllerHostEventType.DOWN, it.target, it.output)) }
    }

    /** Queues a transition and advances projected state without invoking host code. */
    private fun enqueue(event: ControllerHostEvent) {
        val targeted = ControllerHostTargetedOutput(event.target, event.output)
        when (event.type) {
            ControllerHostEventType.DOWN -> if (plannedDelivered.add(targeted)) pendingEvents.addLast(event)
            ControllerHostEventType.UP -> if (plannedDelivered.remove(targeted)) pendingEvents.addLast(event)
            ControllerHostEventType.REPEAT -> pendingEvents.addLast(event)
        }
    }

    private fun reconcileRepeats(affected: Set<ControllerHostTargetedOutput>) {
        for (targeted in affected.sortedWith(targetedComparator)) {
            val owners = requestedOwners[targeted.target]?.get(targeted.output).orEmpty()
            val desiredOwner = owners
                .asSequence()
                .filter { bindings[it]?.repeat?.enabled == true }
                .minWithOrNull(compareBy { it })
            val existing = repeats[targeted]
            if (desiredOwner == null) {
                existing?.handle?.cancel()
                repeats.remove(targeted)
                continue
            }
            val desiredSpec = bindings.getValue(desiredOwner).repeat
            if (existing != null && existing.owner == desiredOwner && existing.spec == desiredSpec) continue
            val scheduler = repeatScheduler
                ?: error("A RepeatScheduler is required for enabled repeats")
            val now = scheduler.nowMillis()
            val delay = if (existing == null) {
                desiredSpec.initialDelayMillis
            } else {
                existing.handle?.cancel()
                maxOf(desiredSpec.intervalMillis,
                    (existing.nextDueMillis - now).coerceAtLeast(0L))
            }
            val registration = RepeatRegistration(
                target = targeted.target,
                output = targeted.output,
                owner = desiredOwner,
                spec = desiredSpec,
                nextDueMillis = safeAdd(now, delay),
            )
            registration.handle = scheduler.schedule(delay, desiredSpec.intervalMillis) {
                repeatFired(registration)
            }
            repeats[targeted] = registration
        }
    }

    private fun repeatFired(registration: RepeatRegistration) {
        synchronized(stateLock) {
            val targeted = ControllerHostTargetedOutput(registration.target, registration.output)
            if (repeats[targeted] !== registration || !isRequestedInternal(targeted)) {
                return@synchronized
            }
            val scheduler = repeatScheduler ?: return@synchronized
            registration.nextDueMillis = safeAdd(scheduler.nowMillis(), registration.spec.intervalMillis)
            if (delivered.contains(targeted)) {
                enqueue(ControllerHostEvent(ControllerHostEventType.REPEAT, targeted.target, targeted.output))
            }
        }
        dispatchPending()
    }

    /** Delivers pending transitions without holding [stateLock], including during re-entrant sinks. */
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
                val targeted = ControllerHostTargetedOutput(event.target, event.output)
                when (event.type) {
                    ControllerHostEventType.DOWN -> delivered += targeted
                    ControllerHostEventType.UP -> delivered -= targeted
                    ControllerHostEventType.REPEAT -> Unit
                }
                if (pendingEvents.isEmpty()) {
                    draining = false
                    return
                }
            }
        }
    }

    private fun requireSchedulerIfNeeded(repeat: RepeatSpec) {
        if (repeat.enabled && repeatScheduler == null) {
            error("A RepeatScheduler is required for enabled repeats")
        }
    }

    private fun isRequestedInternal(targeted: ControllerHostTargetedOutput): Boolean =
        requestedOwners[targeted.target]?.get(targeted.output)?.isNotEmpty() == true

    private fun normalize(outputs: Iterable<ControllerHostOutput>): List<ControllerHostOutput> =
        outputs.toSet().sortedWith(outputComparator)

    private fun safeAdd(first: Long, second: Long): Long =
        if (second > 0L && first > Long.MAX_VALUE - second) Long.MAX_VALUE else first + second

    companion object {
        private val outputComparator = Comparator<ControllerHostOutput> { first, second ->
            (first as ControllerHostOutput.Command).command.name.compareTo(
                (second as ControllerHostOutput.Command).command.name,
            )
        }

        private val targetedComparator = Comparator<ControllerHostTargetedOutput> { first, second ->
            val target = first.target.compareTo(second.target)
            if (target != 0) target else outputComparator.compare(first.output, second.output)
        }

    }
}

data class ControllerHostTargetedOutput(
    val target: ControllerHostTarget,
    val output: ControllerHostOutput,
)
