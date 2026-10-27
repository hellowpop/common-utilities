package com.inzent.apim.utilities.hazelcastmonitoring.install;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;
import java.util.jar.JarFile;

/** Publishes a complete, verified, ordinary Java classpath without a Spring Boot launcher. */
final class RuntimeLayout {
    private static final String MANIFEST="manifest.properties";
    private static final String MAIN="classes/com/inzent/apim/utilities/hazelcastmonitoring/MonitorApplication.class";

    record Result(String status,String localLibrary) {}
    Result install(Path target,Path source) throws IOException {
        LocalHazelcast local=LocalHazelcast.find(target);
        Path runtime=target.resolve("runtime");
        Path originalRuntime=null;
        Map<String,String> originalFiles=null;
        if(Files.isDirectory(source,LinkOption.NOFOLLOW_LINKS)) {
            if(!source.getFileName().toString().equals("classes"))throw new IOException("Install source must be a packaged JAR or installed runtime/classes");
            originalRuntime=source.getParent(); originalFiles=readManifest(originalRuntime); verify(originalRuntime,originalFiles);
            if(originalRuntime.equals(runtime)&&local==null)return new Result("PRESERVED",null);
        } else if(!Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS))throw new IOException("Invalid installation source");
        Path stage=Files.createTempDirectory(target,".hzmon-runtime-");
        try {
            Files.createDirectories(stage.resolve("classes")); Files.createDirectories(stage.resolve("lib"));
            if(originalRuntime==null)extractArchive(source,stage);
            else {
                for(String relative:originalFiles.keySet()) {
                    Path destination=safePath(stage,relative); Files.createDirectories(destination.getParent());
                    try(var bytes=Files.newInputStream(safePath(originalRuntime,relative),LinkOption.NOFOLLOW_LINKS)) { Files.copy(bytes,destination); }
                }
            }
            Map<String,String> files=payload(stage); requireApplication(files);
            if(originalFiles!=null&&!originalFiles.equals(files))throw new IOException("Source runtime changed during installation");
            if(local!=null) { local.apply(stage); files=payload(stage); requireApplication(files); }
            writeManifest(stage,files);
            if(Files.exists(runtime,LinkOption.NOFOLLOW_LINKS)) { verify(runtime,files); return new Result("PRESERVED",local==null?null:local.filename()); }
            try { Files.move(stage,runtime); return new Result("CREATED",local==null?null:local.filename()); }
            catch(IOException collision) {
                if(!Files.exists(runtime,LinkOption.NOFOLLOW_LINKS))throw collision;
                verify(runtime,files); return new Result("PRESERVED",local==null?null:local.filename());
            }
        } finally { deleteStage(stage,target); }
    }
    private void extractArchive(Path archive,Path stage) throws IOException {
        try(var jar=new JarFile(archive.toFile())) {
            Set<String> seen=new HashSet<>();
            var entries=jar.entries();
            while(entries.hasMoreElements()) {
                var entry=entries.nextElement(); if(entry.isDirectory())continue;
                String name=entry.getName(),relative;
                if(name.startsWith("BOOT-INF/classes/"))relative="classes/"+name.substring("BOOT-INF/classes/".length());
                else if(name.startsWith("BOOT-INF/lib/")) {
                    String library=name.substring("BOOT-INF/lib/".length());
                    if(library.contains("/")||!library.toLowerCase(Locale.ROOT).endsWith(".jar"))throw new IOException("Invalid runtime library entry: "+name);
                    relative="lib/"+library;
                } else continue;
                Path destination=safePath(stage,relative);
                if(!seen.add(relative))throw new IOException("Duplicate runtime entry: "+relative);
                Files.createDirectories(destination.getParent());
                try(var bytes=jar.getInputStream(entry)) { Files.copy(bytes,destination); }
            }
        }
    }
    private Path safePath(Path root,String relative) throws IOException {
        if((!relative.startsWith("classes/")&&!relative.startsWith("lib/"))||relative.indexOf('\\')>=0||relative.indexOf(':')>=0)
            throw new IOException("Invalid runtime path: "+relative);
        for(String part:relative.split("/",-1))if(part.isEmpty()||part.equals(".")||part.equals(".."))throw new IOException("Unsafe runtime path: "+relative);
        try {
            Path file=root.resolve(relative).normalize();
            if(!file.startsWith(root))throw new IOException("Runtime path escapes target: "+relative);
            return file;
        } catch(InvalidPathException e) { throw new IOException("Invalid runtime path",e); }
    }
    private Map<String,String> payload(Path root) throws IOException {
        if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))throw new IOException("runtime must be a regular directory: "+root);
        Map<String,String> result=new TreeMap<>();
        try(var walk=Files.walk(root)) {
            for(Path file:walk.toList()) {
                if(Files.isSymbolicLink(file))throw new IOException("Runtime symbolic links are unsupported: "+file);
                if(Files.isDirectory(file,LinkOption.NOFOLLOW_LINKS))continue;
                if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IOException("Invalid runtime file: "+file);
                String relative=root.relativize(file).toString().replace('\\','/');
                if(relative.equals(MANIFEST))continue;
                safePath(root,relative); result.put(relative,sha256(file));
            }
        }
        return result;
    }
    private void requireApplication(Map<String,String> files) throws IOException {
        if(!files.containsKey(MAIN)||files.keySet().stream().noneMatch(p->p.startsWith("lib/")&&p.endsWith(".jar")))
            throw new IOException("Archive/runtime is missing application classes or dependencies");
    }
    private void writeManifest(Path root,Map<String,String> files) throws IOException {
        Properties properties=new Properties(); properties.setProperty("format.version","1");
        files.forEach((name,hash)->properties.setProperty("file."+name,hash));
        try(var writer=Files.newBufferedWriter(root.resolve(MANIFEST),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW)) {
            properties.store(writer,"Hazelcast monitoring runtime integrity manifest; do not edit");
        }
    }
    private Map<String,String> readManifest(Path root) throws IOException {
        if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)||!Files.isRegularFile(root.resolve(MANIFEST),LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Installed runtime manifest is missing: "+root);
        Properties properties=new Properties();
        try(var reader=Files.newBufferedReader(root.resolve(MANIFEST),StandardCharsets.UTF_8)) { properties.load(reader); }
        catch(IllegalArgumentException e) { throw new IOException("Malformed runtime manifest",e); }
        if(!"1".equals(properties.getProperty("format.version")))throw new IOException("Unsupported runtime manifest version");
        Map<String,String> files=new TreeMap<>();
        for(String key:properties.stringPropertyNames()) {
            if(key.equals("format.version"))continue;
            if(!key.startsWith("file."))throw new IOException("Invalid runtime manifest key");
            String relative=key.substring(5),hash=properties.getProperty(key); safePath(root,relative);
            if(!hash.matches("[0-9a-f]{64}"))throw new IOException("Invalid runtime checksum");
            files.put(relative,hash);
        }
        requireApplication(files); return files;
    }
    private void verify(Path runtime,Map<String,String> expected) throws IOException {
        if(!readManifest(runtime).equals(expected)||!payload(runtime).equals(expected))
            throw new IOException("Existing runtime differs or is damaged; use an empty directory or explicitly replace runtime");
    }
    static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(var stream=new DigestInputStream(Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS),digest)) { stream.transferTo(OutputStream.nullOutputStream()); }
            return HexFormat.of().formatHex(digest.digest());
        } catch(NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable",e); }
    }
    private void deleteStage(Path stage,Path target) throws IOException {
        if(!stage.getParent().equals(target)||!stage.getFileName().toString().startsWith(".hzmon-runtime-"))throw new IOException("Refusing to clean an unowned staging path");
        if(!Files.exists(stage,LinkOption.NOFOLLOW_LINKS))return;
        Files.walkFileTree(stage,new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attributes) throws IOException { Files.delete(file); return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult postVisitDirectory(Path directory,IOException error) throws IOException {
                if(error!=null)throw error; Files.delete(directory); return FileVisitResult.CONTINUE;
            }
        });
    }
}
