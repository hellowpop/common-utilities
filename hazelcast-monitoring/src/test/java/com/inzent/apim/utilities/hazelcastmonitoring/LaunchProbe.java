package com.inzent.apim.utilities.hazelcastmonitoring;

/** Minimal standalone JAR entry point used to exercise generated launch scripts. */
public final class LaunchProbe {
    public static void main(String[] args) throws Exception {
        System.out.println("cwd="+System.getProperty("user.dir"));
        for(String arg:args)System.out.println("arg="+arg);
        System.out.println("config="+java.nio.file.Files.readString(java.nio.file.Path.of("application.yml")));
        System.exit(7);
    }
}
