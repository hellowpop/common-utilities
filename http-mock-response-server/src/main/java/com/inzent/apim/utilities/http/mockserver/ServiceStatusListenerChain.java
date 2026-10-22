package com.inzent.apim.utilities.http.mockserver;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Ordered, immutable listener composition. Exceptions never prevent subsequent callbacks. */
public final class ServiceStatusListenerChain implements ServiceStatusListener {
    @FunctionalInterface
    interface FailureLogger {
        void log(int index, ServiceEvent event, Exception failure);
    }
    private static final System.Logger LOGGER = System.getLogger(ServiceStatusListenerChain.class.getName());
    private final List<ServiceStatusListener> listeners;
    private final FailureLogger logger;

    ServiceStatusListenerChain(List<ServiceStatusListener> listeners, FailureLogger logger) {
        this.listeners = List.copyOf(listeners);
        this.logger = Objects.requireNonNull(logger);
    }

    public static ServiceStatusListenerChain of(ServiceStatusListener... listeners) {
        Objects.requireNonNull(listeners, "listeners");
        List<ServiceStatusListener> flattened = new ArrayList<>();
        for (var listener : listeners) append(flattened, listener);
        return new ServiceStatusListenerChain(flattened, (index, event, failure) ->
            LOGGER.log(System.Logger.Level.WARNING,
                "Listener " + index + " failed: event=" + event.type()
                + ", registration=" + event.registrationId() + ", client=" + event.clientId(), failure));
    }

    public ServiceStatusListenerChain andThen(ServiceStatusListener listener) {
        List<ServiceStatusListener> copy = new ArrayList<>(listeners);
        append(copy, listener);
        return new ServiceStatusListenerChain(copy, logger);
    }

    private static void append(List<ServiceStatusListener> target, ServiceStatusListener listener) {
        Objects.requireNonNull(listener, "listener");
        if (listener instanceof ServiceStatusListenerChain chain) target.addAll(chain.listeners);
        else target.add(listener);
    }

    @Override public void onEvent(ServiceEvent event) {
        Objects.requireNonNull(event, "event");
        for (int i = 0; i < listeners.size(); i++) {
            try {
                listeners.get(i).onEvent(event);
            } catch (Exception failure) {
                try {
                    logger.log(i + 1, event, failure);
                } catch (Exception loggingFailure) {
                    // Logging is best effort; it must not break delivery to remaining listeners.
                }
            }
        }
    }
}
