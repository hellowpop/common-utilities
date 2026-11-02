package com.inzent.apim.utilities.netty.http.mockserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.io.File;
import java.net.ServerSocket;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import static org.junit.jupiter.api.Assertions.*;

class EmbeddedLibraryIT {
    @TempDir Path temp;
    @Test void documentedExampleCompilesAndRunsWithLibraryAndDependenciesOnly() throws Exception {
        Path library=Path.of("target/netty-http-mock-server-library.jar").toAbsolutePath();
        assertTrue(Files.isRegularFile(library),"Missing library classifier JAR");
        try (JarFile jar=new JarFile(library.toFile())) {
            assertNotNull(jar.getEntry("com/inzent/apim/utilities/netty/http/mockserver/server/HttpMockServer.class"));
            assertNull(jar.getEntry("io/netty/channel/Channel.class"),"Library must not bundle Netty classes");
        }
        List<String> jars=new ArrayList<>(); jars.add(library.toString());
        for (String entry:System.getProperty("java.class.path").split(File.pathSeparator)) {
            Path path=Path.of(entry);
            // Deliberately exclude target/classes and target/test-classes, so the artifact must stand alone.
            if (Files.isRegularFile(path) && entry.endsWith(".jar")) jars.add(path.toAbsolutePath().toString());
        }
        String classpath=String.join(File.pathSeparator,jars);
        int compiled=ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","17","-classpath",classpath,
                "-d",temp.toString(),Path.of("examples/EmbeddedExample.java").toAbsolutePath().toString());
        assertEquals(0,compiled,"Documented example must compile using the library API");
        // A hosting application commonly has its own unrelated /application.yml resource.
        Files.writeString(temp.resolve("application.yml"),"host-application: {unrelated: true}\n");
        int port; try (ServerSocket socket=new ServerSocket(0)) { port=socket.getLocalPort(); }
        Path output=temp.resolve("consumer.log");
        Process process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "-cp",temp+File.pathSeparator+classpath,
                "com.inzent.apim.utilities.netty.http.mockserver.examples.EmbeddedExample",
                "--server.host=127.0.0.1","--server.port="+port,"--server.worker-threads=1")
                .directory(temp.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(12,TimeUnit.SECONDS),"Embedded server left live event loops after close");
            String log=Files.readString(output);
            assertEquals(0,process.exitValue(),log);
            assertTrue(log.contains("Embedded response: 200 OK"),log);
            assertTrue(log.contains("host JVM continues"),log);
        } finally { if (process.isAlive()) process.destroyForcibly().waitFor(5,TimeUnit.SECONDS); }
    }
}
