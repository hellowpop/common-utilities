package com.inzent.apim.utilities.http.mockserver;

public enum MockCase implements MockScenario {
    WAIT_FOR_CLIENT_DISCONNECT {
        @Override public void execute(MockClientContext context) throws Exception { consume(context, false); }
    },
    SLOW_READ {
        @Override public void execute(MockClientContext context) throws Exception { consume(context, true); }
    };

    private static void consume(MockClientContext c, boolean slow) throws Exception {
        c.runPoint("onConnected", () -> MockPoints.onConnected(c));
        while (true) {
            c.runPoint("beforeRead", () -> MockPoints.beforeRead(c));
            c.runPoint("readChunk", () -> MockPoints.readChunk(c));
            if (c.lastRead() < 0) {
                c.runPoint("onPeerEof", () -> MockPoints.onPeerEof(c));
                return;
            }
            c.runPoint("onChunkReceived", () -> MockPoints.onChunkReceived(c));
            if (slow) c.runPoint("delayBeforeNextRead", () -> MockPoints.delayBeforeNextRead(c));
        }
    }
}
