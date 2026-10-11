package com.inzent.apim.utilities.recycle;

@FunctionalInterface
public interface LeakListener {
    /** Runs outside Main's lock. Observation alone does not revoke the detected lease. */
    void onLeak(LeakEvent event);
}
