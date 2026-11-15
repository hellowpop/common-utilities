package com.inzent.apim.utilities.http.caller.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inzent.apim.utilities.http.caller.persistence.CallerStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.config.annotation.*;

import java.nio.file.Path;

/** Configures the caller engine, persistent store and console inside the mock application. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CallerProperties.class)
@ComponentScan(basePackages = "com.inzent.apim.utilities.http.caller")
public class CallerConfiguration implements WebMvcConfigurer {
    @Bean public CallerStore callerStore(JdbcTemplate jdbc, ObjectMapper json, CallerProperties properties) {
        var store = new CallerStore(jdbc, json, Path.of(properties.dataDirectory()));
        store.initialize();
        return store;
    }

    @Override public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/caller/**").addResourceLocations("classpath:/caller-ui/");
    }

    @Override public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/caller", "/caller/");
        registry.addViewController("/caller/").setViewName("forward:/caller/index.html");
    }
}
