package com.inzent.apim.utilities.http.curl;

import java.time.*;
import java.util.*;

/** Execution metadata remains readable after context disposal; file contents do not. */
public final class ExecutionRecord {
    public enum Stage { VALIDATE, PRE, PREPARE, CURL, POST }
    public enum State { NEW, RUNNING, COMPLETED, FAILED }
    private State state = State.NEW;
    private final EnumMap<Stage,StageRecord> stages = new EnumMap<>(Stage.class);
    private final RequestRecord request = new RequestRecord();
    private final ResponseRecord response = new ResponseRecord();
    private ProcessOutputRecord stdout, stderr;
    private Throwable failure;
    private Integer exitCode;
    private List<String> arguments = List.of();
    private String diagnostics = "", curlVersion = "";
    private boolean diagnosticsTruncated, headersTruncated;
    private Instant startedAt, endedAt;
    private long startNanos;
    private Duration duration = Duration.ZERO;
    ExecutionRecord() { for (Stage stage : Stage.values()) stages.put(stage,new StageRecord()); }
    void start() { state = State.RUNNING; startedAt = Instant.now(); startNanos = System.nanoTime(); }
    void finish(Throwable error) {
        failure = error; state = error == null ? State.COMPLETED : State.FAILED;
        endedAt = Instant.now(); duration = Duration.ofNanos(System.nanoTime()-startNanos);
        stages.values().forEach(StageRecord::skip);
    }
    void outputs(ProcessOutputRecord out, ProcessOutputRecord err) { stdout = out; stderr = err; }
    void exitCode(int code) { exitCode = code; }
    void failure(Throwable error) { failure = error; }
    void arguments(List<String> args) { arguments = List.copyOf(args); }
    void diagnostics(String text, boolean truncated) { diagnostics = text; diagnosticsTruncated = truncated; }
    void headersTruncated(boolean v) { headersTruncated = v; }
    void curlVersion(String v) { curlVersion = v; }
    public State state() { return state; }
    public StageRecord stage(Stage stage) { return stages.get(Objects.requireNonNull(stage)); }
    public RequestRecord request() { return request; }
    public ResponseRecord response() { return response; }
    public Optional<ProcessOutputRecord> stdout() { return Optional.ofNullable(stdout); }
    public Optional<ProcessOutputRecord> stderr() { return Optional.ofNullable(stderr); }
    public Optional<Throwable> failure() { return Optional.ofNullable(failure); }
    public OptionalInt exitCode() { return exitCode == null ? OptionalInt.empty() : OptionalInt.of(exitCode); }
    public List<String> arguments() { return arguments; }
    public String diagnostics() { return diagnostics; }
    public boolean diagnosticsTruncated() { return diagnosticsTruncated; }
    public boolean headersTruncated() { return headersTruncated; }
    public String curlVersion() { return curlVersion; }
    public Optional<Instant> startedAt() { return Optional.ofNullable(startedAt); }
    public Optional<Instant> endedAt() { return Optional.ofNullable(endedAt); }
    public Duration duration() { return duration; }
}
