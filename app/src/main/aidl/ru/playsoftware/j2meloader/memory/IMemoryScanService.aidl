package ru.playsoftware.j2meloader.memory;

import android.os.Bundle;
import ru.playsoftware.j2meloader.memory.IMemoryScanCallback;

interface IMemoryScanService {
    Bundle getCapabilities();
    void registerCallback(IMemoryScanCallback callback);
    void unregisterCallback(IMemoryScanCallback callback);
    long startSearch(long generation, String value, int scope, int valueType);
    long refine(long generation, String value);
    long[] getResultsPage(long generation, int offset, int limit);
    Bundle editValue(long generation, long address, int valueType, String expected, String replacement);
    void clearSearch(long generation);
    void cancelOperation(long generation);
}
