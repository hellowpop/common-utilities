package com.inzent.apim.utilities.http.mockserver;

/** Reusable default points. Custom enums can compose or replace these methods. */
public final class MockPoints {
    private MockPoints() {}
    public static void onConnected(MockClientContext context) throws InterruptedException { context.checkCancelled(); }
    public static void beforeRead(MockClientContext context) throws InterruptedException { context.checkCancelled(); }
    public static void readChunk(MockClientContext context) throws Exception { context.readChunk(); }
    public static void onChunkReceived(MockClientContext context) throws InterruptedException { context.checkCancelled(); }
    public static void delayBeforeNextRead(MockClientContext context) throws InterruptedException {
        context.delay(context.options().readInterval());
    }
    public static void onPeerEof(MockClientContext context) throws InterruptedException { context.checkCancelled(); }
}
