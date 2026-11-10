package com.inzent.apim.utilities.http2mock;

import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class HttpConnectorConfiguration {
    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> httpConnectorCustomizer(
            @Value("${mock.http.port:8080}") int port) {
        return factory -> {
            Connector connector = new Connector("org.apache.coyote.http11.Http11NioProtocol");
            connector.setPort(port);
            connector.setScheme("http");
            connector.setSecure(false);
            // Deliberately omit HTTP/2 upgrade protocols: h2 is available only on the TLS connector.
            factory.addAdditionalTomcatConnectors(connector);
        };
    }
}
