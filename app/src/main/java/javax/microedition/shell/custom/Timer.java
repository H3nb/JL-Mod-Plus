/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

/* Modified in JL-Mod Plus: converted guest timers use the parent-owned timing bridge while
 * retaining the inherited Apache Timer contracts. */

package javax.microedition.shell.custom;

import java.util.Date;
import java.util.concurrent.locks.LockSupport;

import javax.microedition.shell.GuestTimingBridge;
import javax.microedition.shell.timing.TimingSession;
import javax.microedition.shell.timing.TimingSnapshot;

/**
 * Timers schedule one-shot or recurring {@link TimerTask tasks} for execution.
 * Prefer {@link java.util.concurrent.ScheduledThreadPoolExecutor
 * ScheduledThreadPoolExecutor} for new code.
 *
 * <p>Each timer has one thread on which tasks are executed sequentially. When
 * this thread is busy running a task, runnable tasks may be subject to delays.
 *
 * <p>One-shot tasks are scheduled to run at an absolute time or after a relative
 * delay.
 *
 * <p>Recurring tasks are scheduled with either a fixed period or a fixed rate:
 * <ul>
 *   <li>With the default <strong>fixed-delay execution</strong>, each
 *       successive run of a task is scheduled relative to the actual
 *       execution time (dispatch/start) of the previous run, so delays
 *       accumulate naturally.
 *   <li>With <strong>fixed-rate execution</strong>, the start time of each
 *       successive run of a task is scheduled without regard for when the
 *       previous run took place. This may result in a series of bunched-up runs
 *       (one launched immediately after another) if delays prevent the timer
 *       from starting tasks on time.
 * </ul>
 *
 * <p>When a timer is no longer needed, users should call {@link #cancel}, which
 * releases the timer's thread and other resources. Timers not explicitly
 * cancelled may hold resources indefinitely.
 *
 * <p>This class does not offer guarantees about the real-time nature of task
 * scheduling. Multiple threads can share a single timer without
 * synchronization.
 */
public class Timer {

    private static final class TimerImpl extends Thread {

        /** A binary heap whose key is comparable within one Timer deadline domain. */
        private static final class TimerHeap {
            private static final int DEFAULT_HEAP_SIZE = 256;

            private TimerTask[] timers = new TimerTask[DEFAULT_HEAP_SIZE];
            private int size;

            TimerTask minimum() {
                return size == 0 ? null : timers[0];
            }

            boolean isEmpty() {
                return size == 0;
            }

            void insert(TimerTask task) {
                if (timers.length == size) {
                    TimerTask[] appendedTimers = new TimerTask[size * 2];
                    System.arraycopy(timers, 0, appendedTimers, 0, size);
                    timers = appendedTimers;
                }
                timers[size] = task;
                upHeap(size++);
            }

            void delete(int position) {
                if (position < 0 || position >= size) {
                    return;
                }
                int replacement = --size;
                TimerTask moved = timers[replacement];
                timers[replacement] = null;
                if (position == replacement) {
                    return;
                }
                timers[position] = moved;
                int parent = (position - 1) / 2;
                if (position > 0 && timers[position].when < timers[parent].when) {
                    upHeap(position);
                } else {
                    downHeap(position);
                }
            }

            private void upHeap(int position) {
                int current = position;
                while (current > 0) {
                    int parent = (current - 1) / 2;
                    if (timers[parent].when <= timers[current].when) {
                        break;
                    }
                    TimerTask tmp = timers[current];
                    timers[current] = timers[parent];
                    timers[parent] = tmp;
                    current = parent;
                }
            }

            private void downHeap(int position) {
                int current = position;
                while (true) {
                    int child = 2 * current + 1;
                    if (child >= size) {
                        return;
                    }
                    if (child + 1 < size
                            && timers[child + 1].when < timers[child].when) {
                        child++;
                    }
                    if (timers[current].when <= timers[child].when) {
                        return;
                    }
                    TimerTask tmp = timers[current];
                    timers[current] = timers[child];
                    timers[child] = tmp;
                    current = child;
                }
            }

