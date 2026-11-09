// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.gles;

import android.app.ActivityManager;
import android.content.Context;

import javax.microedition.util.ContextHolder;

/**
 * Conservative intermediate-target budget, not an estimate of free GPU memory.
 * Android GPU allocations share system memory on most devices, but the driver
 * does not expose a portable free-VRAM figure for GLES2.
 */
final class EffectGpuBudget {
    static final long MIB = 1024L * 1024L;
    static final long DEFAULT_BYTES = 64L * MIB;
    private static final long GIB = 1024L * MIB;

    private EffectGpuBudget() {}

    static long forCurrentDevice() {
        Context context = ContextHolder.getAppContext();
        if (context == null) return DEFAULT_BYTES;
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) return DEFAULT_BYTES;
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        manager.getMemoryInfo(info);
        return select(info.totalMem, manager.isLowRamDevice(), info.lowMemory);
    }

    static long select(long totalRamBytes, boolean lowRamDevice, boolean lowMemory) {
        if (lowRamDevice || lowMemory) return 32L * MIB;
        if (totalRamBytes <= 0) return DEFAULT_BYTES;
        if (totalRamBytes < 3L * GIB) return 32L * MIB;
        if (totalRamBytes < 5L * GIB) return 64L * MIB;
        if (totalRamBytes < 10L * GIB) return 128L * MIB;
        return 256L * MIB;
    }
}
