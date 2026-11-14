package com.inzent.apim.utilities.http.caller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inzent.apim.utilities.http.caller.config.CallerProperties;
import com.inzent.apim.utilities.http.caller.persistence.CallerStore;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.Path;

@SpringBootApplication
@EnableConfigurationProperties(CallerProperties.class)
public class HttpMockRequestCallerApplication {
    public static void main(String[] args){SpringApplication.run(HttpMockRequestCallerApplication.class,args);}
    @Bean ObjectMapper callerObjectMapper(){return new ObjectMapper().findAndRegisterModules();}
    @Bean CallerStore callerStore(JdbcTemplate jdbc,ObjectMapper json,CallerProperties p){var store=new CallerStore(jdbc,json,Path.of(p.dataDirectory()));store.initialize();return store;}
}
