package com.inzent.apim.utilities.http.curl;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Immutable environment shared by independent executions. */
public final class ExecuteConfigure {
    private final Path curlExecutable, workingDirectory, temporaryDirectory;
    private final Duration connectTimeout;
    private final boolean verifyCertificate;
    private final List<String> additionalArguments;
    private ExecuteConfigure(Builder b) {
        curlExecutable = Objects.requireNonNull(b.curlExecutable);
        workingDirectory = Objects.requireNonNull(b.workingDirectory).toAbsolutePath().normalize();
        temporaryDirectory = Objects.requireNonNull(b.temporaryDirectory).toAbsolutePath().normalize();
        connectTimeout = positive(b.connectTimeout);
        verifyCertificate = b.verifyCertificate;
        additionalArguments = List.copyOf(b.additionalArguments);
    }
    static Duration positive(Duration d) {
        Objects.requireNonNull(d);
        if (d.isZero() || d.isNegative()) throw new IllegalArgumentException("Timeout must be positive");
        d.toNanos(); // Reject durations that cannot be measured with nanoTime.
        return d;
    }
    public static Builder builder() { return new Builder(); }
    public static ExecuteConfigure defaults() { return builder().build(); }
    public Path curlExecutable() { return curlExecutable; }
    public Path workingDirectory() { return workingDirectory; }
    public Path temporaryDirectory() { return temporaryDirectory; }
    public Duration connectTimeout() { return connectTimeout; }
    public boolean verifyCertificate() { return verifyCertificate; }
    public List<String> additionalArguments() { return additionalArguments; }
    public static final class Builder {
        private Path curlExecutable = Path.of(System.getProperty("os.name").startsWith("Windows") ? "curl.exe" : "curl");
        private Path workingDirectory = Path.of(System.getProperty("user.dir"));
        private Path temporaryDirectory = Path.of(System.getProperty("java.io.tmpdir"));
        private Duration connectTimeout = Duration.ofSeconds(10);
        private boolean verifyCertificate;
        private List<String> additionalArguments = List.of();
        public Builder curlExecutable(Path v) { curlExecutable = Objects.requireNonNull(v); return this; }
        public Builder workingDirectory(Path v) { workingDirectory = Objects.requireNonNull(v); return this; }
        public Builder temporaryDirectory(Path v) { temporaryDirectory = Objects.requireNonNull(v); return this; }
        public Builder connectTimeout(Duration v) { connectTimeout = positive(v); return this; }
        public Builder verifyCertificate(boolean v) { verifyCertificate = v; return this; }
        public Builder additionalArguments(List<String> v) { additionalArguments = List.copyOf(v); return this; }
        public ExecuteConfigure build() { return new ExecuteConfigure(this); }
    }
}
