package com.inzent.apim.utilities.http.curl;

import com.sun.net.httpserver.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class CurlHttpsTest {
    @TempDir Path temp;
    @Test void certificateVerificationIsDisabledByDefaultAndCanBeEnabled() throws Exception {
        Path keys = temp.resolve("server.p12"), certificate = temp.resolve("server.pem");
        String keytool = Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool").toString();
        run(keytool,"-genkeypair","-alias","server","-keyalg","RSA","-keysize","2048","-storetype","PKCS12",
            "-keystore",keys.toString(),"-storepass","changeit","-keypass","changeit","-dname","CN=localhost",
            "-validity","1","-ext","SAN=ip:127.0.0.1,dns:localhost","-noprompt");
        run(keytool,"-exportcert","-rfc","-alias","server","-keystore",keys.toString(),"-storepass","changeit","-file",certificate.toString());
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keys)) { store.load(in,"changeit".toCharArray()); }
        KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(store,"changeit".toCharArray());
        SSLContext ssl = SSLContext.getInstance("TLS"); ssl.init(managers.getKeyManagers(),null,null);
        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.setHttpsConfigurator(new HttpsConfigurator(ssl));
        server.createContext("/",e -> { try { e.getRequestBody().readAllBytes(); e.sendResponseHeaders(200,2); e.getResponseBody().write(new byte[]{'o','k'}); } finally { e.close(); } });
        server.start();
        try {
            URI url = URI.create("https://127.0.0.1:"+server.getAddress().getPort()+"/");
            for (int mode=0; mode<3; mode++) {
                var config = ExecuteConfigure.builder().temporaryDirectory(temp).verifyCertificate(mode != 0)
                    .additionalArguments(mode == 2 ? List.of("--cacert",certificate.toString()) : List.of()).build();
                try (var c = ExecuteContext.builder().url(url).configure(config).build()) {
                    if (mode == 1) {
                        assertThrows(CurlExecutionException.class, () -> new CurlHttpMockExecutor().handle(c));
                        assertNotEquals(0,c.record().exitCode().orElseThrow());
                    } else {
                        new CurlHttpMockExecutor().handle(c);
                        assertEquals(200,c.record().response().status().orElseThrow().code());
                    }
                }
            }
            assertTrue(Files.exists(keys)); assertTrue(Files.exists(certificate));
        } finally { server.stop(0); }
    }
    private void run(String... args) throws Exception {
        Process process = new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(temp.resolve("keytool.log").toFile()).start();
        try {
            assertTrue(process.waitFor(20,TimeUnit.SECONDS));
            assertEquals(0,process.exitValue(), () -> { try { return Files.readString(temp.resolve("keytool.log")); } catch (IOException e) { return e.toString(); } });
        } finally { process.destroyForcibly(); }
    }
}
