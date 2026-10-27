package com.inzent.apim.utilities.hazelcastmonitoring.install;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;

/** An explicitly supplied local Hazelcast core archive; addons never count as a replacement. */
record LocalHazelcast(Path source,String filename,String checksum) {
    static LocalHazelcast find(Path directory) throws IOException {
        List<Path> candidates=new ArrayList<>();
        try(var files=Files.list(directory)) {
            for(Path file:files.sorted().toList()) {
                String name=file.getFileName().toString().toLowerCase(Locale.ROOT);
                if(!name.startsWith("hazelcast")||!name.endsWith(".jar")||name.endsWith("-sources.jar")||name.endsWith("-javadoc.jar"))continue;
                if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IOException("Local Hazelcast archive is not a regular file: "+file.getFileName());
                try {
                    int parts=apiParts(file);
                    if(parts==3)candidates.add(file);
                    else if(parts!=0)throw new IOException("Both client and core APIs must be present in one archive; split packaging is unsupported");
                }
                catch(IOException e) { throw new IOException("Invalid local Hazelcast archive "+file.getFileName()+": "+e.getMessage(),e); }
            }
        }
        if(candidates.size()>1)throw new IOException("Multiple Hazelcast core archives in installation directory: "+candidates.stream().map(p->p.getFileName().toString()).toList());
        if(candidates.isEmpty())return null;
        Path source=candidates.get(0);
        return new LocalHazelcast(source,source.getFileName().toString(),RuntimeLayout.sha256(source));
    }
    static boolean isCore(Path file) throws IOException {
        return apiParts(file)==3;
    }
    private static int apiParts(Path file) throws IOException {
        try(var jar=new JarFile(file.toFile())) {
            return (jar.getJarEntry("com/hazelcast/client/HazelcastClient.class")!=null?1:0)
                |(jar.getJarEntry("com/hazelcast/core/HazelcastInstance.class")!=null?2:0);
        }
    }
    void apply(Path stage) throws IOException {
        Path libraries=stage.resolve("lib");
        try(var files=Files.list(libraries)) {
            for(Path library:files.toList())if(library.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")&&isCore(library))Files.delete(library);
        }
        Path destination=libraries.resolve(filename);
        // An identically named addon must not be overwritten by a differently named core archive.
        try(var bytes=Files.newInputStream(source,LinkOption.NOFOLLOW_LINKS)) { Files.copy(bytes,destination); }
        if(!RuntimeLayout.sha256(destination).equals(checksum)||!isCore(destination))throw new IOException("Local Hazelcast archive changed during installation");
    }
}
