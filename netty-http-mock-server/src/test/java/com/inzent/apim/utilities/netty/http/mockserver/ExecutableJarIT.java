package com.inzent.apim.utilities.netty.http.mockserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ExecutableJarIT {
    @TempDir Path temp;
    private Process launch(String... arguments) throws Exception {
        String executable=Path.of(System.getProperty("java.home"),"bin","java").toString();
        String jar=Path.of("target/netty-http-mock-server.jar").toAbsolutePath().toString();
        java.util.List<String> args=new java.util.ArrayList<>(java.util.List.of(executable,"-jar",jar));
        args.addAll(java.util.List.of(arguments));
        return new ProcessBuilder(args).directory(temp.toFile()).redirectErrorStream(true)
                .redirectOutput(temp.resolve("process.log").toFile()).start();
    }
    @Test void shadedJarDiscoversActionsInAnEmptyWorkingDirectory() throws Exception {
        int port; try (ServerSocket socket=new ServerSocket(0)) { port=socket.getLocalPort(); }
        Path config=temp.resolve("config with spaces.yml");
        Files.writeString(config,"server:\n  host: 127.0.0.1\n  port: '${PORT}'\n  worker-threads: 1\n");
        Process process=launch("--config="+config,"--PORT="+port);
        try {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            boolean ready=false;
            while (System.nanoTime()<deadline && process.isAlive()) {
                try (Socket ignored=new Socket("127.0.0.1",port)) { ready=true; break; }
                catch (java.io.IOException ex) { Thread.sleep(25); }
            }
            assertTrue(ready,() -> { try { return Files.readString(temp.resolve("process.log")); } catch(Exception ex) { return ex.toString(); } });
            try (Socket socket=new Socket("127.0.0.1",port)) {
                socket.setSoTimeout(3000);
                ServerIntegrationTest.send(socket,"GET / HTTP/1.1\r\nHost: localhost\r\n\r\n");
                var reply=ServerIntegrationTest.response(socket,false);
                assertEquals("200",reply.status().split(" ")[1]);
                assertTrue(new String(reply.body(),java.nio.charset.StandardCharsets.UTF_8).contains("x-response-action"));
            }
            for (String action:java.util.List.of("default","ok","copy-and-send","error-500","fake-close","close","delay-close","status")) {
                try (Socket socket=new Socket("127.0.0.1",port)) {
                    socket.setSoTimeout(3000);
                    ServerIntegrationTest.send(socket,"POST / HTTP/1.1\r\nHost: localhost\r\nx-response-action: "+action
                            +"\r\nx-delay-amount: 25\r\nx-status: 418\r\nContent-Length: 3\r\n\r\nabc");
                    if (action.equals("close") || action.equals("delay-close")) assertEquals(-1,socket.getInputStream().read());
                    else {
                        var reply=ServerIntegrationTest.response(socket,false);
                        assertEquals(action.equals("status") ? "418" : action.equals("error-500") ? "500" : "200",reply.status().split(" ")[1]);
                        if (action.equals("copy-and-send")) assertArrayEquals(new byte[]{97,98,99},reply.body());
                        if (action.equals("fake-close")) {
                            assertEquals("keep-alive",reply.headers().get("connection")); assertEquals(-1,socket.getInputStream().read());
                        }
                    }
                }
            }
        } finally {
            process.destroy(); if (!process.waitFor(5,TimeUnit.SECONDS)) process.destroyForcibly().waitFor(5,TimeUnit.SECONDS);
        }
    }
    @Test void invalidConfigurationExitsWithOne() throws Exception {
        Process process=launch("--server.port=0");
        try {
            assertTrue(process.waitFor(10,TimeUnit.SECONDS)); assertEquals(1,process.exitValue());
            assertTrue(Files.readString(temp.resolve("process.log")).contains("server.port"));
        }
        finally { if (process.isAlive()) process.destroyForcibly(); }
    }
}
