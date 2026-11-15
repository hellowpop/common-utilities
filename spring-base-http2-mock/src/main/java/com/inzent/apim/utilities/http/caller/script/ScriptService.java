package com.inzent.apim.utilities.http.caller.script;

import org.apache.commons.jexl3.*;
import org.apache.commons.jexl3.introspection.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/** Restricted JEXL for trusted operators, never a general Java execution surface. */
public final class ScriptService implements AutoCloseable {
    private static final Set<String> METHODS=Set.of("get","put","setHeader","assertSuccess");
    private static final Pattern CALL=Pattern.compile("\\.\\s*([\\w$]+)\\s*\\(");
    private final JexlEngine engine;
    private final ThreadPoolExecutor executor;
    private final AtomicBoolean degraded=new AtomicBoolean();
    private volatile boolean closed;
    public ScriptService() {
        var features=JexlFeatures.createNone().script(true).localVar(true).sideEffect(true).sideEffectGlobal(true).methodCall(true).structuredLiteral(false).arrayReferenceExpr(true);
        var sandbox=new JexlSandbox(false);
        sandbox.allow(ScriptList.class.getName()).execute("get");
        sandbox.allow(ScriptMap.class.getName()).execute("get","put","setHeader","assertSuccess");
        engine=new JexlBuilder().features(features).permissions(new FacadePermissions()).sandbox(sandbox).strict(true).silent(false).cancellable(true).antish(false).cache(128).stackOverflow(64).arithmetic(new JexlArithmetic(true) {
            @Override public Object add(Object left,Object right) { Object value=super.add(left,right); if(value instanceof String s && s.length()>1048576) throw new IllegalArgumentException("Script text exceeds 1 MiB");return value; }
        }).create();
        executor=new ThreadPoolExecutor(4,4,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(256),r->{var t=new Thread(r,"caller-script");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    }
    public void validate(String script) { compile(script); }
    private JexlScript compile(String script) {
        if(script==null || script.isBlank()) return null;
        if(script.length()>32768) throw new IllegalArgumentException("Script exceeds 32 KiB");
        if(script.indexOf('`')>=0) throw new IllegalArgumentException("Templates disabled");
        String code=script.replaceAll("(?s)/\\*.*?\\*/|//[^\\r\\n]*|'(?:\\\\.|[^'\\\\])*'|\"(?:\\\\.|[^\"\\\\])*\"", " ");
        if(Pattern.compile("\\b(class|getClass|classLoader|for|while|do|new)\\b").matcher(code).find()) throw new IllegalArgumentException("Forbidden script syntax");
        var calls=CALL.matcher(code); while(calls.find()) if(!METHODS.contains(calls.group(1))) throw new IllegalArgumentException("Forbidden script method");
        return engine.createScript(script);
    }
    public void execute(String script, Map<String,Object> context, Duration timeout) {
        long started=System.nanoTime();
        if(closed || degraded.get()) throw new IllegalStateException("Script executor unavailable");
        if(timeout==null || timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("Positive script timeout required");
        var compiled=compile(script); if(compiled==null)return;
        var safe=new ScriptMap(context);
        var done=new CountDownLatch(1);
        var future=new FutureTask<Void>(() -> {
            compiled.execute(new JexlContext() {
                public Object get(String name){return safe.get(name);}
                public void set(String name,Object value){safe.put(name,value);}
                public boolean has(String name){return safe.containsKey(name);}
            }); safe.check();
            return null;
        }) {
            @Override public void run() { try {super.run();} finally {done.countDown();} }
        };
        try {executor.execute(future);}
        catch(RejectedExecutionException e){throw new IllegalStateException("Script capacity exhausted",e);}
        try {
            long remaining=timeout.toNanos()-(System.nanoTime()-started);
            if(remaining<=0)throw new TimeoutException();
            future.get(remaining,TimeUnit.NANOSECONDS);
            // Metadata is read only. Validate the whole set before copying any worker changes.
            for(String name:List.of("scenario","run","path")) {
                if(context.containsKey(name) && !Objects.equals(context.get(name),safe.get(name)))
                    throw new IllegalArgumentException("Script metadata is read only: "+name);
            }
            ScriptMap.copyBack(context,safe);
        }
        catch(InterruptedException e){cancel(future,done);Thread.currentThread().interrupt();throw new IllegalStateException("Script interrupted",e);}
        catch(TimeoutException e){cancel(future,done);throw new IllegalStateException("Script timeout",e);}
        catch(ExecutionException e){throw new IllegalArgumentException("Script failed: "+e.getCause().getMessage(),e.getCause());}
    }
    private void cancel(Future<?> future,CountDownLatch done) {
        future.cancel(true);
        if(future instanceof Runnable task && executor.remove(task))done.countDown();
        try {if(!done.await(100,TimeUnit.MILLISECONDS))degraded.set(true);}catch(InterruptedException e){degraded.set(true);Thread.currentThread().interrupt();}
    }
    public boolean isDegraded(){return degraded.get();}
    @Override public void close(){closed=true;executor.shutdownNow();try{if(!executor.awaitTermination(1,TimeUnit.SECONDS))degraded.set(true);}catch(InterruptedException e){degraded.set(true);Thread.currentThread().interrupt();}}
    private static final class FacadePermissions implements JexlPermissions {
        public boolean allow(Class<?> c){return c==ScriptMap.class || c==ScriptList.class;}
        public boolean allow(java.lang.reflect.Constructor<?> c){return false;}
        public boolean allow(java.lang.reflect.Field f){return false;}
        public boolean allow(java.lang.reflect.Method m){return (m.getDeclaringClass()==ScriptMap.class && METHODS.contains(m.getName())) || (m.getDeclaringClass()==ScriptList.class && m.getName().equals("get"));}
        public boolean allow(Package p){return p!=null && p.getName().equals(ScriptMap.class.getPackageName());}
        public JexlPermissions compose(String... ignored){return this;}
    }
}
