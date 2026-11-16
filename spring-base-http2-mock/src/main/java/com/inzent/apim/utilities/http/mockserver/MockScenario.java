package com.inzent.apim.utilities.http.mockserver;

/** Implement this interface on an enum to define a per-client procedure. */
public interface MockScenario {
    void execute(MockClientContext context) throws Exception;
}