            void reset() {
                timers = new TimerTask[DEFAULT_HEAP_SIZE];
                size = 0;
            }

            int deleteIfCancelled() {
                int deleted = 0;
                for (int i = 0; i < size; i++) {
                    TimerTask task = timers[i];
                    boolean cancelled;
                    synchronized (task.lock) {
                        cancelled = task.cancelled;
                    }
                    if (cancelled) {
                        delete(i--);
                        deleted++;
                    }
                }
                return deleted;
            }

            int getTask(TimerTask task) {
                for (int i = 0; i < size; i++) {
                    if (timers[i] == task) {
                        return i;
                    }
                }
                return -1;
            }
        }

        /**
         * Keeps separate heaps for guest-monotonic and absolute Date deadlines. The two heads
         * are the only values that need host-delay projection, so mixed-domain selection remains
         * correct without scanning every queued task after each wakeup.
         */
        private static final class TimerQueue {
            private final TimerHeap relativeTasks = new TimerHeap();
            private final TimerHeap absoluteTasks = new TimerHeap();

            boolean isEmpty() {
                return relativeTasks.isEmpty() && absoluteTasks.isEmpty();
            }

            TimerTask minimum(TimerImpl timer) {
                TimerTask relative = relativeTasks.minimum();
                TimerTask absolute = absoluteTasks.minimum();
                if (relative == null) {
                    return absolute;
                }
                if (absolute == null) {
                    return relative;
                }
                return timer.hostDelayMillis(relative) <= timer.hostDelayMillis(absolute)
                        ? relative : absolute;
            }

            void insert(TimerTask task) {
                heapFor(task).insert(task);
            }

            boolean contains(TimerTask task) {
                return heapFor(task).getTask(task) >= 0
                        || (task.relativeGuestTime
                        ? absoluteTasks.getTask(task) >= 0
                        : relativeTasks.getTask(task) >= 0);
            }

            void delete(TimerTask task) {
                TimerHeap heap = heapFor(task);
                int position = heap.getTask(task);
                if (position < 0) {
                    TimerHeap other = task.relativeGuestTime ? absoluteTasks : relativeTasks;
                    position = other.getTask(task);
                    heap = other;
                }
                heap.delete(position);
            }

            void reset() {
                relativeTasks.reset();
                absoluteTasks.reset();
            }

            int deleteIfCancelled() {
                return relativeTasks.deleteIfCancelled() + absoluteTasks.deleteIfCancelled();
            }

            private TimerHeap heapFor(TimerTask task) {
                return task.relativeGuestTime ? relativeTasks : absoluteTasks;
            }
        }

        /**
         * True if the method cancel() of the Timer was called or the !!!stop()
         * method was invoked
         */
        private boolean cancelled;

        /**
         * True if the Timer has become garbage
         */
        private boolean finished;

        /** True only while this worker is in the guest-duration wait outside the Timer lock. */
        private boolean guestWaiting;

        /** Session captured at guest Timer construction; null means host-owned Timer behavior. */
        private final TimingSession timingSession;

        /**
         * Contains scheduled events, sorted according to
         * {@code when} field of TaskScheduled object.
         */
        private final TimerQueue tasks = new TimerQueue();

        /**
         * Starts a new timer.
         *
         * @param name thread's name
         * @param isDaemon daemon thread or not
         */
        TimerImpl(String name, boolean isDaemon) {
            this.setName(name);
            this.setDaemon(isDaemon);
            this.timingSession = GuestTimingBridge.activeSession();
            if (timingSession != null) {
                timingSession.registerCloseAwareThread(this);
            }
            this.start();
        }

