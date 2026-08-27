package ru.playsoftware.j2meloader.memory;

import android.os.Bundle;

/** One-way state notifications keep the :midlet scanner free from fast UI status polling. */
oneway interface IMemoryScanCallback {
    void onStatusChanged(in Bundle status);
    void onTargetClosed();
}
