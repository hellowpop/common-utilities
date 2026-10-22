package com.inzent.apim.utilities.http.mockserver;

import java.io.IOException;
import java.net.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

final class ClientSession implements Runnable {
    private enum WriteState { IDLE, WRITING, SENT, TIMEOUT, CANCELLED }
    final MockServerExecutor executor;
    final MockBinding binding;
    final Socket socket;
    final long id;
    final boolean admitted;
    final InetSocketAddress local;
    final InetSocketAddress remote;
    final AtomicReference<TerminationReason> cancellation = new AtomicReference<>();
    private final AtomicReference<WriteState> writeState = new AtomicReference<>(WriteState.IDLE);
    private final AtomicReference<Throwable> closeFailure = new AtomicReference<>();
    volatile Thread runner;
    MockClientContext context;
    String failedPoint;
    private Map<String, Object> socketOptions = Map.of();
    private TerminationReason reason;
    private Throwable failure;

    ClientSession(MockServerExecutor executor, MockBinding binding, Socket socket, long id, boolean admitted) {
        this.executor = executor; this.binding = binding; this.socket = socket; this.id = id; this.admitted = admitted;
        local = (InetSocketAddress) socket.getLocalSocketAddress();
        remote = (InetSocketAddress) socket.getRemoteSocketAddress();
    }

    @Override public void run() {
        runner = Thread.currentThread();
        ExecutionGuard.enter();
        try {
            event(ServiceEventType.CLIENT_CONNECTED, null, false, 0, null);
            if (!admitted) { reason = TerminationReason.CAPACITY_REJECTED; return; }
            context = new MockClientContext(this);
            context.checkCancelled();
            applyOptions();
            try {
                binding.scenario.execute(context);
                reason = TerminationReason.PEER_EOF;
            } catch (NextActionException action) {
                if (cancelled()) return;
                event(ServiceEventType.EXCEPTION_RECEIVED, action, true, 0, null);
                try {
                    reason = ActionDispatcher.execute(this, action);
                } catch (Exception actionFailure) {
                    if (actionFailure != action && actionFailure.getCause() != action) actionFailure.addSuppressed(action);
                    throw actionFailure;
                }
            }
        } catch (Exception problem) {
            if (!cancelled() || writeState.get() == WriteState.TIMEOUT) {
                failure = problem;
                reason = TerminationReason.ERROR;
                event(ServiceEventType.EXCEPTION_RECEIVED, problem, false, 0, null);
            }
            if (problem instanceof InterruptedException) Thread.currentThread().interrupt();
        } catch (Error fatal) {
            failure = fatal;
            reason = TerminationReason.ERROR;
            throw fatal;
        } finally {
            try {
                finish();
            } finally {
                runner = null;
                context = null;
                executor.sessionFinished(this);
                ExecutionGuard.exit();
            }
        }
    }

    private void applyOptions() throws SocketException {
        MockOptions o = binding.options;
        if (o.receiveBufferSize() != null) socket.setReceiveBufferSize(o.receiveBufferSize());
        if (o.sendBufferSize() != null) socket.setSendBufferSize(o.sendBufferSize());
        socket.setSoLinger(o.lingerSeconds() >= 0, Math.max(0, o.lingerSeconds()));
        socket.setKeepAlive(o.keepAlive());
        socket.setTcpNoDelay(o.tcpNoDelay());
        socket.setSoTimeout(o.readTimeoutMillis());
        socketOptions = Map.of("requested", o, "receiveBufferSize", socket.getReceiveBufferSize(),
            "sendBufferSize", socket.getSendBufferSize(), "lingerSeconds", socket.getSoLinger(),
            "keepAlive", socket.getKeepAlive(), "tcpNoDelay", socket.getTcpNoDelay(),
            "readTimeoutMillis", socket.getSoTimeout());
    }

    boolean cancelled() { return cancellation.get() != null; }

    void cancel(TerminationReason cause) {
        cancellation.compareAndSet(null, cause);
        writeState.getAndUpdate(current -> current == WriteState.IDLE || current == WriteState.WRITING
            ? WriteState.CANCELLED : current);
        Thread t = runner;
        if (t != null) t.interrupt();
        executor.closeAsync(this);
    }

    void forceClose() {
        try {
            if (!socket.isClosed()) {
                try { socket.setSoLinger(false, 0); }
                catch (SocketException ignored) { /* close is still mandatory */ }
                socket.close();
            }
        } catch (Exception problem) { closeFailure.compareAndSet(null, problem); }
    }

    private void finish() {
        TerminationReason cancelledReason = cancellation.get();
        WriteState terminalWrite = writeState.get();
        if (terminalWrite == WriteState.SENT) reason = TerminationReason.RESPONSE_COMPLETED;
        else if (terminalWrite == WriteState.TIMEOUT) reason = TerminationReason.ERROR;
        else if (cancelledReason != null) reason = cancelledReason;
        if (reason == null) reason = TerminationReason.ERROR;
        try {
            if (cancelledReason != null || reason == TerminationReason.STOP || reason == TerminationReason.ERROR
                    || reason == TerminationReason.CAPACITY_REJECTED) forceClose();
            else {
                try { socket.close(); }
                catch (IOException problem) { closeFailure.compareAndSet(null, problem); }
            }
            Throwable cleanup = closeFailure.get();
            if (cleanup != null) {
                reason = TerminationReason.ERROR;
                if (failure != null && failure != cleanup) failure.addSuppressed(cleanup);
                event(ServiceEventType.EXCEPTION_RECEIVED, cleanup, false, 0, null);
            }
        } finally {
            event(ServiceEventType.CLIENT_DISCONNECTED, failure, false, 0, null);
        }
    }

    void send(MockResponse response) throws Exception {
        context.checkCancelled();
        event(ServiceEventType.RESPONSE_SEND, null, false, 0, response.metadata());
        context.checkCancelled();
        if (!writeState.compareAndSet(WriteState.IDLE, WriteState.WRITING))
            throw new IOException("Response already started");
        ScheduledFuture<?> deadline = executor.schedule(() -> {
            if (writeState.compareAndSet(WriteState.WRITING, WriteState.TIMEOUT)) executor.closeAsync(this);
        }, binding.options.responseTimeout());
        try {
            HttpResponseWriter.write(socket.getOutputStream(), response);
            if (writeState.compareAndSet(WriteState.WRITING, WriteState.SENT)) {
                event(ServiceEventType.RESPONSE_SENT, null, false, 0, response.metadata());
            } else if (writeState.get() == WriteState.TIMEOUT) {
                throw new SocketTimeoutException("Response write deadline exceeded");
            } else {
                throw new InterruptedException("Response cancelled");
            }
        } catch (IOException problem) {
            if (writeState.get() == WriteState.TIMEOUT && !(problem instanceof SocketTimeoutException)) {
                SocketTimeoutException timeout = new SocketTimeoutException("Response write deadline exceeded");
                timeout.initCause(problem);
                throw timeout;
            }
            throw problem;
        } finally {
            writeState.compareAndSet(WriteState.WRITING, WriteState.CANCELLED);
            deadline.cancel(false);
        }
    }

    void event(ServiceEventType type, Throwable exception, boolean action, int bytes,
               ServiceEvent.ResponseMetadata response) {
        String point = context == null ? null : context.point();
        if (point == null) point = failedPoint;
        binding.emit(new ServiceEvent(Instant.now(), binding.id, binding.name, id, type, local, remote,
            point, bytes, context == null ? 0 : context.totalBytes(), reason, exception, action, response, socketOptions));
    }
}