        /**
         * This method will be launched on separate thread for each Timer
         * object.
         */
        @Override
        public void run() {
            try {
                while (true) {
                    TimerTask task = null;
                    TimerTask taskToSleep = null;
                    long timeToSleep = 0L;
                    long fixedDelayStartTime = 0L;
                    long fixedDelayStartWallTime = 0L;
                    boolean waitingForTask = false;
                    synchronized (this) {
                        // need to check cancelled inside the synchronized block
                        if (cancelled || isStaleTimingSession()) {
                            return;
                        }
                        if (tasks.isEmpty()) {
                            if (finished) {
                                return;
                            }
                            // no tasks scheduled -- park until any task appears or the session
                            // closes. Unlike Object.wait(), this can be woken by the session.
                            waitingForTask = true;
                        } else {
                            // The queue can contain relative guest-monotonic and absolute
                            // Date/epoch deadlines at the same time. Raw `when` values are not
                            // comparable across those domains, so choose by remaining time.
                            task = tasks.minimum(this);
                            long currentTime = currentTimeMillis(task);
                            if (isStaleTimingSession()) {
                                return;
                            }

                            synchronized (task.lock) {
                                if (task.cancelled) {
                                    tasks.delete(task);
                                    continue;
                                }

                                refreshScheduledWallTime(task);

                                // check the time to sleep for the first task scheduled
                                timeToSleep = task.when - currentTime;
                            }

                            if (timeToSleep > 0) {
                                // Do not hold the Timer lock while waiting. Scheduling/canceling
                                // must be able to interrupt this worker and publish an earlier
                                // deadline.
                                taskToSleep = task;
                                task = null;
                                guestWaiting = true;
                            } else {
                                // no sleep is necessary before launching the task

                                synchronized (task.lock) {
									if (!tasks.contains(task)) {
                                        task = null;
                                        continue;
                                    }
                                    if (task.cancelled) {
										tasks.delete(task);
                                        task = null;
                                        continue;
                                    }

                                    if (task.period >= 0 && !task.fixedRate) {
                                        // CLDC fixed-delay execution is relative to the actual
                                        // execution/dispatch time, not callback completion. Capture
                                        // both domains before entering guest code; the next deadline
                                        // is published after run() returns so queue ownership stays
                                        // serialized.
                                        fixedDelayStartTime = currentTimeMillis(task);
                                        fixedDelayStartWallTime = wallTimeMillis();
                                    }

                                    // Publish the nominal Date.getTime() value, not the dispatch
                                    // clock. Games use this value for tardiness and frame-skipping
                                    // decisions.
                                    task.setScheduledTime(task.scheduledWallTime);
                                    task.executionStarted = true;

                                    // remove task from queue
                                    tasks.delete(task);

                                    // set when the next task should be launched
                                    if (task.period >= 0) {
                                        // this is a repeating task,
                                        if (task.fixedRate) {
                                            // task is scheduled at fixed rate
                                            task.when = saturatingAdd(task.when, task.period);
                                            task.scheduledWallTime = nextFixedRateWallTime(task);
                                        } else {
                                            // Fixed-delay tasks are scheduled after the current run
                                            // completes. Keep the task out of the queue while guest
                                            // code is executing.
                                            task.when = 0L;
                                        }

                                        if (task.fixedRate) {
                                            // Fixed-rate tasks remain queued before the callback so
                                            // missed periods can catch up according to the CLDC
                                            // contract.
                                            insertTask(task);
                                        }
                                    } else {
                                        task.when = 0;
                                    }
                                }
                            }
                        }
                    }

                    if (waitingForTask) {
                        LockSupport.park(this);
                        // Match Object.wait()'s clearing of an internal interrupt while keeping
                        // lifecycle and schedule wakeups independent from guest code.
                        Thread.interrupted();
                        continue;
                    }

                    if (task == null) {
                        try {
                            sleepTaskDuration(taskToSleep, timeToSleep);
                        } catch (InterruptedException ignored) {
                            // A new task, cancellation, or a host lifecycle transition requested
                            // a fresh queue evaluation.
                        } catch (IllegalStateException e) {
                            if (isStaleTimingSession()) {
                                return;
                            }
                            throw e;
                        } finally {
                            synchronized (this) {
                                guestWaiting = false;
                            }
                            // Internal wakeups must never leak as an interrupt into guest
                            // TimerTask code that runs after the queue is reevaluated.
                            Thread.interrupted();
                        }
                        continue;
                    }

                    boolean taskCompletedNormally = false;
                    try {
                        task.run();
                        taskCompletedNormally = true;
                    } finally {
                        if (!taskCompletedNormally) {
                            synchronized (this) {
                                cancelled = true;
                            }
                        }
                    }
                    if (taskCompletedNormally && task.period >= 0 && !task.fixedRate) {
                        rescheduleFixedDelayTask(task, fixedDelayStartTime, fixedDelayStartWallTime);
                    }
                }
			} finally {
				// A worker that exits for any reason is a terminated Timer, even when the exit did
				// not pass through Timer.cancel(). Publishing this state under the Timer lock makes
				// later schedule() calls fail with the CLDC-required IllegalStateException instead
				// of silently accepting tasks into a queue that no thread services.
				synchronized (this) {
					cancelled = true;
					tasks.reset();
					guestWaiting = false;
				}
				if (timingSession != null) {
					timingSession.unregisterCloseAwareThread(this);
                }
            }
        }

