package ru.playsoftware.j2meloader.memory;

/** Tiny :midlet bridge for target identity and resident ART page runs. */
interface IMemoryTargetBridge {
    long getGeneration();
    int getTargetPid();
    long getProbeAddress();
    long getProbeValue();
    int getPageSize();

    /**
     * Returns [count, flags, start0, end0, start1, end1, ...].
     * flags bit 0 means the native run list was truncated to maxRuns.
     */
    long[] getResidentJavaRuns(long generation, int scope, int maxRuns);
}
