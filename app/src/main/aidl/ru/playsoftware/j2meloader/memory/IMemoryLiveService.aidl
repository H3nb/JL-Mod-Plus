package ru.playsoftware.j2meloader.memory;

/** Low-frequency control surface for native live value/address observation in :midlet. */
interface IMemoryLiveService {
    long[] getLiveResultsPage(long generation, int offset, int limit);
    void resetVisibleTracking(long generation);
    String getTrackingDiagnostics(long generation);
}
