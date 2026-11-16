package com.inzent.apim.utilities.http.mockserver;

/** Receipt state of one HTTP request section. */
public enum HttpReceiveState {
    NOT_REACHED, RECEIVING, COMPLETE, NOT_APPLICABLE
}
