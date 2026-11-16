package com.inzent.apim.utilities.http.mockserver;

/** Checked control signal thrown from a mock point. */
public abstract class NextActionException extends Exception {
    protected NextActionException(String message) { super(message); }
}
