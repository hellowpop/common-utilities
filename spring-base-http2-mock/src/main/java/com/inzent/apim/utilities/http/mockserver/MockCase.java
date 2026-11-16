package com.inzent.apim.utilities.http.mockserver;

public enum MockCase implements MockScenario {
    WAIT_FOR_CLIENT_DISCONNECT {
        @Override public void execute(MockClientContext context) throws Exception { consume(context, false); }
    },
    SLOW_READ {
        @Override public void execute(MockClientContext context) throws Exception { consume(context, true); }
    };

    private static void consume(MockClientContext clientContext, boolean slow) throws Exception {
        clientContext.runPoint(MockPoint.ON_CONNECTED);
        while (true) {
            clientContext.runPoint(MockPoint.BEFORE_READ);
            clientContext.runPoint(MockPoint.READ_CHUNK);
            if (clientContext.lastRead() < 0) {
                clientContext.runPoint(MockPoint.ON_PEER_EOF);
                return;
            }
            clientContext.runPoint(MockPoint.ON_CHUNK_RECEIVED);
            if (slow) clientContext.runPoint(MockPoint.DELAY_BEFORE_NEXT_READ);
        }
    }
}
