package com.inzent.apim.utilities.http.curl;

import java.time.*;
import java.util.Optional;

/** Timing and outcome of one execution stage. */
public final class StageRecord {
    public enum Status { NOT_STARTED, RUNNING, SUCCEEDED, FAILED, SKIPPED }
    private Status status = Status.NOT_STARTED;
    private Instant startedAt, endedAt;
    private long startNanos;
    private Duration duration = Duration.ZERO;
    private Throwable failure;
    void start() { status = Status.RUNNING; startedAt = Instant.now(); startNanos = System.nanoTime(); }
    void end(Throwable error) { failure = error; endedAt = Instant.now(); duration = Duration.ofNanos(System.nanoTime()-startNanos); status = error == null ? Status.SUCCEEDED : Status.FAILED; }
    void skip() { if (status == Status.NOT_STARTED) status = Status.SKIPPED; }
    public Status status() { return status; }
    public Optional<Instant> startedAt() { return Optional.ofNullable(startedAt); }
    public Optional<Instant> endedAt() { return Optional.ofNullable(endedAt); }
    public Duration duration() { return duration; }
    public Optional<Throwable> failure() { return Optional.ofNullable(failure); }
}
