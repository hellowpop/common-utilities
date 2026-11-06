package com.inzent.apim.utilities.hazelcastmonitoring.client;

import com.hazelcast.client.config.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.Locale;

/** Only Hazelcast's native client builders interpret connection configuration. */
public final class ClientSettings {
    private ClientSettings() {}
    public static ClientConfig load(Path path) {
        try {
            if(!Files.isRegularFile(path)||!Files.isReadable(path))throw new IOException("Client config is not readable");
            String name=path.getFileName().toString().toLowerCase(Locale.ROOT);
            if(name.endsWith(".xml"))return new XmlClientConfigBuilder(path.toFile()).build();
            if(name.endsWith(".yml")||name.endsWith(".yaml"))return new YamlClientConfigBuilder(path.toFile()).build();
            throw new IllegalArgumentException("client-config must use .yml, .yaml or .xml");
        } catch(IOException|RuntimeException ex) {
            throw new IllegalArgumentException("Cannot read or parse client-config (.yml/.yaml/.xml): "+path,ex);
        }
    }
}
