package com.inzent.apim.utilities.http.mockserver;

@FunctionalInterface
public interface ServiceStatusListener {
    void onEvent(ServiceEvent event);
}