        private void insertTask(TimerTask newTask) {
            // callers are synchronized
            tasks.insert(newTask);
            LockSupport.unpark(this);
            if (guestWaiting) {
                this.interrupt();
            }
        }

			private void rescheduleFixedDelayTask(
					TimerTask task, long executionStartTime, long executionStartWallTime) {
            synchronized (this) {
                if (cancelled || isStaleTimingSession()) {
                    return;
                }
                synchronized (task.lock) {
                    if (task.cancelled) {
                        return;
                    }
					task.when = saturatingAdd(executionStartTime, task.period);
					if (task.relativeGuestTime && timingSession != null) {
						task.scheduledWallTime =
								timingSession.wallTimeMillisForGuestMonotonicMillis(task.when);
					} else {
						task.scheduledWallTime = saturatingAdd(
								executionStartWallTime,
								wallDelayMillis(task.period, task.relativeGuestTime));
					}
					insertTask(task);
                }
            }
        }

        private long currentTimeMillis(boolean relativeGuestTime) {
            if (timingSession == null) {
                return System.currentTimeMillis();
            }
            if (relativeGuestTime) {
                TimingSnapshot snapshot = timingSession.snapshotIfOpen();
                return snapshot == null
                        ? System.currentTimeMillis()
                        : snapshot.guestMonotonicNanos() / 1_000_000L;
            }
            return wallTimeMillis();
        }

        private long currentTimeMillis(TimerTask task) {
            return currentTimeMillis(task.relativeGuestTime);
        }

        /**
         * Relative schedules have a guest-monotonic deadline but publish a Date-domain nominal
         * time. In real-wall-clock mode that nominal wall time must be reprojected after a speed
         * transition; otherwise scheduledExecutionTime() can report the target calculated at the
         * old speed even though the task is now due much earlier or later.
         */
        private void refreshScheduledWallTime(TimerTask task) {
			if (!task.relativeGuestTime
					|| timingSession == null) {
				return;
			}
            long scheduledWallTime = timingSession.wallTimeMillisForGuestMonotonicMillis(task.when);
            task.scheduledWallTime = scheduledWallTime;
            if (task.period > 0L) {
                long nextWhen = saturatingAdd(task.when, task.period);
                if (nextWhen > task.when) {
                    long nextWallTime =
                            timingSession.wallTimeMillisForGuestMonotonicMillis(nextWhen);
                    task.scheduledWallPeriodMillis =
                            nonNegativeDifference(nextWallTime, scheduledWallTime);
                }
            }
        }

		private long nextFixedRateWallTime(TimerTask task) {
			if (task.relativeGuestTime && timingSession != null) {
				long nextWallTime =
						timingSession.wallTimeMillisForGuestMonotonicMillis(task.when);
				task.scheduledWallPeriodMillis = nonNegativeDifference(
						nextWallTime, task.scheduledWallTime);
				return nextWallTime;
			}
			return saturatingAdd(task.scheduledWallTime, task.scheduledWallPeriodMillis);
        }

        private long timeToSleepMillis(TimerTask task) {
            synchronized (task.lock) {
                long currentTime = currentTimeMillis(task);
                return task.when - currentTime;
            }
        }

