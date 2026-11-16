package com.inzent.apim.utilities.http.mockserver;

final class ActionDispatcher {
    private ActionDispatcher() {}
    static TerminationReason execute(ClientSession session, NextActionException action) throws Exception {
        if (action instanceof CloseClientException close) {
            session.context.delay(close.closeDelay());
            return TerminationReason.ACTION_CLOSE;
        }
        if (action instanceof SendResponseException send) {
            session.send(send.response());
            return TerminationReason.RESPONSE_COMPLETED;
        }
        throw new IllegalArgumentException("Unsupported next action: " + action.getClass().getName(), action);
    }
}
