package com.inzent.apim.utilities.http2mock;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;
import com.inzent.apim.utilities.netty.http.mockserver.annotation.HttpResponseType;
import com.inzent.apim.utilities.netty.http.mockserver.config.ConfigLoader;
import com.inzent.apim.utilities.netty.http.mockserver.config.ServerConfig;
import com.inzent.apim.utilities.netty.http.mockserver.registry.ActionRegistry;
import com.inzent.apim.utilities.netty.http.mockserver.server.HttpMockServer;

final class NettyMockFactory {
    private NettyMockFactory() { }
    record Prepared(HttpMockServer server, ServerConfig config) { }
    static Prepared create(MockServicesProperties.Netty settings, ResourceLoader resources) throws IOException {
        // The native loader accepts filesystem YAML. Bound the read and remove this private copy after parsing.
        var temporary = Files.createTempFile("spring-netty-mock-", ".yml");
        ServerConfig config;
        try {
            try (var input = resources.getResource(settings.config()).getInputStream()) {
                byte[] bytes = input.readNBytes(1_048_577);
                if (bytes.length > 1_048_576) throw new IllegalArgumentException("Netty configuration exceeds 1 MiB");
                Files.write(temporary, bytes);
            }
            var arguments = new ArrayList<String>();
            arguments.add("--config=" + temporary);
            arguments.addAll(settings.arguments());
            config = ConfigLoader.load(arguments.toArray(String[]::new));
        } finally { Files.deleteIfExists(temporary); }

        // Spring's resource scanner supports BOOT-INF/lib nested JARs as well as exploded classes.
        var scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
                // Include invalid abstract/non-independent annotated types so the native registry rejects them.
                return definition.getMetadata().hasAnnotation(HttpResponseType.class.getName());
            }
        };
        scanner.setResourceLoader(resources);
        scanner.addIncludeFilter(new AnnotationTypeFilter(HttpResponseType.class));
        List<Class<?>> processors = new ArrayList<>();
        for (String packageName : config.scanPackages()) {
            for (var candidate : scanner.findCandidateComponents(packageName)) {
                processors.add(ClassUtils.resolveClassName(candidate.getBeanClassName(), resources.getClassLoader()));
            }
        }
        return new Prepared(HttpMockServer.create(config, ActionRegistry.ofClasses(processors)), config);
    }
}