        private long hostDelayMillis(TimerTask task) {
            long activeDelay = timeToSleepMillis(task);
            if (activeDelay <= 0L || timingSession == null
                    || (!task.relativeGuestTime && !timingSession.usesGuestWallClock())) {
                return activeDelay;
            }
            return timingSession.hostDelayMillis(activeDelay);
        }

        private long wallTimeMillis() {
            if (timingSession == null) {
                return System.currentTimeMillis();
            }
            TimingSnapshot snapshot = timingSession.snapshotIfOpen();
            if (snapshot == null) {
                return System.currentTimeMillis();
            }
            return timingSession.usesGuestWallClock()
                    ? snapshot.guestWallTimeMillis()
                    : timingSession.hostWallTimeMillisOr(System.currentTimeMillis());
        }

        private long wallDelayMillis(long activeMillis, boolean relativeGuestTime) {
            if (activeMillis <= 0L || timingSession == null
                    || !relativeGuestTime || timingSession.usesGuestWallClock()) {
                return Math.max(0L, activeMillis);
            }
            return timingSession.hostDelayMillis(activeMillis);
        }

        private void sleepTaskDuration(TimerTask task, long millis)
                throws InterruptedException {
            if (task == null) {
                throw new IllegalStateException("Timer deadline has no task");
            }
            if (timingSession == null) {
                Thread.sleep(millis);
            } else {
                boolean guestDuration = task.relativeGuestTime || timingSession.usesGuestWallClock();
                timingSession.awaitSchedulerDuration(millis, guestDuration);
            }
        }

        private static long saturatingAdd(long left, long right) {
            if (right > 0L && left > Long.MAX_VALUE - right) {
                return Long.MAX_VALUE;
            }
            return left + right;
        }

        private static long nonNegativeDifference(long later, long earlier) {
            if (later <= earlier) {
                return 0L;
            }
            if (earlier < 0L && later > Long.MAX_VALUE + earlier) {
                return Long.MAX_VALUE;
            }
            return later - earlier;
        }

        private static long checkedAdd(long left, long right) {
            if (right > 0L && left > Long.MAX_VALUE - right) {
                throw new IllegalArgumentException("Illegal delay to start the TimerTask");
            }
            if (right < 0L && left < Long.MIN_VALUE - right) {
                throw new IllegalArgumentException("Illegal delay to start the TimerTask");
            }
            long result = left + right;
            if (result < 0L) {
                throw new IllegalArgumentException("Illegal delay to start the TimerTask");
            }
            return result;
        }

        private boolean isStaleTimingSession() {
            return timingSession != null
                    && (timingSession.isClosed()
                    || GuestTimingBridge.activeSession() != timingSession);
        }

        /**
         * Cancels timer.
         */
        public synchronized void cancel() {
            cancelled = true;
            tasks.reset();
            LockSupport.unpark(this);
            if (guestWaiting) {
                this.interrupt();
            }
        }

        public int purge() {
            if (tasks.isEmpty()) {
                return 0;
            }
            // callers are synchronized
            return tasks.deleteIfCancelled();
        }

    }

    private static final class FinalizerHelper {
        private final TimerImpl impl;

        FinalizerHelper(TimerImpl impl) {
            this.impl = impl;
        }

        @Override protected void finalize() throws Throwable {
            try {
                synchronized (impl) {
                    impl.finished = true;
                    LockSupport.unpark(impl);
                }
            } finally {
                super.finalize();
            }
        }
    }

    private static long timerId;

    private synchronized static long nextId() {
        return timerId++;
    }

    /* This object will be used in synchronization purposes */
    private final TimerImpl impl;

    // Used to finalize thread
    @SuppressWarnings("unused")
    private final FinalizerHelper finalizer;

    /**
     * Creates a new named {@code Timer} which may be specified to be run as a
     * daemon thread.
     *
     * @throws NullPointerException if {@code name == null}
     */
    public Timer(String name, boolean isDaemon) {
        if (name == null) {
            throw new NullPointerException("name == null");
        }
        this.impl = new TimerImpl(name, isDaemon);
        this.finalizer = new FinalizerHelper(impl);
    }

