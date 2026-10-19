package com.inzent.apim.utilities.http.curl;

import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import static com.inzent.apim.utilities.http.curl.ExecutionRecord.Stage.*;

/** Synchronous single-request curl executor. Distinct contexts can execute concurrently. */
public final class CurlHttpMockExecutor {
    private final ThreadFactory inputThreads;
    public CurlHttpMockExecutor() {
        this(task -> {
            Thread thread = new Thread(task,"curl-stdin");
            thread.setDaemon(true);
            return thread;
        });
    }
    CurlHttpMockExecutor(ThreadFactory inputThreads) { this.inputThreads = Objects.requireNonNull(inputThreads,"inputThreads"); }
    public void handle(ExecuteContext context) {
        Objects.requireNonNull(context,"context").begin();
        ExecutionRecord record = context.record();
        Throwable failure = null;
        boolean validated = false;
        boolean interrupted = false;
        try {
            stage(record,VALIDATE, () -> { CurlArguments.validate(context); PayloadPreparation.defaults(context); });
            validated = true;
            hook(record,PRE,context.preExecute());
            stage(record,PREPARE, () -> PayloadPreparation.prepare(context));
            stage(record,CURL, () -> CurlProcess.execute(context,inputThreads));
        } catch (Throwable e) { failure = e; interrupted = e instanceof InterruptedException; }
        finally {
            record.failure(failure);
            if (validated) {
                try { hook(record,POST,context.postExecute()); }
                catch (Throwable e) { failure = CurlProcess.merge(failure,e); }
            }
            context.finish(failure);
            if (interrupted) Thread.currentThread().interrupt();
        }
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new CurlExecutionException("Curl HTTP execution failed; inspect context.record()",failure);
    }
    private static void hook(ExecutionRecord record, ExecutionRecord.Stage stage, Runnable action) throws Throwable {
        if (action == null) record.stage(stage).skip(); else stage(record,stage,action::run);
    }
    private static void stage(ExecutionRecord record, ExecutionRecord.Stage stage, Operation action) throws Throwable {
        StageRecord entry = record.stage(stage); entry.start();
        try { action.run(); entry.end(null); }
        catch (Throwable e) { entry.end(e); throw e; }
    }
    @FunctionalInterface private interface Operation { void run() throws Throwable; }
}
