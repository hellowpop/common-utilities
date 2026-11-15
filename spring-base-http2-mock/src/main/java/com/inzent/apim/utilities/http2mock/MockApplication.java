package com.inzent.apim.utilities.http2mock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import com.inzent.apim.utilities.http.caller.config.CallerConfiguration;

@SpringBootApplication
@Import(CallerConfiguration.class)
public class MockApplication {
    public static void main(String[] args) {
        SpringApplication.run(MockApplication.class, args);
    }
}
