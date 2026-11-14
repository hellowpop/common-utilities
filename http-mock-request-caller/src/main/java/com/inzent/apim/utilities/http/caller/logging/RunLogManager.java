package com.inzent.apim.utilities.http.caller.logging;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/** Synchronous per-run logs; callers invoke this outside transport event loops. */
public final class RunLogManager implements AutoCloseable {
    private final Path directory;
    private final boolean enabled, gzip;
    private final long maxFileBytes;
    private final int maxFiles;
    private final Map<String,Stream> streams=new HashMap<>();
    private boolean closed;
    private RunLogManager(Path directory,boolean enabled,long maxFileBytes,int maxFiles,boolean gzip) {
        if(directory==null || maxFileBytes<1 || maxFiles<1 || maxFiles>100)throw new IllegalArgumentException("Invalid logging configuration");
        this.directory=directory.toAbsolutePath().normalize();this.enabled=enabled;this.maxFileBytes=maxFileBytes;this.maxFiles=maxFiles;this.gzip=gzip;
        if(enabled)try{Files.createDirectories(this.directory);}catch(IOException e){throw new UncheckedIOException("LOG_IO opening logs",e);}
    }
    public static RunLogManager open(Path directory,boolean enabled,long maxFileBytes,int maxFiles,boolean gzip) {return new RunLogManager(directory,enabled,maxFileBytes,maxFiles,gzip);}
    public void log(String stream,String message) {
        final Stream target;
        synchronized(this) {
            if(closed)throw new IllegalStateException("Logs closed");
            if(!enabled)return;
            if(stream==null || !stream.matches("control|worker-[0-9]{4,8}"))throw new IllegalArgumentException("Invalid log stream");
            target=streams.computeIfAbsent(stream,Stream::new);
        }
        target.write(record(message));
    }
    private static byte[] record(String message) {
        String text=String.valueOf(message).replace('\r',' ').replace('\n',' ');
        // At most 16 KiB, including LF, without splitting a UTF-8 code point.
        int end=0,bytes=0;
        while(end<text.length()) {int cp=text.codePointAt(end);int n=cp<=0x7f?1:cp<=0x7ff?2:cp<=0xffff?3:4;if(bytes+n>16383)break;bytes+=n;end+=Character.charCount(cp);}
        return (text.substring(0,end)+"\n").getBytes(StandardCharsets.UTF_8);
    }
    @Override public synchronized void close() {
        if(closed)return;closed=true;
        UncheckedIOException failure=null;
        for(Stream s:streams.values())try{s.close();}catch(UncheckedIOException e){if(failure==null)failure=e;else failure.addSuppressed(e);}
        streams.clear();if(failure!=null)throw failure;
    }
    private final class Stream {
        private final String name;
        private final Path active;
        private OutputStream output;
        private long size;
        private boolean stopped;
        Stream(String name) {
            this.name=name;active=directory.resolve(name+".log");
            try {size=Files.exists(active)?Files.size(active):0;output=Files.newOutputStream(active,StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(IOException e){throw new UncheckedIOException("LOG_IO opening stream",e);}
        }
        synchronized void write(byte[] record) {
            if(stopped)throw new IllegalStateException("Log stream closed");
            try {if(size>0 && (size>=maxFileBytes || record.length>maxFileBytes-size))roll();output.write(record);output.flush();size+=record.length;}
            catch(IOException e){throw new UncheckedIOException("LOG_IO writing stream",e);}
        }
        private Path archive(int index){return directory.resolve("archive").resolve(name+"."+index+".log"+(gzip?".gz":""));}
        private void roll() throws IOException {
            output.close();
            if(maxFiles>1) {
                Files.createDirectories(directory.resolve("archive"));
                Files.deleteIfExists(archive(maxFiles-1));
                for(int index=maxFiles-2;index>=1;index--)if(Files.exists(archive(index)))Files.move(archive(index),archive(index+1),StandardCopyOption.REPLACE_EXISTING);
                if(gzip) {
                    Path pending=directory.resolve("archive").resolve(name+".pending");
                    try {try(var out=new GZIPOutputStream(Files.newOutputStream(pending))){Files.copy(active,out);}Files.move(pending,archive(1),StandardCopyOption.REPLACE_EXISTING);}
                    finally {Files.deleteIfExists(pending);}
                } else Files.move(active,archive(1),StandardCopyOption.REPLACE_EXISTING);
            }
            output=Files.newOutputStream(active,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);size=0;
        }
        synchronized void close(){stopped=true;try{output.close();}catch(IOException e){throw new UncheckedIOException("LOG_IO closing stream",e);}}
    }
}
