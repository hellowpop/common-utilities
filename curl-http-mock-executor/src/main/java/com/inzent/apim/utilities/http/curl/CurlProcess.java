package com.inzent.apim.utilities.http.curl;

import io.netty.handler.codec.http.HttpMethod;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Owns process wait/write concurrency; stdout and stderr go straight to distinct files. */
final class CurlProcess {
    private static final Map<String,String> VERSIONS = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String,String> entry) { return size() > 32; }
    });
    private CurlProcess() { }

    static void execute(ExecuteContext c, ThreadFactory inputThreads) throws Throwable {
        ExecutionRecord record = c.record();
        record.curlVersion(version(c));
        ProcessOutputRecord stdout = c.newOutput("stdout.bin"); record.outputs(stdout,null);
        ProcessOutputRecord stderr = c.newOutput("stderr.log"); record.outputs(stdout,stderr);
        List<String> args = CurlArguments.build(c,record.request().headers()); record.arguments(CurlArguments.mask(args));
        Process process = builder(c,args,stdout.path(),stderr.path()).start(); c.process(process);
        Throwable failure = null;
        try { executeStarted(c,record,process,stdout,stderr,inputThreads); }
        catch (Throwable e) { failure = e; throw e; }
        finally {
            try { stop(process); }
            catch (Throwable cleanup) { if (failure != null) merge(failure,cleanup); else throw cleanup; }
        }
    }

    private static void executeStarted(ExecuteContext c, ExecutionRecord record, Process process,
            ProcessOutputRecord stdout, ProcessOutputRecord stderr, ThreadFactory inputThreads) throws Throwable {
        AtomicLong count = new AtomicLong();
        FutureTask<Void> input = new FutureTask<>(() -> {
            try (InputStream source = record.request().body().orElseThrow().openStream(); OutputStream sink = process.getOutputStream()) {
                if (!CurlArguments.noBody(c)) {
                    byte[] buffer = new byte[8192];
                    for (int n; (n=source.read(buffer)) != -1;) {
                        try { sink.write(buffer,0,n); } catch (IOException e) { throw new UploadPipeException(e); }
                        count.addAndGet(n);
                    }
                }
            }
            return null;
        });
        Thread writer = inputThreads.newThread(input);
        if (writer == null) throw new IllegalStateException("Input thread factory returned null");
        c.inputWorker(writer); writer.start();
        Throwable failure = null;
        Throwable inputFailure = null;
        boolean interrupted = false;
        try {
            if (!process.waitFor(c.responseTimeout().toNanos(),TimeUnit.NANOSECONDS))
                throw new TimeoutException("Curl response timeout: "+c.responseTimeout());
            record.exitCode(process.exitValue());
            if (process.exitValue() != 0) throw new IOException("Curl exited with code "+process.exitValue());
        } catch (Throwable e) { failure = e; interrupted = e instanceof InterruptedException; }
        finally {
            interrupted |= Thread.interrupted();
            try { stop(process); } catch (Throwable e) { failure = merge(failure,e); interrupted |= e instanceof InterruptedException; }
            interrupted |= Thread.interrupted();
            try {
                long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                while (writer.isAlive() && System.nanoTime() < deadline) {
                    try { writer.join(Math.max(1,TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()))); }
                    catch (InterruptedException e) { interrupted = true; failure = merge(failure,e); }
                }
                if (writer.isAlive()) { writer.interrupt(); throw new IOException("Curl stdin worker did not terminate"); }
                c.inputWorker(null);
                try { input.get(); }
                catch (ExecutionException e) { inputFailure = e.getCause(); }
            } catch (Throwable e) { failure = merge(failure,e); interrupted |= e instanceof InterruptedException; }
            record.request().written(count.get());
            if (!process.isAlive()) record.exitCode(process.exitValue());
            try { stdout.finish(); stderr.finish(); } catch (Throwable e) { failure = merge(failure,e); }
            try { CurlLogParser.parse(stderr.path(),record); } catch (Throwable e) { failure = merge(failure,e); }
            if (inputFailure != null && !(inputFailure instanceof UploadPipeException
                    && record.exitCode().orElse(-1) == 0 && record.response().status().isPresent()))
                failure = merge(failure,inputFailure);
            if (failure == null && record.response().status().isEmpty())
                failure = new IOException("Curl completed without a final HTTP response header");
            try {
                BodyStore body;
                if (c.method().equals(HttpMethod.HEAD)) {
                    body = c.newBody(); body.copyFrom(InputStream.nullInputStream());
                } else if (stdout.size() > BodyStore.MEMORY_LIMIT) body = c.referenceBody(stdout.path());
                else {
                    body = c.newBody();
                    try (InputStream source = Files.newInputStream(stdout.path())) { body.copyFrom(source); }
                }
                record.response().body(body, failure == null && record.response().status().isPresent());
            } catch (Throwable e) { failure = merge(failure,e); }
        }
        if (interrupted) Thread.currentThread().interrupt();
        if (failure != null) throw failure;
    }

    private static ProcessBuilder builder(ExecuteContext c, List<String> args, Path out, Path err) {
        ProcessBuilder builder = new ProcessBuilder(args).directory(c.configure().workingDirectory().toFile())
            .redirectOutput(out.toFile()).redirectError(err.toFile());
        // Do not let verbose formatting vary with inherited tracing configuration.
        builder.environment().remove("CURL_TRACE");
        return builder;
    }
    private static String version(ExecuteContext c) throws Throwable {
        String key = c.configure().curlExecutable()+"|"+c.configure().workingDirectory();
        String known = VERSIONS.get(key); if (known != null) return known;
        ProcessOutputRecord out = c.newOutput("version.out"), err = c.newOutput("version.err");
        Process probe = builder(c,List.of(c.configure().curlExecutable().toString(),"--disable","--version"),out.path(),err.path()).start();
        c.process(probe);
        Throwable failure = null;
        try {
            if (!probe.waitFor(5,TimeUnit.SECONDS)) throw new TimeoutException("Curl version probe timeout");
            if (probe.exitValue() != 0) throw new IOException("Curl version probe failed: "+probe.exitValue());
            try (InputStream input = Files.newInputStream(out.path())) { known = new String(input.readNBytes(65536),StandardCharsets.UTF_8); }
            if (!known.startsWith("curl ")) throw new IOException("Configured executable did not identify as curl");
            VERSIONS.put(key,known); return known;
        } catch (Throwable e) { failure = e; throw e; }
        finally {
            try { stop(probe); }
            catch (Throwable cleanup) { if (failure != null) merge(failure,cleanup); else throw cleanup; }
        }
    }

    static void stop(Process process) throws IOException {
        // Clear an interrupt during bounded process cleanup, restore it at the execution boundary.
        boolean interrupted = Thread.interrupted();
        Throwable failure = null;
        try {
            if (process.isAlive()) {
                failure = Cleanup.attempt(failure,process::destroy);
                long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                while (process.isAlive() && System.nanoTime() < deadline) {
                    try { process.waitFor(100,TimeUnit.MILLISECONDS); }
                    catch (InterruptedException e) { interrupted = true; }
                    if (process.isAlive()) failure = Cleanup.attempt(failure, () -> process.destroyForcibly());
                }
                if (process.isAlive()) failure = merge(failure,new IOException("Curl process did not terminate"));
            }
            for (Closeable stream : List.of(process.getOutputStream(),process.getInputStream(),process.getErrorStream())) {
                failure = Cleanup.attempt(failure,stream);
            }
            Cleanup.rethrow(failure);
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
    static Throwable merge(Throwable first, Throwable next) {
        if (first == null) return next;
        if (first != next) first.addSuppressed(next);
        return first;
    }
}