    /**
     * Creates a new named {@code Timer} which does not run as a daemon thread.
     *
     * @throws NullPointerException if {@code name == null}
     */
    public Timer(String name) {
        this(name, false);
    }

    /**
     * Creates a new {@code Timer} which may be specified to be run as a daemon thread.
     *
     * @param isDaemon {@code true} if the {@code Timer}'s thread should be a daemon thread.
     */
    public Timer(boolean isDaemon) {
        this("Timer-" + Timer.nextId(), isDaemon);
    }

    /**
     * Creates a new non-daemon {@code Timer}.
     */
    public Timer() {
        this(false);
    }

    /**
     * Cancels the {@code Timer} and all scheduled tasks. If there is a
     * currently running task it is not affected. No more tasks may be scheduled
     * on this {@code Timer}. Subsequent calls do nothing.
     */
    public void cancel() {
        impl.cancel();
    }

    /**
     * Removes all canceled tasks from the task queue. If there are no
     * other references on the tasks, then after this call they are free
     * to be garbage collected.
     *
     * @return the number of canceled tasks that were removed from the task
     *         queue.
     */
    public int purge() {
        synchronized (impl) {
            return impl.purge();
        }
    }

    /**
     * Schedule a task for single execution. If {@code when} is less than the
     * current time, it will be scheduled to be executed as soon as possible.
     *
     * @param task
     *            the task to schedule.
     * @param when
     *            time of execution.
     * @throws IllegalArgumentException
     *                if {@code when.getTime() < 0}.
     * @throws IllegalStateException
     *                if the {@code Timer} has been canceled, or if the task has been
     *                scheduled or canceled.
     */
	public void schedule(TimerTask task, Date when) {
		long requestedTime = when.getTime();
		if (requestedTime < 0) {
			throw new IllegalArgumentException("when < 0: " + requestedTime);
		}
		scheduleAbsoluteImpl(task, requestedTime, -1, false);
    }

    /**
     * Schedule a task for single execution after a specified delay.
     *
     * @param task
     *            the task to schedule.
     * @param delay
     *            amount of time in milliseconds before execution.
     * @throws IllegalArgumentException
     *                if {@code delay < 0}.
     * @throws IllegalStateException
     *                if the {@code Timer} has been canceled, or if the task has been
     *                scheduled or canceled.
     */
    public void schedule(TimerTask task, long delay) {
        if (delay < 0) {
            throw new IllegalArgumentException("delay < 0: " + delay);
        }
		scheduleImpl(task, delay, -1, false, true);
    }

    /**
     * Schedule a task for repeated fixed-delay execution after a specific delay.
     *
     * @param task
     *            the task to schedule.
     * @param delay
     *            amount of time in milliseconds before first execution.
     * @param period
     *            amount of time in milliseconds between subsequent executions.
     * @throws IllegalArgumentException
     *                if {@code delay < 0} or {@code period <= 0}.
     * @throws IllegalStateException
     *                if the {@code Timer} has been canceled, or if the task has been
     *                scheduled or canceled.
     */
    public void schedule(TimerTask task, long delay, long period) {
        if (delay < 0 || period <= 0) {
            throw new IllegalArgumentException();
        }
		scheduleImpl(task, delay, period, false, true);
    }

    /**
     * Schedule a task for repeated fixed-delay execution after a specific time
     * has been reached.
     *
     * @param task
     *            the task to schedule.
     * @param when
     *            time of first execution.
     * @param period
     *            amount of time in milliseconds between subsequent executions.
     * @throws IllegalArgumentException
     *                if {@code when.getTime() < 0} or {@code period <= 0}.
     * @throws IllegalStateException
     *                if the {@code Timer} has been canceled, or if the task has been
     *                scheduled or canceled.
     */
	public void schedule(TimerTask task, Date when, long period) {
		long requestedTime = when.getTime();
		if (period <= 0 || requestedTime < 0) {
			throw new IllegalArgumentException();
		}
		scheduleAbsoluteImpl(task, requestedTime, period, false);
    }

