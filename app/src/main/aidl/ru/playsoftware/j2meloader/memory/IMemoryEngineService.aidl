package ru.playsoftware.j2meloader.memory;

/** Debug prototype control plane for the isolated :memory_engine process. */
interface IMemoryEngineService {
    /**
     * Tests same-UID cross-process raw memory access against a disposable native probe in :midlet.
     * The implementation must restore the original probe value before returning.
     */
    String runCapabilityTest(int targetPid, long probeAddress, long expectedValue);

    int getEnginePid();
}
