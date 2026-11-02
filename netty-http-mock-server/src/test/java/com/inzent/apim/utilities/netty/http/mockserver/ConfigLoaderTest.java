package com.inzent.apim.utilities.netty.http.mockserver;

import com.inzent.apim.utilities.netty.http.mockserver.config.ConfigLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConfigLoaderTest {
    @TempDir Path temp;
    private String file(String content) throws Exception {
        Path path = temp.resolve("config.yml"); Files.writeString(path, content); return "--config=" + path;
    }
    @Test void defaultsAndDirectOverride() {
        var config = ConfigLoader.load(new String[]{"--server.port=9090"});
        assertEquals(9090, config.port()); assertEquals("127.0.0.1", config.host());
        assertEquals(100, config.maxConnections());
    }
    @Test void cliThenSystemThenEnvironmentAndDirectOverride() throws Exception {
        String yaml = file("server:\n  port: '${PORT:8080}'\n");
        Properties props = new Properties(); props.setProperty("PORT", "8082");
        assertEquals(8081, ConfigLoader.load(new String[]{yaml}, new Properties(), Map.of("PORT", "8081")).port());
        assertEquals(8082, ConfigLoader.load(new String[]{yaml}, props, Map.of("PORT", "8081")).port());
        assertEquals(8083, ConfigLoader.load(new String[]{yaml,"--PORT=8083"}, props, Map.of()).port());
        assertEquals(9090, ConfigLoader.load(new String[]{yaml,"--PORT=8083","--server.port=9090"}, props, Map.of()).port());
    }
    @Test void partialExternalYamlAndEmptyDocumentUseCodeDefaults() throws Exception {
        assertEquals(8080, ConfigLoader.load(new String[]{file("")}).port());
        var config = ConfigLoader.load(new String[]{file("server:\n  port: 8088\n")});
        assertEquals(8088, config.port()); assertEquals(1048576, config.maxContentLength());
    }
    @Test void rejectsInvalidYamlAndUnknownKeys() throws Exception {
        for (String yaml : List.of("server: {port: 1, port: 2}", "server: {potr: 8080}",
                "server: {port: null}", "[]", "---\n{}\n---\n{}", "!!java/object {}",
                "server: {port: '${MISSING}'}", "server: {port: '${X:${Y}}'}", "server: {port: '${X}'}", "null", "~")) {
            String arg = file(yaml);
            assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(new String[]{arg}, new Properties(), Map.of()), yaml);
        }
    }
    @Test void rejectsBadArgumentsAndOutOfRangeConfig() {
        for (String arg : List.of("--server.port=0", "--server.port=65536", "--server.port=1.5", "--server.port=+80",
                "--server.keep-alive=yes", "--server.worker-threads=-1", "--server.max-connections=0",
                "--server.write-buffer-low-water-mark=65536", "--server.potr=80", "--UNUSED=x", "--server.port")) {
            assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(new String[]{arg}), arg);
        }
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(new String[]{"--server.port=80","--server.port=81"}));
    }
    @Test void rejectsAliasDepthSizeAndPlaceholderExpansion() throws Exception {
        String deep = "x: ".repeat(80) + "1";
        for (String yaml : List.of("x: &a [1]\ny: *a", deep, "#".repeat(1048577))) {
            String arg = file(yaml); assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(new String[]{arg}));
        }
        String arg = file("server: {port: '${PORT}'}");
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(new String[]{arg}, new Properties(), Map.of("PORT", "${OTHER}")));
    }
}