    /**
     * Schedule a task for repeated fixed-rate execution after a specific delay
     * has passed.
     *
     * @param task
     *            the task to schedule.
     * @param delay
     *            amount of time in milliseconds before first execution.
     * @param period
     *            amount of time in milliseconds between subsequent executions.
     * @throws IllegalArgumentException
     *                if {@code delay < 0} or {@code period <= 0}.
     * @throws IllegalStateException
     *                if the {@code Timer} has been canceled, or if the task has been
     *                scheduled or canceled.
     */
    public void scheduleAtFixedRate(TimerTask task, long delay, long period) {
        if (delay < 0 || period <= 0) {
            throw new IllegalArgumentException();
        }
		scheduleImpl(task, delay, period, true, true);
    }

    /**
     * Schedule a task for repeated fixed-rate execution after a specific time
     * has been reached.
     *
     * @param task
     *            the task to schedule.
     * @param when
     *            time of first execution.
     * @param period
     *            amount of time in milliseconds between subsequent executions.
     * @throws IllegalArgumentException
     *                if {@code when.getTime() < 0} or {@code period <= 0}.
     * @throws IllegalStateException
     *                if the {@code Timer} has been canceled, or if the task has been
     *                scheduled or canceled.
     */
	public void scheduleAtFixedRate(TimerTask task, Date when, long period) {
		long requestedTime = when.getTime();
		if (period <= 0 || requestedTime < 0) {
			throw new IllegalArgumentException();
		}
		scheduleAbsoluteImpl(task, requestedTime, period, true);
	}

    /*
     * Schedule a task.
     */
	private void scheduleImpl(
			TimerTask task,
			long delay,
			long period,
			boolean fixed,
			boolean relativeGuestTime) {
        synchronized (impl) {
            if (impl.cancelled) {
                throw new IllegalStateException("Timer was canceled");
            }

            if (relativeGuestTime) {
                // CLDC requires validation against the wall-clock current time even though the
                // execution queue uses a separate guest-monotonic domain.
                TimerImpl.checkedAdd(delay, impl.wallTimeMillis());
            }

            long when = TimerImpl.saturatingAdd(
                    delay, impl.currentTimeMillis(relativeGuestTime));

            synchronized (task.lock) {
                if (task.isScheduled()) {
                    throw new IllegalStateException("TimerTask is scheduled already");
                }

                if (task.cancelled) {
                    throw new IllegalStateException("TimerTask is canceled");
                }

                task.scheduled = true;
                task.when = when;
				task.period = period;
				task.fixedRate = fixed;
				task.relativeGuestTime = relativeGuestTime;
				if (relativeGuestTime && impl.timingSession != null) {
					task.scheduledWallTime =
							timingSessionWallTimeFor(when);
				} else {
					task.scheduledWallTime = TimerImpl.saturatingAdd(
							impl.wallTimeMillis(), impl.wallDelayMillis(delay, relativeGuestTime));
				}
				task.scheduledWallPeriodMillis = period > 0
						? impl.wallDelayMillis(period, relativeGuestTime)
						: 0L;
            }

            // insert the newTask into queue
            impl.insertTask(task);
		}
	}

	private long timingSessionWallTimeFor(long guestMonotonicMillis) {
		return impl.timingSession.wallTimeMillisForGuestMonotonicMillis(guestMonotonicMillis);
	}

	/** Schedules an epoch-domain Date deadline without converting it through a sampled delay. */
	private void scheduleAbsoluteImpl(
			TimerTask task, long requestedTime, long period, boolean fixed) {
		synchronized (impl) {
			if (impl.cancelled) {
				throw new IllegalStateException("Timer was canceled");
			}
			synchronized (task.lock) {
				if (task.isScheduled()) {
					throw new IllegalStateException("TimerTask is scheduled already");
				}
				if (task.cancelled) {
					throw new IllegalStateException("TimerTask is canceled");
				}
				task.scheduled = true;
				task.when = requestedTime;
				task.period = period;
				task.fixedRate = fixed;
				task.relativeGuestTime = false;
				task.scheduledWallTime = requestedTime;
				task.scheduledWallPeriodMillis = period > 0L ? period : 0L;
			}
			impl.insertTask(task);
		}
	}
}
