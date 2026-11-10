package com.inzent.apim.utilities.http2mock;

import java.util.Arrays;
import org.apache.catalina.connector.Connector;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TestPageController {
    private final ServletWebServerApplicationContext context;
    private final MockServicesLifecycle services;

    public TestPageController(ServletWebServerApplicationContext context, MockServicesLifecycle services) {
        this.context = context; this.services = services;
    }

    @GetMapping("/test/services")
    public ResponseEntity<java.util.List<MockServicesLifecycle.Endpoint>> services() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(services.endpoints());
    }

    @GetMapping("/test/config")
    public ResponseEntity<ConnectionPorts> config() {
        Connector[] connectors = ((TomcatWebServer) context.getWebServer()).getTomcat().getService().findConnectors();
        int http = Arrays.stream(connectors).filter(connector -> !connector.getSecure())
                .mapToInt(Connector::getLocalPort).findFirst().orElseThrow();
        int https = Arrays.stream(connectors).filter(Connector::getSecure)
                .mapToInt(Connector::getLocalPort).findFirst().orElseThrow();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ConnectionPorts(http, https));
    }

    public record ConnectionPorts(int httpPort, int httpsPort) { }
}
